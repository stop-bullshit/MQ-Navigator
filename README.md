# MQ Navigator

![MQ Navigator](src/main/resources/icons/mq-navigator-logo-lockup.svg)

MQ Navigator is an IntelliJ IDEA plugin for navigating between Java message producers and consumers in the current project.

## Supported Sources

| System | Producers | Consumers | Matching |
|---|---|---|---|
| RabbitMQ | `RabbitTemplate` / `AmqpTemplate` `send` and `convertAndSend`, plus source-level wrappers that directly delegate to them | `@RabbitListener` queues, declarations, and bindings | exchange plus direct/topic/fanout routing; `QueueBuilder.durable(...)` + `BindingBuilder.bind(...).to(...).with(...)` topology |
| Kafka | `KafkaTemplate.send(String, ...)` | `@KafkaListener` topics and topicPattern | exact topic or full regular-expression match |
| RocketMQ | `syncSend`, `asyncSend`, `sendOneWay` | `@RocketMQMessageListener` | topic plus TAG selector |

String literals, Java compile-time constants, listener placeholders, direct `@Value` instance fields, and enum constant string getters such as `RoutingKey.ORDER_CREATED.getCode()` are supported when their values can be proven from source. Other runtime expressions, local aliases, method-return flow, SpEL, SQL92 properties, and external broker topology are reported without guessed navigation. RabbitMQ queue methods are linked to static exchange methods through programmatic `BindingBuilder` declarations, and all bindings for the same queue are retained.

## Scope and Diagnostics

The gutter remains available when a destination is dynamic, invalid, ambiguous, or has no match; clicking it reports the reason without moving the editor. RabbitMQ `QueueBuilder.durable(...)` declarations can navigate through static `BindingBuilder.bind(...).to(...)` relationships to exchange-based producers; all bindings for one queue remain available as separate candidates. Headers/custom exchanges, anonymous queues, unresolved bean methods, and external broker topology remain uncertain. Kafka `sendDefault`, `ProducerRecord`, message headers, `topicPartitions`, wrappers, and cross-method topic flow are excluded. RocketMQ orderly, transaction, request/reply methods and SQL92 property matching are excluded. Kotlin, Groovy, UAST, library sources, and external repositories are outside the first release.

## Run

```powershell
.\gradlew.bat test
.\gradlew.bat runIde
```

Open the `MQ Navigator` tool window to browse RabbitMQ endpoints grouped by module and then by Consumers, Producers, and Topology. Filter by module, class, queue, exchange, routing key, endpoint type, or unresolved state. Consumer and producer nodes also show their related bindings and opposite endpoints. Double-click any source node to open it. Existing gutter navigation remains available on Java producers, consumers, and queue declarations. One target opens directly; multiple targets use the IntelliJ target popup. Use the RabbitMQ refresh icon or `Tools > MQ Navigator > Rescan Endpoints` to invalidate the current project snapshot explicitly.

The plugin does not connect to RabbitMQ, Kafka, or RocketMQ and does not modify project source or configuration files.

## Versioning

The plugin uses Semantic Versioning (`MAJOR.MINOR.PATCH`): compatible bug fixes increment `PATCH`, backward-compatible user-visible features increment `MINOR`, and breaking changes increment `MAJOR`. The current release is `0.2.3`.
