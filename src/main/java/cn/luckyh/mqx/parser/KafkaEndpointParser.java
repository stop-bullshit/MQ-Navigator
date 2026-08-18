package cn.luckyh.mqx.parser;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.InheritanceUtil;
import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class KafkaEndpointParser implements MqEndpointParser {

    private static final String TEMPLATE = "org.springframework.kafka.core.KafkaTemplate";
    private static final String LISTENER = "org.springframework.kafka.annotation.KafkaListener";

    private final MqValueResolver valueResolver;

    public KafkaEndpointParser(MqValueResolver valueResolver) {
        this.valueResolver = valueResolver;
    }

    @Override
    public @NotNull List<MqEndpoint> collectEndpoints(
            @NotNull com.intellij.openapi.project.Project project,
            @NotNull GlobalSearchScope projectScope,
            @NotNull Set<Object> dependencies) {
        List<MqEndpoint> endpoints = new ArrayList<>();
        PsiClass listenerClass = JavaPsiFacade.getInstance(project)
                .findClass(LISTENER, GlobalSearchScope.allScope(project));
        if (listenerClass != null) {
            for (PsiMethod method : AnnotatedElementsSearch.searchPsiMethods(listenerClass, projectScope).findAll()) {
                endpoints.addAll(parseConsumers(method.getNameIdentifier(), dependencies));
            }
            for (PsiClass psiClass : AnnotatedElementsSearch.searchPsiClasses(listenerClass, projectScope).findAll()) {
                endpoints.addAll(parseConsumers(psiClass.getNameIdentifier(), dependencies));
            }
        }
        PsiClass templateClass = JavaPsiFacade.getInstance(project)
                .findClass(TEMPLATE, GlobalSearchScope.allScope(project));
        if (templateClass != null) {
            for (PsiMethod method : templateClass.findMethodsByName("send", true)) {
                MethodReferencesSearch.search(method, projectScope, true).forEach(reference -> {
                    if (reference.getElement() instanceof PsiReferenceExpression expression) {
                        parseAnchor(expression.getReferenceNameElement(), dependencies).ifPresent(endpoints::add);
                    }
                    return true;
                });
            }
        }
        return endpoints;
    }

    @Override
    public @NotNull Optional<MqEndpoint> parseAnchor(
            @NotNull PsiElement anchor,
            @NotNull Set<Object> dependencies) {
        PsiMethodCallExpression call = methodCall(anchor);
        if (call != null) {
            PsiMethod method = call.resolveMethod();
            if (method != null && "send".equals(method.getName()) && isKafkaTemplate(method.getContainingClass())) {
                if (method.getParameterList().getParametersCount() == 0
                        || !MqEndpointParser.isStringType(method.getParameterList().getParameters()[0].getType())
                        || call.getArgumentList().getExpressionCount() == 0) {
                    return Optional.empty();
                }
                MqValueResolver.ResolvedValue topic = valueResolver.resolveExpression(
                        call.getArgumentList().getExpressions()[0], dependencies);
                return Optional.of(MqEndpointParser.endpoint(anchor, MqEndpoint.SystemType.KAFKA,
                        MqEndpoint.Role.PRODUCER, MqDestination.kafkaTopic(topic.value(), null),
                        topic.state(), topic.message()));
            }
        }
        return parseConsumers(anchor, dependencies).stream().findFirst();
    }

    private List<MqEndpoint> parseConsumers(PsiElement anchor, Set<Object> dependencies) {
        if (!(anchor instanceof PsiIdentifier) || !(anchor.getParent() instanceof PsiModifierListOwner owner)) {
            return List.of();
        }
        PsiAnnotation listener = owner.getAnnotation(LISTENER);
        if (listener == null) {
            return List.of();
        }
        String group = annotationString(listener, "groupId", anchor, dependencies);
        if (group == null || group.isBlank()) {
            group = annotationString(listener, "id", anchor, dependencies);
        }
        List<MqEndpoint> endpoints = new ArrayList<>();
        for (PsiAnnotationMemberValue value : MqEndpointParser.annotationValues(listener, "topics")) {
            MqValueResolver.ResolvedValue topic = valueResolver.resolveAnnotationValue(value, anchor, dependencies);
            endpoints.add(MqEndpointParser.endpoint(anchor, MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.CONSUMER,
                    MqDestination.kafkaTopic(topic.value(), group), topic.state(), topic.message()));
        }
        PsiAnnotationMemberValue patternValue = listener.findDeclaredAttributeValue("topicPattern");
        if (patternValue != null) {
            MqValueResolver.ResolvedValue topicPattern = valueResolver.resolveAnnotationValue(
                    patternValue, anchor, dependencies);
            MqEndpoint.ResolutionState state = topicPattern.state();
            String message = topicPattern.message();
            if (topicPattern.resolved()) {
                try {
                    Pattern.compile(topicPattern.value());
                } catch (PatternSyntaxException exception) {
                    state = MqEndpoint.ResolutionState.INVALID;
                    message = "invalid Kafka topic pattern";
                }
            }
            endpoints.add(MqEndpointParser.endpoint(anchor, MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.CONSUMER,
                    MqDestination.kafkaPattern(topicPattern.value(), group), state, message));
        }
        return endpoints;
    }

    private String annotationString(
            PsiAnnotation annotation, String name, PsiElement context, Set<Object> dependencies) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(name);
        if (value == null) {
            return null;
        }
        return valueResolver.resolveAnnotationValue(value, context, dependencies).value();
    }

    private boolean isKafkaTemplate(PsiClass owner) {
        return owner != null && (TEMPLATE.equals(owner.getQualifiedName()) || InheritanceUtil.isInheritor(owner, TEMPLATE));
    }

    private PsiMethodCallExpression methodCall(PsiElement anchor) {
        if (!(anchor instanceof PsiIdentifier) || !(anchor.getParent() instanceof PsiReferenceExpression reference)) {
            return null;
        }
        return reference.getParent() instanceof PsiMethodCallExpression call ? call : null;
    }
}
