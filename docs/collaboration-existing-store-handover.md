# Handover from the original closed SDK store

`stageExistingHandover` can stage an exact organization target after a replacement
native login has closed the original SDK. It preserves the original company/native
namespace, protected credentials, message-operation identity and private keys. This
is a local storage capability; it grants no server recovery or group-runtime authority.
The existing open-client handover and credential-replacement guards are unchanged.

The Android repository captures the current process parent and excludes the exact
original physical store. An older local login handle may identify that same store
for draining; it cannot change its account, audience, native family or device. Every
old reservation, including a constructor without an attached resource, must finish
before the repository enters the directory. Account replacement invalidates success
publication while cleanup remains owned. At most five gates may coexist.

Under an existing-only exclusive directory lease, the repository reads the protected
credential record and derives public identity from the actual private SDK account.
It compares the exact original provider user, device and fingerprint before writing.
Both SDK databases must exist. Missing, corrupt or different private material cannot
create a store, restore a session, construct an SDK or replace the retained keys.
The Android composition root supplies the raw inventory and SDK4 identity sources;
repositories do not call other repositories. Unsupported hosts remain unavailable.

The durable target uses the existing strict schema2 record. Only its schema, revision
and handover fields change. A repeated identical target performs no write; any changed
target fails. After process death, the same account may read/retry it with a new local
login handle while the original physical namespace stays unchanged. All legacy
foreground/background/restore/install paths reject the staged record. The current
codec requires `initialized=true`: an interrupted credential replacement that retains
private keys with `initialized=false` needs a separate versioned record path. This
implementation does not invent initialization or silently relax the existing decoder.

The directory remains leased through synchronous private-account work, cancellation
and credential cleanup. A primary cancellation/failure survives cleanup failures.
Cancellation after a committed write retains the original target even when the caller
receives no success. Neither a failed nor an uncertain local write permits server
freeze. The owning use case still needs provider-publication preflight and fresh
account/native/group admission before any server operation; hard-parent registration,
drain/completion and group adoption remain separate unfinished work.

Focused acceptance passes34 cases, including six new registry and nine new repository
cases. Those repository tests use simulated raw storage/provider dependencies. The
separate debug-only `LocalExistingRuntimeHandoverProbe` uses actual SDK4, Keystore,
canonical file locking and SQLite on x86_64 and ARM64. Both stages retain identical
private keys/main database bytes through contention, wrong identity, interrupted
writes, process death and exact replay without new SDK construction. SQLite WAL/SHM
bookkeeping bytes are not claimed unchanged. No messages are sent and no provider,
employee account or product APK is involved. Both owned fixtures and their emulator
are cleaned up. The full root/Android/browser gate passed5,490 fresh cases/644 suites in586.14s,
with zero failures/errors/skips and unchanged4,267 inputs. Detekt has zero findings.
[Evidence](measurements/collaboration-existing-store-handover-2026-11-06.json).

To reproduce the native mechanism, use only a fresh owned emulator and the debug
`collaboration-proof` APK with the published SDK4 dependency. Run instrumentation
scenario `existing-handover-stage`, retain its bounded JSON report, force-stop and
verify that its process is absent, then run `existing-handover-resume`. Verify both
reports and selected ABI, remove only that owned fixture package and stop the owned
emulator. A failed fixture must be retained for inspection before retrying. Never
run this probe on an employee's phone or replace the original Bima fixture data.
