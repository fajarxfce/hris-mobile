# Notification candidate delivery

The notification process accepts an opaque parent/company/conversation/room/event candidate
through `publish`. Queue offer and close share one atomic state, with at most 20 live
caller completions. A caller waits up to eight cooperative seconds and joins cancellation
of its registered work and native compensation. The budget cannot forcibly interrupt an
uncooperative OS call. The queue retains no preview, message text or action envelope.

Source slots use company/conversation identity and may temporarily have no journal reference.
Active and provisional slots together remain within 64. New candidates wait for the first
source observation; an actual failure bypasses the publisher. Promotion to a discovered
publication keeps the same source subscription and handled signal. Duplicate source keys
in a complete journal scan are rejected. Changed/forgotten native IDs receive exact cleanup
before a source is released; an incoming full journal displaces excess provisional requests
before adding its sources.

Lazy work captures epoch, source signal, scan revision and request liveness, checked again
after registration. Parent replacement rejects old requests before reading the new journal.
Cancelling one request does not cancel another request sharing its source. Cancellation
following committed preparation leaves that unchanged source handled and its journal
quietly recoverable until a later signal or explicit recovery. It does not immediately
replay behind the cancelled caller. Closing resolves queued callers and joins work and
sources; a late native completion must join exact compensation before cancellation returns.

Eligible candidates alternate with dirty references. Failed source cleanup for an existing
card takes priority. Busy work retains its request until the surface becomes idle with a
new execution revision. A suppressed/older candidate does not acknowledge an unrelated
dirty current card. The admitted publisher remains responsible for source/parent checks,
preparation before native submission, exact compensation and conditional settlement.
Only an explicitly allowed first original may alert. Same-original replay after native
uncertainty retains the same reference and remains silent.

[Evidence](measurements/chat-notification-candidates-2026-11-03.json) records 15 additional
virtual-time candidate cases and three additional actual Koin/encrypted SQLite cases.
The latter prove first-original alert/silent replay, initial mute without publication,
and uncertain native acknowledgment retaining Prepared before silent recovery. They use
fictional acquisition and a fake OS. Focused runs pass 65, 75 and 81 cases as coverage is
added. Full validation passes 3,711 desktop, 230 Android-host, six proof-host and ten
build-logic cases, plus proof assembly, shared Wasm and root quality; no failed attempt.

The API is not yet bound to incoming priority recovery or an HRIS native host. Integration
must respect existing incoming/priority budgets and keep durable hints through uncertainty.
Foreground focus, initial/backfill/dropped-hint rules, permission/channel/capacity recovery,
authenticated callbacks, encrypted input review and notification-input process-death proof
remain next. Chat push remains off and no product APK was installed by this change.

[Incoming identity acquisition](collaboration-incoming-identity.md) now supplies the owned
self identity needed for notification preparation without requiring a previously opened chat.
The priority publication handoff and actual native host are still pending.
