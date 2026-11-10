package cn.luckyh.mqx.navigation;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import cn.luckyh.mqx.match.MqEndpointMatcher;
import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;
import cn.luckyh.mqx.parser.KafkaEndpointParser;
import cn.luckyh.mqx.parser.MqEndpointParser;
import cn.luckyh.mqx.parser.MqValueResolver;
import cn.luckyh.mqx.parser.RabbitMqEndpointParser;
import cn.luckyh.mqx.parser.RocketMqEndpointParser;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service(Service.Level.PROJECT)
@State(name = "MqNavigationEndpointCache", storages = @Storage(StoragePathMacros.CACHE_FILE))
public final class MqNavigationService implements PersistentStateComponent<MqNavigationService.CacheState> {

    // 枚举路由解析升级后重建旧端点缓存
    private static final int CACHE_VERSION = 4;
    private static final Logger LOG = Logger.getInstance(MqNavigationService.class);
    private static final Comparator<MqEndpoint> ENDPOINT_ORDER = Comparator
            .comparing(MqEndpoint::moduleName)
            .thenComparing(MqEndpoint::fileName)
            .thenComparing(MqEndpoint::className)
            .thenComparing(MqEndpoint::memberName)
            .thenComparing(MqEndpoint::endpointId);

    private final Project project;
    private final List<MqEndpointParser> parsers;
    private final MqEndpointMatcher matcher = new MqEndpointMatcher();
    private final SimpleModificationTracker manualRefreshTracker = new SimpleModificationTracker();
    private final CachedValue<EndpointSnapshot> snapshot;
    private volatile CacheState cacheState = new CacheState();
    private volatile boolean persistentRestorePending = true;

    public MqNavigationService(Project project) {
        this.project = project;
        MqValueResolver valueResolver = new MqValueResolver(project);
        this.parsers = List.of(
                new RabbitMqEndpointParser(valueResolver),
                new KafkaEndpointParser(valueResolver),
                new RocketMqEndpointParser(valueResolver));
        this.snapshot = CachedValuesManager.getManager(project)
                .createCachedValue(this::computeSnapshot, false);
    }

    public static MqNavigationService getInstance(Project project) {
        return project.getService(MqNavigationService.class);
    }

    @Override
    public @NotNull CacheState getState() {
        return cacheState;
    }

    @Override
    public void loadState(@NotNull CacheState state) {
        cacheState = state;
        persistentRestorePending = true;
    }

    public Optional<MqEndpoint> parseAnchor(PsiElement anchor) {
        Set<Object> dependencies = new LinkedHashSet<>();
        for (MqEndpointParser parser : parsers) {
            try {
                Optional<MqEndpoint> endpoint = parser.parseAnchor(anchor, dependencies);
                if (endpoint.isPresent()) {
                    return endpoint;
                }
            } catch (com.intellij.psi.PsiInvalidElementAccessException | IllegalArgumentException exception) {
                LOG.debug("Unable to parse MQ endpoint", exception);
            }
        }
        return Optional.empty();
    }

