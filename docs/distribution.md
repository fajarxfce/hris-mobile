# Internal distribution

Two **manual** GitHub Actions workflows distribute builds from `main`:

- **Distribute Android:** signed release APK → Firebase App Distribution. The
  signed AAB is retained as an artifact for a separate Play Console submission.
- **Distribute iOS:** signed archive/IPA → App Store Connect for TestFlight processing.

These workflows do not create a GitHub Release, run on tags/pushes, or publish a
store listing. Signing credentials and Apple membership are required before use.
Configuration-free CI continues to build unsigned Android release artifacts and an
iOS simulator app.

## GitHub setup

Create environments named `internal-dev`, `internal-staging` and `internal-prod`.
Configure required reviewers and restrict deployment branches to `main`. Add the
values below to the matching environment, keeping registrations and signing
material separate where required. Workflows pin their actions to reviewed commit
SHAs and have read-only repository permissions.

Choose the environment, version such as `1.0.0`, an increasing build number such
as `42`, and short release notes. HRIS always uses its remote backend; the inherited
Starter demo option is not supported. Set `API_BASE_URL` to the selected environment's
HTTPS API base, including `/api/v1/`. Distribution passes an explicit per-environment
Gradle property on Android and through Xcode's framework build, with a common
remote fallback for unspecified environments, so checked-in dev
properties cannot silently override the selected endpoint. Ordinary local Xcode
builds retain their project configuration. Android runs the repository
checks first with its normal test configuration, then compiles/signs the selected
release with explicit version, backend and endpoint inputs. This keeps Starter
demo tests independent of a remote release server.

Optional `OIDC_CONFIG_BASE64` is the base64 encoding of that environment's public
OIDC registration JSON. The build validates it using the [SSO rules](sso.md).
Private credentials never belong in that JSON.

## Optional HTTPS application links

Set the environment **variable** `APP_LINKS_CONFIG_BASE64` to the base64 encoding
of the selected public [app-link registration](app-links.md). It is not a signing
credential. Preparation bounds the decoded UTF-8 input to 16 KiB, writes only the
selected `config/app-links/<environment>.json`, refuses existing files, and tracks
its cleanup with the other owned runner files. Gradle performs the full registration
and environment validation before building. Omission keeps HTTPS links disabled.

For iOS the registration must name the signing profile's exact application
identifier and the profile must permit its associated domain. Before packaging,
Android checks the actual APK signer against the registered SHA-256 fingerprints;
iOS checks the signed app's application identifier and exact associated domain.
The selected generated Android/Apple domain statements must match the registration.
A mismatch fails before upload. Keep the Play App Signing certificate separately
in the fingerprint list when it differs from the distributed APK's signer.

Successful artifacts include `app-links/<host>/.well-known/assetlinks.json` and
`apple-app-site-association`, covered by `SHA256SUMS`. `build.json.appLinks` records
the host, environment path and signing verification; it is null when unconfigured.
Deploy the two files to their exact HTTPS paths using the domain owner's release
procedure. Retained files do not publish DNS/TLS or establish OS verification.

## Android

Environment variables:

| Variable | Value |
|---|---|
| `FIREBASE_ANDROID_APP_ID` | Firebase app ID, such as `1:123456:android:abcdef` |
| `FIREBASE_TESTER_GROUPS` | Existing App Distribution group aliases, comma-separated without spaces |
| `API_BASE_URL` | Required HTTPS HRIS API base, including `/api/v1/` |

Environment secrets:

| Secret | Content |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded private release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing alias |
| `ANDROID_KEY_PASSWORD` | Private-key password |
| `FIREBASE_ANDROID_CONFIG_BASE64` | Base64-encoded flavor `google-services.json` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Service account JSON with Firebase App Distribution Admin access to that project |

Enable App Distribution for the registered app, create the tester groups, and
invite testers. Use the same signing key for subsequent builds of an installed
application. Keep a secure backup of the private keystore.

