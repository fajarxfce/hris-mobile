# Continue native delivery recovery and interoperability

The application worker, durable scheduler wakeups, encrypted fair cursor and thread
handoff are now implemented. See [current contract](collaboration-background-delivery.md).
This does not accept all background, provider or product requirements.

Latest-head capacity recovery is now implemented for uncertain delivery and explicit
foreground Refresh. See [recovery contract](collaboration-history-recovery.md). Continue
actual provider and platform execution evidence next.

1. Exercise the actual worker with Android protected stores, real SDK and fictional
   HRIS/Synapse gateway. Existing apps/collaboration-proof / NativeCollaborationSdkInteropTest
   covers prior sender/recovery use cases, not this application worker. Extend a separately
   identified scenario and evidence, preserving the old four-process/three-death assertions.
   The proof app may depend on apps.shared to invoke the same orchestration class; do not
   duplicate the worker into fixture code. Keep provider/SDK proof distinct from WorkManager
   lifecycle proof. A queued command must survive process death before platform execution,
   retain exact content/ID, deliver once, acquire originals and retire only with evidence.
   Use the dedicated emulator; never drive the user's phone for routine tests.

2. Finish remote older-history paging, durable drafts and safe pending cancellation, then
   incoming FCM/pusher/gateway integration and inline replies. Keep payloads opaque and
   admission-bound; notification actions must use the same durable command pipeline.
   Android background delivery alone does not supply Apple's PushKit/CallKit, notification
   service extension or browser service-worker behavior.

3. Continue all remaining P0–P8 / 54 catalog groups: groups, media/stickers/voice notes,
   edit/forward/delete, E2EE verification and cross-family keys, backups, full Apple/web
   adapters, calling and meetings. Do not mark provider feasibility as product acceptance.
   Record each tested increment and push its atomic commit during the authorized long run.

`RefreshWorkspace` remains the registered periodic live policy renewal task. Known
company/MFA denials remain gated until admitted acquisition; owned local history and
pending intent survive ordinary freshness/credential expiry. Never hide a denial merely
because another read or scheduler trigger started.