    public NavigationOutcome findTargets(PsiElement anchor) {
        if (anchor == null || !anchor.isValid()) {
            return NavigationOutcome.unresolved("MQ endpoint is no longer valid");
        }

        // 逻辑变动: 每次导航从当前不可变快照重新推导目标-20260817-1826-01
        EndpointSnapshot current = snapshot.getValue();
        List<MqEndpoint> sources = current.endpoints().stream()
                .filter(endpoint -> equivalent(endpoint, anchor))
                .toList();
        if (sources.isEmpty()) {
            Optional<MqEndpoint> parsed = parseAnchor(anchor);
            if (parsed.isEmpty()) {
                return NavigationOutcome.unresolved("MQ endpoint cannot be resolved");
            }
            sources = List.of(parsed.get());
        }

        boolean hasResolvedSource = sources.stream().anyMatch(MqEndpoint::isResolved);
        if (!hasResolvedSource) {
            String message = sources.get(0).resolutionMessage();
            return new NavigationOutcome(sources.get(0), NavigationStatus.UNRESOLVED, List.of(),
                    message == null ? "MQ destination is dynamic" : message);
        }

        Map<String, MqEndpoint> exact = new LinkedHashMap<>();
        boolean uncertain = false;
        for (MqEndpoint source : sources) {
            if (!source.isResolved()) {
                continue;
            }
            for (MqEndpoint candidate : current.endpoints()) {
                if (source.endpointId().equals(candidate.endpointId())
                        || source.system() != candidate.system()
                        || !candidate.isResolved()) {
                    continue;
                }
                MqEndpointMatcher.MatchKind kind = matcher.match(source, candidate);
                if (kind == MqEndpointMatcher.MatchKind.EXACT && validProjectSource(candidate)) {
                    exact.put(candidate.endpointId(), candidate);
                } else if (kind == MqEndpointMatcher.MatchKind.UNCERTAIN) {
                    uncertain = true;
                }
            }
        }

        List<MqEndpoint> targets = exact.values().stream().sorted(ENDPOINT_ORDER).toList();
        MqEndpoint.Role sourceRole = sources.get(0).role();
        MqEndpoint.Role preferredRole = sourceRole == MqEndpoint.Role.PRODUCER
                ? MqEndpoint.Role.CONSUMER
                : sourceRole == MqEndpoint.Role.CONSUMER ? MqEndpoint.Role.PRODUCER : null;
        if (preferredRole != null && targets.stream().anyMatch(target -> target.role() == preferredRole)) {
            targets = targets.stream().filter(target -> target.role() == preferredRole).toList();
        }
        NavigationStatus status;
        String message = null;
        if (targets.size() == 1) {
            status = NavigationStatus.ONE_MATCH;
        } else if (targets.size() > 1) {
            status = NavigationStatus.MULTIPLE_MATCHES;
        } else if (uncertain) {
            status = NavigationStatus.UNCERTAIN;
            message = "Matching endpoints exist, but routing conditions cannot be confirmed statically";
        } else {
            status = NavigationStatus.NO_MATCH;
            message = switch (sourceRole) {
                case PRODUCER -> "No matching MQ consumer was found in the current project";
                case CONSUMER -> "No matching MQ producer was found in the current project";
                case DECLARATION -> "Queue declarations are available in the MQ topology view";
                case BINDING -> "No matching MQ producer was found for this binding in the current project";
                case EXCHANGE_DECLARATION -> "No matching MQ binding was found for this exchange in the current project";
            };
        }
        return new NavigationOutcome(sources.get(0), status, targets, message);
    }

    public void requestRefresh() {
        persistentRestorePending = false;
        manualRefreshTracker.incModificationCount();
    }

    public List<MqEndpoint> currentEndpoints() {
        return snapshot.getValue().endpoints();
    }

    private CachedValueProvider.Result<EndpointSnapshot> computeSnapshot() {
        Set<Object> dependencies = new LinkedHashSet<>();
        dependencies.add(PsiModificationTracker.MODIFICATION_COUNT);
        dependencies.add(ProjectRootModificationTracker.getInstance(project));
        dependencies.add(manualRefreshTracker);

        if (persistentRestorePending) {
            persistentRestorePending = false;
            Optional<List<MqEndpoint>> restored = restoreCachedEndpoints();
            if (restored.isPresent()) {
                EndpointSnapshot result = new EndpointSnapshot(restored.get(), currentModificationCount());
                return CachedValueProvider.Result.create(result, dependencies.toArray());
            }
        }

        Map<String, MqEndpoint> endpoints = new LinkedHashMap<>();
        boolean scanComplete = true;
        GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
        for (MqEndpointParser parser : parsers) {
            try {
                for (MqEndpoint endpoint : parser.collectEndpoints(project, projectScope, dependencies)) {
                    endpoints.put(endpoint.endpointId(), endpoint);
                }
            } catch (com.intellij.psi.PsiInvalidElementAccessException | IllegalArgumentException exception) {
                scanComplete = false;
                LOG.debug("Unable to collect endpoints with " + parser.getClass().getSimpleName(), exception);
            }
        }
        List<MqEndpoint> sorted = endpoints.values().stream().sorted(ENDPOINT_ORDER).toList();
        if (scanComplete) {
            CacheState newState = createCacheState(sorted);
            if (newState != null) {
                cacheState = newState;
            }
        }
        EndpointSnapshot result = new EndpointSnapshot(sorted, currentModificationCount());
        return CachedValueProvider.Result.create(result, dependencies.toArray());
    }

