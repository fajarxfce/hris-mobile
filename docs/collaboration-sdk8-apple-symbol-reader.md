# Inspect Rust archives with the matching LLVM reader

Native run 43296438399 built all five targets and the three-slice XCFramework, then
failed while Apple's LLVM17 `nm` read LLVM22.1.2 bitcode bundled by Rust1.96.
The failure occurred after 10,471 seconds, before any of the four Swift tests ran.
The failed artifact and full job log are preserved; extending the build deadline again
would not resolve this error.

The builder now installs `llvm-tools-preview` for the pinned Rust toolchain and selects
`llvm-nm` from that compiler's host sysroot. It checks the exact Rust release, Darwin
host and LLVM version and rejects a reader outside the sysroot. There is no fallback
that ignores tool errors. Before building the SDK, it compiles and inspects a tiny real
Rust static archive. The probe source, archive and hashes are retained in CI artifacts.

The SDK still requires exact exported entry points in all five architectures, the four
actual Swift crypto/ownership tests, and device/simulator builds. Local Python guards
pass 17 tests, including missing components, foreign toolchains, symlink escape, wrong
LLVM versions and failed or incomplete archive inspection. These mocked local guards
do not claim a native pass. [Evidence](measurements/collaboration-sdk8-apple-symbol-reader-2026-11-12.json)
records the failed run and exact candidate tool hashes.

Keep this isolated candidate separate from application changes. Dispatch one native
run from the committed head, prepare its exact-head artifact audit, and retain failures.
No SDK publication or application binding is authorized by local guard success alone.
