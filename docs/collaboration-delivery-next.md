# Continue native delivery recovery and interoperability

The application worker, durable scheduler wakeups, encrypted fair cursor and thread
handoff are now implemented. See [current contract](collaboration-background-delivery.md).
This does not accept all background, provider or product requirements.

Latest-head capacity recovery is now implemented for uncertain delivery and explicit
foreground Refresh. See [recovery contract](collaboration-history-recovery.md). Continue
actual provider and platform execution evidence next.

1. [Native worker evidence](collaboration-native-worker.md) and the separate
   [WorkManager scenario](collaboration-workmanager.md) now pass. The latter proves
   task-key-only persistence, offline constraints, ordinary process death, automatic OS
   startup, exact original-history recovery and retirement. Both prior native regressions
   remain intact. Add this scenario to the pinned native CI job and audit its artifacts.
   Deliberate interruption during sending, retry/cancellation races and production login
   recovery remain separate acceptance criteria. Force-stop blocks automatic Android
   execution until the app is restarted. Use only the dedicated emulator for routine tests.

2. [Bounded foreground remote history](collaboration-remote-history.md) now passes domain,
   ViewModel, layout and browser tests. Extend the existing ten-page local window to bounded
   sliding/bidirectional browsing, and prove the foreground coordinator with the actual SDK
   and provider. Continue durable drafts and safe pending cancellation, then
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
