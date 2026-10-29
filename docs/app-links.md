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

The shared parser bounds input to 4,096 characters and accepts only the exact
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
