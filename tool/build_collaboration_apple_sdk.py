"""Build SDK8 Apple slices and exercise their Swift ABI in a disposable checkout."""
from __future__ import annotations

import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

from build_collaboration_sdk import run, sha


# The first three release targets took 38–43 minutes each on the Intel runner.
FRAMEWORK_BUILD_TIMEOUT_SECONDS = 5 * 60 * 60
SLICE_ARCHITECTURES = {
    ("ios", ""): {"arm64"},
    ("ios", "simulator"): {"arm64", "x86_64"},
    ("macos", ""): {"arm64", "x86_64"},
}
SWIFT_TESTS = {
    "testNativeCryptoIdentitySurvivesReopenAndRejectsUnrelatedStoreKey",
    "testPassphraseIdentityReaderPreservesKeysAndRejectsAnUnrelatedPassphrase",
    "testMissingStoreReadDoesNotCreateFiles",
    "testSDK8ListenerCancellationAndObserverCloseRetainNoListener",
    "testExistingIdentityRejectsParentSymlinkWithoutChangingRetainedKeys",
}
PRIVATE_CRATE_TESTS = {
    "rust_196_codegen_units_and_allocator_share_the_crate",
    "legacy_codegen_units_keep_their_existing_group",
    "different_rust_crates_never_share_a_group",
    "ordinary_c_and_archive_members_keep_their_names",
    "malformed_legacy_hashes_are_not_crate_identifiers",
    "non_rust_prefixes_and_extensions_remain_independent",
    "a_single_sqlite_caller_selects_every_sibling_codegen_unit",
    "bundled_sqlite_object_does_not_absorb_unrelated_c_objects",
}


def verify_swift_tests(report):
    root = ET.parse(report).getroot()
    cases = list(root.iter("testcase"))
    names = {case.get("name", "").removesuffix("()") for case in cases}
    if len(cases) != len(SWIFT_TESTS) or names != SWIFT_TESTS:
        raise ValueError("Missing or unexpected Swift native contract")
    if any(list(root.iter(tag)) for tag in ("failure", "error", "skipped")):
        raise ValueError("Swift native contract failed or was skipped")
    return {"tests": len(cases), "failures": 0, "errors": 0, "skipped": 0, "reportSha256": sha(report)}


def run_swift_contracts(proof, output, jobs, env, log, record):
    """Own temporary stores until the Swift process and its native threads have exited."""
    fixtures = output / "swift-fixtures"
    fixtures.mkdir(mode=0o700, exist_ok=False)
    native_env = env | {"HRIS_SDK8_FIXTURE_ROOT": str(fixtures.resolve(strict=True))}
    try:
        run(["swift", "test", "--jobs", str(jobs), "--xunit-output", str(output / "swift-tests.xml")],
            cwd=proof, env=native_env, log=log, timeout=1800)
        return verify_swift_tests(output / "swift-tests.xml")
    finally:
        record["fixtureCleanup"] = {"afterSwiftProcessExit": True, "removed": False}
        shutil.rmtree(fixtures)
        if fixtures.exists() or fixtures.is_symlink():
            raise ValueError("Swift fixture root survived process-owned cleanup")
        record["fixtureCleanup"]["removed"] = True
        log.write("SDK8 fixture cleanup completed after Swift process exit\n")
        log.flush()


def load_manifest(patches):
    manifest = json.loads((patches / "apple-incoming-hints-build.json").read_text())
    base_path = patches / manifest["baseManifest"]
    if sha(base_path) != manifest["baseManifestSha256"]:
        raise ValueError("Android SDK8 source manifest changed")
    base = json.loads(base_path.read_text())
    digests = base["patches"] | {manifest["builderPatch"]: manifest["builderPatchSha256"]}
    for name, expected in digests.items():
        if sha(patches / name) != expected:
            raise ValueError("SDK patch digest changed")
    return manifest, base


def contained_file(root, relative):
    path = (root / relative).resolve(strict=True)
    if root.resolve() not in path.parents or not path.is_file():
        raise ValueError("Framework file escapes its package")
    return path


