# Source-bound room-key observation

A typed key batch carries the exact logical runtime, original physical source, copied room IDs,
reconciliation flag, and opaque receipt. Invalid or oversized raw locators retain reconciliation;
invalid batch identity is a validation failure. Structural capture grants no live authorization.

The owning Android runtime repository registers explicitly under its existing source and maps the
retained callback stream. Reads and collection never register, start sync, retry acquisition, or
acknowledge. Retirement/credential failure terminates observation. ACK resolves the exact current
runtime/source and only releases the matching receipt; a stale receipt cannot consume its successor.
Unsupported hosts return Unavailable. DI reuses the existing runtime owner rather than another client.

The admitted use case must still confirm durable recovery intent before ACK and own bounded retry.
No production registration or source/restore/key producer is activated here. Tests use real repository
adoption with fake SDK handles; they are not native SDK or device execution. Android, browser, and
portable/Apple compilation do not implement a native Apple room-key adapter.

[Exact validation evidence](measurements/collaboration-space-room-key-binding-2026-11-12.json).
