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

## Rust codegen-unit ownership during SQLite localization

Run43327713661 at65e649d1 passed the pinned LLVM22 reader probe, all five native
builds and each architecture's required exports. Swift linking then failed on
internal Rust symbols; zero Swift contracts executed. The retained macOS archive
contains FFI units00–09 and11–15 plus its allocator, while unit10 alone was moved
into the merged SQLite object. The upstream crate selector only recognizes the
older `crate-hash` filenames, so Rust1.96 `crate.crate.hash-cgu.N.rcgu.o` members
were treated as unrelated objects despite sharing hidden references.

The build-only patch now groups all `.rcgu.o` members by their Rust crate prefix,
retains legacy grouping and keeps C objects independent. SQLite symbol localization
stays enabled. The driver runs eight tests of the exact patched selector before
building the five targets. The old selector fails three of those tests locally;
the corrected selector passes all eight, and all20 Python build guards pass.
The standalone test source and executable are retained in the hosted artifact.
See [failure and correction evidence](measurements/collaboration-sdk8-apple-crate-units-2026-11-13.json).
The four native Swift contracts and both iOS links remain required; Linux selector
tests do not establish Apple runtime acceptance. Android SDK8 is unchanged.

## Completed framework and Swift cleanup investigation

Run43349484661 at6cc04d1f completed all five native architectures and required symbols.
The crate-unit correction also passed all eight probe tests and resolved the Swift link
failure. Four actual Swift cases then ran: three passed, while the passphrase continuity
case reported NSCocoaErrorDomain513/POSIX EPERM when removing its temporary UUID directory.
The XML report was not produced and iOS link checks did not run. This is not native acceptance.
The complete failed artifact14009837009 and logs are retained with verified GitHub digest,
ZIP CRC and every recorded package/proof file. Its SHA256 is
1fd951cc4f0c71adcbf4f1914d31f191ecb8c6dfcc1ac40a86eb0d8474d094ca.

The existing workflow now has an explicit `recheck_completed_candidate` option. It uses
only that exact run/job/commit/artifact and verifies its immutable native package, manifest,
all five architectures and required exports before rerunning Swift and both iOS links.
It never rebuilds or publishes Rust. The original raw-key contract remains byte-for-byte
unchanged. The SDK8 fixture retains all four test identities and assertions, adds bounded
file-metadata phase diagnostics, and makes cleanup failure explicit instead of ignoring it.
No file contents, passphrases, tokens or fingerprints are printed. Root cause remains under
investigation; diagnostics do not count as a fix or successful native execution.

The 20 existing builder guards and nine new recheck guards pass locally. The new verifier
also checked the actual retained archive and all49 recorded package/proof files. Local
checks execute no Swift. See [failure and local guard evidence](measurements/collaboration-sdk8-apple-proof-cleanup-2026-11-13.json).

## Canonical fixture root and parent-symlink refusal

Recheck43365790802 on2ff1b276 used the exact completed native archive. All native file,
architecture and export checks passed again; four Swift cases ran, three passed. This time
all three cleanups completed. The passphrase case instead exposed the underlying
ExistingCryptoIdentityError.Unavailable at its first direct existing-store read. The failed
artifact14010089330 (290891237B/0429a0bcfac4f279e052decb4d53ce89b2e048649200a9a556ce1997a2e1ea22)
and full phase/file metadata remain preserved. No iOS links or native acceptance is claimed.

The metadata reveals Foundation's temporary URL uses /var while enumeration resolves to
/private/var. The reader uses SQLite OPEN_NOFOLLOW. A local system-SQLite probe confirms
that this flag rejects a parent symlink with extended code1550, while the canonical path
opens; ordinary opening follows that same alias. This corroborates the path-policy cause,
but does not replace an actual Apple recheck.

The fixture now resolves only the OS-owned temporary parent before creating its private
UUID leaf. It does not relax SDK NOFOLLOW, resolve arbitrary store paths or change the
native archive. A fifth actual Swift contract creates a parent symlink to a real encrypted
store, requires exact Unavailable refusal and verifies its original keys still reopen.
All four original contracts and assertions remain. Cleanup diagnostics and explicit failure
remain enabled. The current gate requires all five cases and both iOS links; 20 builder and
nine recheck guards pass locally. See [canonical-root evidence](measurements/collaboration-sdk8-apple-canonical-store-2026-11-13.json).
