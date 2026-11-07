"""Verify and extract an immutable Apple SDK candidate without recompiling its native code."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import stat
import zipfile


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verify(archive, expected_sha, output):
    if sha(archive) != expected_sha:
        raise ValueError("Candidate archive digest mismatch")
    config_path = Path(__file__).resolve().parent / "build.json"
    config = json.loads(config_path.read_text())
    patches = config_path.parent.parent / "collaboration-sdk-patches"
    base_path = patches / config["baseManifest"]
    if sha(base_path) != config["baseManifestSha256"]:
        raise ValueError("Candidate base manifest changed")
    base = json.loads(base_path.read_text())
    for name, expected in base["patches"].items():
        if sha(patches / name) != expected:
            raise ValueError("Candidate base patch changed")
    if sha(config_path.parent / config["patch"]) != config["patchSha256"]:
        raise ValueError("Candidate Apple patch changed")
    with zipfile.ZipFile(archive) as bundle:
        record = json.loads(bundle.read("result.json"))
        if not all(record.get(key) is True for key in
                   ("passed", "sourcesUnchanged", "swiftCompiled", "objectOwnerTestsPassed")):
            raise ValueError("Candidate build is not accepted")
        if record["manifestSha256"] != sha(config_path) or record["baseManifestSha256"] != sha(base_path):
            raise ValueError("Candidate manifest mismatch")
        if record["sourceSha256"] != {**base["patchedSources"], **config["patchedSources"]}:
            raise ValueError("Candidate patched source mismatch")
        if record["patches"] != {**base["patches"], config["patch"]: config["patchSha256"]}:
            raise ValueError("Candidate patch inventory mismatch")
        if record["upstream"] != base["upstream"] or record["targets"] != config["targets"]:
            raise ValueError("Candidate target or upstream mismatch")
        if record["profile"] != config["profile"] or record["rust"] != base["rust"]:
            raise ValueError("Candidate compiler or profile mismatch")
        output.mkdir(parents=True, exist_ok=False)
        entries = bundle.infolist()
        if len(entries) > 10_000 or sum(entry.file_size for entry in entries) > 2_000_000_000:
            raise ValueError("Candidate archive exceeds the extraction bound")
        if len({entry.filename for entry in entries}) != len(entries):
            raise ValueError("Duplicate candidate archive entry")
        for entry in entries:
            target = output / entry.filename
            if not target.resolve().is_relative_to(output.resolve()) or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Unexpected archive entry")
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(entry) as source, target.open("wb") as destination:
                shutil.copyfileobj(source, destination, 1024 * 1024)
    generated = output / "bindings/apple/generated"
    for name, expected in record["generated"].items():
        target = generated / name
        if not target.resolve().is_relative_to(generated.resolve()) or sha(target) != expected:
            raise ValueError("Generated native or Swift file mismatch")
    if sha(output / "Cargo.lock") != record["cargoLockSha256"]:
        raise ValueError("Candidate Cargo lock mismatch")
    return {"archiveSha256": expected_sha, "generatedFilesVerified": len(record["generated"]),
            "sourceManifestsVerified": True, "nativeRecompiled": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.archive.resolve(strict=True), args.sha256, args.output.resolve())))
