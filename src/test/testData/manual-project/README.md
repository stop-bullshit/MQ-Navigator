# Manual Sandbox Map

Open `src/main/java/demo/MqExamples.java` after indexing completes.

- Scenario A: use the `rabbitDefault`, `rabbitDirect`, `rabbitTopic`, and `rabbitFanout` producers. `QueueDeclarations.ordersQueue` exercises `QueueBuilder.durable(...)` navigation to the same-name listener. The two fanout consumers exercise the multiple-target popup; `rabbitUncertain` exercises headers diagnostics.
- Scenario B: use `kafkaConfigured`, `kafkaStatic`, and `kafkaPatternAndGroups`. The two `shared-events` groups exercise multiple targets; `kafkaDynamic`, `kafkaExcluded`, and `kafkaInvalidPattern` exercise diagnostics and exclusions.
- Scenario C: use `rocketSms` and `rocketEmail` with the TAG consumers. `RocketSqlConsumer` must remain uncertain because message properties are unavailable.
- Scenario D: use `ValueExamples` for direct `@Value`, configuration ambiguity, a literal placeholder, SpEL, a local alias, a method return, and a method parameter.
- Scenario E: edit, move, rename, or delete one of the matching consumers after its marker is visible, then reuse the same producer marker and run `Tools > MQ Navigator > Rescan Endpoints`.
- Scenario F: temporarily leave one method incomplete while navigating between unaffected files; also repeat a marker click while IDEA is indexing.

No broker needs to be started. Make all manual edits only in the copied `build/manual-sandbox-project` directory.
