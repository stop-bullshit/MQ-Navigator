# Changelog

## [0.4.0] - 2026-11-10

### Added

- Optionally locate Queue/Exchange Java Bean factories through IDEA's public Spring model when injection references cannot be resolved from direct source expressions.
- Support explicit Bean names and aliases, qualified fields, `@Resource` names, Bean method parameters, and unambiguous type candidates.

### Fixed

- Read the factory's actual returned object for the RabbitMQ name and exchange type instead of an unrelated earlier construction.
- Keep unresolved or ambiguous Bean definitions dynamic and refresh Bean-dependent snapshots when the model changes or the IDE is reopened.

## [0.3.0] - 2026-11-10

### Added

- Resolve enum-derived RabbitMQ routing keys at internal send calls using statically known project caller arguments and unique interface implementations.
- Recognize source-proven enum code lookups, including Lombok getters and fields assigned from differently named constructor parameters.

### Fixed

- Keep unknown codes, ambiguous dispatch, runtime reassignment, and unsupported getter or constructor calculations unresolved.
- Rebuild persisted endpoint snapshots after the routing resolver upgrade.

## [0.2.14] - 2026-11-10

### Fixed

- Extended the declared IDE compatibility range through IntelliJ IDEA 2026.3 (build `263.*`).
- Added IntelliJ IDEA 2026.3 to the binary compatibility verification targets.

## [0.2.13] - 2026-11-10

### Changed

- Redesigned the plugin logo around an N-shaped route connecting two message endpoints.
- Aligned the plugin and README logos, with monochrome 16px and 20px tool-window icons for light and dark themes.

## [0.2.12] - 2026-11-10

### Changed

- Aligned the Gradle project name, plugin directory, and plugin JAR name with MQ Navigator.
- Updated repository, source, and issue links to `stop-bullshit/MQ-Navigator`.
- Updated the release workflow to publish `MQ-Navigator-0.2.12.zip` with matching SHA-256 filenames.

### Distribution

- Local builds produce `build/distributions/MQ Navigator-0.2.12.zip`.
- GitHub release assets use `MQ-Navigator-0.2.12.zip`.
- The plugin ID remains `cn.luckyh.mqx` so existing installations retain their identity.

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
