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
import xml.etree.ElementTree as ET


PACKAGE = "dev.fajar.hris.collaboration.proof"
PERMISSION = "android.permission.POST_NOTIFICATIONS"


def system_ui_reply(adb, output):
    """Type only the fixed fictional fixture text into Android's actual RemoteInput editor."""
    def tree():
        adb("shell", "uiautomator", "dump", "/sdcard/owned-action-proof.xml")
        return ET.fromstring(adb("shell", "cat", "/sdcard/owned-action-proof.xml"))

    def tap(node):
        points = [int(v) for v in re.findall(r"\d+", node.attrib["bounds"])]
        assert len(points) == 4
        adb("shell", "input", "tap", str((points[0] + points[2]) // 2), str((points[1] + points[3]) // 2))

    def capture(name):
        (output / (name + ".xml")).write_text(ET.tostring(tree(), encoding="unicode"))
        adb("shell", "screencap", "-p", "/sdcard/owned-action-proof.png")
        adb("pull", "/sdcard/owned-action-proof.png", str(output / (name + ".png")))

    adb("shell", "cmd", "statusbar", "expand-notifications")
    current = tree()
    replies = [n for n in current.iter("node") if n.get("text") == "Reply"]
    if not replies:
        expand = [n for n in current.iter("node") if n.get("resource-id", "").endswith("/expand_button")]
        assert len(expand) == 1, "Expected the owned fixture notification expansion"
        tap(expand[0])
        replies = [n for n in tree().iter("node") if n.get("text") == "Reply"]
    assert len(replies) == 1, "Expected exactly one fictional reply action"
    capture("reply-notification")
    tap(replies[0])
    focused = [n for n in tree().iter("node") if n.get("class") == "android.widget.EditText" and n.get("focused") == "true"]
    assert len(focused) == 1, "SystemUI reply editor did not acquire focus"
    adb("shell", "input", "text", "Fictional%snative%sreply")
    capture("reply-editor")
    send = [n for n in tree().iter("node") if n.get("resource-id", "").endswith("/remote_input_send") and n.get("enabled") == "true"]
    assert len(send) == 1, "SystemUI send action unavailable"
    tap(send[0])
    adb("shell", "rm", "/sdcard/owned-action-proof.xml", "/sdcard/owned-action-proof.png")


def reply_surface_retired(xml):
    nodes = list(ET.fromstring(xml).iter("node"))
    # Require the shade itself, not an unrelated foreground screen. A silent neutral
    # replacement must disappear too; absence from NotificationManager is insufficient.
    return any(n.get("package") == "com.android.systemui" for n in nodes) and not any(
        n.get("text") in {"Fictional action proof", "Fictional native reply", "HRIS Protocol Proof"}
        or n.get("class") == "android.widget.EditText"
        or n.get("resource-id", "").endswith("/remote_input_send")
        for n in nodes
    )


def action_callbacks(adb, output, reply_ui=False):
    began = time.monotonic()

    def read(name):
        try:
            return json.loads(adb("shell", "run-as", PACKAGE, "cat", f"files/protocol-proof/{name}.json"))
        except subprocess.CalledProcessError:
            return None

    def await_file(name, predicate):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            failure = read("action-failure")
            if failure is not None:
                (output / "action-failure.json").write_text(json.dumps(failure, indent=2) + "\n")
                raise AssertionError("Fictional action proof failed; redacted evidence retained")
            value = read(name)
            if value and predicate(value):
                return value
            time.sleep(0.1)
        raise TimeoutError(f"Native action phase did not finish: {name}")

    cases = []
    for count, mode in enumerate(("Open", "MarkRead", "Dismiss", "Reply", "Mute"), 1):
        adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.NotificationActionProofActivity", "--es", "mode", mode)
        staged = await_file("action-staged", lambda v: v.get("complete") is True and v.get("mode") == mode)
        if mode == "Reply" and reply_ui:
            system_ui_reply(adb, output)
        else:
            adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.NotificationActionSenderActivity", "--es", "mode", mode)
        callback = await_file("action-callback", lambda v: v.get("count") == count)
        (output / f"action-{mode}-callback.json").write_text(json.dumps(callback, indent=2) + "\n")
        assert callback["applied"] is True, callback
        assert callback["pid"] == staged["pid"]
        if mode == "Reply" and reply_ui:
            adb("shell", "uiautomator", "dump", "/sdcard/owned-action-proof.xml")
            xml = adb("shell", "cat", "/sdcard/owned-action-proof.xml")
            (output / "reply-acknowledged.xml").write_text(xml)
            ui_retired = reply_surface_retired(xml)
            adb("shell", "screencap", "-p", "/sdcard/owned-action-proof.png")
            adb("pull", "/sdcard/owned-action-proof.png", str(output / "reply-acknowledged.png"))
            adb("shell", "rm", "/sdcard/owned-action-proof.xml", "/sdcard/owned-action-proof.png")
        adb("shell", "cmd", "statusbar", "collapse")
        adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.NotificationActionProofActivity", "--es", "mode", mode, "--ez", "verify", "true")
        verified = await_file("action-verified", lambda v: v.get("complete") is True and v.get("mode") == mode)
        assert verified["httpCalls"] == verified["messageCommands"] == 0
        assert verified["nativeCards"] == (1 if mode == "Open" else 0)
        assert verified["pid"] == staged["pid"]
        assert verified["state"] == {"Open": "Published", "MarkRead": "Cancelled", "Dismiss": "Dismissed", "Reply": "Dismissed", "Mute": "Cancelled"}[mode]
        cases.append({**verified, "callbackCount": count})
        (output / "action-progress.json").write_text(json.dumps(cases, indent=2) + "\n")
        if mode == "Reply" and reply_ui:
            assert ui_retired, "SystemUI retained the replied card or editor"
    return {"complete": True, "cases": cases, "systemUiReply": reply_ui,
            "seconds": round(time.monotonic() - began, 3),
            "scope": "Actual Android application callbacks, encrypted Room/Keystore and native cards; seeded fictional session/conversation/history; no discovery actor, provider send or product activation"}


def input_recovery(adb, output):
    """Same owned fixture, ordinary process kills; never force-stop during the scenario."""
    began = time.monotonic()

    def read(name):
        try:
            return json.loads(adb("shell", "run-as", PACKAGE, "cat", f"files/protocol-proof/{name}.json"))
        except subprocess.CalledProcessError:
            return None

    def await_file(name, predicate, seconds=20):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            failure = read("reply-failure")
            assert failure is None, failure
            value = read(name)
            if value is not None and predicate(value):
                return value
            time.sleep(0.1)
        raise TimeoutError(f"Owned notification input phase did not finish: {name}")

    def kill_owned(pid):
        assert isinstance(pid, int) and pid > 1
        # Verify exact normal application process before sending any signal.
        actual = adb("shell", "run-as", PACKAGE, "cat", f"/proc/{pid}/cmdline").strip("\x00\r\n")
        assert actual == PACKAGE, "Refusing to kill a different process"
        adb("shell", "run-as", PACKAGE, "kill", "-9", str(pid))
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            listing = adb("shell", "ps", "-A", "-o", "PID,NAME")
            if not any(line.split()[:1] == [str(pid)] for line in listing.splitlines()[1:]):
                return
            time.sleep(0.1)
        raise TimeoutError("Owned receiver process did not exit")

    def send(mode, count, saved):
        adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.NotificationInputSenderActivity", "--es", "mode", mode)
        callback = await_file("reply-callback", lambda value: value.get("count") == count)
        assert callback["saved"] is saved
        sender = await_file("reply-sent", lambda value: value.get("mode") == mode)
        assert sender["pid"] != callback["pid"]
        return callback

    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.NotificationInputProofActivity")
    staged = await_file("reply-staged", lambda value: value.get("complete") is True)
    kill_owned(staged["pid"])
    first = send("reply", 1, True)
    assert first["pid"] != staged["pid"]
    kill_owned(first["pid"])
    second = send("reply", 2, True)
    assert second["pid"] not in [staged["pid"], first["pid"]]
    missing = send("missing", 3, False)
    nonreply = send("nonreply", 4, False)
    assert missing["pid"] == nonreply["pid"] == second["pid"]
    kill_owned(second["pid"])
    log = adb("shell", "am", "instrument", "-w", "-e", "scenario", "notification-input-verify",
              f"{PACKAGE}/.MatrixProofInstrumentation", timeout=150)
    (output / "notification-input-verify.log").write_text(log)
    status = read("instrumentation")
    recovery = read("reply-recovery")
    if recovery is not None:
        (output / "reply-recovery.json").write_text(json.dumps(recovery, indent=2) + "\n")
    assert status and status.get("complete") is True, status
    verified = read("reply-verified")
    assert verified and verified.get("complete") is True and verified.get("cleanedUp") is True
    assert recovery and recovery.get("turns") == verified.get("recoveryTurns")
    validate_input_recovery_turns(verified["recoveryTurns"])
    assert verified["protectedRowsBeforeRecovery"] == verified["reviewRowsAfterRecovery"] == 1
    assert verified["wrongAccountPreservedInput"] and verified["oldFamilyRequiresReview"]
    assert verified["httpCalls"] == 0 and verified["commandRetained"] is False
    assert verified["pid"] not in [staged["pid"], first["pid"], second["pid"]]
    return {**verified, "ordinaryProcessKills": 3, "callbackCount": 4,
            "processIds": [staged["pid"], first["pid"], second["pid"], verified["pid"]],
            "seconds": round(time.monotonic() - began, 3),
            "scope": "Real Android callback, Keystore/Room/app recovery; fictional account; no HTTP/Matrix send or production host activation"}


def validate_input_recovery_turns(turns):
    assert isinstance(turns, list) and 1 <= len(turns) <= 4, "Missing or unbounded recovery turns"
    for index, turn in enumerate(turns, 1):
        assert isinstance(turn, dict) and turn.get("turn") == index
        expected = "Complete" if index == len(turns) else "Retry"
        assert turn.get("result") == expected, "Recovery is still pending or blocked"
        counts = [turn.get("protectedRows"), turn.get("reviewRows")]
        assert all(type(value) is int and 0 <= value <= 1 for value in counts)
        assert sum(counts) >= 1, "Captured input disappeared before its reviewed copy"
    assert turns[-1]["protectedRows"] == 0 and turns[-1]["reviewRows"] == 1


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
        try:
            assert "Success" in adb("install", "--no-incremental", str(args.apk.resolve()), timeout=180)
        except subprocess.CalledProcessError as failure:
            # APK installer diagnostics only; no application logs or private callback inputs.
            (args.output / "install-error.log").write_text((failure.stdout or '') + (failure.stderr or ''))
            result["installFailureCategory"] = type(failure).__name__
            raise
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
        result["inputRecovery"] = input_recovery(adb, args.output)
        result["actionCallbacks"] = action_callbacks(adb, args.output, getattr(args, "reply_system_ui", False))
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
                    present = bool(adb("shell", "pm", "list", "packages", PACKAGE).strip())
                    if present:
                        assert "Success" in adb("uninstall", PACKAGE)
                    assert not adb("shell", "pm", "list", "packages", PACKAGE).strip()
                    result["fixtureAbsent"] = True
                    result["fixtureUninstalled"] = present
                    result["fixtureDataCleared"] = present
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
    parser.add_argument("--reply-system-ui", action="store_true", help="Type the fixed fictional reply through the emulator's SystemUI editor")
    print(json.dumps(run(parser.parse_args())))


if __name__ == "__main__":
    main()
