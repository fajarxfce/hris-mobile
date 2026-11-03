#!/usr/bin/env python3
"""Exercise only the separately packaged fictional notification fixture on an explicit emulator."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time


PACKAGE = "dev.fajar.hris.collaboration.proof"
PERMISSION = "android.permission.POST_NOTIFICATIONS"


def aapt_path(args):
    if args.aapt2:
        return args.aapt2
    adb = shutil.which(args.adb)
    if not adb:
        raise ValueError("Android platform tools are required")
    sdk = Path(adb).resolve().parent.parent
    return str(sdk / "build-tools/36.0.0/aapt2")


def run(args):
    if not re.fullmatch(r"emulator-\d{4,5}", args.serial):
        raise ValueError("Only an explicitly selected emulator serial is allowed")
    if not args.apk.is_file():
        raise ValueError("Build the dedicated proof APK first")
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False)

    def adb(*command, timeout=30):
        result = subprocess.run(
            [args.adb, "-s", args.serial, *command],
            text=True, capture_output=True, timeout=timeout, check=True,
        )
        return result.stdout

    result = {"serial": args.serial, "scenarios": []}
    start = time.monotonic()
    installation_attempted = False
    try:
        # This caller must exclusively own the dedicated AVD for the whole run.
        assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1"
        assert adb("shell", "getprop", "sys.boot_completed").strip() == "1"
        assert adb("emu", "avd", "name").splitlines()[:1] == ["hris-mobile-dev"]
        sdk = int(adb("shell", "getprop", "ro.build.version.sdk").strip())
        assert sdk >= 33, "Permission-denial scenario requires Android 13 or newer"
        metadata = subprocess.run(
            [aapt_path(args), "dump", "badging", str(args.apk.resolve())],
            text=True, capture_output=True, timeout=15, check=True,
        ).stdout
        assert metadata.startswith(f"package: name='{PACKAGE}' ") and "application-debuggable" in metadata
        assert not adb("shell", "pm", "list", "packages", PACKAGE).strip(), "Proof package already exists; refusing unowned data"
        digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        result.update(sdk=sdk, apkSha256=digest)
        # No replacement/downgrade. Cover an installed package whose success response is lost.
        installation_attempted = True
        assert "Success" in adb("install", "--no-incremental", str(args.apk.resolve()), timeout=180)
        for scenario, granted, expected in [
            ("native-notification-denied", False, 1),
            ("native-notification", True, 15),
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
            if installation_attempted:
                failures = []
                try:
                    adb("shell", "am", "force-stop", PACKAGE)
                except Exception as failure:
                    failures.append(type(failure).__name__)
                try:
                    # The following SDK/WorkManager driver requires an absent package before it
                    # can assume ownership. Clearing app data alone does not release the package.
                    assert "Success" in adb("uninstall", PACKAGE)
                    assert not adb("shell", "pm", "list", "packages", PACKAGE).strip()
                    result["fixtureUninstalled"] = True
                    result["fixtureDataCleared"] = True
                except Exception as failure:
                    failures.append(type(failure).__name__)
                if failures:
                    result["cleanupFailures"] = failures
                    raise RuntimeError("Owned notification fixture cleanup did not finish")
        except Exception as failure:
            result["complete"] = False
            result["cleanupFailureCategory"] = type(failure).__name__
            raise
        finally:
            result["seconds"] = round(time.monotonic()-start, 3)
            (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--aapt2", help="Defaults to build-tools/36.0.0 beside the selected adb SDK")
    print(json.dumps(run(parser.parse_args())))


if __name__ == "__main__":
    main()
