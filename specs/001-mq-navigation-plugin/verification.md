# MQ Navigator Verification

## Story Red/Green Evidence

- Phase 9 enum getter green: `MqValueResolverTest` and `MqNavigationServiceTest` cover explicit enum getter bodies and Lombok-style unresolved getter calls. The full `QueueBuilder → BindingBuilder → ExchangeBuilder → producer wrapper` fixture resolves `checkpoint.ib.hi` and returns the unique `publish` producer.
- Phase 9 UI/icon build green: `compileJava`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`, and `buildPlugin` passed after adding module/Consumers/Producers/Topology grouping, text/type filters, relationship nodes, and the 16px RabbitMQ SVG icon.
- Tool Window double-click regression green: producer nodes now use the mouse coordinates to resolve the clicked `TreePath`, disable JTree's own double-click expansion, and call `OpenSourceUtil` with the exact smart-pointer navigation element. The full offline test run passed 46 tests with 0 failures and 0 errors; the preceding online test attempt was blocked only by a JetBrains repository SSL handshake and succeeded with the already cached dependencies.
- Icon resource integration green: the standard `META-INF/pluginIcon.svg` is present, the plugin descriptor and Tool Window use `mq-navigator-plugin-logo.svg`, and the README includes the lockup artwork. `buildPlugin`, `verifyPluginProjectConfiguration`, and `verifyPluginStructure` pass offline for version `0.2.3`.
- Icon regression test green: `MqLineMarkerProviderTest` uses the repository SVG gutter resources, and the refresh action uses the repository RabbitMQ SVG resource.

- US1 red: targeted Gradle test run failed at `compileTestJava` with 30 missing-symbol errors for the seven intentionally absent production classes. The model and test-framework setup compiled first, so the red result is isolated to the planned US1 behavior.
- US1 green: 11 targeted tests passed across value resolution, RabbitMQ/Kafka/RocketMQ parsing, matching, project snapshot lookup, and producer line markers.
- US2: reverse-navigation tests were green on first execution because the US1 service and provider already used one role-neutral matcher/query path. Five navigation/marker tests and three matcher tests passed; no second reverse implementation was added.
- US3 red: targeted compilation failed only because `MqTargetRenderer` was intentionally absent.
- US3 green: navigation and marker suites passed with two stable candidates, project-context rendering, and the standard `NavigationUtil` popup path.
- US4 red: targeted compilation failed only because `RefreshMqIndexAction` was intentionally absent; existing cache and same-pointer regression tests remained compilable.
- US4 green: 17 targeted parser/navigation/marker tests passed, including manual cache invalidation, committed document changes through the same smart pointer, incomplete-file isolation, and the registered Rescan action class.
- Queue declaration red: the real-project regression suite failed at `compileTestJava` only because `MqEndpoint.Role.DECLARATION` did not exist.
- Queue declaration green: 17 targeted Rabbit parser/navigation/marker tests passed. Coverage includes `QueueBuilder.durable(...)`, an imported `public static final String` constant in a separate source file, declaration→listener, listener→declaration when no project sender exists, sender→listener priority, and the declaration gutter marker.
- Rabbit topology regression green: 4 navigation tests cover `BindingBuilder.bind(...).to(...)` fanout bindings, `ExchangeBuilder.topicExchange(...).with(...)` routing keys, a source-level `MessagePublisher.publish(...)` wrapper that delegates to `RabbitTemplate`, one queue with multiple exchange bindings, and queue/exchange/binding configurations split across files. All passed.

## Functional Tests

- Phase 1-2: `compileJava` passed with JDK 17 and Gradle Wrapper 9.7.0 on 2026-08-17.
- Initial wrapper generation first failed because the machine-wide Gradle init script adds MavenLocal while repository mode was strict; removing the unnecessary strict mode resolved the environment conflict.
- Final full test: PASS, 46 tests in 9 suites, 0 failures, 0 errors, 0 skipped. The final code also passed `clean test --rerun-tasks`; the additional tests cover enum constant getter resolution and the real CheckPoint-style RabbitMQ route chain.
- Real-project regression: `MessageStrategy.java` exposed an instrumented `@NotNull className` failure for an anonymous class. The endpoint factory now normalizes anonymous class/member names before constructing the record; the dedicated Kafka parser test passes with `className=""` and `memberName="run"`.
- Real-project Rabbit regression: the inspected `CdtcQueueMqConfiguration.java` uses `QueueBuilder.durable(CdtcDockingQueueConstants.*).quorum().build()` and `RabbitAdmin.declareQueue(...)`, while listeners use the same imported constant. The implementation now recognizes the `durable` call as a static queue declaration and resolves the annotation constant through its final field initializer.

## Plugin Verifier

- `verifyPluginProjectConfiguration` and `verifyPluginStructure`: PASS.
- Plugin Verifier 1.409: `IC-233.11799.241` compatible; `IU-262.9437.185` compatible.
- IDEA 2026.2.1 reports one deprecated baseline-compatible call to `DaemonCodeAnalyzer.restart()`; no binary compatibility error was reported. Downloads completed successfully, so there is no network blocker.

## Performance

- Heavy fixture: PASS on 2026-08-17 with 50 real Java modules and exactly 2,000 endpoints. Distribution assertions passed for RabbitMQ 350 producer/350 consumer, Kafka 350/350, and RocketMQ 300/300.
- Sampling: one initial snapshot, 20 navigation warmups, three destination edit/commit/refresh warmups, 200 navigation samples, and 40 destination-changing refresh samples completed under the JUnit 360-second timeout.
- Final full-suite result (nanoseconds): navigation n=200, p50=818,500, p95=1,598,700, max=1,990,800; refresh n=40, p50=164,015,900, p95=212,036,500, max=213,793,600. Both p95 gates passed (<1 s and <3 s).
- Environment: IntelliJ `IC-233.11799.241`, JBR 17.0.9+7-b1087.7, Windows 11, 16 CPUs, max heap 2,147,483,648 bytes. Fixture/module/source/index preparation was 3,021,671,600 ns and first snapshot was 955,747,100 ns.

## Sandbox

- The manual fixture compiled successfully against real Spring Rabbit/Kafka/Rocket dependencies after correcting `QueueBinding` to use `org.springframework.amqp.rabbit.annotation.Queue` and `Exchange`.
- `runIde` scenarios A-F were not executed automatically; desktop interaction and visual gutter behavior remain a manual gate.
- The rebuilt distribution for this change is `build/distributions/mqx-plugin-0.2.1.zip`. The Tool Window now resolves the node directly from the double-click coordinates, disables the tree's own double-click expansion, and navigates through `OpenSourceUtil`; real-project reinstall remains a manual gate.

## Usability

- The required 10 first-time-user timed sessions were not performed; SC-004 remains unverified.

## Broker Isolation

- `MqBrokerIsolationTest`: PASS. RabbitMQ, Kafka, and RocketMQ loopback sentinel accepted-connection counts were each zero after scanning, bidirectional lookup, and Rescan.
- `runtimeClasspath` contains no `spring-rabbit`, `amqp-client`, `kafka-clients`, or `rocketmq-client`; production Java contains no direct `java.net` or `javax.net` API usage.

## Source Safety

- Canonical `manual-project` and staged `build/manual-sandbox-project`: 6 protected files, 0 path/hash differences. Aggregate sorted-manifest SHA-256: `b6233922f44a06653355d29e5594e4dadbe01db8ccf0a0c475122effb963908e`.

## Scope Review

- Production remains 12 Java classes with one interface (`MqEndpointParser`) and one project-level `CachedValue`. No extra service layer, custom index, PSI/VFS listener, thread pool, lock, static project state, settings page, or tool window was added.
- Production source contains no direct network API and no unused imports. The click handler retains a project plus smart pointer rather than a naked source PSI; cached endpoints retain only smart pointers.
- Queue declaration and programmatic topology support stays inside the existing model/parser/matcher/service/provider classes: only resolve-confirmed `QueueBuilder.durable(String)`, `BindingBuilder.bind(...).to(...)`, static `*Exchange`/`ExchangeBuilder.*Exchange` names, and source-level wrappers that directly delegate to Spring AMQP send methods are included; unresolved bean graphs and custom exchange types remain excluded.

## Final Status

- Verified: parser/matcher/navigation/marker/cache tests, anonymous-class regression, static queue declaration regression, 50-module performance, broker isolation, runtime/source network boundaries, manual fixture compilation, package structure, and Plugin Verifier for IDEA 2023.3/2026.2.1.
- Unverified: interactive `runIde` scenarios A-F and the 10-person timed usability study.
- Environment blockers: none.
- Production validation: the real-project queue/binding/exchange shape was inspected read-only and reproduced with cross-file constants in automated PSI fixtures; the rebuilt plugin has not yet been reinstalled and clicked inside the user's real project.
