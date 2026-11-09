# Matrix credential continuity

Device encryption keys, optional secret-storage metadata, and gateway access tokens
have separate lifetimes. A new device creates and publishes its private/public key
pair through activation. Existing encrypted history must retain that original private
store; missing keys require recovery. An absent `m.secret_storage.default_key` does
not establish that device encryption keys are absent.

Native78 retained its private identity and old messages, but sent a refresh token
matching neither current nor previous gateway hash. A repeated SDK soft logout is a
credential failure. Opening another client with the same rejected token does not
repair it. See [native evidence](measurements/collaboration-recovery-native78-2026-11-09.json).

## Closing the credential owner

The Android lease previously closed `SdkSessionPersistence` before stopping child
work, joining E2EE initialization and pausing native stores. A successful refresh
callback during that interval could not persist its new tokens. A regression against
unchanged production reproduces this failure. The writer now remains available
through this drain and closes after the native handle, before its exclusive directory
is released. A failed drain keeps the writer and directory owned for the same close
operation to finish. New entry is still prohibited immediately when closing begins.

This is a resource-lifetime correction, not proof of every possible native task's
termination. SDK `pause()` waits for database operations; disabling queues does not
abort an in-flight HTTP send. Network loss or process death can also interrupt a
rotating refresh response. Credential recovery must therefore remain correct even
when the client has a stale token. The historical loss instant in native77 is unknown.

## Retained rejection

Confirmed gateway rejection is now stored in the exact protected credential namespace.
Reopening that namespace preserves rejection before constructing another network
client. The original and adopted namespaces carry separate flags. Callback tokens,
initialization and exact bootstrap replay cannot clear them; an admitted replacement
clears only its own namespace. Original keys and queue identity stay unchanged.
Ordinary age, offline failures and absent backup metadata do not mark credentials
rejected. A failed rejection write retains the credential/native/directory owner for
the same close attempt to finish. [Focused evidence](measurements/collaboration-credential-rejection-2026-11-09.json).

## Automatic recovery ownership

The domain activation/recovery owner uses current HRIS admission and the existing
durable Bootstrap command to replace gateway credentials. Preserve private keys,
queue namespace, original operation IDs and pending messages. Late rejections,
duplicate foreground/background consumers, failed persistence, cancellation and
restart must not manufacture a chain of new bootstrap operations. The application
owns observation; a ViewModel or network inspector must not implement this policy.

Native acceptance must still resume the preserved `Recovery78 005634` original, demonstrate
one server event, and measure Home/room/background traffic after recovery. Do not
resend that text or replace the fixture with a new account to hide the stale session.

An asynchronous auth error after READY now enters a scoped signal flow from existing
SDKs. The domain observation admits one recovery until a different generation completes
live Matrix sync. Duplicate rejection, a response from the rejected generation and an
unvalidated rejected replacement cannot manufacture another bootstrap. Retired or
replaced runtime reservations cannot publish a late signal. Observation opens no SDK.

Application composition serializes selected-owner activation and recovery signals.
Owner changes cancel old activation and its observer. A rejected live lease gets an
explicit exclusive drain/inspection before original private identity checks; ordinary
healthy inspection remains read-only. Other denials and inspection-only activation do
not authorize this drain. Background work retains the selected-activation boundary.
See [focused source evidence](measurements/collaboration-credential-reactivation-2026-11-09.json).
