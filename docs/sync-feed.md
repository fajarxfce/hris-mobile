# HRIS change feed

`core/sync` exposes `SyncFeedRepository` for the backend's explicit collection
bootstrap and incremental endpoints. It maps references and opaque cursors into
domain values; it does not hydrate resources, persist a cursor, retry, or select
a collection for its caller. Feature use cases own those decisions.

The authenticated API adapter bounds pages to 200 references and responses to
64 KiB. Validation rejects unknown/unrequested collections, malformed identifiers,
invalid versions, oversized cursors, and a non-progressing page that claims more
data. Request acquisition and mapping share the HTTP failure boundary. Coroutine
cancellation propagates, and cursor values are excluded from entity diagnostics.

Bootstrap returns one continuation cursor or one changes cursor. Incremental
pages retain ordered updates and tombstones, including repeated references to
the same resource. Canonical resource fetching and version reconciliation remain
feature policy. A collection must be selected on every request.

JVM and browser tests cover collection/cursor encoding and validation, ordered
tombstones, request bounds, and cancellation. The shared authenticated client
owns credentials and rejection of requests from a replaced session.
See the server's [protocol](https://github.com/fajarxfce/hris-platform/blob/main/docs/synchronization.md)
for authorization, cursor expiry, polling, and post-commit publication.
