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
