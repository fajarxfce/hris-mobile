# Trusted local sender identity

Chat history identifies its original Matrix sender independently of HRIS account UUIDs
and message-controlled logical IDs. `ReadCollaborationIdentity.current` reads a locally
retained account/company identity; explicit `acquire` can learn it from this native
family's protected, initialized SDK record. Neither path requires fresh live credentials,
opens an SDK client or directory, starts synchronization, enrolls a device or performs
network I/O. Both paths check local ownership and the shared company admission gate
around every acquisition/publication. Cancellation and account replacement prevent a
late result from reaching presentation. A failed or absent SDK read leaves previously
retained identity available through the separate local read.

Android's runtime adapter creates a short-lived protected credential handle, validates
the complete existing scope/record through `CollaborationSdkRecordCodec`, and returns
only Matrix user identity. Missing/uninitialized records return no identity. The handle
closes on success, technical failure and cancellation. There is no credential rewrite,
key creation, native client construction or directory lease. Unbound platforms return
explicit unavailability. No access token, refresh token or passphrase reaches the new
domain entity or its redacted string representation.

`EncryptedCollaborationIdentityRepository` retains the first identity or accepts an
exact replay; a different identity cannot overwrite it. Publication uses the raw
replica checkpoint CAS and account fence. The payload has a distinct authenticated
encryption purpose bound to account, audience, company, generation and revision. The
sender appears only inside ciphertext, never in visible indexes. A changed checkpoint,
owner scope or ciphertext fails validation. The `collaboration-history/self/<company>`
partition deliberately falls under the existing history key-loss guard, so an identity
record alone prevents generation of a replacement key. Originals, traversal metadata
and identity reuse the existing protected content-key format with separate purposes.
No schema migration or new platform credential container is required.

The local identity survives native-family replacement because it belongs to the
account/company. The protected SDK record itself remains native-family scoped. This
cache does not migrate cryptographic keys, repair a lost key, establish device trust
or complete backup. A provider identity change requires an explicit migration protocol;
there is no inference from a peer message or silent replacement.

Next, thread presentation must use this identity together with exact original sender,
content hash and retained intent when joining history and pending messages. A matching
logical ID alone cannot hide a pending send or classify a peer message as outgoing.
Thread navigation, delivery scheduling and all remaining P0–P8 work continue. This
identity foundation alone accepts no complete PRD group.

[Validation and source hashes](measurements/collaboration-sender-identity-2026-11-01.json)
retain every attempt, exact source inputs and artifact hashes.
