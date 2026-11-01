# Private Android phone updates

Latest prepared artifact (1 November): release 6 from Mobile `2d6753ee`, documented in
[release evidence](measurements/android-release-history-2026-11-01.json). Its local directory
is `.work/release-phone-2026-11-01-build6/`, including current preflight/install-only helpers.
Release 5 below is historical and was never installed. Both are pending phone connectivity;
prefer the latest validated build and inspect the actual installed version before updating.


The authorized tester phone uses `dev.fajar.hris.mobile.dev` through
`http://10.77.77.1:8088/api/v1/` on WireGuard. Build `devRelease`, keep the existing
certificate (SHA-256 `aca5ce6a26b978dee1d63c63103b6799848ab760d47ef4cf5ee68620fc8721f3`)
and increase the version code when necessary. The local development keystore is
`/home/fajar/.android/debug.keystore`; supply the existing `ANDROID_KEYSTORE_FILE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` configuration
privately. This does not create production distribution credentials.

Run required root checks on the source before delivery. With JDK 21 and the configured
Android SDK, assemble and lint using these Gradle arguments:

```text
:apps:android:assembleDevRelease :apps:android:lintDevRelease
-PrequireSigning=true -PappEnvironment=dev
-PappVersion=1.0.0 -PappVersionCode=5
-PapiBaseUrl.dev=http://10.77.77.1:8088/api/v1/
--no-daemon --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m
```

For this arm64 phone, an invocation-local Gradle init script limits the APK's native
libraries. Pass it with `--init-script`; keep the shared build defaults unchanged:

```groovy
gradle.beforeProject { project ->
    project.pluginManager.withPlugin('com.android.application') {
        if (project.path == ':apps:android') {
            def filters = project.extensions.getByName('android').defaultConfig.ndk.abiFilters
            filters.clear()
            filters.add('arm64-v8a')
        }
    }
}
```

Verify `apksigner`, package/version, disabled debuggable/testOnly flags, expected API and
Firebase app, allowed native ABI and `zipalign -c -P 16 4`. Comparing universal/arm64 DEX
and resources can establish that only packaging changed; Git/Crashlytics VCS metadata
can legitimately change between builds. Record the actual artifact hash and build inputs.

Before each installation, notify the user and explicitly select `10.77.77.3:5555` (or
the user's updated endpoint). Confirm ADB authorization and actual device ABI. Read the
installed package's version, app ID and first installation time; pull its APK and verify
the current signer matches. Never downgrade or uninstall to bypass a mismatch.

Install with `adb -s SERIAL install -r --no-incremental APK`, without opening the app.
Afterward, recheck version/non-debuggable status, unchanged app ID/firstInstallTime,
and the SHA-256 of the newly pulled installed APK. Record success only after those
checks. Preserve data and use the dedicated emulator for routine UI interaction.

[Build 5 evidence](measurements/android-release-progress-2026-11-01.json) currently records
a prepared APK and an unavailable phone; it does not claim installation. Local preflight
and install-only helpers are in `.work/release-phone-2026-11-01/`. Device availability
must not stop the remaining implementation work.
