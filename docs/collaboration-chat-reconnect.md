# Recover the original chat connection

## Retained room feedback — 6 November 2026

A retained SDK rejection belongs to the reconnect action while the thread is in
SETUP_REQUIRED. When the route stops or starts connecting again, that old SDK
failure must not become a generic warning and Retry button. The page now derives
its feedback once and uses the same result for both the notice and retry action.
Local-read failures remain visible and keep the composer disabled during retry.
The ViewModel, SDK failure state, authorization and credential recovery are unchanged.

Release41 recordings exposed the old warning during an online reentry transition;
the final UI had already recovered. A subsequent three-entry online probe showed
nine retained rows and no auth diagnostics. Read-only server audit confirmed normal
token refreshes. The original callback's transport cause remains unestablished;
the old capture also enabled networking before terminating the preceding process.
Clean first-entry captures now stop that process before changing connectivity.
Interrupted refresh recovery remains a separate acceptance task.

The new UI regression failed on the old page and passes with the correction. It
checks cached messages, drafts and send availability through OFFLINE/CONNECTING/
CONNECTED, the one current reconnect action, and retained local-read failures.

Validation: the corrected focused run passed four Compose tests; the complete
root/Android/browser/proof/format/architecture gate passed 1,114 fresh tests in
369.573 seconds on 4,204 unchanged product inputs, with zero lint issues.
[Measurements](measurements/collaboration-retained-feedback-2026-11-06.json).
Release 41 still requires exact-APK emulator acceptance before phone installation.

## Original connection recovery implementation

A rejected Matrix refresh now reaches the product through the pinned SDK ClientDelegate, registered before restore. The callback exposes only a closed unauthorized classification. It has no reference to the SDK client, route or Activity. Its native task handle belongs to the runtime and is deactivated before shutdown, canceled/closed after native drain. Repeated and late closed callbacks are ignored. Background task strings are discarded. Known authentication failure survives storage callback failures, delayed writes and failing acquisition cleanup; cancellation remains cancellation.

This failure stops live SDK activity and retains account-owned local chat reads under the existing policy. It does not imply HRIS account/company revocation. Explicit HRIS denials remain sticky through normal acquisition. No keys, private messages, tokens or provider exception details enter diagnostics.

A confirmed newer bootstrap can replace only its original owner, HRIS device, Matrix identity and gateway. The runtime registry retires the exact existing client, invalidates borrowers, retains its capacity until full native pause/store drain, then atomically replaces its gate with the new foreground reservation. Other openers cannot enter between drain and install. Parent/foreground changes, wrong identity, stale observations, capacity, repeated gates and late release callbacks cannot authorize another owner. A failed or unfinished drain leaves the original store owned; the durable setup intent remains available. Installation reuses the original private store/passphrase and revalidates the durable record. Initial unknown outcomes never create a replacement operation automatically.

The thread presents cached messages and a reconnect action for this specific error without repeated background connection attempts. The setup page uses an explicit reconnect phase and short EN/ID text. Initial server provisioning is checked quietly at most six times (2/4/8/16/30/30 seconds), while visible; it creates no provisioning command. Navigation, known access loss and disposal stop the checks. Routine refresh controls are removed from setup; failures without another action may still offer an actionable retry.

Validation: 19 new data cases (3 common persistence, 3 Android delegate, 3 Android handover, 8 registry replacement, 2 metadata), 5 ViewModel and 2 Compose cases. Actual separate API/PG/Synapse/pinned Android SDK fixture replaces only the original gateway credentials, receives real 401 refresh and typed callback, then uses the product repository to drain the old client, install the confirmed new credentials in the same store and complete an actual SDK sync. Original private key fingerprint is compared only in memory. One device/key publication, parent/device still active, exactly one gateway rotation and owned cleanup are asserted. Server test clock moves 11 seconds beyond the real ten-second rotation guard; no production guard is disabled. No encrypted message exchange is claimed by this small probe. Normal encrypted/process-death/history/renewal proof is run separately. The local-read flag checks policy, not a new actual-cache read.

The user confirmed that release31 shows Bima messages immediately when reopening the room. The earlier generic connection error remains unattributed; this confirmation does not claim a physical offline test. Native normal and rejection fixtures are isolated fictional accounts, not the live phone. Original-bootstrap lost-response resolution after finite server replay, original hard-parent recovery, organization adoption and the rest of communications PRD remain separate open work. No iOS, live FCM, actual organization runtime or calls/meetings acceptance is implied. These adapters and policies are HRIS-specific; no Starter port.

Final combined root, Android, browser and proof-APK gate passes 2479 fresh cases in 531.299 seconds on 4,050 unchanged inputs. It includes browser data (588) and presentation (151), Android-host, actual app DI, formatting, architecture and detekt. Earlier unaffected outputs remain bound by their report hashes; do not add fresh and reused counts as independently rerun tests. Focused final data (334 cases / 146.405s) and initial UI (59 cases / 293.259s) passed before the final compact-notice visual adjustment, which is covered by the combined gate.

Both actual native suites pass in 145.221 seconds on 12,260 unchanged backend/Mobile inputs and the same APK `ab255d5b991727ab1ef29fae990cc9f82e218e1e67ce847639977c26008a6ed6`. Normal renewal retains all original encrypted exchange, transaction, history, receipt and process-death assertions, with exactly three encrypted sends. Owned emulator shutdown is confirmed.

[Measurements](measurements/chat-reconnect-2026-11-05.json). Fictional Compose renders were opened and reviewed: [compact reconnect action](images/chat-reconnect/chat-reconnect-local-history.png) and [Indonesian 320px at 200% text](images/chat-reconnect/chat-reconnect-indonesian-large.png). These are state-driven renders, not screenshots of the user's phone.

NEXT: build, validate and install release32 with advance notice and install-only on the authorized phone. Release31 remains installed until that separate delivery is recorded; the user confirms Bima messages appear immediately when reopening it. Continue actual Bima operation diagnosis, delivered edit/quoted reply/forward/delete-for-all, original bootstrap resolution, organization adoption, calls and meetings.
