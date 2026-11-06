"""Build a pinned Android Matrix SDK candidate; never publish or change app dependencies."""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time


TARGETS = {
    "arm64-v8a": ("aarch64-linux-android", "AArch64", "ELF64"),
    "armeabi-v7a": ("armv7-linux-androideabi", "ARM", "ELF32"),
    "x86": ("i686-linux-android", "Intel 80386", "ELF32"),
    "x86_64": ("x86_64-linux-android", "Advanced Micro Devices X86-64", "ELF64"),
}
BUILD_MANIFESTS = ("android-build.json", "android-existing-identity-build.json")


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(command, *, cwd, env, log, timeout=4800):
    with subprocess.Popen(command, cwd=cwd, env=env, stdout=log,
                          stderr=subprocess.STDOUT, start_new_session=True) as process:
        try:
            code = process.wait(timeout=timeout)
        except BaseException:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
            raise
        if code:
            raise subprocess.CalledProcessError(code, command)


def verify_native_library(native, abi, ndk, required_functions=()):
    """Validate the ELF ABI and Android's page alignment requirement for that ABI."""
    _, machine, elf_class = TARGETS[abi]
    if not 1 <= native.stat().st_size <= 512 * 1024 * 1024:
        raise ValueError("Unexpected native library size")
    readelf = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    header = subprocess.check_output([str(readelf), "-h", str(native)], text=True, timeout=30)
    segments = subprocess.check_output([str(readelf), "-lW", str(native)], text=True, timeout=30)
    symbols = subprocess.check_output([str(readelf), "--dyn-syms", "--wide", str(native)], text=True, timeout=30)
    symbol_names = {line.split()[-1] for line in symbols.splitlines() if line.split()}
    alignments = [int(line.split()[-1], 16) for line in segments.splitlines() if line.strip().startswith("LOAD")]
    # Android's 16 KB requirement applies to arm64-v8a/x86_64. The NDK still emits
    # 4 KB-aligned ARMv7/x86 libraries for their 32-bit device targets.
    minimum = 16384 if elf_class == "ELF64" else 4096
    if machine not in header or elf_class not in header or len(alignments) < 2 or not all(n >= minimum for n in alignments):
        raise ValueError("Unexpected ELF architecture or alignment")
    required = ["load_or_fetch_raw_event", "queued_message_events", "settle_confirmed_queue_event"]
    if not all("uniffi_matrix_sdk_ffi_fn_method_room_" + name in symbol_names for name in required):
        raise ValueError("Missing SDK entry point")
    if not all("uniffi_matrix_sdk_ffi_fn_func_" + name in symbol_names for name in required_functions):
        raise ValueError("Missing SDK function entry point")
    return {"nativeSha256": sha(native), "nativeBytes": native.stat().st_size,
            "elfClass": elf_class, "requiredElfAlignment": minimum,
            "loadAlignments": alignments, "requiredSymbolsPresent": True}


