# Changelog

## [0.2.11] - 2026-10-26

### Added

- MIT license, third-party notices, installation instructions, and contributor guidelines.
- Windows and Linux CI checks, IntelliJ compatibility verification, and tag-based GitHub Releases with SHA-256 checksums.
- Generic examples that do not depend on a particular organization or project layout.

### Fixed

- RabbitMQ source-level sending wrappers and delegating overloads retain their exchange and routing key.
- Queue declarations remain topology data and no longer appear as message senders or receivers in gutter navigation.
- RabbitMQ topic bindings with `#` match zero or more routing segments.
- Multiple-target popups emphasize class/method, module, and queue differences.
- IDE compatibility is explicitly limited to builds `233` through `262.*`.
- Gradle caches and generated reports are no longer tracked. The Unix Gradle wrapper is executable.

### Distribution

- Download `mqx-plugin-0.2.11.zip` from this release, then use **Settings / Preferences → Plugins → ⚙ → Install Plugin from Disk**.
- The plugin is a static navigation tool for supported Java/Spring APIs. It does not connect to brokers.
- This release is distributed on GitHub; it is not a JetBrains Marketplace publication.

### Known limitations

- Java only; native MQ clients, Spring Cloud Stream, and arbitrary runtime routing are outside the supported inputs.
- Some conditional local assignments and direct exchanges with an explicitly empty binding key can remain unresolved or miss a match.
- After editing source/configuration outside the IDE, use **Rescan Endpoints** if a restored topology snapshot appears stale.
- IDEA 2026.2 reports two deprecated platform API calls; binary compatibility is verified within the declared range.