    private Optional<List<MqEndpoint>> restoreCachedEndpoints() {
        CacheState state = cacheState;
        if (state.version != CACHE_VERSION || state.savedAt <= 0 || state.endpoints == null) {
            return Optional.empty();
        }

        Map<String, SmartPsiElementPointer<PsiElement>> sourcePointers = new LinkedHashMap<>();
        Map<String, MqEndpoint> endpoints = new LinkedHashMap<>();
        try {
            for (CachedEndpointState cached : state.endpoints) {
                if (!validCachedFields(cached) || endpoints.containsKey(cached.endpointId)) {
                    return Optional.empty();
                }
                SmartPsiElementPointer<PsiElement> sourcePointer = restoreSourcePointer(cached, sourcePointers);
                if (sourcePointer == null) {
                    return Optional.empty();
                }
                MqDestination.RabbitQueueSettings rabbitQueueSettings = cached.rabbitQueueSettingsPresent
                        ? new MqDestination.RabbitQueueSettings(
                        cached.deadLetterExchangeConfigured,
                        cached.deadLetterExchange,
                        cached.deadLetterRoutingKeyConfigured,
                        cached.deadLetterRoutingKey,
                        cached.messageTtlConfigured,
                        cached.messageTtlMillis,
                        cached.rabbitQueueDiagnostics)
                        : null;
                MqDestination destination = new MqDestination(
                        cached.topic,
                        cached.exchange,
                        cached.queue,
                        cached.routingKey,
                        cached.bindingKeys == null ? List.of() : cached.bindingKeys,
                        cached.exchangeType,
                        cached.topicPattern,
                        cached.tag,
                        cached.selectorType,
                        cached.selectorExpression,
                        cached.consumerGroup,
                        rabbitQueueSettings);
                MqEndpoint endpoint = new MqEndpoint(
                        cached.endpointId,
                        MqEndpoint.SystemType.valueOf(cached.system),
                        MqEndpoint.Role.valueOf(cached.role),
                        destination,
                        MqEndpoint.ResolutionState.valueOf(cached.resolutionState),
                        cached.resolutionMessage,
                        sourcePointer,
                        cached.moduleName,
                        cached.fileName,
                        cached.className,
                        cached.memberName);
                endpoints.put(endpoint.endpointId(), endpoint);
            }
        } catch (IllegalArgumentException | com.intellij.psi.PsiInvalidElementAccessException exception) {
            LOG.debug("Unable to restore MQ endpoint cache", exception);
            return Optional.empty();
        }
        return Optional.of(endpoints.values().stream().sorted(ENDPOINT_ORDER).toList());
    }

    private SmartPsiElementPointer<PsiElement> restoreSourcePointer(
            CachedEndpointState cached,
            Map<String, SmartPsiElementPointer<PsiElement>> sourcePointers) {
        String sourceKey = cached.sourceUrl + ':' + cached.sourceOffset;
        SmartPsiElementPointer<PsiElement> existing = sourcePointers.get(sourceKey);
        if (existing != null) {
            return existing;
        }
        VirtualFile sourceFile = VirtualFileManager.getInstance().findFileByUrl(cached.sourceUrl);
        if (sourceFile == null || !sourceFile.isValid()
                || !ProjectFileIndex.getInstance(project).isInSourceContent(sourceFile)) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(sourceFile);
        PsiElement source = psiFile == null ? null : psiFile.findElementAt(cached.sourceOffset);
        if (source == null || !source.isValid()) {
            return null;
        }
        SmartPsiElementPointer<PsiElement> sourcePointer =
                SmartPointerManager.getInstance(project).createSmartPsiElementPointer(source);
        sourcePointers.put(sourceKey, sourcePointer);
        return sourcePointer;
    }

