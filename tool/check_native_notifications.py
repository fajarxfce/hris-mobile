#!/usr/bin/env python3
"""Exercise only the separately packaged fictional notification fixture on an explicit emulator."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time


PACKAGE = "dev.fajar.hris.collaboration.proof"
PERMISSION = "android.permission.POST_NOTIFICATIONS"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d{4,5}", args.serial):
        parser.error("Only an explicitly selected emulator serial is allowed")
    if not args.apk.is_file():
        parser.error("Build the dedicated proof APK first")
    args.output.mkdir(parents=True, exist_ok=False)
    os.umask(0o077)

    def adb(*command, timeout=30):
        result = subprocess.run(
            [args.adb, "-s", args.serial, *command],
            text=True, capture_output=True, timeout=timeout, check=True,
        )
        return result.stdout

    # Refuse physical devices regardless of serial naming, before package mutation.
    assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1"
    assert adb("shell", "getprop", "sys.boot_completed").strip() == "1"
    sdk = int(adb("shell", "getprop", "ro.build.version.sdk").strip())
    assert sdk >= 33, "Permission-denial scenario requires Android 13 or newer"
    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    result = {"serial": args.serial, "sdk": sdk, "apkSha256": digest, "scenarios": []}
    start = time.monotonic()
    installed = False
    try:
        install = adb("install", "-r", "-d", "--no-incremental", str(args.apk.resolve()), timeout=180)
        assert "Success" in install
        installed = True
        assert "Success" in adb("shell", "pm", "clear", PACKAGE)
        for scenario, granted, expected in [
            ("native-notification-denied", False, 1),
            ("native-notification", True, 10),
        ]:
            began = time.monotonic()
            adb("shell", "pm", "grant" if granted else "revoke", PACKAGE, PERMISSION)
            output = adb("shell", "am", "instrument", "-w", "-e", "scenario", scenario,
                         f"{PACKAGE}/.MatrixProofInstrumentation", timeout=150)
            (args.output / f"{scenario}.log").write_text(output)
            status = json.loads(adb("shell", "run-as", PACKAGE, "cat", "files/protocol-proof/instrumentation.json"))
            assert status.get("complete") is True, status
            proof = json.loads(adb("shell", "run-as", PACKAGE, "cat", "files/protocol-proof/native-notification.json"))
            assert proof.get("complete") is True and proof.get("cleanedUp") is True
            assert len(proof["cases"]) == expected and len(set(proof["cases"])) == expected
            result["scenarios"].append({"name": scenario, "seconds": round(time.monotonic()-began, 3), **proof})
        assert hashlib.sha256(args.apk.read_bytes()).hexdigest() == digest
        result["complete"] = True
    finally:
        try:
            if installed:
                adb("shell", "am", "force-stop", PACKAGE)
                result["fixtureDataCleared"] = "Success" in adb("shell", "pm", "clear", PACKAGE)
                assert result["fixtureDataCleared"], "Fictional fixture cleanup did not finish"
        except Exception as failure:
            result["complete"] = False
            result["cleanupFailureCategory"] = type(failure).__name__
            raise
        finally:
            result["seconds"] = round(time.monotonic()-start, 3)
            (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
