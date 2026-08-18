package cn.luckyh.mqx.navigation;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import cn.luckyh.mqx.match.MqEndpointMatcher;
import cn.luckyh.mqx.model.MqEndpoint;
import cn.luckyh.mqx.parser.KafkaEndpointParser;
import cn.luckyh.mqx.parser.MqEndpointParser;
import cn.luckyh.mqx.parser.MqValueResolver;
import cn.luckyh.mqx.parser.RabbitMqEndpointParser;
import cn.luckyh.mqx.parser.RocketMqEndpointParser;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service(Service.Level.PROJECT)
public final class MqNavigationService {

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
                case PRODUCER -> "No matching MQ consumer or queue declaration was found in the current project";
                case CONSUMER -> "No matching MQ producer or queue declaration was found in the current project";
                case DECLARATION -> "No matching MQ producer or consumer was found in the current project";
                case BINDING -> "No matching MQ producer was found for this binding in the current project";
                case EXCHANGE_DECLARATION -> "No matching MQ binding was found for this exchange in the current project";
            };
        }
        return new NavigationOutcome(sources.get(0), status, targets, message);
    }

    public void requestRefresh() {
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

        Map<String, MqEndpoint> endpoints = new LinkedHashMap<>();
        GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
        for (MqEndpointParser parser : parsers) {
            try {
                for (MqEndpoint endpoint : parser.collectEndpoints(project, projectScope, dependencies)) {
                    endpoints.put(endpoint.endpointId(), endpoint);
                }
            } catch (com.intellij.psi.PsiInvalidElementAccessException | IllegalArgumentException exception) {
                LOG.debug("Unable to collect endpoints with " + parser.getClass().getSimpleName(), exception);
            }
        }
        List<MqEndpoint> sorted = endpoints.values().stream().sorted(ENDPOINT_ORDER).toList();
        EndpointSnapshot result = new EndpointSnapshot(sorted, currentModificationCount());
        return CachedValueProvider.Result.create(result, dependencies.toArray());
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
}