    private boolean validCachedFields(CachedEndpointState cached) {
        return cached != null
                && cached.endpointId != null && !cached.endpointId.isBlank()
                && cached.system != null && !cached.system.isBlank()
                && cached.role != null && !cached.role.isBlank()
                && cached.resolutionState != null && !cached.resolutionState.isBlank()
                && cached.moduleName != null
                && cached.fileName != null
                && cached.className != null
                && cached.memberName != null
                && (cached.bindingKeys == null || !cached.bindingKeys.contains(null))
                && validCachedRabbitQueueSettings(cached)
                && cached.sourceUrl != null && !cached.sourceUrl.isBlank()
                && cached.sourceOffset >= 0;
    }

    private boolean validCachedRabbitQueueSettings(CachedEndpointState cached) {
        if (cached.rabbitQueueDiagnostics == null || cached.rabbitQueueDiagnostics.contains(null)) {
            return false;
        }
        if (!cached.rabbitQueueSettingsPresent) {
            return !cached.deadLetterExchangeConfigured
                    && cached.deadLetterExchange == null
                    && !cached.deadLetterRoutingKeyConfigured
                    && cached.deadLetterRoutingKey == null
                    && !cached.messageTtlConfigured
                    && cached.messageTtlMillis == null
                    && cached.rabbitQueueDiagnostics.isEmpty();
        }
        return (cached.deadLetterExchangeConfigured || cached.deadLetterExchange == null)
                && (cached.deadLetterRoutingKeyConfigured || cached.deadLetterRoutingKey == null)
                && (cached.messageTtlConfigured || cached.messageTtlMillis == null)
                && (cached.messageTtlMillis == null || cached.messageTtlMillis >= 0);
    }

    private CacheState createCacheState(List<MqEndpoint> endpoints) {
        CacheState newState = new CacheState();
        newState.version = CACHE_VERSION;
        newState.savedAt = System.currentTimeMillis();
        newState.endpoints = new ArrayList<>(endpoints.size());
        for (MqEndpoint endpoint : endpoints) {
            PsiElement source = endpoint.sourcePointer().getElement();
            if (source == null || !source.isValid() || source.getContainingFile() == null) {
                return null;
            }
            VirtualFile sourceFile = source.getContainingFile().getVirtualFile();
            if (sourceFile == null || !ProjectFileIndex.getInstance(project).isInSourceContent(sourceFile)) {
                return null;
            }
            MqDestination destination = endpoint.destination();
            CachedEndpointState cached = new CachedEndpointState();
            cached.endpointId = endpoint.endpointId();
            cached.system = endpoint.system().name();
            cached.role = endpoint.role().name();
            cached.resolutionState = endpoint.resolutionState().name();
            cached.resolutionMessage = endpoint.resolutionMessage();
            cached.topic = destination.topic();
            cached.exchange = destination.exchange();
            cached.queue = destination.queue();
            cached.routingKey = destination.routingKey();
            cached.bindingKeys = new ArrayList<>(destination.bindingKeys());
            cached.exchangeType = destination.exchangeType();
            cached.topicPattern = destination.topicPattern();
            cached.tag = destination.tag();
            cached.selectorType = destination.selectorType();
            cached.selectorExpression = destination.selectorExpression();
            cached.consumerGroup = destination.consumerGroup();
            MqDestination.RabbitQueueSettings rabbitQueueSettings = destination.rabbitQueueSettings();
            if (rabbitQueueSettings != null) {
                cached.rabbitQueueSettingsPresent = true;
                cached.deadLetterExchangeConfigured = rabbitQueueSettings.deadLetterExchangeConfigured();
                cached.deadLetterExchange = rabbitQueueSettings.deadLetterExchange();
                cached.deadLetterRoutingKeyConfigured = rabbitQueueSettings.deadLetterRoutingKeyConfigured();
                cached.deadLetterRoutingKey = rabbitQueueSettings.deadLetterRoutingKey();
                cached.messageTtlConfigured = rabbitQueueSettings.messageTtlConfigured();
                cached.messageTtlMillis = rabbitQueueSettings.messageTtlMillis();
                cached.rabbitQueueDiagnostics = new ArrayList<>(rabbitQueueSettings.diagnostics());
            }
            cached.moduleName = endpoint.moduleName();
            cached.fileName = endpoint.fileName();
            cached.className = endpoint.className();
            cached.memberName = endpoint.memberName();
            cached.sourceUrl = sourceFile.getUrl();
            cached.sourceOffset = source.getTextOffset();
            newState.endpoints.add(cached);
        }
        return newState;
    }

