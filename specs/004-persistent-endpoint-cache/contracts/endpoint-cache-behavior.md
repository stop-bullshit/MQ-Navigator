# Internal Behavior: MQ Endpoint Cache

## 1. First Snapshot Read

| Condition | Required Behavior | Result Source |
|-----------|-------------------|---------------|
| No saved snapshot | Run the existing complete scan | Fresh scan |
| Valid non-empty snapshot | Restore every endpoint and publish the existing immutable snapshot | Persistent cache |
| Valid empty snapshot | Publish an empty immutable snapshot without reviving older endpoints | Persistent cache |
| Unsupported version | Ignore the whole cache and scan | Fresh scan |
| Missing required field | Ignore the whole cache and scan | Fresh scan |
| Any source location cannot be restored | Ignore the whole cache and scan; never publish a partial cache | Fresh scan |

The persistent cache is attempted at most once per project service instance.

## 2. Refresh and Invalidation

| Event | Required Behavior |
|-------|-------------------|
| Existing manual refresh action | Disable persistent restore for this service instance, invalidate the current runtime snapshot, then scan current source |
| PSI change in current session | Existing snapshot dependency invalidates; next read scans and updates persistent state |
| Project roots change | Existing snapshot dependency invalidates; next read scans and updates persistent state |
| Complete scan succeeds | Replace the saved project snapshot, including a legal empty result |
| One or more parser collections fail | Keep current runtime error isolation, but do not replace the last complete saved snapshot |

## 3. Endpoint Fidelity

Every restored endpoint must preserve endpoint identity, message system, role, resolution state, every destination field, binding key order, display fields, current-project source navigation, stable order, duplicate handling, matching and role preference.

## 4. Isolation and Safety

- Cache storage is project-scoped and located in the IDE system cache.
- A restored source file must belong to the current project's source content.
- No cache operation may write under the user project or connect to a broker.
- Cache failure must degrade to existing scanning without changing UI, parser, matcher or refresh entry behavior.

## 5. Versioning

- Current cache version: `1`.
- Unknown versions are rejected as a whole.
- Version migration, history and cross-project sharing are outside this feature.
