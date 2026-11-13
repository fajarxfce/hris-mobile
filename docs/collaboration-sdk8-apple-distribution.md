# SDK8 Swift distribution

The accepted native Apple artifact now has a reproducible, runtime-only Swift package.
`tool/package_collaboration_apple_sdk.py` verifies the exact accepted GitHub archive,
native provenance, all five actual XCTest cases, fixture cleanup and both iOS links
before creating an output directory. It verifies every recorded package/proof file,
then copies only the 45 runtime files with fixed timestamps and modes. Package.swift,
the license, Swift bindings and every accepted XCFramework slice stay unchanged.

The resulting `matrix-rust-sdk-26.10.23-hris.8-apple.zip` is 283,386,841 bytes with SHA256
`966c198f042a092423ca7e5c55ca3dc69bf7d448ef8caf991c1b4e44d7e5b687`.
An adjacent build record and SHA256SUMS retain the accepted source/artifact and exact
runtime inventory. No Rust build or native test was repeated during packaging.

Ten packaging tests cover altered archive/content/provenance, missing or failed native
gates, changed successful XML bytes, license inclusion, deterministic runtime-only ZIPs
and refusal to overwrite existing output. The existing 26 builder and nine recheck
guards also pass. These 45 Python tests validate packaging; the five actual native
tests remain the accepted run43370071243 at ad96a35a8d4ea2f931476a906a4183fb6e1f3c5e.

Run from this isolated SDK branch, with the accepted archive already downloaded:

```sh
python3 tool/package_collaboration_apple_sdk.py \
  --native-archive /path/to/accepted/native.zip \
  --output /path/to/new/distribution
```

The local output is retained under Mobile `.work/sdk8-apple-distribution-2026-11-13/accepted`.
This increment does not publish a release or activate an application runtime. Immutable
dependency consumption and the actual Swift bridge follow. Android SDK8 artifacts and
the original user stores remain unchanged.

Evidence: `docs/measurements/collaboration-sdk8-apple-distribution-2026-11-13.json`.
