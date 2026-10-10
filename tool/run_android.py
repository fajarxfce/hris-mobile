#!/usr/bin/env python3
from pathlib import Path
import argparse
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
devices = subprocess.check_output([str(adb), "devices"], text=True)
serials = [line.split()[0] for line in devices.splitlines()[1:] if line.endswith("\tdevice")]
parser = argparse.ArgumentParser()
parser.add_argument("serial", nargs="?")
parser.add_argument("--flavor", choices=["dev", "staging", "prod"], default="dev")
args = parser.parse_args()
serial = args.serial or (serials[0] if len(serials) == 1 else None)
if serial is None:
    sys.exit("Connect one device, or run: python3 tool/run_android.py <device-serial>")
environment = dict(os.environ, ANDROID_SERIAL=serial)
subprocess.run([str(wrapper), f":apps:android:install{args.flavor.capitalize()}Debug"], cwd=root, env=environment, check=True)
subprocess.run([str(adb), "-s", serial, "shell", "am", "start", "-n",
                f"dev.fajar.fluent.starter{'.' + args.flavor if args.flavor != 'prod' else ''}/dev.fajar.fluent.MainActivity"], check=True)