The workflow validates the Firebase application ID/package against the selected
flavor. It runs checks, signs APK/AAB, verifies their signatures and produces a
CycloneDX SBOM for the packaged runtime. Google's authentication action obtains a
short-lived access token after the build. The upload uses the official REST API,
streams the APK, waits for successful processing, and distributes that release to
the configured groups. It does not follow redirects with the access token.

To sign locally, supply these environment variables and keep the keystore outside
tracked files:

```sh
export ANDROID_KEYSTORE_FILE=/private/path/release.jks
export ANDROID_KEYSTORE_PASSWORD=...
export ANDROID_KEY_ALIAS=...
export ANDROID_KEY_PASSWORD=...
./gradlew :apps:android:assembleStagingRelease -PrequireSigning=true \
  -PapiBaseUrl.staging=https://hris.example.com/api/v1/
```

The `starter.android.signing` convention rejects partial credentials and makes
`requireSigning=true` fail when credentials are absent. Omit all signing variables
for an ordinary unsigned CI build.

## iOS

Register the flavor bundle ID in the Apple Developer portal and create its app
record in App Store Connect. Create an Apple Distribution certificate with its
private key and an **App Store** provisioning profile for that exact bundle ID.
Enable capabilities such as push notifications when required by the profile.
Create an App Store Connect API key with access to upload builds for that app.

Environment variables: `APPLE_TEAM_ID`, `APPLE_API_KEY_ID`, `APPLE_API_ISSUER_ID`,
and the required HTTPS `API_BASE_URL`.

Environment secrets:

| Secret | Content |
|---|---|
| `APPLE_CERTIFICATE_BASE64` | Base64-encoded `.p12` containing certificate and private key |
| `APPLE_CERTIFICATE_PASSWORD` | Password used to export the `.p12` |
| `APPLE_PROFILE_BASE64` | Base64-encoded App Store `.mobileprovision` |
| `APPLE_API_KEY_BASE64` | Base64-encoded App Store Connect `.p8` |
| `FIREBASE_IOS_CONFIG_BASE64` | Optional base64-encoded flavor `GoogleService-Info.plist` |

The job validates the profile's team, app identifier, expiry and distribution
type. It uses a temporary keychain and API key directory, archives the matching
scheme, exports an IPA, verifies its signature and uploads it. Cleanup removes
its installed profile, generated configuration, keys and keychain, and restores
the previous keychain search list. It does not overwrite existing credential files.

After Apple finishes processing, complete any export-compliance prompts and
assign the build to TestFlight groups in App Store Connect. External testing can
require Beta App Review. Upload success is not proof of Apple processing, tester
availability, or approval. The workflow's notes are build metadata; manage the
TestFlight “What to Test” field in App Store Connect.

## Build records and verification

Each job retains signed artifacts, available mapping files/dSYMs, a `build.json`
with commit/version/environment, and `SHA256SUMS` for 14 days. Download the artifact
and verify it from its extracted directory with `sha256sum -c SHA256SUMS` (or
`shasum -a 256 -c SHA256SUMS` on macOS). Checksums detect changes; they are not a
third-party signing attestation.

Generate the Android dependency inventory locally:

```sh
./gradlew :apps:android:cyclonedxDirectBom \
  -PsbomConfiguration=stagingReleaseRuntimeClasspath
```

The report is `apps/android/build/reports/sbom/android.cdx.json`. It includes
resolved runtime components and available license metadata. Review missing
license data and scan the SBOM with your organization's vulnerability tooling;
an inventory alone does not establish license compliance or absence of advisories.
It is an Android inventory, not an inventory of the Swift packages inside iOS.
Gradle dependency verification remains enforced; see [quality gates](quality.md).

Ordinary CI also resolves the production Android SBOM, so metadata needed only
by license/POM discovery is verified before a manual distribution.

`distributionCheck` exercises input validation, credential-file ownership, profile
validation, shell argument forwarding, app-link profile/signer matching and the
Firebase upload/poll/distribute sequence with fake responses.
Live signing/distribution needs the configured secrets and a manual workflow run.
The helper scripts require Python 3.11 or newer. No release upload is performed by
local tests or normal CI.
