"""Package the accepted SDK8 Swift sources and XCFramework without rebuilding or publishing."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

from build_collaboration_apple_sdk import SWIFT_TESTS, load_manifest, validate_swift_test_report
from build_collaboration_sdk import sha
from recheck_collaboration_apple_sdk import checked_members, verify_origin


def validate_candidate(archive, distribution, apple_manifest_sha):
    if (distribution["schema"] != 1 or distribution["version"] != "26.10.23-hris.8"
            or distribution["appleManifestSha256"] != apple_manifest_sha
            or distribution["nativeTests"] != len(SWIFT_TESTS)):
        raise ValueError("Distribution does not match the accepted SDK8 manifest")
    result = json.loads(archive.read("result.json"))
    if (not result["passed"] or not result["candidateOnly"] or result["published"]
            or result["nativeRebuilt"] or not result["sourcesUnchanged"]
            or not result["iosDeviceLinked"] or not result["iosSimulatorLinked"]
            or result["version"] != distribution["version"]
            or result["sourceRun"] != distribution["nativeSourceRun"]
            or result["sourceCommit"] != distribution["nativeSourceCommit"]
            or result["completedBuild"]["manifestSha256"] != apple_manifest_sha
            or result["fixtureCleanup"] != {"afterSwiftProcessExit": True, "removed": True}
            or result["packageFiles"] != distribution["packageFiles"]
            or result["proofFiles"] != distribution["proofFiles"]
            or result["slices"] != distribution["slices"]):
        raise ValueError("Native candidate lacks the exact accepted source, files or gates")
    origin = json.loads(archive.read("origin.json"))
    verify_origin(origin["run"], origin["artifact"])
    records = {prefix + name: digest
               for prefix, key in [("sdk/", "packageFiles"), ("proof/", "proofFiles")]
               for name, digest in distribution[key].items()}
    checked_members(archive, records)
    # Parse the actual report bytes directly; never reconstruct a report from log lines.
    report = archive.read("swift-tests.xml")
    expected = validate_swift_test_report(report)
    if result["nativeTests"] != expected:
        raise ValueError("Actual Swift report differs from its accepted record")
    required = {"Package.swift", "LICENSE"}
    if not required.issubset(distribution["packageFiles"]):
        raise ValueError("Swift package entry or license is missing")
    return result


def write_runtime_package(archive, records, destination):
    """Stable paths, timestamps and modes; include only the verified runtime package."""
    with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for name in sorted(records):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            with archive.open("sdk/" + name) as source, bundle.open(entry, "w", force_zip64=True) as target:
                shutil.copyfileobj(source, target, length=1024 * 1024)
    with zipfile.ZipFile(destination) as bundle:
        if set(bundle.namelist()) != set(records) or bundle.testzip() is not None:
            raise ValueError("Packaged runtime inventory or ZIP CRC differs")
        for name, expected in records.items():
            with bundle.open(name) as content:
                if hashlib.file_digest(content, "sha256").hexdigest() != expected:
                    raise ValueError("Packaged runtime content changed")


def package(native_archive, output):
    tool = Path(__file__).resolve().parent
    patches = tool / "collaboration-sdk-patches"
    manifest, _ = load_manifest(patches)
    distribution = json.loads((patches / "apple-distribution.json").read_text())
    source = native_archive.resolve(strict=True)
    output = output.resolve()
    if output == source or output in source.parents:
        raise ValueError("Use an output directory separate from the accepted archive")
    if sha(source) != distribution["archiveSha256"]:
        raise ValueError("Native archive differs from the accepted GitHub artifact")
    if distribution["version"] != manifest["version"]:
        raise ValueError("Distribution version differs from SDK8")
    with zipfile.ZipFile(source) as archive:
        result = validate_candidate(archive, distribution, sha(patches / "apple-incoming-hints-build.json"))
        output.mkdir(parents=True, exist_ok=False)
        name = "matrix-rust-sdk-" + distribution["version"] + "-apple.zip"
        bundle = output / name
        write_runtime_package(archive, distribution["packageFiles"], bundle)
        evidence = {
            "version": distribution["version"], "passed": True, "published": False,
            "nativeRebuilt": False, "nativeArchiveSha256": sha(source),
            "distributionManifestSha256": sha(patches / "apple-distribution.json"),
            "acceptedRun": distribution["acceptedRun"], "acceptedJob": distribution["acceptedJob"],
            "acceptedSource": distribution["acceptedSource"], "artifactId": distribution["artifactId"],
            "nativeTests": result["nativeTests"], "fixtureCleanup": result["fixtureCleanup"],
            "iosDeviceLinked": True, "iosSimulatorLinked": True,
            "slices": result["slices"], "packageFiles": result["packageFiles"],
            "bundle": {"name": name, "bytes": bundle.stat().st_size, "sha256": sha(bundle)},
        }
        record = output / "apple-sdk-build.json"
        record.write_text(json.dumps(evidence, indent=2) + "\n")
        (output / "SHA256SUMS").write_text("".join(sha(p) + "  " + p.name + "\n" for p in (bundle, record)))
    print(json.dumps({k: v for k, v in evidence.items() if k not in ("packageFiles", "slices")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    package(args.native_archive, args.output)
