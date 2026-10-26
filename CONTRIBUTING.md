# Contributing

Small bug fixes, reproducible reports, and focused support for additional MQ source forms are welcome.

## Report a bug

Open an [issue](https://github.com/stop-bullshit/mqx-plugin/issues) with:

- MQ Navigator version and the full IntelliJ IDEA build number.
- Operating system, build tool, and relevant Spring/MQ library versions.
- A minimal Java producer/listener example and any local destination configuration needed to reproduce it.
- Expected navigation, actual navigation, and the smallest useful log excerpt or screenshot.

Replace company package names, real destinations, internal paths, credentials, and customer data with generic examples such as `org.example`, `orders.exchange`, and `orders.created`. Preserve the relationship between the values so the example still reproduces the problem. Do not post secrets or proprietary application code in public issues.

For a security-sensitive report, use GitHub's private vulnerability-reporting option if the repository offers it. Do not attach credentials, private source code, or exploit details to a public issue.

## Make a change

1. Use JDK 17 and the repository's Gradle Wrapper.
2. Read the existing parser or navigation path before changing it. Keep the change within the reported source form or behavior.
3. Add a focused regression case for new parsing or matching behavior. Test fixtures belong in `src/test/testData/`; keep them synthetic and include them in the change.
4. Run the checks below and report what passed and what you did not run.

Windows PowerShell:

```powershell
.\gradlew.bat test verifyPluginProjectConfiguration verifyPluginStructure
.\gradlew.bat verifyPlugin
```

macOS / Linux:

```bash
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure
./gradlew verifyPlugin
```

The tests use IntelliJ's Java test platform and framework stubs. No broker is required. Use `runIde` and the manual sample project when a change affects gutter placement, target popups, or the topology view; describe the IDE version used for that manual check.

## Submit a pull request

Explain the concrete input that failed, the resulting behavior, and the checks performed. Update the supported-source or limitation notes when the behavior changes. Keep generated Gradle caches, build reports, IDE state, and release ZIPs out of the commit.

Use a concise Conventional Commit title with a Simplified Chinese summary, such as `fix(rabbitmq): 修复井号匹配零段路由`. A pull request should not publish a release or modify unrelated application examples.

Contributions are distributed under the repository's [MIT license](LICENSE).
