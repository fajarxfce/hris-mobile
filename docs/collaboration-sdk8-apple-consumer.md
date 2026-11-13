# Verified local Swift dependency

`tool/prepare_collaboration_apple_sdk.py` prepares the accepted SDK8 distribution for
a local SwiftPM/Xcode dependency. The consumer pin fixes the archive size and SHA256,
the distribution manifest SHA256, all45 file hashes and1,101,750,302 expanded bytes.
The tool validates these before creating a new output directory, then verifies each
written file. Existing output is never overwritten; a failed preparation is not accepted.
Logs, proof fixtures and extra Swift sources cannot enter the runtime package.

```sh
python3 tool/prepare_collaboration_apple_sdk.py \
  --archive /path/to/matrix-rust-sdk-26.10.23-hris.8-apple.zip \
  --output /path/to/new/MatrixRustSDK
```

The real accepted archive was prepared successfully under Mobile
`.work/sdk8-apple-consumer-2026-11-13/sdk`; session86083 is reaped. All45 runtime files
match. The archive remains283,386,841 bytes with SHA256
`966c198f042a092423ca7e5c55ca3dc69bf7d448ef8caf991c1b4e44d7e5b687`.
No native build or native test was repeated. Eight consumer tests pass, covering exact
extraction, changed/extra/missing/duplicate content, path redirection, links/special files,
expanded-size/license checks and refusal to replace existing output or accept another ZIP.

The tool accepts a local verified artifact; it does not publish, download or link the
application. App integration must point at this prepared package and use the synchronous
SDK8 existing-identity reader through an owned iOS datasource. Keep its directory lease
until the native call completes, including cancellation. The actual Swift/Kotlin bridge
and host/runtime behavior still require their own checks.

Evidence: `docs/measurements/collaboration-sdk8-apple-consumer-2026-11-13.json`.
