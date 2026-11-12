# MQ Navigator

![MQ Navigator](src/main/resources/icons/mq-navigator-logo-lockup.svg)

MQ Navigator is an IntelliJ IDEA plugin for navigating between Java message producers and consumers in the current project. It uses source code and local configuration, so no broker connection is required.

## Install

1. Download `MQ-Navigator-<version>.zip` from [GitHub Releases](https://github.com/stop-bullshit/MQ-Navigator/releases). Choose the plugin ZIP under **Assets**, not GitHub's source-code archives.
2. In IntelliJ IDEA, open **Settings / Preferences → Plugins → ⚙ → Install Plugin from Disk…**.
3. Select the ZIP and restart the IDE if prompted.
4. Open a Java project, import its Maven or Gradle dependencies, and wait for indexing to finish.

The declared IDE range is **IntelliJ IDEA 2023.3 through 2026.3** (builds `233` through `263.*`), with Java support enabled. Automated compatibility checks target IDEA Community 2023.3, IDEA Ultimate 2023.3, and IDEA 2026.2.1/2026.3; see each release's notes for its verification results. Other IDE products and framework versions are not automatically covered by those checks.

GitHub Releases provide installable packages. Availability on JetBrains Marketplace is not implied.

## Use

- Click the gutter icon beside a supported send call to find consumers, or beside a listener to find senders. One target opens directly; multiple targets use IDEA's selection popup.
- If the destination is dynamic or no matching endpoint exists in the current project, clicking the icon shows a diagnostic without moving the editor.
- Open **View → Tool Windows → MQ Navigator** to browse the **RabbitMQ** topology by module, consumer, producer, queue, binding, and exchange. Double-click a source node to open it.
- Use the tool-window refresh button or **Tools → MQ Navigator → Rescan Endpoints** after source/configuration changes or when a restored snapshot looks stale.

Queue and exchange declarations describe topology. They are not treated as senders or receivers and do not have producer/consumer gutter icons.

## Supported sources

| System | Producers | Consumers | Matching |
|---|---|---|---|
| RabbitMQ / Spring AMQP | `RabbitTemplate` / `AmqpTemplate` `send` and `convertAndSend`; source-level wrappers and delegating overloads that forward destination parameters | `@RabbitListener` with queues, queue declarations, or bindings | exchange plus direct/topic/fanout routing, including `*` and `#`; static `QueueBuilder` / `BindingBuilder` / `ExchangeBuilder` topology |
| Kafka / Spring Kafka | `KafkaTemplate.send(String, …)` | `@KafkaListener` topics and `topicPattern` | exact topic or full regular-expression match |
| RocketMQ / RocketMQ Spring | `RocketMQTemplate.syncSend`, `asyncSend`, `sendOneWay` | `@RocketMQMessageListener` | topic plus TAG selector |

Recognition uses the public framework types and source-level parameter flow, not application package names, queue prefixes, or company-specific utility names. For example, a RabbitMQ wrapper named `MessagePublisher.publish` can be recognized if its source forwards the exchange/routing key to `RabbitTemplate.convertAndSend`.

### Destination values and configuration

- String literals, Java compile-time constants, source-provable enum string getters, and certain static local-variable alternatives are supported.
- Listener placeholders and direct `@Value` instance fields support a single `${key}` or `${key:default}` expression. Values are read from the owning module's `application*` / `bootstrap*` properties or YAML files.
- Different values for the same key are reported as ambiguous. The plugin does not select an active Spring profile or reproduce Spring's configuration-precedence rules.
- RabbitMQ programmatic bindings supply the exchange and routing keys for queue-based listeners. Multiple bindings for one queue remain separate candidates.
- Enum lookup getters can resolve a code to its route when the source proves a simple `values()` loop, an equality comparison, and a return of the matching constant. Computed getters, transformed constructor values, and additional lookup conditions remain unresolved.
- RabbitMQ sends inside business methods can trace destination inputs to static project call sites, including callers through an interface with one concrete source implementation. Unknown inputs or ambiguous implementations remain dynamic; the plugin does not substitute every enum route.

## Optional Spring Bean lookup

With IDEA Spring Support enabled and a single Spring context available for the current file, unresolved Queue/Exchange injection references can use the Spring model to locate Java `@Bean` factories. Named qualifiers, aliases, `@Resource` names, and unique type candidates are supported. The actual RabbitMQ name and exchange type come from the returned builder or constructor, rather than the Bean's registration name.

Multiple candidates, custom qualifiers, opaque definitions, XML/compiled factories without Java source, and runtime calculations remain unresolved. The plugin does not start an application context or create Bean instances. Manually declaring a RabbitMQ object through `RabbitAdmin` does not by itself register a Spring Bean; those declarations continue to use source parsing.

Model dependencies invalidate live topology snapshots. Bean-dependent snapshots are scanned again after reopening the IDE so an old context or profile selection is not restored as a current binding. Without Spring Support, direct Java source parsing remains available.

## Limitations and known issues

This is static navigation, not proof that a message is delivered at runtime. The plugin does not connect to RabbitMQ, Kafka, or RocketMQ and does not modify application source or configuration files.

- Only Java source in the current IDEA project is scanned. Kotlin, Groovy, external repositories, and library-only source flow are outside the current scope.
- Native MQ client APIs, Spring Cloud Stream, and programmatically registered listener containers are not supported.
- Kafka `sendDefault`, `ProducerRecord`, message headers, `topicPartitions`, and custom wrappers are excluded. RocketMQ orderly, transactional, request/reply, SQL92 property matching, and custom wrappers are excluded.
- Remote configuration services, environment variables, SpEL, arbitrary method-return flow, and runtime destination transformations cannot be resolved.
- Headers/custom exchanges and incomplete topology may remain uncertain.
- Endpoints refresh automatically after opening a project, indexing, and source or module changes. Continuous edits are merged, and rescanning waits for committed documents and completed indexing. **Rescan Endpoints** remains available for an immediate manual refresh.
- Mutable local routing variables with conditional reassignments are not a full control-flow analysis and can miss or over-report alternatives.
- Direct-exchange bindings with an explicitly empty routing key are not yet matched correctly.

The tests include synthetic framework stubs and a small [manual sample project](src/test/testData/manual-project). They do not certify every Spring version or third-party application.

## Build and verify

Use **JDK 17** and the checked-in Gradle Wrapper. The first build downloads Gradle, IDEA test platforms, and Maven dependencies. A local broker is not needed.

Compilation uses the IDEA Ultimate 2023.3 SDK for the public Spring API. Spring is an optional runtime dependency; core parser tests use Java-only fixtures and controlled Bean definitions, while IDE compatibility checks cover both Community and Ultimate distributions.

Windows PowerShell:

```powershell
.\gradlew.bat test verifyPluginProjectConfiguration verifyPluginStructure
.\gradlew.bat verifyPlugin
.\gradlew.bat buildPlugin
.\gradlew.bat runIde
```

macOS / Linux:

```bash
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure
./gradlew verifyPlugin
./gradlew buildPlugin
./gradlew runIde
```

`buildPlugin` writes `MQ Navigator-<version>.zip` to `build/distributions/`, with `MQ Navigator` as the plugin directory and JAR base name. GitHub release assets use `MQ-Navigator-<version>.zip` so the downloaded filename matches `SHA256SUMS.txt`. `runIde` starts a separate development IDE; it does not install into your existing IDE. Tests and Plugin Verifier serve different purposes: tests exercise behavior, while the verifier checks binary compatibility with the configured IDEA builds.

## Releases and versioning

The project uses Semantic Versioning (`MAJOR.MINOR.PATCH`). `pluginVersion` in `gradle.properties` identifies the version built from that checkout.

```powershell
.\gradlew.bat buildSnapshot  # Builds <version>-SNAPSHOT for local testing without changing pluginVersion
.\gradlew.bat buildRelease   # Verifies and builds <version>, then advances the next PATCH version locally
```

The same tasks are available as `./gradlew buildSnapshot` and `./gradlew buildRelease` on macOS/Linux. Run snapshot and release tasks separately. Neither task by itself publishes to GitHub or JetBrains Marketplace.

A GitHub release tag such as `vX.Y.Z` must match the checkout's `pluginVersion=X.Y.Z`. The tag workflow verifies and packages that version without advancing it, then uploads the installable ZIP to GitHub Releases.

## Contribute

Bug reports with small, sanitized examples are welcome. Include the plugin version, IDEA build, framework versions, expected result, and actual result. See [CONTRIBUTING.md](CONTRIBUTING.md) for the local checks and reporting guidelines.

## 中文快速开始

从 [GitHub Releases](https://github.com/stop-bullshit/MQ-Navigator/releases) 的 **Assets** 下载 `MQ-Navigator-<version>.zip`，在 IDEA 的 **设置 → 插件 → ⚙ → 从磁盘安装插件** 中选择该文件，按提示重启。导入项目依赖并等待索引完成后，点击发送调用或监听方法旁的图标进行双向跳转；RabbitMQ 拓扑可在 **MQ Navigator** 工具窗口查看。

插件面向常见 Java/Spring 写法，不依赖应用的公司包名或队列命名。它进行静态分析，不连接真实 MQ；动态配置、复杂表达式及上面的未支持写法可能无法定位。结果疑似过期时执行 **Tools → MQ Navigator → Rescan Endpoints**。

## License

[MIT](LICENSE). Framework names identify supported integrations; this project is not an official RabbitMQ, Kafka, RocketMQ, or JetBrains product.
