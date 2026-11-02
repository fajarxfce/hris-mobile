# Shared admitted background delivery worker

`CollaborationDeliveryWorker` accepts an ephemeral background admission alongside
its existing selected-company `SyncTask` entry. Both entries share one worker and
use nonwaiting mutex admission: a simultaneous invocation returns Retry with
`client.collaboration_delivery_busy`, without joining a waiting queue.

The background pass uses the remaining original wall and monotonic admission
budget, capped at 60 seconds. Setup, local discovery and native acquisition consume
that same budget; each room retains its existing 12-second bound. Setup loading
can readmit an old company denial only through the already validated exact-device
and gate-CAS path. The resulting captured local scope discovers frozen drafts and
messages without reading or changing workspace selection.

Only nonempty delivery work acquires a finite background runtime reference. The
worker keeps it across the existing room preparation/replay, timeline, staging,
delivery, original-receipt recovery/projection and retirement path. It releases
only that reference, including after cancellation or a late acquisition result.
Foreground acquisition, enrollment and implicit pusher activation are not part of
this background entry.

An application-owned monitor observes SDK credential-write failure, runtime
interruption and local ownership/denial independently. A native failure does not
wait for an initial access-flow emission. Already failed references cannot start
delivery. Termination cancels and joins pending child work and disposes all three
observations; the surrounding pass owns reference release. The original command,
receipt and cursor facts remain in their existing stores.

Background history focus capacity returns a bounded retry instead of reclaiming
the shared foreground runtime. A later pass recovers the original receipt without
sending the message again. The selected path keeps its existing controlled history
reopening behavior. Tests also verify that releasing a background pass preserves
an already retained foreground client.

[Validation](measurements/chat-admitted-worker-2026-11-02.json) binds the exact source
inputs and executed tasks. The actual application graph and encrypted stores run
against a simulated SDK and control-plane wire fixture. New cases cover restart,
readmission, room-response loss, history capacity, company/session/SDK interruption,
concurrent invocation, original deadline expiry, late cancelled acquisition and
monitor disposal. The raw fake SDK explicitly opts into background borrowing and
cleans a cancelled acquisition; it is not a replacement for native-provider proof.

This entry does not yet persist all-company wake generations, enumerate every
company, receive FCM, or post notifications. Durable wake/directory/reconnect/
periodic coordination and initial native-parent readiness remain next. Platform
job input must keep only the existing durable task key; an ephemeral admission or
message body must not be serialized into that input. Backend chat push remains
disabled until receive/decrypt, privacy/read/mute and inline-reply acceptance.
