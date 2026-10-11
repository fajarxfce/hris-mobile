#!/usr/bin/env python3
"""Build and install on one explicitly selected Android device."""

from pathlib import Path
import argparse
import os
import subprocess


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", nargs="?")
    parser.add_argument("--flavor", choices=["dev", "staging", "prod"], default="dev")
    parser.add_argument("--skip-build", action="store_true", help="Install the existing debug APK")
    parser.add_argument("--install-only", action="store_true", help="Update without opening the app")
    args = parser.parse_args(arguments)

    root = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Android/Sdk")
    adb = str(sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
    devices = subprocess.check_output([adb, "devices"], text=True)
    serials = [line.split()[0] for line in devices.splitlines()[1:] if line.endswith("\tdevice")]
    serial = args.serial or (serials[0] if len(serials) == 1 else None)
    if serial is None:
        parser.error("Choose a device: python3 tool/run_android.py <device-serial>")
    if serial not in serials:
        parser.error("The selected device must be connected and authorized")

    if not args.skip_build:
        wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
        subprocess.run([str(wrapper), f":apps:android:assemble{args.flavor.capitalize()}Debug"], cwd=root, check=True)
    apk = root / "apps/android/build/outputs/apk" / args.flavor / "debug" / f"android-{args.flavor}-debug.apk"
    if not apk.is_file():
        parser.error("The debug APK is missing; run without --skip-build")
    subprocess.run([adb, "-s", serial, "install", "-r", str(apk)], check=True)
    if not args.install_only:
        package = f"dev.fajar.hris.mobile{'.' + args.flavor if args.flavor != 'prod' else ''}"
        subprocess.run([adb, "-s", serial, "shell", "am", "start", "-n", f"{package}/dev.fajar.hris.MainActivity"], check=True)


if __name__ == "__main__":
    main()
