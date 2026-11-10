package cn.luckyh.mqx.parser;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiBlockStatement;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiConditionalExpression;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiForeachStatement;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import cn.luckyh.mqx.model.MqEndpoint;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MqValueResolver {

    private static final String VALUE_ANNOTATION = "org.springframework.beans.factory.annotation.Value";
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^}:]+)(?::([^}]*))?}$");

    private final Project project;

    public MqValueResolver(Project project) {
        this.project = project;
    }

    public ResolvedValue resolveExpression(PsiExpression expression, Set<Object> cacheDependencies) {
        // 每次目的地解析建立独立递归路径
        return resolveExpression(expression, cacheDependencies, new LinkedHashSet<>());
    }

    private ResolvedValue resolveExpression(
            PsiExpression expression,
            Set<Object> cacheDependencies,
            Set<PsiElement> path) {
        if (expression == null) {
            return ResolvedValue.dynamic("destination is missing");
        }
        Set<PsiElement> nextPath = new LinkedHashSet<>(path);
        if (!nextPath.add(expression)) {
            return ResolvedValue.dynamic("destination expression is recursive");
        }

        // 逻辑变动: 仅解析可证明的静态值和直接Value字段-20260817-1826-01
        Object constant = JavaPsiFacade.getInstance(project)
                .getConstantEvaluationHelper()
                .computeConstantExpression(expression);
        if (constant instanceof String value) {
            return ResolvedValue.staticValue(value);
        }

        if (expression instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiVariable variable) {
            if (variable.computeConstantValue() instanceof String value) {
                return ResolvedValue.staticValue(value);
            }
            if (variable.hasModifierProperty(PsiModifier.FINAL) && variable.getInitializer() != null) {
                ResolvedValue initializer = resolveExpression(variable.getInitializer(), cacheDependencies, nextPath);
                if (initializer.resolved()) {
                    return initializer;
                }
            }
        }
        if (expression instanceof PsiPolyadicExpression polyadic
                && polyadic.getOperationTokenType() == com.intellij.psi.JavaTokenType.PLUS) {
            StringBuilder combined = new StringBuilder();
            for (PsiExpression operand : polyadic.getOperands()) {
                ResolvedValue operandValue = resolveExpression(operand, cacheDependencies, nextPath);
                if (!operandValue.resolved() || operandValue.value() == null) {
                    return ResolvedValue.dynamic("string concatenation contains a runtime value");
                }
                combined.append(operandValue.value());
            }
            return ResolvedValue.staticValue(combined.toString());
        }

        // 枚举getter按已知消息编码解析唯一字符串值
        if (expression instanceof PsiMethodCallExpression methodCall) {
            List<ResolvedValue> values = resolveEnumGetterAlternatives(methodCall, cacheDependencies,
                    argument -> resolveExpressionAlternatives(argument, cacheDependencies, nextPath), path);
            return values.size() == 1 ? values.get(0)
                    : ResolvedValue.dynamic("enum getter has multiple possible values");
        }

        if (!(expression instanceof PsiReferenceExpression reference)) {
            return ResolvedValue.dynamic("destination comes from a runtime expression");
        }
        if (!isDirectFieldReference(reference) || !(reference.resolve() instanceof PsiField field)) {
            return ResolvedValue.dynamic("destination is not a direct field reference");
        }
        if (field.hasModifierProperty(PsiModifier.STATIC)
                || field.hasModifierProperty(PsiModifier.FINAL)
                || !MqEndpointParser.isStringType(field.getType())) {
            return ResolvedValue.dynamic("@Value destination must be a mutable String instance field");
        }

        PsiAnnotation annotation = field.getAnnotation(VALUE_ANNOTATION);
        if (annotation == null) {
            return ResolvedValue.dynamic("destination field is not annotated with Spring @Value");
        }
        PsiAnnotationMemberValue annotationValue = annotation.findAttributeValue("value");
        String placeholder = constantString(annotationValue);
        if (placeholder == null) {
            return ResolvedValue.dynamic("@Value destination is not a single string placeholder");
        }
        return resolvePlaceholder(placeholder, field, cacheDependencies);
    }

    /**
     * 根据已知消息编码解析枚举getter候选值。
     */
    public List<ResolvedValue> resolveEnumGetterAlternatives(
            PsiMethodCallExpression methodCall,
            Set<Object> cacheDependencies,
            Function<PsiExpression, List<ResolvedValue>> argumentResolver) {
        // 枚举解析入口隔离不同调用和线程的递归路径
        return resolveEnumGetterAlternatives(methodCall, cacheDependencies, argumentResolver, new LinkedHashSet<>());
    }

    private List<ResolvedValue> resolveEnumGetterAlternatives(
            PsiMethodCallExpression methodCall,
            Set<Object> cacheDependencies,
            Function<PsiExpression, List<ResolvedValue>> argumentResolver,
            Set<PsiElement> path) {
        Set<PsiElement> nextPath = new LinkedHashSet<>(path);
        if (!nextPath.add(methodCall)) {
            return List.of(ResolvedValue.dynamic("enum getter expression is recursive"));
        }
        // 直接枚举常量getter沿用构造字段解析
        ResolvedValue directValue = resolveEnumGetter(methodCall, cacheDependencies, nextPath);
        if (directValue != null) {
            return List.of(directValue);
        }
        if (methodCall.getArgumentList().getExpressionCount() != 0
                || !(methodCall.getMethodExpression().getQualifierExpression()
                instanceof PsiMethodCallExpression lookupCall)
                || lookupCall.getArgumentList().getExpressionCount() != 1) {
            return List.of(ResolvedValue.dynamic("destination comes from a runtime expression"));
        }

        // 枚举查找仅接受同枚举的单字符串参数静态方法
        PsiMethod lookupMethod = lookupCall.resolveMethod();
        if (lookupMethod == null || lookupMethod.getContainingClass() == null
                || !lookupMethod.getContainingClass().isEnum()
                || !lookupMethod.hasModifierProperty(PsiModifier.STATIC)
                || lookupMethod.getParameterList().getParametersCount() != 1
                || !MqEndpointParser.isStringType(lookupMethod.getParameterList().getParameters()[0].getType())
                || PsiUtil.resolveClassInClassTypeOnly(lookupMethod.getReturnType())
                != lookupMethod.getContainingClass()) {
            return List.of(ResolvedValue.dynamic("enum lookup is not statically resolved"));
        }
        PsiClass enumClass = lookupMethod.getContainingClass();
        PsiField codeField = enumLookupCodeField(lookupMethod, enumClass);
        PsiField valueField = enumGetterField(enumClass, methodCall.resolveMethod(),
                methodCall.getMethodExpression().getReferenceName());
        if (codeField == null || valueField == null || !MqEndpointParser.isStringType(valueField.getType())) {
            return List.of(ResolvedValue.dynamic("enum lookup getter is not statically resolved"));
        }

        // 已知编码按枚举声明顺序匹配，任一未确定输入保留动态诊断
        List<ResolvedValue> codes = argumentResolver.apply(lookupCall.getArgumentList().getExpressions()[0]);
        if (codes.isEmpty()) {
            return List.of(ResolvedValue.dynamic("enum lookup argument is missing"));
        }
        List<ResolvedValue> result = new ArrayList<>();
        for (ResolvedValue code : codes) {
            if (!code.resolved() || code.value() == null) {
                return List.of(ResolvedValue.dynamic("enum lookup code is not statically resolved"));
            }
            ResolvedValue selected = null;
            for (PsiField field : enumClass.getFields()) {
                if (!(field instanceof PsiEnumConstant constant)) {
                    continue;
                }
                ResolvedValue constantCode = resolveEnumFieldValue(
                        enumClass, constant, codeField, cacheDependencies, nextPath);
                if (!constantCode.resolved() || constantCode.value() == null) {
                    return List.of(ResolvedValue.dynamic("enum lookup mapping is not statically resolved"));
                }
                if (!code.value().equals(constantCode.value())) {
                    continue;
                }
                selected = resolveEnumFieldValue(enumClass, constant, valueField, cacheDependencies, nextPath);
                if (!selected.resolved() || selected.value() == null) {
                    return List.of(selected);
                }
                if (code.state() == MqEndpoint.ResolutionState.CONFIG_RESOLVED
                        || constantCode.state() == MqEndpoint.ResolutionState.CONFIG_RESOLVED) {
                    selected = ResolvedValue.configValue(selected.value());
                }
                break;
            }
            if (selected == null) {
                return List.of(ResolvedValue.dynamic("enum lookup code has no matching constant"));
            }
            result.add(selected);
        }
        return result.stream().distinct().toList();
    }

    // 枚举查找验证values遍历、字符串等值条件和当前枚举返回
    private PsiField enumLookupCodeField(PsiMethod lookupMethod, PsiClass enumClass) {
        PsiCodeBlock body = lookupMethod.getBody();
        if (body == null || body.getStatements().length != 2
                || !(body.getStatements()[0] instanceof PsiForeachStatement loop)
                || !(body.getStatements()[1] instanceof PsiThrowStatement)
                || !(loop.getIteratedValue() instanceof PsiMethodCallExpression valuesCall)) {
            return null;
        }
        PsiMethod valuesMethod = valuesCall.resolveMethod();
        if (valuesCall.getArgumentList().getExpressionCount() != 0 || valuesMethod == null
                || !"values".equals(valuesMethod.getName()) || valuesMethod.getContainingClass() != enumClass) {
            return null;
        }
        PsiStatement loopBody = loop.getBody();
        if (loopBody instanceof PsiBlockStatement block) {
            if (block.getCodeBlock().getStatements().length != 1) {
                return null;
            }
            loopBody = block.getCodeBlock().getStatements()[0];
        }
        if (!(loopBody instanceof PsiIfStatement condition) || condition.getElseBranch() != null
                || !(condition.getCondition() instanceof PsiMethodCallExpression comparisonCall)) {
            return null;
        }
        PsiStatement matchingBranch = condition.getThenBranch();
        if (matchingBranch instanceof PsiBlockStatement block) {
            if (block.getCodeBlock().getStatements().length != 1) {
                return null;
            }
            matchingBranch = block.getCodeBlock().getStatements()[0];
        }
        if (!(matchingBranch instanceof PsiReturnStatement matchingReturn)
                || !(matchingReturn.getReturnValue() instanceof PsiReferenceExpression returned)
                || returned.resolve() != loop.getIterationParameter()) {
            return null;
        }
        PsiMethod comparisonMethod = comparisonCall.resolveMethod();
        if (comparisonMethod == null || comparisonMethod.getContainingClass() == null
                || !comparisonMethod.hasModifierProperty(PsiModifier.STATIC)
                || !"equals".equals(comparisonMethod.getName())) {
            return null;
        }
        String comparisonOwner = comparisonMethod.getContainingClass().getQualifiedName();
        if (!"org.apache.commons.lang3.StringUtils".equals(comparisonOwner)
                && !"java.util.Objects".equals(comparisonOwner)) {
            return null;
        }
        PsiExpression[] compared = comparisonCall.getArgumentList().getExpressions();
        if (compared.length != 2) {
            return null;
        }
        PsiParameter codeParameter = lookupMethod.getParameterList().getParameters()[0];
        for (int index = 0; index < compared.length; index++) {
            if (!(compared[index] instanceof PsiMethodCallExpression getterCall)
                    || getterCall.getArgumentList().getExpressionCount() != 0
                    || !(getterCall.getMethodExpression().getQualifierExpression() instanceof PsiReferenceExpression value)
                    || value.resolve() != loop.getIterationParameter()
                    || !(compared[1 - index] instanceof PsiReferenceExpression argument)
                    || argument.resolve() != codeParameter) {
                continue;
            }
            PsiField codeField = enumGetterField(enumClass, getterCall.resolveMethod(),
                    getterCall.getMethodExpression().getReferenceName());
            if (codeField != null && MqEndpointParser.isStringType(codeField.getType())) {
                return codeField;
            }
        }
        return null;
    }

    private ResolvedValue resolveEnumGetter(
            PsiMethodCallExpression methodCall,
            Set<Object> cacheDependencies,
            Set<PsiElement> path) {
        if (methodCall.getArgumentList().getExpressionCount() != 0
                || !(methodCall.getMethodExpression().getQualifierExpression()
                instanceof PsiReferenceExpression qualifier)
                || !(qualifier.resolve() instanceof PsiEnumConstant enumConstant)) {
            return null;
        }

        PsiClass enumClass = enumConstant.getContainingClass();
        if (enumClass == null || !enumClass.isEnum()) {
            return null;
        }

        PsiMethod getter = methodCall.resolveMethod();
        PsiField valueField = enumGetterField(enumClass, getter, methodCall.getMethodExpression().getReferenceName());
        if (valueField == null || !MqEndpointParser.isStringType(valueField.getType())) {
            return null;
        }

        return resolveEnumFieldValue(enumClass, enumConstant, valueField, cacheDependencies, path);
    }

    // 枚举字段从对应常量的构造实参解析字符串
    private ResolvedValue resolveEnumFieldValue(
            PsiClass enumClass,
            PsiEnumConstant enumConstant,
            PsiField valueField,
            Set<Object> cacheDependencies,
            Set<PsiElement> path) {
        int argumentIndex = enumConstructorArgumentIndex(enumClass, enumConstant, valueField);
        if (argumentIndex < 0
                || enumConstant.getArgumentList() == null
                || argumentIndex >= enumConstant.getArgumentList().getExpressionCount()) {
            return ResolvedValue.dynamic("enum getter value is not statically resolved");
        }

        ResolvedValue argument = resolveExpression(
                enumConstant.getArgumentList().getExpressions()[argumentIndex], cacheDependencies, path);
        if (!argument.resolved() || argument.value() == null) {
            return ResolvedValue.dynamic("enum getter value is not a static string");
        }
        return argument;
    }

    private PsiField enumGetterField(PsiClass enumClass, PsiMethod getter, String methodName) {
        if (getter != null) {
            PsiElement navigationElement = getter.getNavigationElement();
            if (navigationElement instanceof PsiField field
                    && field.getContainingClass() == enumClass) {
                return field;
            }
            // 源码getter只接受直接字段返回，不按名称猜测自定义计算
            PsiCodeBlock getterBody = getter.getBody();
            if (getterBody != null) {
                PsiStatement[] statements = getterBody.getStatements();
                if (statements.length == 1 && statements[0] instanceof PsiReturnStatement returnStatement
                        && returnStatement.getReturnValue() instanceof PsiReferenceExpression reference
                        && (reference.getQualifierExpression() == null
                        || reference.getQualifierExpression() instanceof PsiThisExpression)
                        && reference.resolve() instanceof PsiField field
                        && field.getContainingClass() == enumClass) {
                    return field;
                }
                return null;
            }
            if (getter.hasModifierProperty(PsiModifier.ABSTRACT)) {
                return null;
            }
        }

        String fieldName = getterPropertyName(methodName);
        return fieldName == null ? null : enumClass.findFieldByName(fieldName, true);
    }

    private String getterPropertyName(String methodName) {
        if (methodName == null || methodName.length() <= 3) {
            return null;
        }
        if (methodName.startsWith("get") || methodName.startsWith("is")) {
            int prefixLength = methodName.startsWith("get") ? 3 : 2;
            String property = methodName.substring(prefixLength);
            if (property.isEmpty()) {
                return null;
            }
            return Character.toLowerCase(property.charAt(0)) + property.substring(1);
        }
        return null;
    }

    private int enumConstructorArgumentIndex(
            PsiClass enumClass,
            PsiEnumConstant enumConstant,
            PsiField valueField) {
        if (enumConstant.getArgumentList() == null) {
            return -1;
        }
        List<PsiMethod> constructors = new ArrayList<>();
        for (PsiMethod constructor : enumClass.getConstructors()) {
            if (constructor.getParameterList().getParametersCount()
                    == enumConstant.getArgumentList().getExpressionCount()) {
                constructors.add(constructor);
            }
        }
        if (constructors.size() != 1) {
            return -1;
        }

        PsiMethod constructor = constructors.get(0);
        var parameters = constructor.getParameterList().getParameters();
        // 枚举构造字段仅接受唯一顶层直接参数赋值
        PsiCodeBlock constructorBody = constructor.getBody();
        if (constructorBody != null) {
            List<PsiAssignmentExpression> assignments = new ArrayList<>(
                    PsiTreeUtil.findChildrenOfType(constructorBody, PsiAssignmentExpression.class));
            PsiAssignmentExpression fieldAssignment = null;
            for (PsiAssignmentExpression assignment : assignments) {
                if (!(assignment.getLExpression() instanceof PsiReferenceExpression left)
                        || left.resolve() != valueField) {
                    continue;
                }
                if (fieldAssignment != null) {
                    return -1;
                }
                fieldAssignment = assignment;
            }
            if (fieldAssignment == null
                    || fieldAssignment.getOperationTokenType() != com.intellij.psi.JavaTokenType.EQ
                    || !(fieldAssignment.getParent() instanceof PsiExpressionStatement statement)
                    || statement.getParent() != constructorBody) {
                return -1;
            }
            PsiReferenceExpression fieldReference = (PsiReferenceExpression) fieldAssignment.getLExpression();
            if (fieldReference.getQualifierExpression() != null
                    && !(fieldReference.getQualifierExpression() instanceof PsiThisExpression)) {
                return -1;
            }
            if (!(fieldAssignment.getRExpression() instanceof PsiReferenceExpression right)
                    || !(right.resolve() instanceof PsiParameter parameter)
                    || parameter.getDeclarationScope() != constructor) {
                return -1;
            }

            // 构造参数在字段赋值前不能被改写
            for (PsiAssignmentExpression assignment : assignments) {
                if (assignment.getTextOffset() < fieldAssignment.getTextOffset()
                        && assignment.getLExpression() instanceof PsiReferenceExpression left
                        && left.resolve() == parameter) {
                    return -1;
                }
            }
            for (int index = 0; index < parameters.length; index++) {
                if (parameters[index] == parameter) {
                    return index;
                }
            }
            return -1;
        }
        for (int index = 0; index < parameters.length; index++) {
            if (valueField.getName().equals(parameters[index].getName())) {
                return index;
            }
        }
        return -1;
    }

    public List<ResolvedValue> resolveExpressionAlternatives(
            PsiExpression expression,
            Set<Object> cacheDependencies) {
        // 候选值解析入口建立独立递归路径
        return resolveExpressionAlternatives(expression, cacheDependencies, new LinkedHashSet<>());
    }

    private List<ResolvedValue> resolveExpressionAlternatives(
            PsiExpression expression,
            Set<Object> cacheDependencies,
            Set<PsiElement> path) {
        if (expression == null) {
            return List.of(ResolvedValue.dynamic("destination is missing"));
        }
        Set<PsiElement> nextPath = new LinkedHashSet<>(path);
        if (!nextPath.add(expression)) {
            return List.of(ResolvedValue.dynamic("destination expression is recursive"));
        }
        // 枚举查找保留所有已证明的编码候选值
        if (expression instanceof PsiMethodCallExpression methodCall) {
            return resolveEnumGetterAlternatives(methodCall, cacheDependencies,
                    argument -> resolveExpressionAlternatives(argument, cacheDependencies, nextPath), path);
        }
        ResolvedValue resolved = resolveExpression(expression, cacheDependencies, path);
        if (resolved.resolved()) {
            return List.of(resolved);
        }
        if (!(expression instanceof PsiReferenceExpression reference)
                || !(reference.resolve() instanceof PsiLocalVariable variable)) {
            return List.of(resolved);
        }

        // 逻辑变动: 收集局部条件初始化和分支赋值的静态路由键-20260818-1530-01
        List<ResolvedValue> alternatives = resolveLocalAlternatives(
                variable, expression, cacheDependencies, nextPath);
        return alternatives.isEmpty() ? List.of(resolved) : alternatives;
    }

    private List<ResolvedValue> resolveLocalAlternatives(
            PsiLocalVariable variable,
            PsiExpression use,
            Set<Object> cacheDependencies,
            Set<PsiElement> path) {
        List<PsiAssignmentExpression> assignments = assignmentsBeforeUse(variable, use);
        if (!assignments.isEmpty()) {
            List<ResolvedValue> alternatives = new ArrayList<>();
            for (PsiAssignmentExpression assignment : assignments) {
                PsiExpression value = assignment.getRExpression();
                List<ResolvedValue> assignmentValues;
                if (value == null) {
                    assignmentValues = List.of();
                } else if (value instanceof PsiConditionalExpression) {
                    assignmentValues = resolveConditionalAlternatives(value, cacheDependencies, path);
                } else {
                    ResolvedValue assignmentValue = resolveExpression(value, cacheDependencies, path);
                    assignmentValues = assignmentValue.resolved() ? List.of(assignmentValue) : List.of();
                }
                if (assignmentValues.stream().anyMatch(candidate -> !candidate.resolved())) {
                    return assignmentValues;
                }
                if (assignmentValues.isEmpty()) {
                    return List.of();
                }
                alternatives.addAll(assignmentValues);
            }
            return alternatives.stream().distinct().toList();
        }
        if (variable.getInitializer() == null) {
            return List.of();
        }
        PsiExpression initializer = variable.getInitializer();
        if (initializer instanceof PsiConditionalExpression) {
            return resolveConditionalAlternatives(initializer, cacheDependencies, path);
        }
        // 逻辑变动: 解析发送调用前普通局部变量的静态初始化值-20260818-1605-01
        ResolvedValue initializerValue = resolveExpression(initializer, cacheDependencies, path);
        return initializerValue.resolved() ? List.of(initializerValue) : List.of();
    }

    private List<ResolvedValue> resolveConditionalAlternatives(
            PsiExpression expression,
            Set<Object> cacheDependencies,
            Set<PsiElement> path) {
        if (!(expression instanceof PsiConditionalExpression conditional)
                || conditional.getThenExpression() == null
                || conditional.getElseExpression() == null) {
            return List.of();
        }
        // 条件分支各自延伸路径，循环分支保留动态结果
        Set<PsiElement> nextPath = new LinkedHashSet<>(path);
        if (!nextPath.add(expression)) {
            return List.of(ResolvedValue.dynamic("conditional destination expression is recursive"));
        }

        List<ResolvedValue> alternatives = new ArrayList<>();
        for (PsiExpression branch : List.of(
                conditional.getThenExpression(), conditional.getElseExpression())) {
            List<ResolvedValue> branchValues;
            if (branch instanceof PsiConditionalExpression) {
                branchValues = resolveConditionalAlternatives(branch, cacheDependencies, nextPath);
            } else {
                ResolvedValue branchValue = resolveExpression(branch, cacheDependencies, nextPath);
                branchValues = branchValue.resolved() ? List.of(branchValue) : List.of();
            }
            if (branchValues.stream().anyMatch(candidate -> !candidate.resolved())) {
                return branchValues;
            }
            if (branchValues.isEmpty()) {
                return List.of();
            }
            alternatives.addAll(branchValues);
        }
        return alternatives.stream().distinct().toList();
    }

    private List<PsiAssignmentExpression> assignmentsBeforeUse(PsiLocalVariable variable, PsiExpression use) {
        PsiCodeBlock scope = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class);
        if (scope == null) {
            return List.of();
        }
        List<PsiAssignmentExpression> assignments = new ArrayList<>();
        for (PsiAssignmentExpression assignment : PsiTreeUtil.findChildrenOfType(
                scope, PsiAssignmentExpression.class)) {
            if (assignment.getTextOffset() <= variable.getTextOffset()
                    || assignment.getTextOffset() >= use.getTextOffset()) {
                continue;
            }
            PsiExpression target = assignment.getLExpression();
            if (target instanceof PsiReferenceExpression reference && reference.resolve() == variable) {
                assignments.add(assignment);
            }
        }
        return assignments;
    }

    public ResolvedValue resolveAnnotationValue(
            PsiAnnotationMemberValue value,
            com.intellij.psi.PsiElement context,
            Set<Object> cacheDependencies) {
        String constant = constantString(value);
        if (constant == null) {
            return ResolvedValue.dynamic("annotation value is not a constant string");
        }
        if (PLACEHOLDER.matcher(constant).matches()) {
            return resolvePlaceholder(constant, context, cacheDependencies);
        }
        return ResolvedValue.staticValue(constant);
    }

    private boolean isDirectFieldReference(PsiReferenceExpression reference) {
        PsiExpression qualifier = reference.getQualifierExpression();
        if (qualifier == null) {
            return true;
        }
        return qualifier instanceof PsiThisExpression thisExpression && thisExpression.getQualifier() == null;
    }

    private String constantString(PsiAnnotationMemberValue value) {
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String text) {
            return text;
        }
        if (value instanceof PsiExpression expression) {
            Object constant = JavaPsiFacade.getInstance(project)
                    .getConstantEvaluationHelper()
                    .computeConstantExpression(expression);
            if (constant instanceof String text) {
                return text;
            }
            if (expression instanceof PsiReferenceExpression reference
                    && reference.resolve() instanceof PsiVariable variable
                    && variable.computeConstantValue() instanceof String text) {
                return text;
            }
            if (expression instanceof PsiReferenceExpression reference
                    && reference.resolve() instanceof PsiVariable variable
                    && variable.hasModifierProperty(PsiModifier.FINAL)
                    && variable.getInitializer() != null) {
                Object initializer = JavaPsiFacade.getInstance(project)
                        .getConstantEvaluationHelper()
                        .computeConstantExpression(variable.getInitializer());
                if (initializer instanceof String text) {
                    return text;
                }
            }
        }
        return null;
    }

    private ResolvedValue resolvePlaceholder(
            String placeholder,
            com.intellij.psi.PsiElement context,
            Set<Object> cacheDependencies) {
        Matcher matcher = PLACEHOLDER.matcher(placeholder);
        if (!matcher.matches()) {
            return ResolvedValue.dynamic("only a single ${key} or ${key:default} placeholder is supported");
        }

        Module module = ModuleUtilCore.findModuleForPsiElement(context);
        if (module == null) {
            return ResolvedValue.dynamic("destination module cannot be determined");
        }

        Set<String> values = new LinkedHashSet<>();
        String key = matcher.group(1);
        for (PsiFile configFile : configurationFiles(module)) {
            cacheDependencies.add(configFile);
            String value = readConfigurationValue(configFile, key);
            if (value != null) {
                values.add(value);
            }
        }

        if (values.size() == 1) {
            return ResolvedValue.configValue(values.iterator().next());
        }
        if (values.size() > 1) {
            return ResolvedValue.dynamic("configuration key has multiple values: " + key);
        }
        if (matcher.group(2) != null) {
            return ResolvedValue.configValue(matcher.group(2));
        }
        return ResolvedValue.dynamic("configuration key is not defined: " + key);
    }

    private List<PsiFile> configurationFiles(Module module) {
        List<PsiFile> files = new ArrayList<>();
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile contentRoot : com.intellij.openapi.roots.ModuleRootManager.getInstance(module).getContentRoots()) {
            VfsUtilCore.iterateChildrenRecursively(contentRoot, file -> true, file -> {
                if (!file.isDirectory() && isConfigurationFile(file.getName())) {
                    PsiFile psiFile = psiManager.findFile(file);
                    if (psiFile != null) {
                        files.add(psiFile);
                    }
                }
                return true;
            });
        }
        return files;
    }

    private boolean isConfigurationFile(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        boolean supportedName = lower.startsWith("application") || lower.startsWith("bootstrap");
        return supportedName && (lower.endsWith(".properties") || lower.endsWith(".yml") || lower.endsWith(".yaml"));
    }

    private String readConfigurationValue(PsiFile file, String key) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".properties")) {
            Properties properties = new Properties();
            try {
                properties.load(new StringReader(file.getText()));
                return properties.getProperty(key);
            } catch (IOException ignored) {
                return null;
            }
        }

        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(file.getText());
        } catch (org.yaml.snakeyaml.error.YAMLException ignored) {
            return null;
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> flattened = new LinkedHashMap<>();
        flattenYaml("", map, flattened);
        return flattened.get(key);
    }

    private void flattenYaml(String prefix, Map<?, ?> source, Map<String, String> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flattenYaml(key, child, target);
            } else if (entry.getValue() != null
                    && !(entry.getValue() instanceof Iterable<?>)) {
                target.put(key, String.valueOf(entry.getValue()));
            }
        }
    }

    public record ResolvedValue(
            String value,
            @NotNull MqEndpoint.ResolutionState state,
            String message) {

        static ResolvedValue staticValue(String value) {
            return new ResolvedValue(value, MqEndpoint.ResolutionState.STATIC, null);
        }

        static ResolvedValue configValue(String value) {
            return new ResolvedValue(value, MqEndpoint.ResolutionState.CONFIG_RESOLVED, null);
        }

        static ResolvedValue dynamic(String message) {
            return new ResolvedValue(null, MqEndpoint.ResolutionState.DYNAMIC, message);
        }

        public boolean resolved() {
            return state == MqEndpoint.ResolutionState.STATIC
                    || state == MqEndpoint.ResolutionState.CONFIG_RESOLVED;
        }
    }
}
