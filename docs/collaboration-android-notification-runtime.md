# Application notification runtime

The Android Application owns one chat notification runtime across Activity recreation.
It binds the actual application collaboration state to a process and native surface.
Native parent identity comes from the observed local session/audience/account/native
family; changing the selected company does not replace that parent. Company access,
source validity, notification permission and focused-thread suppression remain
independent checks inside each finite publication/action.

Startup is explicit. The delivery binding is installed before collecting application
state, but remains unready while that state is Pending. A cold MarkRead/Mute/Dismiss
or reply acknowledgment waits for the first consumed observation within the same
8-second caller deadline used by its operation. No second deadline is added for
startup. Closing releases waiters and prevents rebinding; cancellation joins owned
work. A stale Blocked surface phase for a replaced parent is only Pending advisory
state. A cleanup failure for the current parent remains Blocked until explicit retry.
All leases still verify the actual parent/epoch and serialize native I/O.

Open links authenticate through the domain and route independently of this display
binding. Inline replies retain protected input before acknowledgment and recover
through the existing bounded worker. Acknowledging a reply retires its notification;
it does not itself confirm network delivery. Saved replies expose pending/review
status, encrypted working drafts and explicit discard. [Reviewed sending](collaboration-reviewed-replies.md)
persists separate consent and handles its new operation before original completion.

AndroidCollaborationNotificationRuntime holds only application context. It owns the
language collector and creates native display/intents plus private callback adapters.
An immutable public copy is sampled once per publication/reconciliation. Resources
follow the observed app language (English/Indonesian) or current device resources for
System; System is the fallback while the initial preference is unavailable. Language
changes do not recreate the process or replay a business action. Callback feedback
uses the same resources. Explicit closeAndJoin retires native cards and joins both
host and language collection. Application termination cancels the runtime; normal
Android process death relies on the previously tested durable journals/recovery.

Validation layers remain distinct. Common virtual-time tests cover host ownership,
readiness/cancellation/close and stale epochs. Desktop integration uses the actual
application Koin graph and encrypted SQLite, including a sealed MarkRead arriving
before real session observation starts. Android runtime proof uses the real runtime,
resources, process/surface and NotificationManager with fictional admission and
language-storage ports; it checks English, Indonesian, System and joined cleanup.
Separate Android native callback proof exercises real PendingIntents/RemoteInput,
protected inputs, durable action settlement, ordinary process deaths and SystemUI.
The minified product still requires cold-start verification before phone installation.

Local acceptance: [measurement](measurements/chat-native-host-2026-11-03.json) records
112 focused desktop cases; full3 covers3,968 desktop,230 Android host,15 proof and ten
logic cases with zero failures/errors/skips. Root quality/Wasm/Android assembly and
lint pass. Proof4 validates corrected fixture IDs before native1 passes105.401s; four
runtime cases, raw16, three process kills and five callbacks/SystemUI pass. All three
SystemUI screenshots were actually inspected. [Release24](measurements/android-release-phone-2026-11-03-build24.json) is installed
on the authorized phone after minified emulator startup checks. Updated hosted
[Apple](measurements/chat-native-host-apple-2026-11-03.json) and
[Native](measurements/chat-native-host-hosted-2026-11-03.json) checks of this host source
are accepted. New reviewed-send source has separate gates.
Chat push remains disabled on the live server. This application/session policy has
no Starter port; the previously accepted generic notification sources remain shared.
Voice/video/meeting behavior and physical iOS/APNs/CallKit are not established here.
