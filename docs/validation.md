# Validation

Current Linux checks:

- Architecture checks for 33 modules, ktfmt and Detekt with no findings.
- 92 JVM tests: session restoration and revocation, concurrent refresh, cancelled
  and late results, secure-store failure recovery, account cache isolation,
  SQLite migrations/transactions, stale pagination, offline data, form validation,
  duplicate submissions, feature flags, notifications, workers and navigation.
- The assembled Compose UI covers onboarding, login, load more, flags, inbox,
  changing language and logout. Resource tests load both languages and plurals.
- 53 browser tests cover IndexedDB, DataStore, session policy, API error mapping,
  language resources, feature flags, workers and diagnostics.
- Android dev/staging debug and prod unsigned release APKs; Web distribution.

Gradle dependency verification is enabled using the committed SHA-256 metadata.
See [quality gates](quality.md) for updates and their trust boundary.

Screenshots from UI tests are under `apps/shared/build/reports/screenshots`.
The CI workflow also builds an unsigned iOS simulator application and Windows
classes. Compilation does not validate native credential-manager access, Apple
signing, Firebase delivery, or OS permission behavior on physical devices.

Earlier revisions were tested on Android 16 for notification access, Settings
recovery, local delivery/taps, FCM registration and WorkManager sync. Those device
checks were not repeated for the session/localization changes: the device was
unreachable. No deliberate crash was triggered and Crashlytics delivery has not
been verified in Firebase Console.

The demo transport runs in-process. Its tests verify client contracts and
idempotent replay behavior; a real backend still needs its own authentication,
refresh-token rotation, idempotency and conflict-resolution validation. Web
credentials are ephemeral; persistent browser authentication requires a backend
cookie integration.