def build(args):
    patches = Path(__file__).resolve().parent / "collaboration-sdk-patches"
    manifest_name = getattr(args, "manifest", "android-build.json")
    if manifest_name not in BUILD_MANIFESTS:
        raise ValueError("Unknown SDK build manifest")
    manifest_path = patches / manifest_name
    manifest = json.loads(manifest_path.read_text())
    sdk = args.sdk.resolve(strict=True)
    out = args.output.resolve()
    if out == sdk or sdk in out.parents or out in sdk.parents:
        raise ValueError("SDK checkout and generated output must be separate")
    if subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=sdk, text=True).strip() != manifest["upstream"]:
        raise ValueError("Unexpected SDK commit")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=sdk, text=True).strip():
        raise ValueError("Use a fresh, clean SDK checkout")
    for name, expected in manifest["patches"].items():
        if sha(patches / name) != expected:
            raise ValueError("SDK patch digest changed")
    out.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    if any(value for name, value in env.items()
           if name in {"RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS"}
           or (name.startswith("CARGO_TARGET_") and name.endswith("_RUSTFLAGS"))):
        raise ValueError("Use manifest-pinned Rust flags; ambient Rust flags are unsupported")
    env.pop("CARGO_NET_OFFLINE", None)
    ndk = args.android_sdk.resolve(strict=True) / "ndk" / manifest["ndk"]
    tools_dir = args.tools_dir.resolve()
    env.update(RUSTUP_TOOLCHAIN=manifest["rust"], CARGO_BUILD_JOBS=str(manifest["jobs"]),
               CARGO_PROFILE_DEV_DEBUG="0", CARGO_PROFILE_RELEASE_DEBUG="0", CARGO_INCREMENTAL="0",
               ANDROID_NDK_HOME=str(ndk), CARGO_NDK_PLATFORM=str(manifest["androidApiLevel"]))
    env["PATH"] = str(tools_dir / "bin") + os.pathsep + env["PATH"]
    target = TARGETS[args.abi][0]
    started = time.monotonic()
    record = {"startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "abi": args.abi, "target": target, "manifest": manifest_name,
              "manifestSha256": sha(manifest_path),
              "upstream": manifest["upstream"], "patches": manifest["patches"],
              "rust": manifest["rust"], "ndk": manifest["ndk"], "cargoNdk": manifest["cargoNdk"],
              "androidApiLevel": manifest["androidApiLevel"], "jobs": manifest["jobs"],
              "candidateOnly": True, "published": False, "passed": False}
    (out / "running.json").write_text(json.dumps(record, indent=2) + "\n")
    try:
        with (out / "build.log").open("w") as log:
            if args.bootstrap:
                run(["rustup", "toolchain", "install", manifest["rust"], "--profile", "minimal",
                     "--target", target], cwd=sdk, env=env, log=log, timeout=600)
                run(["sdkmanager", "ndk;" + manifest["ndk"]], cwd=sdk, env=env, log=log, timeout=900)
                run(["cargo", "install", "cargo-ndk", "--version", manifest["cargoNdk"],
                     "--locked", "--root", str(tools_dir)], cwd=sdk, env=env, log=log, timeout=900)
            if "Pkg.Revision = " + manifest["ndk"] not in (ndk / "source.properties").read_text():
                raise ValueError("Unexpected NDK revision")
            rust = subprocess.check_output(["rustc", "--version"], env=env, text=True, timeout=15)
            cargo_ndk = subprocess.check_output(["cargo", "ndk", "--version"], env=env, text=True, timeout=15)
            if not rust.startswith("rustc " + manifest["rust"] + " ") or cargo_ndk.strip() != "cargo-ndk " + manifest["cargoNdk"]:
                raise ValueError("Unexpected Rust or cargo-ndk version")
            for name in manifest["patches"]:
                run(["git", "apply", "--check", str(patches / name)], cwd=sdk, env=env, log=log, timeout=15)
                run(["git", "apply", str(patches / name)], cwd=sdk, env=env, log=log, timeout=15)
            fingerprints = lambda: {name: sha(sdk / name) for name in manifest["patchedSources"]}
            if fingerprints() != manifest["patchedSources"]:
                raise ValueError("Patched SDK inputs differ from the tested source")
            # UniFFI invokes unfiltered cargo metadata, so fetch locked dependencies for all targets.
            run(["cargo", "fetch", "--locked"], cwd=sdk, env=env, log=log, timeout=900)
            env["CARGO_NET_OFFLINE"] = "true"
            run(["cargo", "run", "--locked", "-p", "xtask", "--", "kotlin", "build-android-library",
                 "--package", "full-sdk", "--release", "--only-target", target,
                 "--src-dir", str(out / "main")], cwd=sdk, env=env, log=log)
            if fingerprints() != manifest["patchedSources"]:
                raise ValueError("SDK inputs changed during the build")
        main = out / "main"
        kotlin = {str(p.relative_to(main)): sha(p) for p in main.glob("kotlin/**/*.kt")}
        if kotlin != manifest["generatedKotlin"]:
            raise ValueError("Generated Kotlin differs from the accepted binding pair")
        libraries = list(main.glob("jniLibs/**/*.so"))
        native = main / "jniLibs" / args.abi / "libmatrix_sdk_ffi.so"
        if libraries != [native]:
            raise ValueError("Unexpected native library output")
        inspected = verify_native_library(native, args.abi, ndk, manifest.get("requiredFunctions", []))
        shutil.copy2(sdk / "LICENSE", out / "LICENSE")
        shutil.copy2(sdk / "Cargo.lock", out / "Cargo.lock")
        record.update(passed=True, sourcesUnchanged=True, sourceSha256=manifest["patchedSources"],
                      generatedKotlin=kotlin, **inspected)
    finally:
        record["elapsedSeconds"] = round(time.monotonic() - started, 3)
        (out / "result.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({k: v for k, v in record.items() if k not in {"sourceSha256", "generatedKotlin"}}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tools-dir", type=Path, required=True)
    parser.add_argument("--android-sdk", type=Path, required=True)
    parser.add_argument("--abi", choices=TARGETS, required=True)
    parser.add_argument("--manifest", choices=BUILD_MANIFESTS, default="android-build.json")
    parser.add_argument("--bootstrap", action="store_true", help="Install pinned tooling in the owned build environment")
    build(parser.parse_args())