    private long currentModificationCount() {
        return PsiModificationTracker.getInstance(project).getModificationCount();
    }

    private boolean equivalent(MqEndpoint endpoint, PsiElement anchor) {
        PsiElement element = endpoint.sourcePointer().getElement();
        return element != null && element.isValid()
                && PsiManager.getInstance(project).areElementsEquivalent(element, anchor);
    }

    private boolean validProjectSource(MqEndpoint endpoint) {
        PsiElement element = endpoint.sourcePointer().getElement();
        if (element == null || !element.isValid() || element.getContainingFile() == null
                || element.getContainingFile().getVirtualFile() == null) {
            return false;
        }
        return ProjectFileIndex.getInstance(project)
                .isInSourceContent(element.getContainingFile().getVirtualFile());
    }

    private record EndpointSnapshot(@NotNull List<MqEndpoint> endpoints, long modificationCount) {
        private EndpointSnapshot {
            endpoints = List.copyOf(endpoints);
        }
    }

    public record NavigationOutcome(
            MqEndpoint source,
            @NotNull NavigationStatus status,
            @NotNull List<MqEndpoint> targets,
            String message) {
        public NavigationOutcome {
            targets = List.copyOf(targets);
        }

        static NavigationOutcome unresolved(String message) {
            return new NavigationOutcome(null, NavigationStatus.UNRESOLVED, List.of(), message);
        }
    }

    public enum NavigationStatus {
        UNRESOLVED,
        NO_MATCH,
        ONE_MATCH,
        MULTIPLE_MATCHES,
        UNCERTAIN
    }

    /**
     * Project-level persistent endpoint snapshot state.
     */
    public static final class CacheState {
        public int version;
        public long savedAt;
        public List<CachedEndpointState> endpoints = new ArrayList<>();
    }

    /**
     * Serializable endpoint fields and current-project source location.
     */
    public static final class CachedEndpointState {
        public String endpointId;
        public String system;
        public String role;
        public String resolutionState;
        public String resolutionMessage;
        public String topic;
        public String exchange;
        public String queue;
        public String routingKey;
        public List<String> bindingKeys = new ArrayList<>();
        public String exchangeType;
        public String topicPattern;
        public String tag;
        public String selectorType;
        public String selectorExpression;
        public String consumerGroup;
        public boolean rabbitQueueSettingsPresent;
        public boolean deadLetterExchangeConfigured;
        public String deadLetterExchange;
        public boolean deadLetterRoutingKeyConfigured;
        public String deadLetterRoutingKey;
        public boolean messageTtlConfigured;
        public Long messageTtlMillis;
        public List<String> rabbitQueueDiagnostics = new ArrayList<>();
        public String moduleName;
        public String fileName;
        public String className;
        public String memberName;
        public String sourceUrl;
        public int sourceOffset = -1;
    }
}
