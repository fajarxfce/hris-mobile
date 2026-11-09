# Development network inspection

This integration candidate uses the separate [KMP Network Inspector](https://github.com/fajarxfce/kmp-network-inspector)
repository. The repository is now public with explicit owner approval; no library package is published.
Do not copy its source into this repository or change its visibility to make CI pass.

The consumer workflows now check out inspector commit
`742a2d1c2285c720cef80ea3913195f3ece388cc` and select it through
`networkInspectorDirectory`. Android, web, desktop, Apple and distribution jobs use
the same immutable source. The native protocol fixture receives the same setting
even though its application checkout is nested. No credentials are persisted in
the dependency checkout. SDK `26.10.23-hris.6` resolves from its published release
with strict checksum verification; no private SDK mirror is configured in CI.
The library's [verified CI run](https://github.com/fajarxfce/kmp-network-inspector/actions/runs/42591111996)
passed 226 portable/Android host cases and 69 Apple simulator cases. Application CI
for this integration is pending; native Android fixture compilation is separate
from device execution, and the Apple Matrix native binding remains unverified.
Both application verification workflows explicitly run the Android collaboration
host tests, including native runtime ownership regressions.

**Historical build 60 device acceptance:**
Ktor/API and Rust Matrix HTTP capture, filters, detail/back, Clear and cold offline chat
continuity passed. That candidate selected SDK5; current builds use published SDK6.
[Device/package evidence](measurements/network-inspector-host-device-2026-11-07.json).

The development app opens the viewer from a silent Android notification after network
activity, or through Account → Settings → Network inspector. The notification opens the
shared viewer after normal navigation gates, including a cold process start. Android
attaches the adapter on Activity resume so background session restoration cannot remove
the retained shortcut before a tap. The host respects notification permission/channel
settings. [Actual six OS cases, taps and exact installation](measurements/network-inspector-notification-device-2026-11-08.json).
Ktor clients for HRIS, OIDC and Firebase and the Android Matrix Rust client share one
bounded capture. The inspector records actual HTTP attempts, including separate Matrix
response/body timing; it does not turn HTTP200 into message acknowledgement. No response
bodies, query values or credentials are retained. Current library text is English, while
the application entry, title and Clear action follow the app language.

Application composition creates capture only by default for the Dev environment.
`ApplicationNetworkInspection` passively observes the published session/user identity
without acquiring credentials or activating storage, preserves
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

For a fresh local build, clone the public library, check out the same commit as CI,
and pass its absolute location. The SDK comes from the public release:

```sh
./gradlew :apps:shared:desktopTest --tests '*NetworkInspection*' \
  -PnetworkInspectorDirectory=/absolute/path/to/kmp-network-inspector \
  --dependency-verification strict
```

Catalog versions and exact checksums are retained. The library remains a source
composite until its packages are published. Validate the application CI before
merging this integration into the primary branch. The library targets Android, iOS,
JVM and Wasm. Portable
core/Ktor/viewer tests and Apple klib compilation are separate from platform acceptance:
Android's actual JNI observer passes six loopback scenarios. Four Swift/native observer
cases pass on macOS against the same verified SDK candidate. Apple library binding,
Apple simulator tests, a capture-overhead comparison and reusable Starter integration
were separate acceptance targets. The library simulator tests now pass as recorded
above. The generic Starter integration is accepted and
pushed asb4d6aa4, with independent root/Android/browser/production web/iOS source checks;
the native Apple binding and overhead targets remain pending. Android host runtime
is accepted separately below.
The former private-repository billing block was removed when the owner authorized
the public library repository; the current accepted CI evidence is linked above.

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
platform Back, clear/ID reuse and large text on a small screen. Android device viewer acceptance comes from the separate exact-APK probe. Nine actual
screenshots and three sampled video storyboards were inspected. One send had a239ms
Matrix response/240ms transfer; pending and sent UI polls occurred at2.876s/5.071s. Those
are different boundaries, not an overhead benchmark or a solved phone-latency claim.
The phone received the same APK with --install-only, preserving app identity and data.

Firebase's optional inspection parameter precedes its final HTTP engine factory,
preserving existing trailing-lambda injection. Two actual Ktor/Koin cases verify lazy
engine ownership, the original response, and capture after reset. The complete
workspace gate also passes; [exact compatibility evidence](measurements/firebase-inspector-engine-factory-2026-11-07.json).

Build58 adds correlated local send timing without changing delivery. See the
[device measurement](measurements/collaboration-send-timing-device-2026-11-07.json).
Inspector [PR1](https://github.com/fajarxfce/kmp-network-inspector/pull/1) now runs
public portable/Apple checks; its first portable setup exposed an obsolete SDK tools
package and has a workflow-only correction. Apple binding and overhead remain open.
