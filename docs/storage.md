# Local storage

## Proto DataStore

`core/datastore` owns `user_preferences.proto`, Wire generation, platform storage,
and `UserPreferencesStore`. `data` exposes `Flow<UserPreferences>`;
`update { current -> current.copy(...) }` is an atomic read-modify-write operation.
Keep transforms pure. A single application container owns each store and cancels
its observations on close. Do not create a store per screen.

Wire generates Kotlin models for all targets instead of JVM-only protobuf models.
Repositories consume the store, map DTOs, and wrap acquisition/mapping with
`safeStorageCall` or `safeStorageFlow`. Domain and presentation never import proto types.
Onboarding uses this path and migrates its previous platform preference once.

Android/iOS/desktop use DataStore with protobuf files. DataStore 1.2.1's published
Wasm factory is unimplemented, so Web uses a mutex-protected reactive localStorage
adapter with the same protobuf bytes and `UserPreferencesStore` contract. Browser ownership is one store per
key per page; this does not provide cross-tab reactive invalidation. Browser storage
is subject to browser eviction/private-mode restrictions. It is not credential storage.

Add fields with new protobuf numbers. Reserve deleted field numbers/names. Wire
preserves unknown fields through `copy`; do not reconstruct a message when updating
one field. Corrupt data is reported, not silently replaced with defaults.

## Database

`core/database` exposes raw `InboxStore` and `DashboardStore` contracts. One `AppDatabase`
owns the connection and its stores; close the owner, not individual stores. Room
implements it on Android, iOS, and desktop using bundled SQLite. Web uses IndexedDB
because Room does not publish a Wasm target. The inbox adapters support ordered observation,
upsert, marking a record read, and clearing records. Repository owns domain mapping.

Room DAOs, entities, and database stay in `sqliteMain`; platform files only construct
the database. Commit exported JSON schemas. Increment the database version and add a
tested migration when changing an existing schema. No destructive fallback is enabled.
IndexedDB schema upgrades belong in `onupgradeneeded`; its transaction completion,
not individual request completion, determines write success. Observers update after
committed writes in the current app instance.

Run `./gradlew :core:datastore:desktopTest :core:database:desktopTest` for actual
filesystem/SQLite tests. Browser behavior also needs a browser smoke test.

Database version 2 adds dashboard content, device-owned saved preferences, and an ordered outbox.
Room uses a tested automatic migration from v1; IndexedDB adds stores during its v2 upgrade. Both
preserve existing inbox records. Updating a saved preference and inserting its outbox command is
one transaction. Acknowledgements delete by operation ID. Refreshing content does not overwrite
local preferences. The dashboard flow reads content, preferences, and pending counts in one query
or transaction. Platform roots create the database once with `createAppDatabase`.

## Transfer storage

Room version 5 adds `transfers` and `transfer_chunks` through the v4→v5 migration.
IndexedDB version 4 adds equivalent stores. Metadata contains no file bodies, tokens or URLs.
Content blocks are at most 256 KiB; account ownership, storage admission, version checks,
and metadata/content updates execute transactionally. Removing a transfer or changing
accounts also removes its chunks. Browser transfer invalidations use their own signal,
so checkpoints do not trigger dashboard/inbox reads.

See [file transfers](file-transfers.md) for limits, resume policy and retention.
