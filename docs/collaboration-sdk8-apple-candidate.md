# SDK8 Apple native candidate

The Apple runtime needs the same retained-identity reader, HTTP observation, sync positions
and owned room-key callbacks already used by Android SDK8. The earlier Swift proof still pins
upstream26.10.27; its success does not validate these patched entry points.

`tool/build_collaboration_apple_sdk.py` requires a fresh macOS checkout at the exact upstream
revision in the immutable Android SDK8 manifest. It verifies and applies the same nine patches,
then one separately hashed build-tool patch. That patch replaces the Swift builder's explicit
`rustup run stable` commands with `cargo build --locked`, honoring the manifest's pinned Rust
toolchain. No application Rust code is changed by the Apple extension. The Android artifacts
and existing SDK checkouts remain untouched.

The build runs sequentially with two Cargo jobs and explicit iOS16/macOS12 deployment targets.
It produces device ARM64, simulator ARM64/Intel and macOS ARM64/Intel slices. Each architecture
must contain the exact recovery and SDK8 symbols, with native architectures matching the
XCFramework declarations. The generated Swift package, native libraries, license, Cargo lock
and input/output hashes are retained together.

The first Intel candidate, run43267867675 at9b7c4d3f, hit the build driver's
150-minute deadline during the fourth target. The first three optimized targets
completed in42m46s,37m37s and41m46s; no Rust compiler error or Swift test failure
preceded the timeout. The five-target build now has a bounded300-minute budget,
recorded in its result, inside a360-minute job. Two Cargo jobs, sequential targets,
all five architectures and the existing30-minute Swift/link deadlines remain fixed.
The failed artifact and its digest are retained in the
[build-budget evidence](measurements/collaboration-sdk8-apple-build-budget-2026-11-12.json).
This adjusts infrastructure scheduling; native acceptance still requires all four
Swift contracts, architecture/symbol checks and both iOS links to pass.

The driver stages the existing Swift source and its original raw-key continuity test unchanged,
plus three native SDK8 tests. These exercise passphrase-based retained identity and wrong-key
refusal, absent-store reads without file creation, stored sync position, room-key registration
reconciliation/cancellation and listener release, and HTTP observation closure. The passphrase
reader is tested against a passphrase store; raw-key and passphrase encodings are not conflated.
The four exact test identities must pass before device and simulator application slices compile.
Test clients use only new temporary stores and fictional offline sessions. No employee account
or existing device keys enter this job.

The GitHub workflow `collaboration-apple-sdk.yml` builds a candidate and retains diagnostics.
It uses the standard `macos-15-intel` runner after both attempts of run43261761056 failed
to acquire an ARM runner before any step started. The five target architectures, Rust source
and four Swift test identities are unchanged. Native Swift tests execute on the macOS host;
iOS device and simulator slices are compiled and linked, not executed by this job.
It does not publish a release or install a production Apple runtime. Local Python checks validate
the artifact guards; real Swift execution and Mach-O linking require its macOS job. A successful
candidate still leaves application binding, background execution, signed-in E2EE chat, calls and
device acceptance to subsequent work. All54 PRD acceptance groups remain open.

```sh
python3 -m unittest discover -s tool -p test_build_collaboration_apple_sdk.py
python3 tool/build_collaboration_apple_sdk.py \
  --sdk /path/to/fresh-matrix-sdk --output /path/to/new-artifact --bootstrap
```
