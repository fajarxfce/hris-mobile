# Development network inspection

This integration candidate uses the separate [KMP Network Inspector](https://github.com/fajarxfce/kmp-network-inspector)
repository. The repository is currently private and no library package is published.
Do not copy its source into this repository or change its visibility to make CI pass.

**Host integration is locally accepted; actual application capture and overhead are still pending.**
The installed release56 remains on SDK4 without the inspector. This branch selects the
unpublished, paired four-ABI SDK5 candidate; immutable SDK4 release assets are unchanged.

The development app opens the viewer through Account → Settings → Network inspector.
Ktor clients for HRIS, OIDC and Firebase and the Android Matrix Rust client share one
bounded capture. The inspector records actual HTTP attempts, including separate Matrix
response/body timing; it does not turn HTTP200 into message acknowledgement. No response
bodies, query values or credentials are retained. Current library text is English, while
the application entry, title and Clear action follow the app language.

Application composition creates capture only by default for the Dev environment.
`ApplicationNetworkInspection` observes the stable session/user identity, preserves
capture through token refresh, and closes/erases the old recorder on identity replacement.
Source completion/failure and container disposal close diagnostics. It owns one supervised
job, independently of delivery. The raw Android hook runs in the exact SDK client's lease;
cancel/join releases its native subscription before client disposal. Capture never changes
authentication, retries, payloads, TLS or durable send state.

Each Ktor attempt keeps its original recorder. Matrix reset cancels/joins the old observer
before opening a fresh subscription. Clear and account change cannot feed old buffered
starts or completions into the replacement recorder. Navigation owns collection and
selection; the recorder keys the composition so a reused request ID cannot select an old
detail. The library viewer owns no DI, navigation, ViewModel or transport. App placement
uses a bounded column around its lazy list, with safe bottom insets and Fluent colors.

Build the candidate with the separately authorized checkout and verified SDK artifact
mirror, for example:

```sh
./gradlew :apps:shared:desktopTest --tests '*NetworkInspection*' \
  -PnetworkInspectorDirectory=/absolute/path/to/kmp-network-inspector \
  -PmatrixSdkRepository=/absolute/path/to/verified/sdk-repository \
  --dependency-verification strict
```

Catalog versions and exact checksums are retained. Public CI cannot resolve the private,
unpublished dependency by default; do not merge this candidate into the primary branch
until distribution is ready. The library targets Android, iOS, JVM and Wasm. Portable
core/Ktor/viewer tests and Apple klib compilation are separate from platform acceptance:
Android's actual JNI observer passes six loopback scenarios. Four Swift/native observer
cases pass on macOS against the same verified SDK candidate. Apple library binding,
Apple simulator tests, application runtime/overhead and reusable Starter integration
are still pending. Private-repository CI currently cannot run because of GitHub billing.

The first host check stopped before compilation on an inherited plugin checksum missing
from Mobile. The follow-up imports32 exact entries already verified in the separate
library; verification remains strict. The final full local gate passes5,645 fresh cases with zero failures/errors/skips on4,424
unchanged source inputs. Root check, Android host tests/release compilation/lint,
Wasm/iOS source compilation, formatting, architecture and detekt pass; lint has zero
issues. Initial fixture, opt-in and DI-length failures are retained in the
[exact measurement](measurements/network-inspector-host-local-2026-11-07.json).

Environment/build/server configuration and inspector lifetime registration have explicit
application DI modules. Feature ViewModels and repositories do not own diagnostics.
Six lifetime tests cover token rotation, account replacement/logout, failed/completed
identity observation, pending disposal and late cleanup. Two desktop UI cases cover
platform Back, clear/ID reuse and large text on a small screen. No phone/device viewer
or overhead result is inferred from these local checks.
