# Android Compose resource packaging

The first release93 candidate compiled successfully but crashed while opening the real Android
application. `AppLoading` requested an English string and Compose raised `MissingResourceException`
for `strings_en.commonMain.cvr`. Both the product APK and native test-host APK contained zero
`composeResources` entries. Native Matrix checks had passed because their host did not open the
product's localized bootstrap UI. This candidate was never installed on the user's phone.

Compose1.10.3 registers generated resources as Android variant assets. The shared Compose
convention applied `starter.android.library`, which leaves Android resources disabled. Only the
application's shared module separately opted in; `core/localization` compiled its generated
accessors without packaging their corresponding files. The convention now applies the existing
`starter.android.resources` plugin, enabling the normal Gradle asset pipeline for Compose modules.
No strings fallback, manual file copying, SDK change or duplicate Android configuration is added.

Focused asset merging succeeds. The generated English and Indonesian files match the merged
Android bytes, and full validation independently checks those exact bytes inside the proof APK.
The root checks preserve all17,931 prior cases (567 freshly executed and
17364 exact unchanged results), with no new mirrored unit tests.
The first full run reached its40-minute orchestration deadline while compiling the remaining
browser tests. Its17,193 completed passing cases were archived without declaring that run
accepted. The successful continuation uses the identical source and checks exact archived
report hashes before carrying results forward; all required targets must finish successfully.
Android compilation/lint, shared Web and Apple source/test-source checks also pass. This does not
yet establish current-source native Matrix execution or real product startup; those remain the
next release gates. The prior crash and failed first startup remain recorded separately.

Continue release93 from `.work/release-phone-2026-11-14-build93-resources`. Its validator checks
the exact compiled locale assets in both release ABIs. Run current-source native acceptance,
actual product cold startup and process reopen, inspect both screenshots, then announce
install-only delivery to10.77.77.3:5555 and verify the installed bytes. Never install the first
resource-less candidate. The original loop fixes are unchanged; see
[delivery wake ownership](collaboration-delivery-wake-ownership.md) and
[runtime renewal](collaboration-runtime-renewal.md).

Port this generic convention change to Compose Fluent Starter after Mobile acceptance, using
independent Android asset and actual application startup checks. No HRIS policy belongs in that
port. Then continue canonical recipient transfer and the communications plan. Phone92, live
GroupOFF and undeployed V117–140 remain; all54 PRD acceptance groups remain OPEN.

[Exact validation and failed candidate](measurements/android-compose-resources-2026-11-14.json).

## Independent Starter port — 14 November 2026

Starter [e9b4ff500baf62f2642871c6705d64b35bd1d2e1](https://github.com/fajarxfce/compose-fluent-starter/commit/e9b4ff500baf62f2642871c6705d64b35bd1d2e1) independently accepts the one-line Compose Android
resource convention port. It preserves all1,052 prior cases (568 fresh /
484 exact unchanged), verifies both languages in dev/staging/prod APKs,
and passes actual production cold start/process reopen with two inspected screenshots.
Full/session66562 and startup/session36477 reaped0; emulator stopped.
Source 47a1b986dc011fcb3537dbbfd9a975ee111bff963a61e80a5a337ce2d222161b; reports 16db517eb2e8b47e9af6dd87a832fcb1cfe4224b9b4de041cc77e483b99aab13.
Pinned Inspector and its existing gradlew.bat difference remain unchanged. No HRIS policy port,
physical phone interaction or new Apple runtime claim. Do not rerun accepted checks.