def resolve_symbol_reader(env, expected_rust):
    """Use the pinned compiler's reader: Apple's nm cannot parse newer Rust LLVM bitcode."""
    verbose = subprocess.check_output(["rustc", "-vV"], env=env, text=True, timeout=15)
    fields = dict(line.split(": ", 1) for line in verbose.splitlines() if ": " in line)
    host = fields.get("host")
    llvm = fields.get("LLVM version")
    if (fields.get("release") != expected_rust or
            host not in {"aarch64-apple-darwin", "x86_64-apple-darwin"} or
            not llvm or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", llvm)):
        raise ValueError("Unexpected Rust compiler provenance for symbol inspection")
    root = Path(subprocess.check_output(
        ["rustc", "--print", "sysroot"], env=env, text=True, timeout=15).strip()).resolve(strict=True)
    reader = root / "lib" / "rustlib" / host / "bin" / "llvm-nm"
    if not reader.is_file():
        raise ValueError("Install llvm-tools-preview for the pinned Rust toolchain")
    reader = reader.resolve(strict=True)
    if root not in reader.parents:
        raise ValueError("Symbol reader escapes the pinned Rust sysroot")
    version = subprocess.check_output([str(reader), "--version"], env=env, text=True, timeout=15)
    reported = re.search(r"\bLLVM version ([0-9]+\.[0-9]+\.[0-9]+)(?:[-\w.]*)", version)
    if reported is None or reported.group(1) != llvm:
        raise ValueError("Symbol reader LLVM version differs from the pinned compiler")
    return reader, {"rust": expected_rust, "host": host, "llvmVersion": llvm,
                    "version": version.strip(), "sha256": sha(reader)}


def probe_symbol_reader(symbol_reader, provenance, output, env, log):
    """Read a real host staticlib, including Rust's bundled LLVM bitcode, before the long build."""
    probe = output / "symbol-reader-probe"
    probe.mkdir()
    source = probe / "probe.rs"
    source.write_text('#[unsafe(no_mangle)]\npub extern "C" fn sdk8_symbol_reader_probe() -> u32 { 8 }\n')
    archive = probe / "libprobe.a"
    run(["rustc", "--edition=2024", "--crate-type=staticlib", "--crate-name=sdk8_reader_probe",
         "--target", provenance["host"], "-O", str(source), "-o", str(archive)],
        cwd=probe, env=env, log=log, timeout=120)
    architecture = "arm64" if provenance["host"] == "aarch64-apple-darwin" else "x86_64"
    symbols = subprocess.check_output(
        [str(symbol_reader), "--defined-only", "--extern-only", "--format=just-symbols",
         "--arch=" + architecture, str(archive)], env=env, text=True, timeout=120)
    names = {line.strip().removeprefix("_") for line in symbols.splitlines()}
    if "sdk8_symbol_reader_probe" not in names:
        raise ValueError("Pinned symbol reader could not inspect a real Rust archive")
    return {"passed": True, "architecture": architecture, "sourceSha256": sha(source),
            "archiveSha256": sha(archive)}


def probe_private_symbol_crates(sdk, output, env, log):
    """Execute the exact patched crate selector before any expensive target build."""
    builder = (sdk / "xtask/src/swift.rs").read_text()
    start = builder.index("fn crate_of(member: &str) -> &str {")
    end = builder.index("/// The path of the built library for a specific target and profile.", start)
    source = builder[start:end]
    methods = re.findall(r"#\[test\]\s+fn\s+(\w+)\s*\(", source)
    if len(methods) != len(PRIVATE_CRATE_TESTS) or set(methods) != PRIVATE_CRATE_TESTS:
        raise ValueError("Missing or unexpected private crate selection contract")
    probe = output / "private-crate-probe"
    probe.mkdir()
    path = probe / "probe.rs"
    path.write_text(source)
    binary = probe / "crate-tests"
    run(["rustc", "--edition=2024", "--test", str(path), "-o", str(binary)],
        cwd=probe, env=env, log=log, timeout=120)
    run([str(binary), "--test-threads=1"], cwd=probe, env=env, log=log, timeout=30)
    return {"passed": True, "tests": len(methods), "methods": sorted(methods),
            "sourceSha256": sha(path), "binarySha256": sha(binary)}


def verify_framework(framework, base, symbol_reader):
    info = plistlib.loads((framework / "Info.plist").read_bytes())
    required = {"uniffi_matrix_sdk_ffi_fn_method_room_" + name for name in (
        "load_or_fetch_raw_event", "queued_message_events", "settle_confirmed_queue_event")}
    required.update("uniffi_matrix_sdk_ffi_fn_func_" + name for name in base["requiredFunctions"])
    required.update("uniffi_matrix_sdk_ffi_fn_method_" + name for name in base["requiredMethods"])
    seen = set()
    slices = []
    for item in info["AvailableLibraries"]:
        key = (item["SupportedPlatform"], item.get("SupportedPlatformVariant", ""))
        if key not in SLICE_ARCHITECTURES or key in seen:
            raise ValueError("Unexpected or duplicate Apple slice")
        seen.add(key)
        architectures = SLICE_ARCHITECTURES[key]
        if set(item["SupportedArchitectures"]) != architectures:
            raise ValueError("Incomplete declared Apple architectures")
        native = contained_file(framework, item["LibraryIdentifier"] + "/" + item["LibraryPath"])
        actual = subprocess.check_output(["xcrun", "lipo", "-archs", str(native)], text=True, timeout=30)
        if set(actual.split()) != architectures:
            raise ValueError("Native architectures differ from the slice declaration")
        for architecture in sorted(architectures):
            symbols = subprocess.check_output(
                [str(symbol_reader), "--defined-only", "--extern-only", "--format=just-symbols",
                 "--arch=" + architecture, str(native)], text=True, timeout=120)
            names = {line.split()[-1].removeprefix("_") for line in symbols.splitlines() if line.split()}
            if not required.issubset(names):
                raise ValueError("Native SDK8 entry point missing from an Apple architecture")
        slices.append({"platform": key[0], "variant": key[1], "architectures": sorted(architectures),
                       "nativeSha256": sha(native), "nativeBytes": native.stat().st_size,
                       "requiredSymbolsPresent": True})
    if seen != set(SLICE_ARCHITECTURES):
        raise ValueError("Missing Apple platform slice")
    return slices


def build(args):
    tool = Path(__file__).resolve().parent
    patches = tool / "collaboration-sdk-patches"
    manifest, base = load_manifest(patches)
    sdk = args.sdk.resolve(strict=True)
    out = args.output.resolve()
    if out == sdk or sdk in out.parents or out in sdk.parents:
        raise ValueError("Use separate SDK checkout and output directories")
    if platform.system() != "Darwin":
        raise ValueError("Apple SDK verification requires a macOS host")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=sdk, text=True).strip()
    dirty = subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=sdk, text=True)
    if revision != base["upstream"] or dirty.strip():
        raise ValueError("Use a fresh, clean checkout of the pinned SDK commit")
    env = os.environ.copy()
    if any(value for name, value in env.items() if name in {"RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS"}
           or name.startswith("CARGO_TARGET_") and name.endswith("_RUSTFLAGS")):
        raise ValueError("Ambient Rust flags cannot override the pinned build")
    env.pop("CARGO_NET_OFFLINE", None)
    env.update(RUSTUP_TOOLCHAIN=base["rust"], CARGO_BUILD_JOBS=str(base["jobs"]), CARGO_INCREMENTAL="0",
               CARGO_TARGET_DIR=str(sdk / "target"),
               CARGO_PROFILE_DEV_DEBUG="0", CARGO_PROFILE_RELEASE_DEBUG="0")
    out.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    record = {"startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "version": manifest["version"], "upstream": revision, "rust": base["rust"],
              "manifestSha256": sha(patches / "apple-incoming-hints-build.json"),
              "baseManifestSha256": manifest["baseManifestSha256"], "targets": manifest["targets"],
              "candidateOnly": True, "published": False, "passed": False,
              "frameworkBuildTimeoutSeconds": FRAMEWORK_BUILD_TIMEOUT_SECONDS}
    try:
        with (out / "build.log").open("w") as log:
            if args.bootstrap:
                run(["rustup", "toolchain", "install", base["rust"], "--profile", "minimal",
                     "--component", "llvm-tools-preview", "--target", ",".join(manifest["targets"])],
                    cwd=sdk, env=env, log=log, timeout=900)
            rust = subprocess.check_output(["rustc", "--version"], env=env, text=True, timeout=15)
            if not rust.startswith("rustc " + base["rust"] + " "):
                raise ValueError("Unexpected Rust toolchain")
            record["rustVersion"] = rust.strip()
            symbol_reader, record["symbolReader"] = resolve_symbol_reader(env, base["rust"])
            log.write("Pinned symbol reader: " + json.dumps(record["symbolReader"]) + "\n")
            log.flush()
            record["symbolReaderProbe"] = probe_symbol_reader(
                symbol_reader, record["symbolReader"], out, env, log)
            for name in base["patches"]:
                run(["git", "apply", "--check", str(patches / name)], cwd=sdk, env=env, log=log, timeout=15)
                run(["git", "apply", str(patches / name)], cwd=sdk, env=env, log=log, timeout=15)
            if sha(sdk / manifest["builderSource"]) != manifest["originalBuilderSha256"]:
                raise ValueError("Unexpected upstream Swift builder")
            run(["git", "apply", "--check", str(patches / manifest["builderPatch"])], cwd=sdk, env=env, log=log, timeout=15)
            run(["git", "apply", str(patches / manifest["builderPatch"])], cwd=sdk, env=env, log=log, timeout=15)
            expected = base["patchedSources"] | {
                manifest["builderSource"]: manifest["patchedBuilderSha256"],
                manifest["swiftPackageSource"]: manifest["swiftPackageSourceSha256"],
            }
            before = {name: sha(sdk / name) for name in expected}
            if before != expected:
                raise ValueError("Patched Apple inputs differ from SDK8 and its pinned builder")
            record["privateCrateProbe"] = probe_private_symbol_crates(sdk, out, env, log)
            run(["cargo", "fetch", "--locked"], cwd=sdk, env=env, log=log, timeout=900)
            env["CARGO_NET_OFFLINE"] = "true"
            command = ["cargo", "run", "--locked", "-p", "xtask", "--", "swift", "build-framework",
                       "--profile", manifest["profile"], "--sequentially",
                       "--ios-deployment-target", manifest["iosDeploymentTarget"],
                       "--macos-deployment-target", manifest["macosDeploymentTarget"]]
            for target in manifest["targets"]:
                command += ["--target", target]
            run(command, cwd=sdk, env=env, log=log, timeout=FRAMEWORK_BUILD_TIMEOUT_SECONDS)
            if {name: sha(sdk / name) for name in expected} != before:
                raise ValueError("SDK source changed during compilation")
            generated = sdk / "bindings/apple/generated"
            record["slices"] = verify_framework(generated / "MatrixSDKFFI.xcframework", base, symbol_reader)
            package = out / "sdk"
            destination = package / "bindings/apple/generated"
            destination.mkdir(parents=True)
            for name in ("MatrixSDKFFI.xcframework", "swift"):
                shutil.copytree(generated / name, destination / name)
            shutil.copy2(sdk / manifest["swiftPackageSource"], package / "Package.swift")
            shutil.copy2(sdk / "LICENSE", package / "LICENSE")
            shutil.copy2(sdk / "Cargo.lock", out / "Cargo.lock")
            record["packageFiles"] = {str(p.relative_to(package)): sha(p)
                                      for p in sorted(package.rglob("*")) if p.is_file()}
            proof = out / "proof"
            proof.mkdir()
            # Retain the original raw-key contract; add SDK8-only tests without changing the upstream proof.
            for name in ("Sources", "Tests"):
                shutil.copytree(tool / "collaboration-apple-proof" / name, proof / name)
            extension = tool / "collaboration-apple-sdk-proof"
            shutil.copy2(extension / "Package.swift", proof / "Package.swift")
            shutil.copy2(extension / "SDK8NativeTests.swift", proof / "Tests/HrisCollaborationAppleProofTests/SDK8NativeTests.swift")
            record["proofFiles"] = {str(p.relative_to(proof)): sha(p)
                                    for p in sorted(proof.rglob("*")) if p.is_file()}
            record["nativeTests"] = run_swift_contracts(proof, out, base["jobs"], env, log, record)
            for name, destination in (("device", "generic/platform=iOS"), ("simulator", "generic/platform=iOS Simulator")):
                run(["xcodebuild", "-scheme", "HrisCollaborationAppleProof", "-destination", destination,
                     "-derivedDataPath", str(out / "derived" / name), "-jobs", str(base["jobs"]),
                     "CODE_SIGNING_ALLOWED=NO", "build"], cwd=proof, env=env, log=log, timeout=1800)
            if {name: sha(sdk / name) for name in expected} != before:
                raise ValueError("SDK source changed during Swift verification")
            record.update(passed=True, sourceSha256=before, sourcesUnchanged=True,
                          macosNativeTests=True, iosDeviceCompiled=True, iosSimulatorCompiled=True)
    finally:
        record["elapsedSeconds"] = round(time.monotonic() - started, 3)
        (out / "result.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({k: v for k, v in record.items() if k not in {"sourceSha256", "packageFiles", "proofFiles"}}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--bootstrap", action="store_true")
    build(parser.parse_args())
