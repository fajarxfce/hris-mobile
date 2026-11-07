#!/usr/bin/env python3
"""Actual paused native queue across Space adoption and offline process restart, emulator only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import zipfile

from original_queue_seed import OriginalQueueSeed

PACKAGE = "dev.fajar.hris.collaboration.proof"
NATIVE = {
    "x86_64": "55bde5be2080c3e42a2ece6053f94231d27165cc39698c89c50e36ecbdb2909a",
    "arm64-v8a": "5df0419b7f1a5dd5d934b0cce3846a439812581e35f86defe3c2098f29260d34",
}


def validate_apk(apk, abi, aapt2):
    metadata = subprocess.check_output([aapt2, "dump", "badging", str(apk)], text=True, timeout=20)
    if not metadata.startswith(f"package: name='{PACKAGE}' ") or "application-debuggable" not in metadata:
        raise ValueError("Only the isolated debug proof package is allowed")
    with zipfile.ZipFile(apk) as archive:
        digest = hashlib.sha256(archive.read(f"lib/{abi}/libmatrix_sdk_ffi.so")).hexdigest()
    if digest != NATIVE[abi]:
        raise ValueError("Expected the immutable SDK4 native library")
    return hashlib.sha256(apk.read_bytes()).hexdigest()


def run(args):
    if not re.fullmatch(r"emulator-\d{4,5}", args.serial):
        raise ValueError("An explicitly selected owned emulator is required")
    digest = validate_apk(args.apk, args.abi, args.aapt2)
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    result = {"complete": False, "serial": args.serial, "abi": args.abi,
              "apkSha256": digest, "sdk": "26.10.23-hris.4", "syntheticRoomSeed": True,
              "providerMessagesSent": 0, "liveHrisAcceptance": False}
    installed = False
    reverse = None
    seed = None

    def adb(*command, timeout=30, input_text=None):
        value = subprocess.run([args.adb, "-s", args.serial, *command], input=input_text,
                               text=True, capture_output=True, timeout=timeout, check=True).stdout
        if len(value.encode()) > 1_048_576:
            raise ValueError("Owned emulator response exceeded its bound")
        return value

    def absent():
        return "package:" + PACKAGE not in adb("shell", "pm", "list", "packages", PACKAGE).splitlines()

    def read(name):
        value = adb("exec-out", "run-as", PACKAGE, "cat", "files/protocol-proof/" + name)
        if len(value.encode()) > 16384:
            raise ValueError("Owned checkpoint exceeded its bound")
        return json.loads(value)

    def instrument(phase):
        # An interrupted phase must not inherit a prior phase's success checkpoint.
        adb("shell", "run-as", PACKAGE, "rm", "-f", "files/protocol-proof/instrumentation.json")
        log = adb("shell", "am", "instrument", "-w", "-e", "scenario", phase,
                  PACKAGE + "/.MatrixProofInstrumentation", timeout=210)
        (args.output / (phase + ".log")).write_text(log)
        state = read("instrumentation.json")
        if state.get("complete") is not True:
            result["instrumentationFailure"] = state
            raise RuntimeError("Owned instrumentation failed")

    try:
        assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1"
        assert adb("shell", "getprop", "sys.boot_completed").strip() == "1"
        assert adb("emu", "avd", "name").splitlines()[:1] == ["hris-mobile-dev"]
        assert absent(), "Never overwrite a preexisting fixture"
        installed = True  # A timed-out install may still have committed.
        adb("install", "--abi", args.abi, str(args.apk), timeout=180)
        assert "primaryCpuAbi=" + args.abi in adb("shell", "dumpsys", "package", PACKAGE)
        result["selectedAbiVerified"] = True
        seed = OriginalQueueSeed()
        seed.__enter__()
        mapping = "tcp:" + str(seed.port)
        assert all(mapping not in row.split() for row in adb("reverse", "--list").splitlines())
        reverse = mapping  # A timed-out owned reverse request may still have committed.
        adb("reverse", "--no-rebind", mapping, mapping)
        script = "mkdir -p files/protocol-proof && cat > files/protocol-proof/space-queue-fixture.json"
        adb("shell", "run-as", PACKAGE, "sh", "-c", shlex.quote(script),
            input_text=json.dumps({"baseUrl": seed.base_url}))
        instrument("space-queue-adoption-stage")
        stage = read("space-adoption-stage.json")
        result["stage"] = stage
        for key in ("complete", "originalSdkClosed", "committedBeforeCancellation",
                    "uninitializedAdoptionRetained", "originalFieldsAndQueueNamespacePreserved",
                    "originalPrivateKeysPreserved", "ownedResourcesReleased"):
            assert stage[key] is True, key
        assert type(stage["processId"]) is int and stage["processId"] > 0
        assert stage["newMessagesSent"] == 0
        seed.__exit__(None, None, None)
        result["seedRequests"] = seed.counts
        result["unexpectedSeedRoutes"] = seed.unexpected_routes
        seed = None
        assert result["seedRequests"].get("GET /_matrix/client/v3/sync", 0) == 1
        assert result["seedRequests"].get("unexpected", 0) == 0
        adb("reverse", "--remove", reverse)
        reverse = None
        result["seedClosedBeforeRecovery"] = True
        adb("shell", "am", "force-stop", PACKAGE)
        pid = subprocess.run([args.adb, "-s", args.serial, "shell", "pidof", PACKAGE],
                             text=True, capture_output=True, timeout=15)
        assert not pid.stdout.strip()
        result["processAbsentBetweenPhases"] = True
        instrument("space-queue-adoption-resume")
        resumed = read("space-adoption-result.json")
        result["continuity"] = resumed
        for key in ("complete", "durableAfterProcessDeath", "actualExistingOnlySdkRestore",
                    "unpublishedUntilAcceptance", "hrCompanySelectionAndDenialIndependent",
                    "legacyRuntimeLookupExcluded", "originalFieldsAndQueueNamespacePreserved",
                    "originalPrivateKeysPreserved", "sameBootstrapReplayWithoutWrite",
                    "actualPendingQueueRetained", "originalQueueReadOffline",
                    "ownedResourcesReleased", "ownedFilesRemoved"):
            assert resumed[key] is True, key
        assert resumed["newMessagesSent"] == 0 and resumed["liveGroupActivation"] is False
        assert type(resumed["processId"]) is int and resumed["processId"] != stage["processId"]
        assert hashlib.sha256(args.apk.read_bytes()).hexdigest() == digest
        result["complete"] = True
    except Exception as failure:
        result["failureCategory"] = type(failure).__name__
        if installed:
            try:
                result["instrumentationFailure"] = read("instrumentation.json")
            except Exception as capture:
                result["captureFailureCategory"] = type(capture).__name__
    finally:
        for operation in ([lambda: seed.__exit__(None, None, None)] if seed else []) + (
                [lambda: adb("reverse", "--remove", reverse)] if reverse else []):
            try:
                operation()
            except Exception as failure:
                result["complete"] = False
                result.setdefault("cleanupFailureCategories", []).append(type(failure).__name__)
        if installed:
            if result["complete"]:
                try:
                    adb("uninstall", PACKAGE, timeout=45)
                    assert absent()
                    result["fixtureAbsent"] = True
                except Exception as failure:
                    result["complete"] = False
                    result["cleanupFailureCategory"] = type(failure).__name__
            if not result["complete"]:
                try:
                    result["failedFixtureRetainedForInspection"] = not absent()
                except Exception:
                    result["fixtureAbsenceUnknown"] = True
        result["seconds"] = round(time.monotonic() - started, 3)
        (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--abi", choices=list(NATIVE), required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--aapt2", required=True)
    options = parser.parse_args()
    raise SystemExit(0 if run(options)["complete"] else 1)
