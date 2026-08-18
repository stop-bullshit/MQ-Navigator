package cn.luckyh.mqx.parser;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
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

public final class RocketMqEndpointParser implements MqEndpointParser {

    private static final String TEMPLATE = "org.apache.rocketmq.spring.core.RocketMQTemplate";
    private static final String LISTENER = "org.apache.rocketmq.spring.annotation.RocketMQMessageListener";
    private static final List<String> SEND_METHODS = List.of("syncSend", "asyncSend", "sendOneWay");

    private final MqValueResolver valueResolver;

    public RocketMqEndpointParser(MqValueResolver valueResolver) {
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
            for (PsiClass psiClass : AnnotatedElementsSearch.searchPsiClasses(listenerClass, projectScope).findAll()) {
                parseAnchor(psiClass.getNameIdentifier(), dependencies).ifPresent(endpoints::add);
            }
        }
        PsiClass templateClass = JavaPsiFacade.getInstance(project)
                .findClass(TEMPLATE, GlobalSearchScope.allScope(project));
        if (templateClass != null) {
            for (String methodName : SEND_METHODS) {
                for (PsiMethod method : templateClass.findMethodsByName(methodName, true)) {
                    MethodReferencesSearch.search(method, projectScope, true).forEach(reference -> {
                        if (reference.getElement() instanceof PsiReferenceExpression expression) {
                            parseAnchor(expression.getReferenceNameElement(), dependencies).ifPresent(endpoints::add);
                        }
                        return true;
                    });
                }
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
            if (method != null && SEND_METHODS.contains(method.getName()) && isTemplate(method.getContainingClass())
                    && method.getParameterList().getParametersCount() > 0
                    && MqEndpointParser.isStringType(method.getParameterList().getParameters()[0].getType())
                    && call.getArgumentList().getExpressionCount() > 0) {
                MqValueResolver.ResolvedValue value = valueResolver.resolveExpression(
                        call.getArgumentList().getExpressions()[0], dependencies);
                String destination = value.value() == null ? "" : value.value();
                int separator = destination.indexOf(':');
                String topic = separator >= 0 ? destination.substring(0, separator) : destination;
                String tag = separator >= 0 ? destination.substring(separator + 1) : "";
                return Optional.of(MqEndpointParser.endpoint(anchor, MqEndpoint.SystemType.ROCKETMQ,
                        MqEndpoint.Role.PRODUCER, MqDestination.rocketProducer(topic, tag),
                        value.state(), value.message()));
            }
        }

        if (!(anchor instanceof PsiIdentifier) || !(anchor.getParent() instanceof PsiClass psiClass)) {
            return Optional.empty();
        }
        PsiAnnotation listener = psiClass.getAnnotation(LISTENER);
        if (listener == null) {
            return Optional.empty();
        }
        MqValueResolver.ResolvedValue topic = annotationValue(listener, "topic", anchor, dependencies, "");
        MqValueResolver.ResolvedValue group = annotationValue(listener, "consumerGroup", anchor, dependencies, "");
        MqValueResolver.ResolvedValue type = annotationValue(listener, "selectorType", anchor, dependencies, "TAG");
        MqValueResolver.ResolvedValue selector = annotationValue(
                listener, "selectorExpression", anchor, dependencies, "*");
        MqEndpoint.ResolutionState state = topic.resolved()
                ? topic.state() : MqEndpoint.ResolutionState.DYNAMIC;
        return Optional.of(MqEndpointParser.endpoint(anchor, MqEndpoint.SystemType.ROCKETMQ,
                MqEndpoint.Role.CONSUMER,
                MqDestination.rocketConsumer(topic.value(), group.value(), type.value(), selector.value()),
                state, topic.message()));
    }

    private MqValueResolver.ResolvedValue annotationValue(
            PsiAnnotation annotation,
            String name,
            PsiElement context,
            Set<Object> dependencies,
            String defaultValue) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(name);
        if (value == null) {
            return MqValueResolver.ResolvedValue.staticValue(defaultValue);
        }
        String text = value.getText();
        if ("selectorType".equals(name) && text.contains(".")) {
            return MqValueResolver.ResolvedValue.staticValue(text.substring(text.lastIndexOf('.') + 1));
        }
        return valueResolver.resolveAnnotationValue(value, context, dependencies);
    }

    private boolean isTemplate(PsiClass owner) {
        return owner != null && (TEMPLATE.equals(owner.getQualifiedName()) || InheritanceUtil.isInheritor(owner, TEMPLATE));
    }

    private PsiMethodCallExpression methodCall(PsiElement anchor) {
        if (!(anchor instanceof PsiIdentifier) || !(anchor.getParent() instanceof PsiReferenceExpression reference)) {
            return null;
        }
        return reference.getParent() instanceof PsiMethodCallExpression call ? call : null;
    }
}
