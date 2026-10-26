package cn.luckyh.mqx.navigation;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;
import cn.luckyh.mqx.parser.MqEndpointParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MQ endpoint persistent cache tests.
 *
 * @author heng.wang
 * @since 2026-08-24
 */
public class MqNavigationCacheTest extends BasePlatformTestCase {

    private static final int CACHE_VERSION = 3;
    private static final int PERFORMANCE_ENDPOINT_COUNT = 2_000;
    private static final int PERFORMANCE_WARMUPS = 5;
    private static final int PERFORMANCE_SAMPLES = 20;

    @Override
    protected String getTestDataPath() {
        return Path.of("src/test/testData").toAbsolutePath().toString();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyDirectoryToProject("stubs", "");
    }

    public void testMissingCacheScansCurrentSourceAndCreatesState() throws Exception {
        configureSource("source-topic");
        MqNavigationService service = new MqNavigationService(getProject());

        List<MqEndpoint> endpoints = readEndpoints(service);

        assertEquals(1, endpoints.size());
        assertEquals("source-topic", endpoints.get(0).destination().topic());
        assertEquals(CACHE_VERSION, service.getState().version);
        assertTrue(service.getState().savedAt > 0);
        assertEquals(1, service.getState().endpoints.size());
    }

    public void testPreviousParserCacheIsRebuiltAfterUpgrade() throws Exception {
        configureSource("source-topic");
        MqNavigationService.CacheState oldState = cacheState(List.of());
        oldState.version = 2;

        assertFallsBackToSource(oldState, "source-topic");
    }

    public void testValidCacheRestoresWithoutScanningAndReturnsImmutableSortedSnapshot() throws Exception {
        PsiIdentifier source = configureSource("source-topic");
        List<MqNavigationService.CachedEndpointState> cached = new ArrayList<>();
        cached.add(cachedEndpoint(source, "cached-z", MqEndpoint.SystemType.KAFKA,
                MqEndpoint.Role.PRODUCER, 2));
        cached.add(cachedEndpoint(source, "cached-a", MqEndpoint.SystemType.KAFKA,
                MqEndpoint.Role.CONSUMER, 1));
        MqNavigationService service = new MqNavigationService(getProject());
        service.loadState(cacheState(cached));

        List<MqEndpoint> endpoints = readEndpoints(service);

        assertEquals(List.of("cached-a", "cached-z"), endpoints.stream().map(MqEndpoint::endpointId).toList());
        assertEquals("topic-1", endpoints.get(0).destination().topic());
        assertSnapshotIsImmutable(endpoints);
    }

    public void testValidEmptyCacheDoesNotReviveCurrentSource() throws Exception {
        configureSource("source-topic");
        MqNavigationService service = new MqNavigationService(getProject());
        service.loadState(cacheState(List.of()));

        assertTrue(readEndpoints(service).isEmpty());
    }

    public void testPlatformSerializationRoundTripRestoresSnapshot() throws Exception {
        PsiIdentifier source = configureSource("source-topic");
        MqNavigationService.CachedEndpointState cached = cachedEndpoint(
                source, "serialized-cache", MqEndpoint.SystemType.ROCKETMQ,
                MqEndpoint.Role.CONSUMER, 3);
        MqNavigationService.CacheState serialized = XmlSerializer.deserialize(
                XmlSerializer.serialize(cacheState(List.of(cached))),
                MqNavigationService.CacheState.class);
        MqNavigationService service = new MqNavigationService(getProject());
        service.loadState(serialized);

        List<MqEndpoint> restored = readEndpoints(service);

        assertEquals(1, restored.size());
        assertEndpointEquals(cached, restored.get(0), source);
    }

    public void testRabbitQueueSettingsRoundTripAndVersionOneFallsBack() throws Exception {
        PsiIdentifier source = configureSource("source-topic");
        MqNavigationService.CachedEndpointState cached = cachedEndpoint(
                source, "special-queue", MqEndpoint.SystemType.RABBITMQ,
                MqEndpoint.Role.DECLARATION, 1);
        cached.rabbitQueueSettingsPresent = true;
        cached.deadLetterExchangeConfigured = true;
        cached.deadLetterExchange = "dead.exchange";
        cached.deadLetterRoutingKeyConfigured = true;
        cached.deadLetterRoutingKey = "dead.key";
        cached.messageTtlConfigured = true;
        cached.messageTtlMillis = 300000L;
        cached.rabbitQueueDiagnostics = new ArrayList<>(List.of("diagnostic"));
        MqNavigationService service = new MqNavigationService(getProject());
        service.loadState(cacheState(List.of(cached)));

        MqEndpoint restored = readEndpoints(service).get(0);
        MqDestination.RabbitQueueSettings settings = restored.destination().rabbitQueueSettings();

        assertNotNull(settings);
        assertEquals("dead.exchange", settings.deadLetterExchange());
        assertEquals("dead.key", settings.deadLetterRoutingKey());
        assertEquals(Long.valueOf(300000L), settings.messageTtlMillis());
        assertEquals(List.of("diagnostic"), settings.diagnostics());

        MqNavigationService.CacheState oldVersion = cacheState(List.of(cached));
        oldVersion.version = 1;
        assertFallsBackToSource(oldVersion, "source-topic");
    }

