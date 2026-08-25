# UI Behavior: Sidebar Source Navigation

## Trigger

- User double-clicks a visible MQ sidebar tree row with the primary mouse button.
- The target row is resolved from the event coordinates.

## Valid Source Target

When the row represents an endpoint or relationship with a valid source location:

1. The row becomes selected.
2. The source location is validated without reading the source model outside its permitted context.
3. The editor opens the exact source declaration once.
4. No internal thread-access error is reported.

This behavior applies equally to ordinary endpoints, special queues, exchanges, bindings and relationship targets.

## Invalid or Missing Source Target

When the row has no endpoint, the project is disposed, the source location is stale, or the target cannot navigate:

1. No file is opened.
2. Existing editor selection remains unchanged.
3. No retry, refresh, popup or notification is started.
4. No internal thread-access error is reported.

## Preserved Behavior

- Search, filtering, refresh, progress, module expansion, caching and relationship rendering are unchanged.
- Gutter navigation behavior is unchanged.
- User project files and message services are untouched.
