"""Prepare the exact accepted SDK8 Swift package for a local Xcode/SwiftPM consumer."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import zipfile


def digest(path):
    with path.open("rb") as content:
        return hashlib.file_digest(content, "sha256").hexdigest()


def validate_archive(archive, records, expanded_bytes):
    infos = archive.infolist()
    names = [item.filename for item in infos]
    if len(names) != len(set(names)) or set(names) != set(records) or len(names) > 128:
        raise ValueError("Swift package inventory differs from the accepted distribution")
    if not {"Package.swift", "LICENSE"}.issubset(records):
        raise ValueError("Swift package entry or license is missing")
    if (not 0 < expanded_bytes <= 2 * 1024**3
            or sum(item.file_size for item in infos) != expanded_bytes):
        raise ValueError("Swift package expanded size differs from the accepted distribution")
    for item in infos:
        path = PurePosixPath(item.filename)
        if (path.is_absolute() or ".." in path.parts or "\\" in item.filename
                or str(path) != item.filename or item.is_dir()
                or stat.S_IFMT(item.external_attr >> 16) != stat.S_IFREG):
            raise ValueError("Swift package contains a redirected or non-file entry")
        with archive.open(item) as content:
            if hashlib.file_digest(content, "sha256").hexdigest() != records[item.filename]:
                raise ValueError("Swift package content differs from its accepted digest")
    if archive.testzip() is not None:
        raise ValueError("Swift package ZIP CRC failed")


def extract_verified(archive, output, records, expanded_bytes):
    validate_archive(archive, records, expanded_bytes)
    # Refuse existing outputs. A killed/failed preparation cannot be mistaken for a completed one.
    output.mkdir(parents=True, exist_ok=False)
    for name in sorted(records):
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(name) as source, target.open("xb") as destination:
            shutil.copyfileobj(source, destination, length=1024 * 1024)
        if digest(target) != records[name]:
            raise ValueError("Prepared Swift file differs from its accepted digest")


def prepare(source, output):
    patches = Path(__file__).resolve().parent / "collaboration-sdk-patches"
    pin = json.loads((patches / "apple-consumer.json").read_text())
    distribution_path = patches / "apple-distribution.json"
    if (pin["schema"] != 1 or pin["version"] != "26.10.23-hris.8"
            or digest(distribution_path) != pin["distributionManifestSha256"]):
        raise ValueError("Consumer pin differs from the accepted SDK8 distribution")
    distribution = json.loads(distribution_path.read_text())
    if distribution["version"] != pin["version"] or len(distribution["packageFiles"]) != pin["packageFiles"]:
        raise ValueError("Consumer version or inventory differs")
    source = source.resolve(strict=True)
    output = output.resolve()
    if output == source or output in source.parents:
        raise ValueError("Use a new package directory separate from the source archive")
    if source.stat().st_size != pin["archiveBytes"] or digest(source) != pin["archiveSha256"]:
        raise ValueError("Swift archive is not the accepted SDK8 distribution")
    with zipfile.ZipFile(source) as archive:
        extract_verified(archive, output, distribution["packageFiles"], pin["expandedBytes"])
    result = {"passed": True, "version": pin["version"], "archiveSha256": pin["archiveSha256"],
              "packageFiles": pin["packageFiles"], "expandedBytes": pin["expandedBytes"],
              "packagePath": str(output), "nativeRebuilt": False, "applicationLinked": False}
    print(json.dumps(result))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.archive, args.output)
