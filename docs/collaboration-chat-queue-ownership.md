# Delivery across retained device namespaces

Account-owned conversation history can contain pending commands from several native
sessions. Previously, a staged command from an older session rejected the current
room's entire send mapping. The worker could also repeatedly take its oldest eight
commands from that older namespace, starving new messages.

Delivery now maps only the acquired physical SDK store's commands. Original native
family and device identity define that boundary; bootstrap aliases in the same store
still require exact captured transaction evidence. A foreign staged transaction
actually present in the current queue is rejected. Every current echo still needs an
authenticated mapping before the SDK queue can resume. Same-store unfinished staging,
invalid records, foreign accounts and room mismatches retain their existing guards.

Other stores' pending records keep their original revisions, private keys, transaction
IDs and uncertainty. They are neither restaged nor acknowledged by the current device.
New staging ignores competing attempts in a different physical store, while its
complete-snapshot check still rejects unknown current work. A pure domain batch policy
selects current-store work before applying the eight-command limit. The platform
worker delegates that decision; repository and datasource responsibilities do not move.

The physical build50 log exposed three QueueMappingNativeFamily failures. Seven new
regression cases reproduced five failures on unchanged production code, including an
actual encrypted AppContainer restart with ten older staged/staging commands. The
same tests pass after the correction, alongside existing delivery/staging/worker
coverage: 76 focused cases, zero failures/errors/skips. The integration test observes
one new confirmation, retains every old record unchanged through another restart,
and never enqueues or resumes the old transactions. Its provider is simulated;
physical old-message delivery has not been accepted.

The complete root/Android/browser gate passed in 540.556 seconds: 5,454 fresh cases
in 639 suites, zero failures/errors/skips, 4,254 unchanged source inputs, and zero
Android lint/Detekt findings. Exact release APK checks remain required.
[Evidence](measurements/collaboration-chat-queue-ownership-2026-11-06.json). Recovery of
original pending commands through their original private store and current authority
is separate ongoing work; this change does not claim those commands were delivered.
