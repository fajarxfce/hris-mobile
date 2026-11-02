# Native chat recovery admission

The client can now discover bounded device references for the current native parent
and acquire separate company admission without reading or changing the foreground
workspace. Automatic company sweeps and SDK background retention are the next
integration step; this increment does not enable notifications.

`LoadCollaborationDevicePage` reads one page (default eight, maximum 32). An existing
cursor requires its originating parent. Parent identity binds the local session,
API audience, account and stable native-session family. The use case rechecks that
identity after pending I/O, rejects late/backward results and has one active request
without a waiting queue plus a 15-second budget. Retained device metadata grants no
company/content authority; REVOKED/PROVISIONING references remain metadata only.

`AcquireCollaborationBackgroundAdmission` requires a matching active native device
and current parent. It calls the separately protected per-company admission route.
The response must match company/account/native parent/device, contain nonnegative
versions and a positive lifetime at most 60 seconds. A device observation cannot
move backward from the directory version. The local deadline starts at request
dispatch, includes transport latency and is capped by the current parent's expiry.
Clock rollback, expiry, caller cancellation or parent replacement withholds success.

The returned value is an ephemeral observation, not a provider bearer, SDK store
lease or local-read authorization. A received company/MFA denial reaches the
retained account/company gate even if subsequent local storage fails. Successful
admission does not clear that gate; admitted content acquisition must do so through
its existing revision check. Workspace selection and owned local facts are untouched.

The authenticated HTTP pool pins the originating local session and configured
origin; its existing bounded native-token renewal remains in place. Recovery owns
no new credential pool, native SDK session or enrollment. Directory and admission
responses are bounded to 16 KiB and 2 KiB, respectively. Mapping rejects foreign scope,
unordered/duplicate/oversized pages, nonprogressing cursors, invalid device metadata,
malformed times and versions. Use-case singleton bindings resolve in the actual
shared application container.

Next, add typed foreground/background operation scopes to existing acquisition and
outbox use cases, so both reuse history-integrity and denial rules. The application
must explicitly retain a foreground SDK owner and finite background borrowers for
the current native parent. Never open one cryptographic store twice, let a borrowed
close capability destroy foreground ownership, or allow late work after replacement.
Collapsed hints/reconnect/periodic sweeps need durable per-parent/company cursors.
Only then connect incoming push, decrypted notification projection, mute/read/privacy,
precise account-owned actions and encrypted durable inline replies. Keep backend
push delivery disabled until the complete native recovery path has provider evidence.

[Validation](measurements/chat-native-recovery-client-2026-11-02.json) records the exact source, test commands, counts and release startup evidence. The matching [Platform contract](https://github.com/fajarxfce/hris-platform/blob/8b77c4fab7f49fc646336412324830261651247c/docs/collaboration/native-recovery.md) is validated separately.
