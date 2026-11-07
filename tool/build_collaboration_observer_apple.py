"""Build paired observer Swift/XCFramework candidates on an owned macOS checkout."""
from __future__ import annotations

import argparse
import datetime
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import time

from build_collaboration_sdk import run, sha


def build(args):
    patches = Path(__file__).resolve().parent / "collaboration-sdk-patches"
    config_path = Path(__file__).resolve().parent / "apple-observer-sdk/build.json"
    config = json.loads(config_path.read_text())
    base_path = patches / config["baseManifest"]
    if sha(base_path) != config["baseManifestSha256"]:
        raise ValueError("Observer base manifest changed")
    base = json.loads(base_path.read_text())
    sdk = args.sdk.resolve(strict=True)
    out = args.output.resolve()
    if sdk == out or sdk in out.parents or out in sdk.parents:
        raise ValueError("SDK and output must be separate")
    if subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=sdk, text=True).strip() != base["upstream"]:
        raise ValueError("Unexpected SDK commit")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=sdk, text=True).strip():
        raise ValueError("Use a fresh clean SDK checkout")
    env = os.environ.copy()
    if any(value for name, value in env.items() if name in {"RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS"}
           or (name.startswith("CARGO_TARGET_") and name.endswith("_RUSTFLAGS"))):
        raise ValueError("Ambient Rust flags are unsupported")
    env.pop("CARGO_NET_OFFLINE", None)
    env.update(RUSTUP_TOOLCHAIN=base["rust"], CARGO_BUILD_JOBS=str(base["jobs"]),
               CARGO_INCREMENTAL="0", CARGO_PROFILE_DEV_DEBUG="0",
               CARGO_PROFILE_SMALL_RELEASE_DEBUG="0")
    patch_hashes = {**base["patches"], config["patch"]: config["patchSha256"]}
    source_hashes = {**base["patchedSources"], **config["patchedSources"]}
    patch_files = {name: patches / name for name in base["patches"]}
    patch_files[config["patch"]] = config_path.parent / config["patch"]
    for name, expected in patch_hashes.items():
        if sha(patch_files[name]) != expected:
            raise ValueError("Patch digest changed: " + name)
    out.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    record = {"startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "manifestSha256": sha(config_path), "baseManifestSha256": sha(base_path),
              "upstream": base["upstream"], "patches": patch_hashes,
              "targets": config["targets"], "rust": base["rust"], "profile": config["profile"],
              "passed": False, "runtimeAccepted": False, "published": False}
    (out / "running.json").write_text(json.dumps(record, indent=2) + "\n")
    try:
        with (out / "build.log").open("w") as log:
            run(["rustup", "toolchain", "install", base["rust"], "--profile", "minimal",
                 "--target", ",".join(config["targets"])], cwd=sdk, env=env, log=log, timeout=900)
            version = subprocess.check_output(["rustc", "--version"], env=env, text=True)
            if not version.startswith("rustc " + base["rust"] + " "):
                raise ValueError("Unexpected Rust compiler")
            for name in patch_hashes:
                run(["git", "apply", "--check", str(patch_files[name])], cwd=sdk, env=env, log=log, timeout=15)
                run(["git", "apply", str(patch_files[name])], cwd=sdk, env=env, log=log, timeout=15)
            fingerprints = lambda: {name: sha(sdk / name) for name in source_hashes}
            if fingerprints() != source_hashes:
                raise ValueError("Patched sources differ")
            lock_hash = sha(sdk / "Cargo.lock")
            run(["cargo", "fetch", "--locked"], cwd=sdk, env=env, log=log, timeout=900)
            env["CARGO_NET_OFFLINE"] = "true"
            run(["cargo", "test", "--locked", "-p", "xtask", "object_owner_tests"],
                cwd=sdk, env=env, log=log, timeout=900)
            record["objectOwnerTestsPassed"] = True
            command = ["cargo", "run", "--locked", "-p", "xtask", "--", "swift", "build-framework",
                       "--profile", config["profile"], "--sequentially",
                       "--macos-deployment-target", config["macosDeploymentTarget"],
                       "--ios-deployment-target", config["iosDeploymentTarget"]]
            for target in config["targets"]:
                command += ["--target", target]
            run(command, cwd=sdk, env=env, log=log, timeout=6600)
            if fingerprints() != source_hashes or sha(sdk / "Cargo.lock") != lock_hash:
                raise ValueError("Build inputs changed")
            run(["swift", "build", "-c", "release", "--jobs", "2"], cwd=sdk, env=env, log=log, timeout=900)
        generated = sdk / "bindings/apple/generated"
        framework = generated / "MatrixSDKFFI.xcframework"
        info = plistlib.loads((framework / "Info.plist").read_bytes())
        actual = {(entry["SupportedPlatform"], entry.get("SupportedPlatformVariant", ""),
                   tuple(entry["SupportedArchitectures"])) for entry in info["AvailableLibraries"]}
        expected = {("macos", "", ("arm64",)), ("ios", "", ("arm64",)), ("ios", "simulator", ("arm64",))}
        if actual != expected:
            raise ValueError("Unexpected XCFramework slices")
        swift_files = list((generated / "swift").glob("*.swift"))
        combined = "\n".join(p.read_text() for p in swift_files)
        for api in ("func observeHttpRequests(", "func nextBatch(", "func stop(",
                    "struct HttpObservationBatch", "enum HttpObservationEvent"):
            if api not in combined:
                raise ValueError("Missing generated observer API: " + api)
        shutil.copytree(generated, out / "bindings/apple/generated")
        shutil.copy2(sdk / "Package.swift", out / "Package.swift")
        shutil.copy2(sdk / "LICENSE", out / "LICENSE")
        shutil.copy2(sdk / "Cargo.lock", out / "Cargo.lock")
        record.update(passed=True, sourcesUnchanged=True, sourceSha256=source_hashes,
                      swiftCompiled=True, cargoLockSha256=lock_hash,
                      generated={str(p.relative_to(generated)): sha(p) for p in generated.rglob("*") if p.is_file()},
                      slices=info["AvailableLibraries"])
    finally:
        record["elapsedSeconds"] = round(time.monotonic() - started, 3)
        (out / "result.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({k: v for k, v in record.items() if k not in {"sourceSha256", "generated"}}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    build(parser.parse_args())
