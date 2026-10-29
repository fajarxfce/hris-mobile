# iOS provider and device acceptance

This is the remaining device procedure, not an executed acceptance record. The
provider implementation at `43cd8ec6` passed full CI `40858176601`, including
97 simulator cases and the unsigned Xcode application. Android Google/Home and
actual Android FCM have their own [accepted evidence](validation.md).

## Available configuration and missing inputs

All three HRIS apps are registered in Firebase project `cmp-fajar`. Matching public
plists are already stored locally, ignored and mode 600 at
`apps/ios/Configuration/{dev,staging,prod}/GoogleService-Info.plist`. The dev bundle
is `dev.fajar.hris.mobile.dev`; staging is `dev.fajar.hris.mobile.staging`, and
production is `dev.fajar.hris.mobile`.
Do not request these configurations or the supplied FCM server credential again.

| Input | Current boundary |
| --- | --- |
| Firebase Authentication | Email/password and Google enabled; exact dev iOS OAuth request reaches Google's sign-in page, and direct iOS-configured password HTTPS returns proof for the owned fictional fixture. Neither probe exercised iOS UI. |
| Apple build | Open `apps/ios/HrisMobile.xcodeproj`, select `HrisMobile-dev` and the intended device using the repository's JDK/Xcode toolchain. CI's unsigned simulator app is not an installable phone IPA. |
| Device signing | An available iPhone, Mac and matching Apple team/development profile are needed for local device delivery. Register the exact bundle and required capabilities. Signed TestFlight delivery has its separate [distribution procedure](distribution.md). |
| HRIS connectivity | Supply the approved API origin for the selected flavor and verify it from the device under iOS transport policy. The current private HTTP WireGuard endpoint is accepted on Android only; iOS reachability has not been proved. |
| APNs | Configure an APNs authentication key/certificate for this Apple app in Firebase's Cloud Messaging settings, with the matching Apple team and signed app entitlement. An App Store Connect upload key and the server FCM service account do not configure APNs. Keep keys outside Git/chat. |
| HTTPS app links | Await the controlled domain. Then implement its environment-specific association files, Android/iOS native declarations and explicit HTTPS route admission; today's custom-scheme routes do not already support arbitrary HTTPS links. |

## Sign-in procedure

Use the already approved HRIS account/Google binding. Its private reference is in
Platform's [current runtime record](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/current-development-runtime.md).
One active Firebase UID per account/issuer is intentional. The retired fictional
password UID must not replace the user's real Google binding. A separate password
device scenario needs its own owned account/binding.

1. Record the exact source, bundle, app version/build, iOS version, configured
   environment and signing result. Verify API discovery before provider interaction.
2. Have the device owner launch Google sign-in, dismiss the system browser, and
   verify the existing application session remains unchanged. Start a fresh attempt;
   a result from the cancelled attempt must not reopen or replace it.
3. Complete Google sign-in, select the intended company and confirm Home. Record
   the user's observation separately from instrumented UI evidence. Independently
   review safe native-session metadata through authorized HRIS administration.
4. Check background/resume and application restart preserve the admitted account
   and company. Verify a later explicit sign-out clears application access.
   Google system-browser cookies are not HRIS session credentials.

Do not copy tokens, account identifiers, emails or raw provider callbacks into the
tracked result. Preserve the Android session while testing another device; do not
replace its binding or use global revocation as fixture cleanup.

## Push and link procedure

Use one explicitly owned fictional publication and an approved test recipient.
Coordinate notification delivery with the owner before sending. The existing
Platform Android FCM driver checks a specific emulator; it is not an iPhone driver.

1. Sign in to the intended test account, enable Inbox notifications explicitly and allow the iOS permission request.
   Confirm successful HRIS device registration without exposing its provider token.
2. Publish separate foreground/background hints and independently check worker
   acceptance plus actual device presentation. A successful FCM response alone
   does not show that APNs or iOS delivered an alert. Background/suspended scheduling
   remains controlled by iOS; record observed delay and a bounded result.
3. Open the displayed notification. Require the intended account/company/Inbox
   item, generic OS copy and unchanged read/acknowledgement state. Record these
   independently on the server; opening a message must not submit either command.
4. Disable notifications and sign out of this test device. Verify its registration
   is retired and the provider token removed, without revoking another device.
5. Exercise custom-scheme cold/warm entry and account/company rejection using the
   [existing link contract](deep-links.md). Test verified HTTPS separately only
   after its domain, native configuration and route admission are implemented.

## Retained result

Track source/build and device versions, configuration references, bounded UTC
observations, safe server counters, each failed/cancelled attempt and owned cleanup.
State which actions the user observed and which were independently instrumented.
Keep unperformed sign-in, APNs, physical GPS and HTTPS cases explicitly open.
If a reusable defect appears, fix and prove it in Mobile before porting to Starter.
