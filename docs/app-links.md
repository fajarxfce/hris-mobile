# HTTPS application links

Optional public registration files enable HTTPS App Links and Universal Links for
one exact DNS host and environment. Missing configuration keeps HTTPS admission
disabled; existing custom-scheme and browser-fragment routes remain available.
The path is `/app/<environment>/<route>`, for example
`https://links.example.com/app/dev/inbox`. The route after the environment uses
the [HRIS link contract](deep-links.md), including scoped Inbox identifiers.

## Configuration and generated artifacts

Use `config/app-links/dev.json.example` as the shape for ignored `dev.json`,
`staging.json` or `prod.json` in that directory. Replace its example host,
fingerprint and Apple application ID with the selected registration before use.
The files contain public registration metadata only. Unknown fields, non-UTF-8,
files over 16 KiB, unknown/duplicate environments, wildcard/IP/credential/port
hosts, wrong app IDs and malformed/duplicate fingerprints fail the build.

Android registration is required and accepts one to eight uppercase SHA-256
certificate fingerprints for that environment's exact application ID. Obtain them
from the actual signing certificate. A Play-installed build uses its Play App
Signing certificate, which can differ from the upload key. Do not substitute the
Firebase Android SHA-1 value. Optional `ios.applicationId` is the ten-character
Apple application-identifier prefix followed by the exact bundle ID. Supply the
value from the app's signing profile; omit the iOS entry until it is available.

```sh
./gradlew :core:common:generateAppLinks
./gradlew :apps:android:assembleDevDebug
```

`-PappLinksConfigDirectory=/absolute/path/to/public-config` selects a separate
registration directory consistently for generated Kotlin and Android manifests.
For Xcode, export `ORG_GRADLE_PROJECT_appLinksConfigDirectory` with that path so
both its preparation and framework Gradle invocations use the same input.

The generator owns `core/common/build/generated/app-links`:

- `kotlin/`: exact environment-to-host runtime admission.
- `ios/<environment>.entitlements`: existing push entitlement plus the configured
  associated domain when the iOS registration exists. Unconfigured builds have no
  associated-domain key; the expected bundle ID is checked by the Xcode pre-action.
- `sites/<host>/.well-known/assetlinks.json` and `apple-app-site-association`:
  combined public Android/Apple registrations for that host. Apple path components
  restrict each app to its environment prefix.

All registrations validate before replacing the task's owned output. Removing or
changing a registration removes obsolete generated host files and entitlements.
It does not delete files already deployed on a web server; update those explicitly.
The Android convention transforms each variant's merged manifest, adding one
separate auto-verified HTTPS intent filter to its launcher. Custom links and the
OAuth callback filter retain their separate scheme/host restrictions.

Every checked-in Xcode scheme prepares generated entitlements before building.
The app forwards `NSUserActivityTypeBrowsingWeb` through the same owned link
channel as ordinary custom URLs. No new navigation or credential owner is created.

## Admission and deployment

The shared parser bounds input to 4,096 printable ASCII characters and accepts only the exact
configured HTTPS authority and case-sensitive environment prefix. Explicit ports,
credentials, trailing-dot hosts, queries, fragments, encoding, traversal, empty
segments and control characters are rejected. The application then admits known
routes and canonical resource IDs. A URL never selects another account/company,
grants access, marks a message read or acknowledges it; current admission and
pending-link lifecycle rules apply unchanged.

Serve both association files at their exact `https://<host>/.well-known/...` paths
with HTTP 200, JSON content type, valid TLS and no authentication or redirect.
These paths must bypass the dashboard SPA fallback. The dashboard's `/app/...`
landing route supplies an explicit app-opening action when the OS stays in the
browser; it has separate Platform validation and does not load application data.
Keep the selected domain, certificates, signing profile and deployed files in the
environment's deployment record. Generation alone does not prove DNS/TLS, OS
domain verification or APNs delivery.

## Distribution builds

The manual Android/iOS workflows accept the environment variable
`APP_LINKS_CONFIG_BASE64` for the selected public registration. They carry that
input into the real build, verify the resulting signing identity, and retain the
matching domain files with checksums. See [distribution](distribution.md#optional-https-application-links).
A regular unconfigured build still disables HTTPS admission.

## Validation boundary

Local root/Android and Chromium checks passed: 1,859 desktop cases, six common and
20 application Chromium cases, no failures/errors/skips. The new application UI
case covers a cold HTTPS Inbox link through sign-in/company selection, rejection
of another company, background/resume and unchanged read/ack commands. Ten build
logic cases include malformed configuration, native filter separation, Apple
entitlement preservation and removal of stale generated registrations.

CI also checks all three configured Android manifests/domain files, includes
common simulator tests and builds the Xcode app using fictional public app-link
registrations. The fixture does not contact the example domain. Exact current
results belong in [validation](validation.md) and the [handoff](agent-handoff.md).
The user waived physical iPhone testing for this completion pass; no physical
iOS or live-domain result is inferred from these automated checks.

## Actual Android routing acceptance — 29 October 2026

Mobile `d36a1bc9` with Platform `d21a1346` / schema 101 passed the owned Android 35
HTTPS case in 3.2 minutes (3.6 with setup). A package-selected VIEW/BROWSABLE intent
cold-started the real activity, waited for login/company, and opened the scoped
Inbox message. Repeated opening needed one Back to the list. Wrong account/company
and query inputs stayed closed; the manifest rejected wrong host/environment.
Account navigation survived background/resume. Offline cold start retained the
owned message; after the server archived it, refresh and another offline cold start
kept it unavailable. Independent server checks found 28 requests, zero native
company writes/foreign-company requests and unchanged read/ack/version-zero state.
All owned fixture resources were removed; emulator data and the private dev stack
were preserved. [Measurements](measurements/app-links-2026-10-29.json) retain limits.
The fictional domain and explicit package mean this is native routing acceptance,
not public DNS/TLS or OS domain verification. Physical iPhone tests are waived.

## Unicode authority correction

A new regression reproduced Unicode dotless-i host aliasing under Kotlin's
case-insensitive matching: the first JVM run failed one of seven cases in 24s.
The parser now rejects non-ASCII input before matching; configured DNS names and
supported routes already use ASCII. This also rejects long-s scheme/host aliases
while retaining normal ASCII uppercase input. Seven JVM and seven Chromium cases
then passed in 31s. This pure parser correction is proved in Mobile before copying
the same two files to Starter; its final full CI is recorded separately from the
preceding `d36a1bc9` foundation run. No URL grants account/company access.

## Final accepted verification

Final Mobile source `72341ef6` passed all four jobs in full
[40895095518](https://github.com/fajarxfce/hris-mobile/actions/runs/40895095518).
Downloaded Apple reports confirm 104 cases in 28 suites, zero failures/errors/skips
and the complete Xcode app; compilation/tests/Xcode took 701s/77s/1040s.
Starter port `738db36` independently passed all three jobs in full
[40895798634](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40895798634),
with 84 Apple cases in 24 downloaded suites, no failures/errors/skips and Xcode;
compilation/tests/Xcode took 561s/84s/703s. Generated Android/Apple association
checks passed, and the Xcode/Apple artifact success markers were inspected.
No CI or owned local acceptance remains active from this increment.

The implementation queue is complete for this development pass. Physical iPhone
testing was explicitly waived. Public domain/DNS/TLS association, actual APNs,
physical GPS and production deployment remain separate configuration/target
observations; no result for those is inferred from builds or fixtures. Default
native builds do not enable an unconfigured HTTPS host. Reopen implementation for
an observed failure or a documented requirement; do not recreate accepted modules
from old next-step paragraphs.