    public void testSpecialQueueSettingsSurviveTenRefreshesAndTenReopensPerKind() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) {
            PsiIdentifier source = configureSpecialQueueSource(iteration);
            MqNavigationService.CachedEndpointState stale = cachedEndpoint(
                    source, "stale-special-" + iteration, MqEndpoint.SystemType.RABBITMQ,
                    MqEndpoint.Role.DECLARATION, iteration);
            stale.queue = "stale.queue." + iteration;
            MqNavigationService service = new MqNavigationService(getProject());
            service.loadState(cacheState(List.of(stale)));

            service.requestRefresh();
            List<MqEndpoint> refreshed = readEndpoints(service);
            assertSpecialQueueSettings(refreshed, iteration);

            MqNavigationService reopenedService = new MqNavigationService(getProject());
            reopenedService.loadState(service.getState());
            assertSpecialQueueSettings(readEndpoints(reopenedService), iteration);
        }
    }

    public void testInvalidRabbitQueueSettingsFallBackWithoutPartialRestore() throws Exception {
        for (int iteration = 0; iteration < 5; iteration++) {
            String topic = "source-topic-" + iteration;
            PsiIdentifier source = configureSource(topic);
            MqNavigationService.CachedEndpointState invalid = cachedEndpoint(
                    source, "invalid-special-" + iteration, MqEndpoint.SystemType.RABBITMQ,
                    MqEndpoint.Role.DECLARATION, iteration);
            invalid.rabbitQueueSettingsPresent = true;
            invalid.messageTtlConfigured = true;
            invalid.messageTtlMillis = -1L;
            assertFallsBackToSource(cacheState(List.of(invalid)), topic);

            invalid.messageTtlMillis = null;
            invalid.rabbitQueueDiagnostics = new ArrayList<>();
            invalid.rabbitQueueDiagnostics.add(null);
            assertFallsBackToSource(cacheState(List.of(invalid)), topic);

            invalid.rabbitQueueDiagnostics = new ArrayList<>();
            invalid.deadLetterExchangeConfigured = false;
            invalid.deadLetterExchange = "unexpected.exchange";
            assertFallsBackToSource(cacheState(List.of(invalid)), topic);
        }
    }

    public void testAllSupportedSystemsRolesAndFieldsRestoreExactly() throws Exception {
        PsiIdentifier source = configureSource("source-topic");
        List<MqNavigationService.CachedEndpointState> cached = new ArrayList<>();
        addRoleSamples(cached, source, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.PRODUCER);
        addRoleSamples(cached, source, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.CONSUMER);
        addRoleSamples(cached, source, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.DECLARATION);
        addRoleSamples(cached, source, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.BINDING);
        addRoleSamples(cached, source, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.EXCHANGE_DECLARATION);
        addRoleSamples(cached, source, MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.PRODUCER);
        addRoleSamples(cached, source, MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.CONSUMER);
        addRoleSamples(cached, source, MqEndpoint.SystemType.ROCKETMQ, MqEndpoint.Role.PRODUCER);
        addRoleSamples(cached, source, MqEndpoint.SystemType.ROCKETMQ, MqEndpoint.Role.CONSUMER);
        MqNavigationService service = new MqNavigationService(getProject());
        service.loadState(cacheState(cached));

        List<MqEndpoint> restored = readEndpoints(service);

        assertEquals(45, restored.size());
        Map<String, MqEndpoint> byId = new LinkedHashMap<>();
        restored.forEach(endpoint -> byId.put(endpoint.endpointId(), endpoint));
        for (MqNavigationService.CachedEndpointState expected : cached) {
            assertEndpointEquals(expected, byId.get(expected.endpointId), source);
        }
    }

    public void testTwoThousandEndpointRestoreP95DoesNotExceedFiveHundredMilliseconds() throws Exception {
        PsiIdentifier source = configureSource("source-topic");
        List<MqNavigationService.CachedEndpointState> cached = new ArrayList<>(PERFORMANCE_ENDPOINT_COUNT);
        for (int index = 0; index < PERFORMANCE_ENDPOINT_COUNT; index++) {
            cached.add(cachedEndpoint(source, "performance-" + index, MqEndpoint.SystemType.KAFKA,
                    index % 2 == 0 ? MqEndpoint.Role.PRODUCER : MqEndpoint.Role.CONSUMER, index));
        }
        MqNavigationService.CacheState state = cacheState(cached);

        List<Long> samples = measureRestores(state);
        long p50 = percentile(samples, 0.50);
        long p95 = percentile(samples, 0.95);
        long max = samples.stream().mapToLong(Long::longValue).max().orElseThrow();

        assertEquals(PERFORMANCE_SAMPLES, samples.size());
        assertTrue("cache restore p95 ns=" + p95, p95 <= 500_000_000L);
        System.out.printf("MQ_CACHE_RESTORE endpoints=%d warmups=%d n=%d p50=%d p95=%d max=%d%n",
                PERFORMANCE_ENDPOINT_COUNT, PERFORMANCE_WARMUPS, samples.size(), p50, p95, max);
    }

    public void testManualRefreshReplacesRestoredCacheForCurrentAndNextService() throws Exception {
        for (int iteration = 0; iteration < 5; iteration++) {
            PsiIdentifier source = configureSource("source-topic-" + iteration);
            MqNavigationService.CachedEndpointState old = cachedEndpoint(
                    source, "old-" + iteration, MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.PRODUCER, iteration);
            old.topic = "old-topic-" + iteration;
            MqNavigationService service = new MqNavigationService(getProject());
            service.loadState(cacheState(List.of(old)));
            assertEquals(old.topic, readEndpoints(service).get(0).destination().topic());

            service.requestRefresh();
            List<MqEndpoint> refreshed = readEndpoints(service);

            assertEquals(1, refreshed.size());
            assertEquals("source-topic-" + iteration, refreshed.get(0).destination().topic());
            MqNavigationService nextService = new MqNavigationService(getProject());
            nextService.loadState(service.getState());
            assertEquals("source-topic-" + iteration,
                    readEndpoints(nextService).get(0).destination().topic());
        }
    }

    public void testManualRefreshPersistsLegalEmptySnapshot() throws Exception {
        for (int iteration = 0; iteration < 5; iteration++) {
            PsiIdentifier source = configureSource("source-topic-" + iteration);
            MqNavigationService service = new MqNavigationService(getProject());
            service.loadState(cacheState(List.of(cachedEndpoint(
                    source, "old-" + iteration, MqEndpoint.SystemType.KAFKA,
                    MqEndpoint.Role.PRODUCER, iteration))));
            assertEquals(1, readEndpoints(service).size());
            myFixture.configureByText("CacheSource.java", "class CacheSource {}\n");

            service.requestRefresh();

            assertTrue(readEndpoints(service).isEmpty());
            assertTrue(service.getState().savedAt > 0);
            assertTrue(service.getState().endpoints.isEmpty());
            MqNavigationService nextService = new MqNavigationService(getProject());
            nextService.loadState(service.getState());
            assertTrue(readEndpoints(nextService).isEmpty());
        }
    }

    public void testPsiChangesRescanAndPersistLatestSourceFiveTimes() throws Exception {
        configureSource("source-topic-0");
        MqNavigationService service = new MqNavigationService(getProject());
        assertEquals("source-topic-0", readEndpoints(service).get(0).destination().topic());

        for (int iteration = 1; iteration <= 5; iteration++) {
            configureSource("source-topic-" + iteration);

            List<MqEndpoint> refreshed = readEndpoints(service);

            assertEquals("source-topic-" + iteration, refreshed.get(0).destination().topic());
            MqNavigationService nextService = new MqNavigationService(getProject());
            nextService.loadState(service.getState());
            assertEquals("source-topic-" + iteration,
                    readEndpoints(nextService).get(0).destination().topic());
        }
    }

    public void testProjectRootChangeBypassesRestoredCache() throws Exception {
        PsiIdentifier source = configureSource("source-topic");
        MqNavigationService.CachedEndpointState old = cachedEndpoint(
                source, "old", MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.PRODUCER, 1);
        old.topic = "old-topic";
        MqNavigationService service = new MqNavigationService(getProject());
        service.loadState(cacheState(List.of(old)));
        assertEquals("old-topic", readEndpoints(service).get(0).destination().topic());

        ModuleRootModificationUtil.addContentRoot(
                ModuleManager.getInstance(getProject()).getModules()[0],
                myFixture.getTempDirFixture().findOrCreateDir("additional-content-root"));

        assertEquals("source-topic", readEndpoints(service).get(0).destination().topic());
    }

    public void testIncompleteScanKeepsLastCompletePersistentState() throws Exception {
        PsiIdentifier source = configureSource("source-topic");
        MqNavigationService.CacheState lastComplete = cacheState(List.of(cachedEndpoint(
                source, "last-complete", MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.PRODUCER, 1)));
        MqNavigationService service = new MqNavigationService(getProject());
        service.loadState(lastComplete);
        assertEquals(1, readEndpoints(service).size());
        replaceParsers(service, List.of(failingParser()));

        service.requestRefresh();

        assertTrue(readEndpoints(service).isEmpty());
        assertSame(lastComplete, service.getState());
    }

    public void testInvalidCacheCategoriesFallbackAsWholeFiveTimes() throws Exception {
        for (int iteration = 0; iteration < 5; iteration++) {
            String sourceTopic = "source-topic-" + iteration;
            PsiIdentifier source = configureSource(sourceTopic);
            MqNavigationService.CachedEndpointState valid = cachedEndpoint(
                    source, "valid-" + iteration, MqEndpoint.SystemType.KAFKA,
                    MqEndpoint.Role.PRODUCER, iteration);

            MqNavigationService.CacheState unsupportedVersion = cacheState(List.of(valid));
            unsupportedVersion.version = CACHE_VERSION + 1;
            assertFallsBackToSource(unsupportedVersion, sourceTopic);

            MqNavigationService.CachedEndpointState missingField = cachedEndpoint(
                    source, "missing-field-" + iteration, MqEndpoint.SystemType.KAFKA,
                    MqEndpoint.Role.PRODUCER, iteration);
            missingField.endpointId = "";
            assertFallsBackToSource(cacheState(List.of(missingField)), sourceTopic);

            MqNavigationService.CachedEndpointState invalidEnum = cachedEndpoint(
                    source, "invalid-enum-" + iteration, MqEndpoint.SystemType.KAFKA,
                    MqEndpoint.Role.PRODUCER, iteration);
            invalidEnum.system = "UNKNOWN";
            assertFallsBackToSource(cacheState(List.of(invalidEnum)), sourceTopic);

            MqNavigationService.CachedEndpointState deletedSource = deletedSourceEndpoint(iteration);
            assertFallsBackToSource(cacheState(List.of(deletedSource)), sourceTopic);

            MqNavigationService.CachedEndpointState invalidOffset = cachedEndpoint(
                    source, "invalid-offset-" + iteration, MqEndpoint.SystemType.KAFKA,
                    MqEndpoint.Role.PRODUCER, iteration);
            invalidOffset.sourceOffset = Integer.MAX_VALUE;
            assertFallsBackToSource(cacheState(List.of(invalidOffset)), sourceTopic);

            assertFallsBackToSource(cacheState(List.of(valid, deletedSource)), sourceTopic);

            MqNavigationService.CachedEndpointState duplicate = cachedEndpoint(
                    source, valid.endpointId, MqEndpoint.SystemType.KAFKA,
                    MqEndpoint.Role.CONSUMER, iteration + 100);
            assertFallsBackToSource(cacheState(List.of(valid, duplicate)), sourceTopic);

            MqNavigationService.CachedEndpointState invalidBinding = cachedEndpoint(
                    source, "invalid-binding-" + iteration, MqEndpoint.SystemType.RABBITMQ,
                    MqEndpoint.Role.BINDING, iteration);
            invalidBinding.bindingKeys = new ArrayList<>();
            invalidBinding.bindingKeys.add(null);
            assertFallsBackToSource(cacheState(List.of(invalidBinding)), sourceTopic);
        }
    }

    public void testSourceOutsideCurrentProjectFallsBackFiveTimes() throws Exception {
        PsiIdentifier source = configureSource("source-topic");
        Path externalPath = Files.createTempFile("mqx-external-cache", ".java");
        try {
            Files.writeString(externalPath, "class ExternalCacheSource {}\n");
            VirtualFile externalFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(externalPath);
            assertNotNull(externalFile);
            for (int iteration = 0; iteration < 5; iteration++) {
                MqNavigationService.CachedEndpointState external = cachedEndpoint(
                        source, "external-" + iteration, MqEndpoint.SystemType.KAFKA,
                        MqEndpoint.Role.PRODUCER, iteration);
                external.sourceUrl = externalFile.getUrl();
                external.sourceOffset = 0;
                assertFallsBackToSource(cacheState(List.of(external)), "source-topic");
            }
        } finally {
            Files.deleteIfExists(externalPath);
        }
    }

    public void testTwoProjectsRestoreAndRefreshWithoutLeakageTenTimes() throws Exception {
        Path projectDirectory = Files.createTempDirectory("mqx-cache-project-b");
        Project secondProject = createSecondProject(projectDirectory);
        try {
            PsiIdentifier firstSource = addSource(getProject(), "ProjectACacheSource.java", "project-a-source");
            PsiIdentifier secondSource = addSource(secondProject, "ProjectBCacheSource.java", "project-b-source");

            for (int iteration = 0; iteration < 10; iteration++) {
                MqNavigationService.CacheState firstState = cacheState(List.of(cachedEndpoint(
                        firstSource, "project-a-" + iteration, MqEndpoint.SystemType.KAFKA,
                        MqEndpoint.Role.PRODUCER, iteration)));
                firstState.endpoints.get(0).topic = "project-a-cache";
                MqNavigationService.CacheState secondState = cacheState(List.of(cachedEndpoint(
                        secondSource, "project-b-" + iteration, MqEndpoint.SystemType.KAFKA,
                        MqEndpoint.Role.PRODUCER, iteration)));
                secondState.endpoints.get(0).topic = "project-b-cache";

                MqNavigationService firstService = new MqNavigationService(getProject());
                replaceParsers(firstService, List.of(staticParser(firstSource, "project-a-source")));
                firstService.loadState(firstState);
                assertEquals("project-a-cache",
                        readEndpointsWithoutSmartWait(firstService).get(0).destination().topic());
                firstService.requestRefresh();
                assertEquals("project-a-source",
                        readEndpointsWithoutSmartWait(firstService).get(0).destination().topic());

                MqNavigationService secondService = new MqNavigationService(secondProject);
                replaceParsers(secondService, List.of(staticParser(secondSource, "project-b-source")));
                secondService.loadState(secondState);
                assertEquals("project-b-cache",
                        readEndpointsWithoutSmartWait(secondService).get(0).destination().topic());
                secondService.requestRefresh();
                assertEquals("project-b-source",
                        readEndpointsWithoutSmartWait(secondService).get(0).destination().topic());

                MqNavigationService foreignInFirst = new MqNavigationService(getProject());
                replaceParsers(foreignInFirst, List.of(staticParser(firstSource, "project-a-source")));
                foreignInFirst.loadState(secondState);
                assertEquals("project-a-source",
                        readEndpointsWithoutSmartWait(foreignInFirst).get(0).destination().topic());
                MqNavigationService foreignInSecond = new MqNavigationService(secondProject);
                replaceParsers(foreignInSecond, List.of(staticParser(secondSource, "project-b-source")));
                foreignInSecond.loadState(firstState);
                assertEquals("project-b-source",
                        readEndpointsWithoutSmartWait(foreignInSecond).get(0).destination().topic());
            }
        } finally {
            PlatformTestUtil.forceCloseProjectWithoutSaving(secondProject);
            com.intellij.openapi.util.io.FileUtil.delete(projectDirectory.toFile());
        }
    }

    private PsiIdentifier configureSource(String topic) {
        myFixture.configureByText("CacheSource.java", """
                class CacheSource {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("%s", new Object()); }
                }
                """.formatted(topic));
        return PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(identifier -> "send".equals(identifier.getText()))
                .findFirst()
                .orElseThrow();
    }

    private PsiIdentifier configureSpecialQueueSource(int iteration) {
        myFixture.configureByText("SpecialCacheSource.java", """
                class SpecialCacheSource {
                    org.springframework.amqp.core.Queue dead() {
                        return org.springframework.amqp.core.QueueBuilder.durable("cache.dead.%1$d")
                            .withArgument("x-dead-letter-exchange", "dead.exchange.%1$d").build();
                    }
                    org.springframework.amqp.core.Queue ttl() {
                        return org.springframework.amqp.core.QueueBuilder.durable("cache.ttl.%1$d")
                            .withArgument("x-message-ttl", %2$dL).build();
                    }
                    org.springframework.amqp.core.Queue delay() {
                        return org.springframework.amqp.core.QueueBuilder.durable("cache.delay.%1$d")
                            .withArgument("x-dead-letter-exchange", "delay.exchange.%1$d")
                            .withArgument("x-dead-letter-routing-key", "delay.key.%1$d")
                            .withArgument("x-message-ttl", %2$dL).build();
                    }
                }
                """.formatted(iteration, iteration + 1));
        return PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(identifier -> "durable".equals(identifier.getText()))
                .findFirst()
                .orElseThrow();
    }

    private void assertSpecialQueueSettings(List<MqEndpoint> endpoints, int iteration) {
        assertEquals(3, endpoints.size());
        Map<String, MqEndpoint> byQueue = new LinkedHashMap<>();
        endpoints.forEach(endpoint -> byQueue.put(endpoint.destination().queue(), endpoint));

        MqEndpoint dead = byQueue.get("cache.dead." + iteration);
        assertNotNull(dead);
        assertEquals(MqDestination.RabbitQueueKind.DEAD_LETTER_SOURCE,
                dead.destination().rabbitQueueSettings().kind());
        assertEquals("dead.exchange." + iteration,
                dead.destination().rabbitQueueSettings().deadLetterExchange());

        MqEndpoint ttl = byQueue.get("cache.ttl." + iteration);
        assertNotNull(ttl);
        assertEquals(MqDestination.RabbitQueueKind.STANDARD,
                ttl.destination().rabbitQueueSettings().kind());
        assertEquals(Long.valueOf(iteration + 1L),
                ttl.destination().rabbitQueueSettings().messageTtlMillis());

        MqEndpoint delay = byQueue.get("cache.delay." + iteration);
        assertNotNull(delay);
        assertEquals(MqDestination.RabbitQueueKind.DELAY,
                delay.destination().rabbitQueueSettings().kind());
        assertEquals("delay.exchange." + iteration,
                delay.destination().rabbitQueueSettings().deadLetterExchange());
        assertEquals("delay.key." + iteration,
                delay.destination().rabbitQueueSettings().deadLetterRoutingKey());
        assertEquals(Long.valueOf(iteration + 1L),
                delay.destination().rabbitQueueSettings().messageTtlMillis());

        for (MqEndpoint endpoint : endpoints) {
            assertNotNull(endpoint.sourcePointer().getElement());
            assertEquals("SpecialCacheSource.java", endpoint.fileName());
        }
    }

    private PsiIdentifier addSource(Project project, String fileName, String topic) {
        VirtualFile[] sourceRoots = ProjectRootManager.getInstance(project).getContentSourceRoots();
        assertTrue(sourceRoots.length > 0);
        AtomicReference<VirtualFile> sourceFile = new AtomicReference<>();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                VirtualFile file = sourceRoots[0].findChild(fileName);
                if (file == null) {
                    file = sourceRoots[0].createChildData(this, fileName);
                }
                VfsUtil.saveText(file, """
                class ProjectCacheSource {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("%s", new Object()); }
                }
                """.formatted(topic));
                sourceFile.set(file);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        PsiFile file = PsiManager.getInstance(project).findFile(sourceFile.get());
        assertNotNull(file);
        return PsiTreeUtil.findChildrenOfType(file, PsiIdentifier.class).stream()
                .filter(identifier -> "send".equals(identifier.getText()))
                .findFirst()
                .orElseThrow();
    }

    private void addRoleSamples(
            List<MqNavigationService.CachedEndpointState> cached,
            PsiIdentifier source,
            MqEndpoint.SystemType system,
            MqEndpoint.Role role) {
        for (int sample = 0; sample < 5; sample++) {
            int index = cached.size();
            cached.add(cachedEndpoint(source, system + "-" + role + "-" + sample, system, role, index));
        }
    }

    private MqNavigationService.CachedEndpointState cachedEndpoint(
            PsiElement source,
            String endpointId,
            MqEndpoint.SystemType system,
            MqEndpoint.Role role,
            int index) {
        MqNavigationService.CachedEndpointState endpoint = new MqNavigationService.CachedEndpointState();
        endpoint.endpointId = endpointId;
        endpoint.system = system.name();
        endpoint.role = role.name();
        endpoint.resolutionState = MqEndpoint.ResolutionState.values()[
                index % MqEndpoint.ResolutionState.values().length].name();
        endpoint.resolutionMessage = "resolution-" + index;
        endpoint.topic = "topic-" + index;
        endpoint.exchange = "exchange-" + index;
        endpoint.queue = "queue-" + index;
        endpoint.routingKey = "routing-" + index;
        endpoint.bindingKeys = new ArrayList<>(List.of("binding-" + index, "binding-alt-" + index));
        endpoint.exchangeType = "exchange-type-" + index;
        endpoint.topicPattern = "topic-pattern-" + index;
        endpoint.tag = "tag-" + index;
        endpoint.selectorType = "selector-type-" + index;
        endpoint.selectorExpression = "selector-expression-" + index;
        endpoint.consumerGroup = "consumer-group-" + index;
        if (system == MqEndpoint.SystemType.RABBITMQ && role == MqEndpoint.Role.DECLARATION) {
            endpoint.rabbitQueueSettingsPresent = true;
            endpoint.deadLetterExchangeConfigured = true;
            endpoint.deadLetterExchange = "dead-exchange-" + index;
            endpoint.deadLetterRoutingKeyConfigured = true;
            endpoint.deadLetterRoutingKey = "dead-routing-" + index;
            endpoint.messageTtlConfigured = true;
            endpoint.messageTtlMillis = (long) index;
            endpoint.rabbitQueueDiagnostics = new ArrayList<>(List.of("diagnostic-" + index));
        }
        endpoint.moduleName = "module-" + (index % 10);
        endpoint.fileName = "file-" + index + ".java";
        endpoint.className = "Class" + index;
        endpoint.memberName = "member" + index;
        endpoint.sourceUrl = source.getContainingFile().getVirtualFile().getUrl();
        endpoint.sourceOffset = source.getTextOffset();
        return endpoint;
    }

    private MqNavigationService.CacheState cacheState(
            List<MqNavigationService.CachedEndpointState> endpoints) {
        MqNavigationService.CacheState state = new MqNavigationService.CacheState();
        state.version = CACHE_VERSION;
        state.savedAt = System.currentTimeMillis();
        state.endpoints = new ArrayList<>(endpoints);
        return state;
    }

    private MqNavigationService.CachedEndpointState deletedSourceEndpoint(int iteration) {
        PsiFile file = myFixture.addFileToProject("DeletedCacheSource" + iteration + ".java", """
                class DeletedCacheSource%s {
                    void deletedSource() {}
                }
                """.formatted(iteration));
        PsiIdentifier source = PsiTreeUtil.findChildrenOfType(file, PsiIdentifier.class).stream()
                .filter(identifier -> "deletedSource".equals(identifier.getText()))
                .findFirst()
                .orElseThrow();
        MqNavigationService.CachedEndpointState endpoint = cachedEndpoint(
                source, "deleted-source-" + iteration, MqEndpoint.SystemType.KAFKA,
                MqEndpoint.Role.PRODUCER, iteration);
        VirtualFile virtualFile = file.getVirtualFile();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            try {
                virtualFile.delete(this);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        return endpoint;
    }

    private void assertFallsBackToSource(
            MqNavigationService.CacheState state, String expectedTopic) throws Exception {
        MqNavigationService service = new MqNavigationService(getProject());
        service.loadState(state);

        List<MqEndpoint> endpoints = readEndpoints(service);

        assertEquals(1, endpoints.size());
        assertEquals(expectedTopic, endpoints.get(0).destination().topic());
        assertEquals(1, service.getState().endpoints.size());
        assertEquals(expectedTopic, service.getState().endpoints.get(0).topic);
    }

    @SuppressWarnings("deprecation")
    private Project createSecondProject(Path projectDirectory) throws Exception {
        Path contentRoot = Files.createDirectories(projectDirectory.resolve("project-b"));
        Path sourceRoot = Files.createDirectories(contentRoot.resolve("src/main/java"));
        Project project = ProjectManagerEx.getInstanceEx().newProject(
                "MqNavigationCacheProjectB", projectDirectory.toString(), true, false);
        assertNotNull(project);
        VirtualFile contentRootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(contentRoot);
        VirtualFile sourceRootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceRoot);
        assertNotNull(contentRootFile);
        assertNotNull(sourceRootFile);
        AtomicReference<Module> module = new AtomicReference<>();
        WriteCommandAction.runWriteCommandAction(project, () -> module.set(ModuleManager.getInstance(project)
                .newModule(projectDirectory.resolve("project-b.iml").toString(), "JAVA_MODULE")));
        ModuleRootModificationUtil.updateModel(module.get(), model -> {
            com.intellij.openapi.roots.ContentEntry contentEntry = model.addContentEntry(contentRootFile);
            contentEntry.addSourceFolder(sourceRootFile, false);
        });
        return project;
    }

    private void assertEndpointEquals(
            MqNavigationService.CachedEndpointState expected,
            MqEndpoint actual,
            PsiElement source) {
        assertNotNull(expected.endpointId, actual);
        assertEquals(expected.endpointId, actual.endpointId());
        assertEquals(expected.system, actual.system().name());
        assertEquals(expected.role, actual.role().name());
        assertEquals(expected.resolutionState, actual.resolutionState().name());
        assertEquals(expected.resolutionMessage, actual.resolutionMessage());
        MqDestination destination = actual.destination();
        assertEquals(expected.topic, destination.topic());
        assertEquals(expected.exchange, destination.exchange());
        assertEquals(expected.queue, destination.queue());
        assertEquals(expected.routingKey, destination.routingKey());
        assertEquals(expected.bindingKeys, destination.bindingKeys());
        assertEquals(expected.exchangeType, destination.exchangeType());
        assertEquals(expected.topicPattern, destination.topicPattern());
        assertEquals(expected.tag, destination.tag());
        assertEquals(expected.selectorType, destination.selectorType());
        assertEquals(expected.selectorExpression, destination.selectorExpression());
        assertEquals(expected.consumerGroup, destination.consumerGroup());
        assertEquals(expected.rabbitQueueSettingsPresent, destination.rabbitQueueSettings() != null);
        if (expected.rabbitQueueSettingsPresent) {
            MqDestination.RabbitQueueSettings settings = destination.rabbitQueueSettings();
            assertEquals(expected.deadLetterExchangeConfigured, settings.deadLetterExchangeConfigured());
            assertEquals(expected.deadLetterExchange, settings.deadLetterExchange());
            assertEquals(expected.deadLetterRoutingKeyConfigured, settings.deadLetterRoutingKeyConfigured());
            assertEquals(expected.deadLetterRoutingKey, settings.deadLetterRoutingKey());
            assertEquals(expected.messageTtlConfigured, settings.messageTtlConfigured());
            assertEquals(expected.messageTtlMillis, settings.messageTtlMillis());
            assertEquals(expected.rabbitQueueDiagnostics, settings.diagnostics());
        }
        assertEquals(expected.moduleName, actual.moduleName());
        assertEquals(expected.fileName, actual.fileName());
        assertEquals(expected.className, actual.className());
        assertEquals(expected.memberName, actual.memberName());
        assertTrue(PsiManager.getInstance(getProject()).areElementsEquivalent(
                source, actual.sourcePointer().getElement()));
    }

    private void assertSnapshotIsImmutable(List<MqEndpoint> endpoints) {
        try {
            endpoints.clear();
            fail("Endpoint snapshot must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(2, endpoints.size());
        }
    }

    private List<MqEndpoint> readEndpoints(MqNavigationService service) throws Exception {
        return readEndpoints(getProject(), service);
    }

    private List<MqEndpoint> readEndpoints(Project project, MqNavigationService service) throws Exception {
        Future<List<MqEndpoint>> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(
                        () -> DumbService.getInstance(project).runReadActionInSmartMode(service::currentEndpoints),
                        new EmptyProgressIndicator()));
        return future.get();
    }

    private List<MqEndpoint> readEndpointsWithoutSmartWait(MqNavigationService service) throws Exception {
        Future<List<MqEndpoint>> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(service::currentEndpoints),
                        new EmptyProgressIndicator()));
        return future.get();
    }

    private List<Long> measureRestores(MqNavigationService.CacheState state) throws Exception {
        Future<List<Long>> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(() -> {
                    List<Long> samples = new ArrayList<>(PERFORMANCE_SAMPLES);
                    for (int iteration = 0; iteration < PERFORMANCE_WARMUPS + PERFORMANCE_SAMPLES; iteration++) {
                        long started = System.nanoTime();
                        MqNavigationService service = new MqNavigationService(getProject());
                        service.loadState(state);
                        List<MqEndpoint> endpoints = ReadAction.compute(service::currentEndpoints);
                        long elapsed = System.nanoTime() - started;
                        assertEquals(PERFORMANCE_ENDPOINT_COUNT, endpoints.size());
                        if (iteration >= PERFORMANCE_WARMUPS) {
                            samples.add(elapsed);
                        }
                    }
                    return samples;
                }, new EmptyProgressIndicator()));
        return future.get();
    }

    private long percentile(List<Long> samples, double percentile) {
        List<Long> sorted = samples.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(index);
    }

    private MqEndpointParser failingParser() {
        return new MqEndpointParser() {
            @Override
            public List<MqEndpoint> collectEndpoints(
                    Project project, GlobalSearchScope projectScope, Set<Object> cacheDependencies) {
                throw new IllegalArgumentException("incomplete scan");
            }

            @Override
            public Optional<MqEndpoint> parseAnchor(PsiElement anchor, Set<Object> cacheDependencies) {
                return Optional.empty();
            }
        };
    }

    private MqEndpointParser staticParser(PsiElement source, String topic) {
        return new MqEndpointParser() {
            @Override
            public List<MqEndpoint> collectEndpoints(
                    Project project, GlobalSearchScope projectScope, Set<Object> cacheDependencies) {
                return List.of(MqEndpointParser.endpoint(
                        source,
                        MqEndpoint.SystemType.KAFKA,
                        MqEndpoint.Role.PRODUCER,
                        MqDestination.kafkaTopic(topic, null),
                        MqEndpoint.ResolutionState.STATIC,
                        null));
            }

            @Override
            public Optional<MqEndpoint> parseAnchor(PsiElement anchor, Set<Object> cacheDependencies) {
                return Optional.empty();
            }
        };
    }

    private void replaceParsers(MqNavigationService service, List<MqEndpointParser> parsers) throws Exception {
        java.lang.reflect.Field field = MqNavigationService.class.getDeclaredField("parsers");
        field.setAccessible(true);
        field.set(service, parsers);
    }
}
