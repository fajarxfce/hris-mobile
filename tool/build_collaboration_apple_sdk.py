"""Build SDK8 Apple slices and exercise their Swift ABI in a disposable checkout."""
from __future__ import annotations

import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import plistlib
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

from build_collaboration_sdk import run, sha


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


def verify_framework(framework, base):
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
                ["xcrun", "nm", "-gU", "-arch", architecture, str(native)], text=True, timeout=120)
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
              "candidateOnly": True, "published": False, "passed": False}
    try:
        with (out / "build.log").open("w") as log:
            if args.bootstrap:
                run(["rustup", "toolchain", "install", base["rust"], "--profile", "minimal",
                     "--target", ",".join(manifest["targets"])], cwd=sdk, env=env, log=log, timeout=900)
            rust = subprocess.check_output(["rustc", "--version"], env=env, text=True, timeout=15)
            if not rust.startswith("rustc " + base["rust"] + " "):
                raise ValueError("Unexpected Rust toolchain")
            record["rustVersion"] = rust.strip()
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
            run(["cargo", "fetch", "--locked"], cwd=sdk, env=env, log=log, timeout=900)
            env["CARGO_NET_OFFLINE"] = "true"
            command = ["cargo", "run", "--locked", "-p", "xtask", "--", "swift", "build-framework",
                       "--profile", manifest["profile"], "--sequentially",
                       "--ios-deployment-target", manifest["iosDeploymentTarget"],
                       "--macos-deployment-target", manifest["macosDeploymentTarget"]]
            for target in manifest["targets"]:
                command += ["--target", target]
            run(command, cwd=sdk, env=env, log=log, timeout=9000)
            if {name: sha(sdk / name) for name in expected} != before:
                raise ValueError("SDK source changed during compilation")
            generated = sdk / "bindings/apple/generated"
            record["slices"] = verify_framework(generated / "MatrixSDKFFI.xcframework", base)
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
            run(["swift", "test", "--jobs", str(base["jobs"]), "--xunit-output", str(out / "swift-tests.xml")],
                cwd=proof, env=env, log=log, timeout=1800)
            record["nativeTests"] = verify_swift_tests(out / "swift-tests.xml")
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
