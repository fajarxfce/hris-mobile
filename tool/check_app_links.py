#!/usr/bin/env python3
"""Prepare fictional public registrations and check actual generated native artifacts."""
import argparse
import json
from pathlib import Path
import plistlib
import re
import xml.etree.ElementTree as ET

ENVIRONMENTS = ("dev", "staging", "prod")
HOST = "links.example.invalid"
FINGERPRINT = ":".join(["AB"] * 32)
ANDROID = "{http://schemas.android.com/apk/res/android}"


def application_id(base, environment):
    return base + ("" if environment == "prod" else "." + environment)


def prepare(root, base):
    directory = root / ".work/app-links-check"
    marker = directory / ".owned"
    if directory.exists() and (directory.is_symlink() or not marker.is_file()
                               or marker.read_text() != "app-link-ci-fixture-v1\n"):
        raise ValueError("Existing app-link fixture requires review.")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    marker.write_text("app-link-ci-fixture-v1\n")
    for environment in ENVIRONMENTS:
        path = directory / (environment + ".json")
        if path.is_symlink():
            raise ValueError("App-link fixture cannot replace a symbolic link.")
        identifier = application_id(base, environment)
        path.write_text(json.dumps({"host": HOST, "android": {
            "applicationId": identifier, "sha256CertFingerprints": [FINGERPRINT]},
            "ios": {"applicationId": "ABCDE12345." + identifier}}, indent=2) + "\n")
        path.chmod(0o600)


def verify(root, base, android):
    output = root / "core/common/build/generated/app-links"
    site = output / "sites" / HOST / ".well-known"
    statements = json.loads((site / "assetlinks.json").read_text())
    assert len(statements) == 3
    for environment in ENVIRONMENTS:
        identifier = application_id(base, environment)
        statement = next(item for item in statements if item["target"]["package_name"] == identifier)
        assert statement["relation"] == ["delegate_permission/common.handle_all_urls"]
        assert statement["target"]["namespace"] == "android_app"
        assert statement["target"]["sha256_cert_fingerprints"] == [FINGERPRINT]
    details = json.loads((site / "apple-app-site-association").read_text())["applinks"]["details"]
    assert len(details) == 3
    for environment in ENVIRONMENTS:
        identifier = application_id(base, environment)
        item = next(item for item in details if item["appIDs"] == ["ABCDE12345." + identifier])
        assert item["components"] == [{"/": "/app/" + environment + "/*"}]
        with (output / "ios" / (environment + ".entitlements")).open("rb") as source:
            entitlements = plistlib.load(source)
        assert entitlements["com.apple.developer.associated-domains"] == ["applinks:" + HOST]
        assert entitlements["aps-environment"] == "$(APS_ENVIRONMENT)"
        assert (output / "ios" / (environment + ".bundle-id")).read_text().strip() == identifier
    if android:
        manifests = list((root / "apps/android/build/intermediates").rglob("AndroidManifest.xml"))
        for environment in ENVIRONMENTS:
            identifier = application_id(base, environment)
            found = []
            for path in manifests:
                if "AppLinks" not in str(path):
                    continue
                manifest = ET.parse(path).getroot()
                if manifest.get("package") != identifier:
                    continue
                for activity in manifest.findall("./application/activity"):
                    for intent in activity.findall("intent-filter"):
                        for data in intent.findall("data"):
                            if data.get(ANDROID + "host") == HOST:
                                assert intent.get(ANDROID + "autoVerify") == "true"
                                assert data.get(ANDROID + "scheme") == "https"
                                assert data.get(ANDROID + "pathPrefix") == "/app/" + environment + "/"
                                assert len(intent.findall("data")) == 1
                                found.append(path)
            assert found, "Configured merged manifest is missing for " + environment
    print("App-link registrations, domain files and " + ("Android/Apple" if android else "Apple") + " artifacts verified.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "verify", "verify-apple"))
    parser.add_argument("--application-id", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+", args.application_id):
        raise ValueError("Use the public base application ID.")
    root = Path(__file__).resolve().parents[1]
    if args.action == "prepare":
        prepare(root, args.application_id)
    else:
        verify(root, args.application_id, args.action == "verify")


if __name__ == "__main__":
    main()
