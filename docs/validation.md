# Validation

Local validation on Linux:

- Architecture checks for 17 modules and Kotlin formatting.
- 21 tests, including cancellation, stale refreshes, buffered effects, storage failures,
  session behavior, and the assembled Fluent UI flow through logout.
- Android debug APK build and Android 15 emulator smoke test: onboarding, sign-in,
  dashboard tabs, logout, and stored onboarding after a process restart.
- Editor task JSON and native runner metadata validation.

UI test captures are generated under `apps/shared/build/reports/screenshots`.
The CI workflow also defines an unsigned iOS simulator build and Windows desktop
compilation. Neither substitutes for a physical Apple device or Windows UI test.
