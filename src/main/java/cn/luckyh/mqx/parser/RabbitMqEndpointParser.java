package cn.luckyh.mqx.parser;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiConditionalExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLoopStatement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiSwitchBlock;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.PsiTreeUtil;
import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RabbitMqEndpointParser implements MqEndpointParser {

    private static final List<String> TEMPLATE_TYPES = List.of(
            "org.springframework.amqp.rabbit.core.RabbitTemplate",
            "org.springframework.amqp.core.AmqpTemplate");
    private static final String QUEUE_BUILDER = "org.springframework.amqp.core.QueueBuilder";
    private static final String EXCHANGE_BUILDER = "org.springframework.amqp.core.ExchangeBuilder";
    private static final String BINDING_BUILDER = "org.springframework.amqp.core.BindingBuilder";
    private static final String LISTENER = "org.springframework.amqp.rabbit.annotation.RabbitListener";
    private static final String DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    private static final String DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";
    private static final String MESSAGE_TTL = "x-message-ttl";
    private static final Set<String> SPECIAL_QUEUE_ARGUMENTS = Set.of(
            DEAD_LETTER_EXCHANGE, DEAD_LETTER_ROUTING_KEY, MESSAGE_TTL);

    private final MqValueResolver valueResolver;

    public RabbitMqEndpointParser(MqValueResolver valueResolver) {
        this.valueResolver = valueResolver;
    }

    @Override
    public @NotNull List<MqEndpoint> collectEndpoints(
            @NotNull com.intellij.openapi.project.Project project,
            @NotNull GlobalSearchScope projectScope,
            @NotNull Set<Object> cacheDependencies) {
        List<MqEndpoint> endpoints = new ArrayList<>();
        PsiClass annotationClass = JavaPsiFacade.getInstance(project)
                .findClass(LISTENER, GlobalSearchScope.allScope(project));
        if (annotationClass != null) {
            for (PsiMethod method : AnnotatedElementsSearch.searchPsiMethods(annotationClass, projectScope).findAll()) {
                endpoints.addAll(parseConsumer(method.getNameIdentifier(), cacheDependencies));
            }
            for (PsiClass psiClass : AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope).findAll()) {
                endpoints.addAll(parseConsumer(psiClass.getNameIdentifier(), cacheDependencies));
            }
        }

        PsiClass queueBuilderClass = JavaPsiFacade.getInstance(project)
                .findClass(QUEUE_BUILDER, GlobalSearchScope.allScope(project));
        if (queueBuilderClass != null) {
            for (PsiMethod method : queueBuilderClass.findMethodsByName("durable", true)) {
                MethodReferencesSearch.search(method, projectScope, true).forEach(reference -> {
                    if (reference.getElement() instanceof PsiReferenceExpression expression) {
                        parseQueueDeclaration(expression.getReferenceNameElement(), cacheDependencies)
                                .ifPresent(endpoints::add);
                    }
                    return true;
                });
            }
        }
        // 逻辑变动: 将Exchange和Binding源码端点加入RabbitMQ项目拓扑快照-20260818-1700-01
        collectExchangeDeclarations(project, projectScope, cacheDependencies, endpoints);

        // 逻辑变动: 维护RabbitMQ队列到绑定交换机的一对多拓扑关系-20260818-01
        List<BindingRelation> bindings = collectBindings(project, projectScope, cacheDependencies);
        for (BindingRelation binding : bindings) {
            endpoints.add(bindingEndpoint(binding));
        }

        Set<PsiMethod> searchedMethods = new LinkedHashSet<>();
        Set<PsiMethod> producerWrappers = new LinkedHashSet<>();
        for (String templateType : TEMPLATE_TYPES) {
            PsiClass templateClass = JavaPsiFacade.getInstance(project)
                    .findClass(templateType, GlobalSearchScope.allScope(project));
            if (templateClass == null) {
                continue;
            }
            for (String methodName : List.of("send", "convertAndSend")) {
                for (PsiMethod method : templateClass.findMethodsByName(methodName, true)) {
                    if (!searchedMethods.add(method)) {
                        continue;
                    }
                    MethodReferencesSearch.search(method, projectScope, true).forEach(reference -> {
                        PsiElement element = reference.getElement();
                        if (element instanceof PsiReferenceExpression expression) {
                            PsiMethod wrapper = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                            if (isProducerWrapper(wrapper)) {
                                producerWrappers.add(wrapper);
                            } else {
                                endpoints.addAll(parseProducerEndpoints(
                                        expression.getReferenceNameElement(), cacheDependencies));
                            }
                        }
                        return true;
                    });
                }
            }
        }
        // 逻辑变动: 沿发送封装委托链收集业务调用并保留空路由键-20261023-1125-01
        List<PsiMethod> pendingWrappers = new ArrayList<>(producerWrappers);
        for (int index = 0; index < pendingWrappers.size(); index++) {
            PsiMethod wrapper = pendingWrappers.get(index);
            if (!searchedMethods.add(wrapper)) {
                continue;
            }
            MethodReferencesSearch.search(wrapper, projectScope, true).forEach(reference -> {
                if (reference.getElement() instanceof PsiReferenceExpression expression) {
                    PsiMethod caller = PsiTreeUtil.getParentOfType(expression, PsiMethod.class);
                    if (isProducerWrapper(caller)) {
                        if (producerWrappers.add(caller)) {
                            pendingWrappers.add(caller);
                        }
                    } else {
                        endpoints.addAll(parseProducerEndpoints(
                                expression.getReferenceNameElement(), cacheDependencies));
                    }
                }
                return true;
            });
        }
        return applyBindings(endpoints, bindings);
    }

    @Override
    public @NotNull Optional<MqEndpoint> parseAnchor(
            @NotNull PsiElement anchor,
            @NotNull Set<Object> cacheDependencies) {
        // Binding 和 Exchange 声明仅用于项目拓扑树，原有 gutter 双向跳转保持不变。
        PsiMethodCallExpression topologyCall = methodCall(anchor);
        if (topologyCall != null && ("bind".equals(topologyCall.getMethodExpression().getReferenceName())
                || isExchangeBuilderMethod(topologyCall.getMethodExpression().getReferenceName()))) {
            return Optional.empty();
        }
        List<MqEndpoint> producers = parseProducerEndpoints(anchor, cacheDependencies);
        if (!producers.isEmpty()) {
            return Optional.of(producers.get(0));
        }
        Optional<MqEndpoint> declaration = parseQueueDeclaration(anchor, cacheDependencies);
        if (declaration.isPresent()) {
            return declaration;
        }
        List<MqEndpoint> consumers = parseConsumer(anchor, cacheDependencies);
        return consumers.stream().findFirst();
    }

    private Optional<MqEndpoint> parseQueueDeclaration(PsiElement anchor, Set<Object> dependencies) {
        PsiMethodCallExpression call = methodCall(anchor);
        if (call == null || call.getArgumentList().getExpressionCount() == 0) {
            return Optional.empty();
        }
        PsiMethod method = call.resolveMethod();
        if (method == null || !"durable".equals(method.getName())
                || method.getContainingClass() == null
                || !QUEUE_BUILDER.equals(method.getContainingClass().getQualifiedName())
                || method.getParameterList().getParametersCount() == 0
                || !MqEndpointParser.isStringType(method.getParameterList().getParameters()[0].getType())) {
            return Optional.empty();
        }

        // 逻辑变动: 识别RabbitAdmin项目中QueueBuilder静态队列声明-20260817-2014-01
        MqValueResolver.ResolvedValue queue = valueResolver.resolveExpression(
                call.getArgumentList().getExpressions()[0], dependencies);
        String queueName = queue.value() == null ? "" : queue.value();
        return Optional.of(MqEndpointParser.endpoint(anchor, MqEndpoint.SystemType.RABBITMQ,
                MqEndpoint.Role.DECLARATION,
                MqDestination.rabbitQueue(queueName, resolveQueueSettings(call, dependencies)),
                queue.state(), queue.message()));
    }

    private MqDestination.RabbitQueueSettings resolveQueueSettings(
            PsiMethodCallExpression durableCall,
            Set<Object> dependencies) {
        Map<String, PsiExpression> argumentValues = new LinkedHashMap<>();
        Set<String> dynamicArguments = new LinkedHashSet<>();
        PsiMethodCallExpression current = durableCall;
        while ((current = nextChainedCall(current)) != null) {
            String methodName = current.getMethodExpression().getReferenceName();
            PsiExpression[] arguments = current.getArgumentList().getExpressions();
            if ("withArgument".equals(methodName) && arguments.length >= 2) {
                collectQueueArgument(
                        arguments[0], arguments[1], false,
                        argumentValues, dynamicArguments, dependencies);
            } else if ("withArguments".equals(methodName) && arguments.length > 0) {
                collectQueueArgumentMap(
                        arguments[0], current, argumentValues, dynamicArguments, dependencies);
            }
        }

        boolean deadLetterExchangeConfigured = configured(
                DEAD_LETTER_EXCHANGE, argumentValues, dynamicArguments);
        boolean deadLetterRoutingKeyConfigured = configured(
                DEAD_LETTER_ROUTING_KEY, argumentValues, dynamicArguments);
        boolean messageTtlConfigured = configured(MESSAGE_TTL, argumentValues, dynamicArguments);
        if (!deadLetterExchangeConfigured && !deadLetterRoutingKeyConfigured && !messageTtlConfigured) {
            return null;
        }

        List<String> diagnostics = new ArrayList<>();
        String deadLetterExchange = resolveQueueStringArgument(
                DEAD_LETTER_EXCHANGE, argumentValues, dynamicArguments, dependencies, diagnostics);
        String deadLetterRoutingKey = resolveQueueStringArgument(
                DEAD_LETTER_ROUTING_KEY, argumentValues, dynamicArguments, dependencies, diagnostics);
        Long messageTtlMillis = resolveMessageTtl(
                argumentValues, dynamicArguments, diagnostics, durableCall);
        return new MqDestination.RabbitQueueSettings(
                deadLetterExchangeConfigured, deadLetterExchange,
                deadLetterRoutingKeyConfigured, deadLetterRoutingKey,
                messageTtlConfigured, messageTtlMillis, diagnostics);
    }

    private void collectQueueArgumentMap(
            PsiExpression mapExpression,
            PsiMethodCallExpression withArgumentsCall,
            Map<String, PsiExpression> argumentValues,
            Set<String> dynamicArguments,
            Set<Object> dependencies) {
        if (mapExpression instanceof PsiMethodCallExpression mapCall && isMapOfCall(mapCall)) {
            PsiExpression[] arguments = mapCall.getArgumentList().getExpressions();
            for (int index = 0; index + 1 < arguments.length; index += 2) {
                collectQueueArgument(
                        arguments[index], arguments[index + 1], false,
                        argumentValues, dynamicArguments, dependencies);
            }
            return;
        }
        if (!(mapExpression instanceof PsiReferenceExpression reference)
                || !(reference.resolve() instanceof PsiVariable variable)) {
            return;
        }

        if (variable.getInitializer() instanceof PsiMethodCallExpression initializer
                && isMapOfCall(initializer)) {
            PsiExpression[] arguments = initializer.getArgumentList().getExpressions();
            for (int index = 0; index + 1 < arguments.length; index += 2) {
                collectQueueArgument(
                        arguments[index], arguments[index + 1], false,
                        argumentValues, dynamicArguments, dependencies);
            }
        }

        PsiMethod owner = PsiTreeUtil.getParentOfType(withArgumentsCall, PsiMethod.class);
        if (owner == null) {
            return;
        }
        List<PsiMethodCallExpression> calls = new ArrayList<>(
                PsiTreeUtil.findChildrenOfType(owner, PsiMethodCallExpression.class));
        calls.sort(Comparator.comparingInt(PsiElement::getTextOffset));
        for (PsiMethodCallExpression call : calls) {
            if (call.getTextOffset() >= withArgumentsCall.getTextOffset()
                    || !"put".equals(call.getMethodExpression().getReferenceName())) {
                continue;
            }
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            PsiExpression[] arguments = call.getArgumentList().getExpressions();
            if (!(qualifier instanceof PsiReferenceExpression mapReference)
                    || mapReference.resolve() != variable || arguments.length < 2) {
                continue;
            }
            collectQueueArgument(
                    arguments[0], arguments[1], isConditional(call),
                    argumentValues, dynamicArguments, dependencies);
        }
    }

    private void collectQueueArgument(
            PsiExpression keyExpression,
            PsiExpression valueExpression,
            boolean conditional,
            Map<String, PsiExpression> argumentValues,
            Set<String> dynamicArguments,
            Set<Object> dependencies) {
        MqValueResolver.ResolvedValue key = valueResolver.resolveExpression(keyExpression, dependencies);
        if (!key.resolved() || !SPECIAL_QUEUE_ARGUMENTS.contains(key.value())) {
            return;
        }
        if (conditional) {
            argumentValues.remove(key.value());
            dynamicArguments.add(key.value());
            return;
        }
        dynamicArguments.remove(key.value());
        argumentValues.put(key.value(), valueExpression);
    }

    private boolean isMapOfCall(PsiMethodCallExpression call) {
        PsiMethod method = call.resolveMethod();
        if (method != null
                && "of".equals(method.getName())
                && method.getContainingClass() != null
                && "java.util.Map".equals(method.getContainingClass().getQualifiedName())) {
            return true;
        }
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        PsiClass resultType = PsiUtil.resolveClassInClassTypeOnly(call.getType());
        return "of".equals(call.getMethodExpression().getReferenceName())
                && (resultType != null && "java.util.Map".equals(resultType.getQualifiedName())
                || qualifier != null && List.of("Map", "java.util.Map").contains(qualifier.getText()));
    }

    private String resolveQueueStringArgument(
            String argumentName,
            Map<String, PsiExpression> argumentValues,
            Set<String> dynamicArguments,
            Set<Object> dependencies,
            List<String> diagnostics) {
        if (dynamicArguments.contains(argumentName)) {
            diagnostics.add(argumentName + " is not statically resolved");
            return null;
        }
        PsiExpression expression = argumentValues.get(argumentName);
        if (expression == null) {
            return null;
        }
        MqValueResolver.ResolvedValue value = valueResolver.resolveExpression(expression, dependencies);
        if (!value.resolved()) {
            diagnostics.add(argumentName + " is not statically resolved");
            return null;
        }
        return value.value();
    }

    private Long resolveMessageTtl(
            Map<String, PsiExpression> argumentValues,
            Set<String> dynamicArguments,
            List<String> diagnostics,
            PsiElement context) {
        if (dynamicArguments.contains(MESSAGE_TTL)) {
            diagnostics.add(MESSAGE_TTL + " is not statically resolved");
            return null;
        }
        PsiExpression expression = argumentValues.get(MESSAGE_TTL);
        if (expression == null) {
            return null;
        }
        Object value = JavaPsiFacade.getInstance(context.getProject())
                .getConstantEvaluationHelper().computeConstantExpression(expression);
        if (!(value instanceof Number number)) {
            diagnostics.add(MESSAGE_TTL + " must be a non-negative whole number");
            return null;
        }
        long millis = number.longValue();
        if (millis < 0 || number.doubleValue() != millis) {
            diagnostics.add(MESSAGE_TTL + " must be a non-negative whole number");
            return null;
        }
        return millis;
    }

    private boolean configured(
            String argumentName,
            Map<String, PsiExpression> argumentValues,
            Set<String> dynamicArguments) {
        return argumentValues.containsKey(argumentName) || dynamicArguments.contains(argumentName);
    }

    private boolean isConditional(PsiElement element) {
        PsiMethod owner = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        for (PsiElement parent = element.getParent(); parent != null && parent != owner; parent = parent.getParent()) {
            if (parent instanceof PsiIfStatement
                    || parent instanceof PsiLoopStatement
                    || parent instanceof PsiConditionalExpression
                    || parent instanceof PsiSwitchBlock) {
                return true;
            }
        }
        return false;
    }

    private PsiMethodCallExpression nextChainedCall(PsiMethodCallExpression call) {
        return call.getParent() instanceof PsiReferenceExpression reference
                && reference.getParent() instanceof PsiMethodCallExpression chained ? chained : null;
    }

    private List<MqEndpoint> parseProducerEndpoints(PsiElement anchor, Set<Object> dependencies) {
        PsiMethodCallExpression call = methodCall(anchor);
        if (call == null) {
            return List.of();
        }
        PsiMethod method = call.resolveMethod();
        if (method == null) {
            return List.of();
        }
        if (isTemplateSendMethod(method)) {
            return parseTemplateProducerEndpoints(anchor, call, method, dependencies);
        }
        if (!isProducerWrapper(method)) {
            return List.of();
        }

        Map<PsiParameter, List<MqValueResolver.ResolvedValue>> parameterValues = new LinkedHashMap<>();
        PsiParameter[] parameters = method.getParameterList().getParameters();
        PsiExpression[] arguments = call.getArgumentList().getExpressions();
        for (int index = 0; index < Math.min(parameters.length, arguments.length); index++) {
            parameterValues.put(parameters[index],
                    valueResolver.resolveExpressionAlternatives(arguments[index], dependencies));
        }
        return parseWrapperProducerEndpoints(anchor, method, parameterValues, dependencies,
                new LinkedHashSet<>(), new LinkedHashMap<>());
    }

    private void collectExchangeDeclarations(
            com.intellij.openapi.project.Project project,
            GlobalSearchScope projectScope,
            Set<Object> dependencies,
            List<MqEndpoint> endpoints) {
        PsiClass exchangeBuilderClass = JavaPsiFacade.getInstance(project)
                .findClass(EXCHANGE_BUILDER, GlobalSearchScope.allScope(project));
        if (exchangeBuilderClass == null) {
            return;
        }
        for (String methodName : List.of(
                "directExchange", "topicExchange", "fanoutExchange", "headersExchange")) {
            for (PsiMethod method : exchangeBuilderClass.findMethodsByName(methodName, true)) {
                MethodReferencesSearch.search(method, projectScope, true).forEach(reference -> {
                    PsiMethodCallExpression call = methodCall(reference.getElement());
                    if (call == null || call.getArgumentList().getExpressionCount() == 0) {
                        return true;
                    }
                    PsiElement anchor = call.getMethodExpression().getReferenceNameElement();
                    if (anchor == null) {
                        return true;
                    }
                    MqValueResolver.ResolvedValue exchange = valueResolver.resolveExpression(
                            call.getArgumentList().getExpressions()[0], dependencies);
                    String exchangeName = exchange.value() == null ? "" : exchange.value();
                    String exchangeType = methodName.substring(0, methodName.length() - "Exchange".length());
                    endpoints.add(MqEndpointParser.endpoint(anchor, MqEndpoint.SystemType.RABBITMQ,
                            MqEndpoint.Role.EXCHANGE_DECLARATION,
                            MqDestination.rabbitConsumer(exchangeName, "", List.of(), exchangeType),
                            exchange.state(), exchange.message()));
                    return true;
                });
            }
        }
    }

    private List<MqEndpoint> parseTemplateProducerEndpoints(
            PsiElement anchor,
            PsiMethodCallExpression call,
            PsiMethod method,
            Set<Object> dependencies) {
        int leadingStrings = leadingStringParameters(method);
        if (call.getArgumentList().getExpressionCount() == 0) {
            return List.of(dynamicProducer(anchor, "RabbitMQ producer destination uses template defaults"));
        }
        var arguments = call.getArgumentList().getExpressions();
        List<MqValueResolver.ResolvedValue> exchanges;
        List<MqValueResolver.ResolvedValue> routingKeys;
        if (leadingStrings >= 2 && arguments.length > 1) {
            exchanges = valueResolver.resolveExpressionAlternatives(arguments[0], dependencies);
            routingKeys = valueResolver.resolveExpressionAlternatives(arguments[1], dependencies);
        } else {
            exchanges = List.of(MqValueResolver.ResolvedValue.staticValue(""));
            routingKeys = valueResolver.resolveExpressionAlternatives(arguments[0], dependencies);
        }
        return producerEndpoints(anchor, exchanges, routingKeys);
    }

    /**
     * 沿发送封装逐层映射实参，跳过循环委托；同一方法的相同实参只解析一次，避免汇合分支重复展开。
     */
    private List<MqEndpoint> parseWrapperProducerEndpoints(
            PsiElement anchor,
            PsiMethod wrapper,
            Map<PsiParameter, List<MqValueResolver.ResolvedValue>> parameterValues,
            Set<Object> dependencies,
            Set<PsiMethod> visited,
            Map<PsiMethod, Set<Map<PsiParameter, List<MqValueResolver.ResolvedValue>>>> parsedWrappers) {
        if (!visited.add(wrapper)
                || !parsedWrappers.computeIfAbsent(wrapper, ignored -> new LinkedHashSet<>()).add(parameterValues)) {
            return List.of();
        }
        List<MqEndpoint> endpoints = new ArrayList<>();
        for (PsiMethodCallExpression templateCall : methodCalls(wrapper)) {
            PsiMethod templateMethod = templateCall.resolveMethod();
            if (templateMethod == null) {
                continue;
            }
            if (!isTemplateSendMethod(templateMethod)) {
                if (isProducerWrapper(templateMethod)) {
                    Map<PsiParameter, List<MqValueResolver.ResolvedValue>> nestedValues = new LinkedHashMap<>();
                    PsiParameter[] parameters = templateMethod.getParameterList().getParameters();
                    PsiExpression[] arguments = templateCall.getArgumentList().getExpressions();
                    for (int index = 0; index < Math.min(parameters.length, arguments.length); index++) {
                        nestedValues.put(parameters[index], resolveWrapperExpressionAlternatives(
                                arguments[index], parameterValues, dependencies, new LinkedHashSet<>()));
                    }
                    endpoints.addAll(parseWrapperProducerEndpoints(anchor, templateMethod, nestedValues,
                            dependencies, new LinkedHashSet<>(visited), parsedWrappers));
                }
                continue;
            }
            int leadingStrings = leadingStringParameters(templateMethod);
            if (templateCall.getArgumentList().getExpressionCount() == 0) {
                endpoints.add(dynamicProducer(anchor, "RabbitMQ producer destination uses template defaults"));
                continue;
            }
            PsiExpression[] arguments = templateCall.getArgumentList().getExpressions();
            List<MqValueResolver.ResolvedValue> exchanges;
            List<MqValueResolver.ResolvedValue> routingKeys;
            if (leadingStrings >= 2 && arguments.length > 1) {
                exchanges = resolveWrapperExpressionAlternatives(
                        arguments[0], parameterValues, dependencies, new LinkedHashSet<>());
                routingKeys = resolveWrapperExpressionAlternatives(
                        arguments[1], parameterValues, dependencies, new LinkedHashSet<>());
            } else {
                exchanges = List.of(MqValueResolver.ResolvedValue.staticValue(""));
                routingKeys = resolveWrapperExpressionAlternatives(
                        arguments[0], parameterValues, dependencies, new LinkedHashSet<>());
            }
            endpoints.addAll(producerEndpoints(anchor, exchanges, routingKeys));
        }
        return endpoints;
    }

    private List<MqEndpoint> producerEndpoints(
            PsiElement anchor,
            List<MqValueResolver.ResolvedValue> exchanges,
            List<MqValueResolver.ResolvedValue> routingKeys) {
        List<MqEndpoint> endpoints = new ArrayList<>();
        for (MqValueResolver.ResolvedValue exchange : exchanges) {
            for (MqValueResolver.ResolvedValue routingKey : routingKeys) {
                MqEndpoint.ResolutionState state = combinedState(exchange, routingKey);
                String message = state == MqEndpoint.ResolutionState.DYNAMIC
                        ? firstMessage(exchange, routingKey) : null;
                MqDestination destination = MqDestination.rabbitProducer(
                        exchange.value() == null ? "" : exchange.value(),
                        routingKey.value() == null ? "" : routingKey.value());
                endpoints.add(MqEndpointParser.endpoint(anchor, MqEndpoint.SystemType.RABBITMQ,
                        MqEndpoint.Role.PRODUCER, destination, state, message));
            }
        }
        return endpoints;
    }

    private boolean isTemplateSendMethod(PsiMethod method) {
        if (method == null || !List.of("send", "convertAndSend").contains(method.getName())) {
            return false;
        }
        PsiClass owner = method.getContainingClass();
        return owner != null && TEMPLATE_TYPES.stream().anyMatch(type -> isType(owner, type));
    }

    private boolean isProducerWrapper(PsiMethod method) {
        if (!wrapperDestinationParameters(method).isEmpty()) {
            return true;
        }
        return methodCalls(method).stream().anyMatch(call -> isTemplateSendMethod(call.resolveMethod())
                && call.getArgumentList().getExpressionCount() == 0);
    }

    // 逻辑变动: 仅将传入MQ目的地的参数沿委托链回溯为发送封装-20261023-1125-01
    private Set<PsiParameter> wrapperDestinationParameters(PsiMethod method) {
        if (method == null || method.getBody() == null) {
            return Set.of();
        }
        List<PsiMethod> methods = new ArrayList<>(List.of(method));
        Map<PsiMethod, List<PsiMethodCallExpression>> callsByMethod = new LinkedHashMap<>();
        Map<PsiMethod, Set<PsiParameter>> parametersByMethod = new LinkedHashMap<>();
        parametersByMethod.put(method, new LinkedHashSet<>());
        for (int index = 0; index < methods.size(); index++) {
            PsiMethod current = methods.get(index);
            List<PsiMethodCallExpression> calls = methodCalls(current);
            callsByMethod.put(current, calls);
            for (PsiMethodCallExpression call : calls) {
                PsiMethod target = call.resolveMethod();
                if (target == null) {
                    continue;
                }
                PsiExpression[] arguments = call.getArgumentList().getExpressions();
                if (isTemplateSendMethod(target)) {
                    int count = Math.min(leadingStringParameters(target) >= 2 ? 2 : 1, arguments.length);
                    for (int argument = 0; argument < count; argument++) {
                        if (isWrapperParameter(arguments[argument], current)) {
                            parametersByMethod.get(current).add(
                                    (PsiParameter) ((PsiReferenceExpression) arguments[argument]).resolve());
                        }
                    }
                    continue;
                }
                if (target.getBody() == null || parametersByMethod.containsKey(target)) {
                    continue;
                }
                for (PsiExpression argument : arguments) {
                    if (isWrapperParameter(argument, current)) {
                        parametersByMethod.put(target, new LinkedHashSet<>());
                        methods.add(target);
                        break;
                    }
                }
            }
        }

        // 每个方法只展开一次，再传播目的地参数，避免重复分支指数扫描并使循环委托收敛。
        boolean changed;
        do {
            changed = false;
            for (int index = methods.size() - 1; index >= 0; index--) {
                PsiMethod current = methods.get(index);
                for (PsiMethodCallExpression call : callsByMethod.get(current)) {
                    PsiMethod target = call.resolveMethod();
                    Set<PsiParameter> targetParameters = parametersByMethod.get(target);
                    if (targetParameters == null) {
                        continue;
                    }
                    PsiExpression[] arguments = call.getArgumentList().getExpressions();
                    for (PsiParameter parameter : List.copyOf(targetParameters)) {
                        int argument = target.getParameterList().getParameterIndex(parameter);
                        if (argument >= 0 && argument < arguments.length
                                && isWrapperParameter(arguments[argument], current)) {
                            changed |= parametersByMethod.get(current).add(
                                    (PsiParameter) ((PsiReferenceExpression) arguments[argument]).resolve());
                        }
                    }
                }
            }
        } while (changed);
        return parametersByMethod.get(method);
    }

    private List<PsiMethodCallExpression> methodCalls(PsiMethod method) {
        if (method == null || method.getBody() == null) {
            return List.of();
        }
        return PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression.class).stream()
                .filter(call -> PsiTreeUtil.getParentOfType(call, PsiMethod.class) == method)
                .toList();
    }

    private boolean isWrapperParameter(PsiExpression expression, PsiMethod wrapper) {
        return expression instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiParameter parameter
                && parameter.getDeclarationScope() == wrapper;
    }

    private List<MqValueResolver.ResolvedValue> resolveWrapperExpressionAlternatives(
            PsiExpression expression,
            Map<PsiParameter, List<MqValueResolver.ResolvedValue>> parameterValues,
            Set<Object> dependencies,
            Set<PsiElement> visited) {
        if (!visited.add(expression)) {
            return List.of(MqValueResolver.ResolvedValue.dynamic("RabbitMQ wrapper expression is recursive"));
        }
        if (expression instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiParameter parameter) {
            return parameterValues.getOrDefault(parameter,
                    List.of(MqValueResolver.ResolvedValue.dynamic("RabbitMQ wrapper argument is missing")));
        }
        if (expression instanceof PsiConditionalExpression conditional
                && conditional.getThenExpression() != null
                && conditional.getElseExpression() != null) {
            List<MqValueResolver.ResolvedValue> result = new ArrayList<>();
            for (PsiExpression branch : List.of(
                    conditional.getThenExpression(), conditional.getElseExpression())) {
                List<MqValueResolver.ResolvedValue> branchValues = resolveWrapperExpressionAlternatives(
                        branch, parameterValues, dependencies, new LinkedHashSet<>(visited));
                if (branchValues.stream().anyMatch(value -> !value.resolved())) {
                    return branchValues;
                }
                result.addAll(branchValues);
            }
            return result.stream().distinct().toList();
        }
        if (expression instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiVariable variable
                && variable.getInitializer() != null) {
            return resolveWrapperExpressionAlternatives(
                    variable.getInitializer(), parameterValues, dependencies, visited);
        }
        return List.of(valueResolver.resolveExpression(expression, dependencies));
    }

    private List<BindingRelation> collectBindings(
            com.intellij.openapi.project.Project project,
            GlobalSearchScope projectScope,
            Set<Object> dependencies) {
        PsiClass bindingBuilderClass = JavaPsiFacade.getInstance(project)
                .findClass(BINDING_BUILDER, GlobalSearchScope.allScope(project));
        if (bindingBuilderClass == null) {
            return List.of();
        }

        List<BindingRelation> bindings = new ArrayList<>();
        for (PsiMethod bindMethod : bindingBuilderClass.findMethodsByName("bind", true)) {
            MethodReferencesSearch.search(bindMethod, projectScope, true).forEach(reference -> {
                PsiMethodCallExpression bindCall = methodCall(reference.getElement());
                if (bindCall == null || bindCall.getArgumentList().getExpressionCount() == 0) {
                    return true;
                }
                PsiMethodCallExpression toCall = chainedCall(bindCall, "to");
                if (toCall == null || toCall.getArgumentList().getExpressionCount() == 0) {
                    return true;
                }
                MqValueResolver.ResolvedValue queue = resolveBeanValue(
                        bindCall.getArgumentList().getExpressions()[0], false, dependencies);
                MqValueResolver.ResolvedValue exchange = resolveBeanValue(
                        toCall.getArgumentList().getExpressions()[0], true, dependencies);
                MqValueResolver.ResolvedValue routingKey = resolveBindingKey(toCall, dependencies);
                String exchangeType = resolveExchangeType(toCall.getArgumentList().getExpressions()[0]);
                PsiElement anchor = bindCall.getMethodExpression().getReferenceNameElement();
                if (anchor == null) {
                    return true;
                }
                bindings.add(new BindingRelation(anchor, queue, exchange, routingKey, exchangeType));
                return true;
            });
        }
        return bindings;
    }

    private MqEndpoint bindingEndpoint(BindingRelation binding) {
        MqEndpoint.ResolutionState state = combinedState(
                binding.queue, binding.exchange, binding.routingKey);
        List<String> keys = binding.routingKey.value() == null || binding.routingKey.value().isBlank()
                ? List.of() : List.of(binding.routingKey.value());
        MqDestination destination = MqDestination.rabbitConsumer(
                binding.exchange.value() == null ? "" : binding.exchange.value(),
                binding.queue.value() == null ? "" : binding.queue.value(),
                keys,
                binding.exchangeType);
        return MqEndpointParser.endpoint(binding.anchor, MqEndpoint.SystemType.RABBITMQ,
                MqEndpoint.Role.BINDING, destination, state,
                state == MqEndpoint.ResolutionState.DYNAMIC
                        ? firstMessage(binding.queue, binding.exchange, binding.routingKey) : null);
    }

    private List<MqEndpoint> applyBindings(List<MqEndpoint> endpoints, List<BindingRelation> bindings) {
        if (bindings.isEmpty()) {
            return endpoints;
        }
        List<MqEndpoint> result = new ArrayList<>();
        for (MqEndpoint endpoint : endpoints) {
            String queue = endpoint.destination().queue();
            if (endpoint.system() != MqEndpoint.SystemType.RABBITMQ
                    || queue == null || queue.isEmpty()
                    || (endpoint.role() != MqEndpoint.Role.CONSUMER
                    && endpoint.role() != MqEndpoint.Role.DECLARATION)
                    || (endpoint.destination().exchange() != null && !endpoint.destination().exchange().isEmpty())) {
                result.add(endpoint);
                continue;
            }
            List<BindingRelation> matches = bindings.stream()
                    .filter(binding -> binding.queue.resolved() && queue.equals(binding.queue.value()))
                    .toList();
            if (matches.isEmpty()) {
                result.add(endpoint);
                continue;
            }
            for (BindingRelation binding : matches) {
                MqEndpoint.ResolutionState state = combinedState(
                        binding.queue, binding.exchange, binding.routingKey);
                List<String> keys = binding.routingKey.value() == null || binding.routingKey.value().isBlank()
                        ? List.of() : List.of(binding.routingKey.value());
                MqDestination destination = endpoint.role() == MqEndpoint.Role.DECLARATION
                        ? MqDestination.rabbitQueue(
                        binding.exchange.value() == null ? "" : binding.exchange.value(),
                        queue, keys, binding.exchangeType, endpoint.destination().rabbitQueueSettings())
                        : MqDestination.rabbitConsumer(
                        binding.exchange.value() == null ? "" : binding.exchange.value(),
                        queue, keys, binding.exchangeType);
                result.add(copyWithDestination(endpoint, destination, state,
                        state == MqEndpoint.ResolutionState.DYNAMIC
                                ? firstMessage(binding.queue, binding.exchange, binding.routingKey) : null));
            }
        }
        return result;
    }

    private MqEndpoint copyWithDestination(
            MqEndpoint endpoint,
            MqDestination destination,
            MqEndpoint.ResolutionState state,
            String message) {
        return new MqEndpoint(endpoint.endpointId() + ":" + destination.summary(), endpoint.system(), endpoint.role(),
                destination, state, message, endpoint.sourcePointer(), endpoint.moduleName(), endpoint.fileName(),
                endpoint.className(), endpoint.memberName());
    }

    private MqValueResolver.ResolvedValue resolveBeanValue(
            PsiExpression expression, boolean exchange, Set<Object> dependencies) {
        if (!(expression instanceof PsiMethodCallExpression call)) {
            return valueResolver.resolveExpression(expression, dependencies);
        }
        PsiMethod method = call.resolveMethod();
        if (method == null) {
            return MqValueResolver.ResolvedValue.dynamic("RabbitMQ bean method cannot be resolved");
        }
        for (PsiMethodCallExpression nested : PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression.class)) {
            String name = nested.getMethodExpression().getReferenceName();
            if ((!exchange && "durable".equals(name)) || (exchange && isExchangeBuilderMethod(name))) {
                if (nested.getArgumentList().getExpressionCount() == 0) {
                    continue;
                }
                return valueResolver.resolveExpression(nested.getArgumentList().getExpressions()[0], dependencies);
            }
        }
        for (PsiNewExpression creation : PsiTreeUtil.findChildrenOfType(method, PsiNewExpression.class)) {
            if (creation.getArgumentList() == null || creation.getArgumentList().getExpressionCount() == 0) {
                continue;
            }
            String type = creation.getClassReference() == null
                    ? "" : creation.getClassReference().getReferenceName();
            type = type == null ? "" : type;
            if ((!exchange && "Queue".equals(type)) || (exchange && type.endsWith("Exchange"))) {
                return valueResolver.resolveExpression(creation.getArgumentList().getExpressions()[0], dependencies);
            }
        }
        for (PsiReturnStatement statement : PsiTreeUtil.findChildrenOfType(method, PsiReturnStatement.class)) {
            PsiExpression returnExpression = statement.getReturnValue();
            if (returnExpression instanceof PsiReferenceExpression reference
                    && reference.resolve() instanceof PsiVariable variable
                    && variable.getInitializer() != null) {
                PsiExpression initializer = variable.getInitializer();
                MqValueResolver.ResolvedValue value = resolveBeanExpression(initializer, exchange, dependencies);
                if (value != null) {
                    return value;
                }
            }
        }
        return MqValueResolver.ResolvedValue.dynamic("RabbitMQ bean name is not statically resolved");
    }

    private MqValueResolver.ResolvedValue resolveBeanExpression(
            PsiExpression expression, boolean exchange, Set<Object> dependencies) {
        if (expression instanceof PsiMethodCallExpression call && call.resolveMethod() != null) {
            return resolveBeanValue(call, exchange, dependencies);
        }
        if (expression instanceof PsiNewExpression creation && creation.getArgumentList() != null
                && creation.getArgumentList().getExpressionCount() > 0) {
            return valueResolver.resolveExpression(creation.getArgumentList().getExpressions()[0], dependencies);
        }
        return valueResolver.resolveExpression(expression, dependencies);
    }

    private MqValueResolver.ResolvedValue resolveBindingKey(
            PsiMethodCallExpression toCall, Set<Object> dependencies) {
        PsiMethodCallExpression withCall = chainedCall(toCall, "with");
        if (withCall == null || withCall.getArgumentList().getExpressionCount() == 0) {
            return MqValueResolver.ResolvedValue.staticValue("");
        }
        return valueResolver.resolveExpression(withCall.getArgumentList().getExpressions()[0], dependencies);
    }

    private String resolveExchangeType(PsiExpression expression) {
        if (!(expression instanceof PsiMethodCallExpression call) || call.resolveMethod() == null) {
            return "direct";
        }
        for (PsiMethodCallExpression nested : PsiTreeUtil.findChildrenOfType(
                call.resolveMethod(), PsiMethodCallExpression.class)) {
            String name = nested.getMethodExpression().getReferenceName();
            if (isExchangeBuilderMethod(name)) {
                return name.substring(0, name.length() - "Exchange".length());
            }
        }
        for (PsiNewExpression creation : PsiTreeUtil.findChildrenOfType(
                call.resolveMethod(), PsiNewExpression.class)) {
            if (creation.getClassReference() != null) {
                String type = creation.getClassReference().getReferenceName();
                if (type != null && type.endsWith("Exchange")) {
                    return type.substring(0, type.length() - "Exchange".length()).toLowerCase();
                }
            }
        }
        return "direct";
    }

    private boolean isExchangeBuilderMethod(String name) {
        return List.of("directExchange", "topicExchange", "fanoutExchange", "headersExchange")
                .contains(name);
    }

    private PsiMethodCallExpression chainedCall(PsiMethodCallExpression call, String methodName) {
        if (!(call.getParent() instanceof PsiReferenceExpression reference)
                || !methodName.equals(reference.getReferenceName())
                || !(reference.getParent() instanceof PsiMethodCallExpression chained)) {
            return null;
        }
        return chained;
    }

    private record BindingRelation(
            PsiElement anchor,
            MqValueResolver.ResolvedValue queue,
            MqValueResolver.ResolvedValue exchange,
            MqValueResolver.ResolvedValue routingKey,
            String exchangeType) {
    }

    private List<MqEndpoint> parseConsumer(PsiElement anchor, Set<Object> dependencies) {
        if (!(anchor instanceof PsiIdentifier) || !(anchor.getParent() instanceof PsiModifierListOwner owner)) {
            return List.of();
        }
        PsiAnnotation listener = MqEndpointParser.annotation(owner, LISTENER);
        if (listener == null) {
            return List.of();
        }

        List<MqEndpoint> endpoints = new ArrayList<>();
        for (PsiAnnotationMemberValue value : MqEndpointParser.annotationValues(listener, "queues")) {
            MqValueResolver.ResolvedValue queue = valueResolver.resolveAnnotationValue(value, anchor, dependencies);
            endpoints.add(consumer(anchor, "", queue, List.of(queue.value() == null ? "" : queue.value()),
                    "direct", queue.state(), queue.message()));
        }
        for (PsiAnnotationMemberValue value : MqEndpointParser.annotationValues(listener, "queuesToDeclare")) {
            if (value instanceof PsiAnnotation queueAnnotation) {
                MqValueResolver.ResolvedValue queue = annotationValue(queueAnnotation, "value", anchor, dependencies);
                endpoints.add(consumer(anchor, "", queue, List.of(queue.value() == null ? "" : queue.value()),
                        "direct", queue.state(), queue.message()));
            }
        }
        for (PsiAnnotationMemberValue value : MqEndpointParser.annotationValues(listener, "bindings")) {
            if (value instanceof PsiAnnotation binding) {
                endpoints.addAll(parseBinding(anchor, binding, dependencies));
            }
        }
        if (endpoints.isEmpty()) {
            endpoints.add(consumer(anchor, "", MqValueResolver.ResolvedValue.dynamic("Rabbit listener queue is dynamic"),
                    List.of(), "direct", MqEndpoint.ResolutionState.DYNAMIC, "Rabbit listener queue is dynamic"));
        }
        return endpoints;
    }

    private List<MqEndpoint> parseBinding(PsiElement anchor, PsiAnnotation binding, Set<Object> dependencies) {
        PsiAnnotationMemberValue queueValue = binding.findAttributeValue("value");
        PsiAnnotationMemberValue exchangeValue = binding.findAttributeValue("exchange");
        if (!(queueValue instanceof PsiAnnotation queueAnnotation)
                || !(exchangeValue instanceof PsiAnnotation exchangeAnnotation)) {
            return List.of();
        }
        MqValueResolver.ResolvedValue queue = annotationValue(queueAnnotation, "value", anchor, dependencies);
        MqValueResolver.ResolvedValue exchange = annotationValue(exchangeAnnotation, "value", anchor, dependencies);
        MqValueResolver.ResolvedValue type = annotationValue(exchangeAnnotation, "type", anchor, dependencies);
        List<MqValueResolver.ResolvedValue> keys = new ArrayList<>();
        for (PsiAnnotationMemberValue key : MqEndpointParser.annotationValues(binding, "key")) {
            keys.add(valueResolver.resolveAnnotationValue(key, anchor, dependencies));
        }
        if (keys.isEmpty()) {
            keys.add(MqValueResolver.ResolvedValue.staticValue(""));
        }

        List<MqEndpoint> endpoints = new ArrayList<>();
        for (MqValueResolver.ResolvedValue key : keys) {
            MqEndpoint.ResolutionState state = combinedState(queue, exchange, type, key);
            String exchangeType = type.value() == null || type.value().isBlank() ? "direct" : type.value();
            endpoints.add(consumer(anchor, exchange.value() == null ? "" : exchange.value(), queue,
                    key.value() == null || key.value().isBlank() ? List.of() : List.of(key.value()),
                    exchangeType, state, state == MqEndpoint.ResolutionState.DYNAMIC
                            ? firstMessage(queue, exchange, type, key) : null));
        }
        return endpoints;
    }

    private MqEndpoint consumer(
            PsiElement anchor,
            String exchange,
            MqValueResolver.ResolvedValue queue,
            List<String> keys,
            String exchangeType,
            MqEndpoint.ResolutionState state,
            String message) {
        return MqEndpointParser.endpoint(anchor, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.CONSUMER,
                MqDestination.rabbitConsumer(exchange, queue.value() == null ? "" : queue.value(), keys, exchangeType),
                state, message);
    }

    private MqEndpoint dynamicProducer(PsiElement anchor, String message) {
        return MqEndpointParser.endpoint(anchor, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.PRODUCER,
                MqDestination.rabbitProducer("", ""), MqEndpoint.ResolutionState.DYNAMIC, message);
    }

    private MqValueResolver.ResolvedValue annotationValue(
            PsiAnnotation annotation, String name, PsiElement context, Set<Object> dependencies) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(name);
        if (value == null) {
            return MqValueResolver.ResolvedValue.staticValue("");
        }
        return valueResolver.resolveAnnotationValue(value, context, dependencies);
    }

    private boolean isType(PsiClass owner, String qualifiedName) {
        return qualifiedName.equals(owner.getQualifiedName()) || InheritanceUtil.isInheritor(owner, qualifiedName);
    }

    private int leadingStringParameters(PsiMethod method) {
        int count = 0;
        for (var parameter : method.getParameterList().getParameters()) {
            if (!MqEndpointParser.isStringType(parameter.getType())) {
                break;
            }
            count++;
        }
        return count;
    }

    private PsiMethodCallExpression methodCall(PsiElement anchor) {
        PsiReferenceExpression reference;
        if (anchor instanceof PsiReferenceExpression expression) {
            reference = expression;
        } else if (anchor instanceof PsiIdentifier
                && anchor.getParent() instanceof PsiReferenceExpression expression) {
            reference = expression;
        } else {
            return null;
        }
        return reference.getParent() instanceof PsiMethodCallExpression call ? call : null;
    }

    private MqEndpoint.ResolutionState combinedState(MqValueResolver.ResolvedValue... values) {
        boolean config = false;
        for (MqValueResolver.ResolvedValue value : values) {
            if (!value.resolved()) {
                return MqEndpoint.ResolutionState.DYNAMIC;
            }
            config |= value.state() == MqEndpoint.ResolutionState.CONFIG_RESOLVED;
        }
        return config ? MqEndpoint.ResolutionState.CONFIG_RESOLVED : MqEndpoint.ResolutionState.STATIC;
    }

    private String firstMessage(MqValueResolver.ResolvedValue... values) {
        for (MqValueResolver.ResolvedValue value : values) {
            if (!value.resolved()) {
                return value.message();
            }
        }
        return null;
    }
}
