"""Recheck the exact completed SDK8 framework; never rebuild or publish native libraries."""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import stat
import subprocess
import time
import zipfile

from build_collaboration_apple_sdk import load_manifest, resolve_symbol_reader, verify_framework, verify_swift_tests
from build_collaboration_sdk import run, sha

SOURCE_RUN = 43349484661
SOURCE_JOB = 131636790768
SOURCE_COMMIT = "6cc04d1f7c92dd60923c8d291e978e6d8a42728a"
ARTIFACT_ID = 14009837009
ARCHIVE_SHA256 = "1fd951cc4f0c71adcbf4f1914d31f191ecb8c6dfcc1ac40a86eb0d8474d094ca"
REPOSITORY = "fajarxfce/hris-mobile"


def checked_members(archive, records):
    """Validate the entire archive before extracting its recorded package/proof files."""
    infos = archive.infolist()
    names = [item.filename for item in infos]
    if len(names) != len(set(names)) or len(names) > 4096:
        raise ValueError("Duplicate or excessive candidate members")
    if sum(item.file_size for item in infos) > 4 * 1024**3:
        raise ValueError("Candidate expanded size exceeds its bound")
    for item in infos:
        path = PurePosixPath(item.filename)
        if path.is_absolute() or ".." in path.parts or "\\" in item.filename:
            raise ValueError("Candidate member escapes its directory")
        mode = item.external_attr >> 16
        if stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
            raise ValueError("Candidate contains a link or special file")
    selected = {n for n in names if n.startswith(("sdk/", "proof/")) and not n.endswith("/")}
    if selected != set(records):
        raise ValueError("Candidate package inventory differs from its record")
    for name, expected in records.items():
        with archive.open(name) as content:
            if hashlib.file_digest(content, "sha256").hexdigest() != expected:
                raise ValueError("Candidate package content changed")
    if archive.testzip() is not None:
        raise ValueError("Candidate ZIP CRC failed")
    return sorted(selected)


def verify_origin(run_record, artifact):
    if (run_record["headSha"] != SOURCE_COMMIT or run_record["status"] != "completed"
            or run_record["conclusion"] != "failure"):
        raise ValueError("Unexpected completed native source run")
    jobs = [job for job in run_record["jobs"] if job["databaseId"] == SOURCE_JOB]
    if len(jobs) != 1 or jobs[0]["conclusion"] != "failure":
        raise ValueError("Unexpected completed native job")
    if (artifact["id"] != ARTIFACT_ID or artifact["expired"]
            or artifact["workflow_run"]["id"] != SOURCE_RUN
            or artifact["workflow_run"]["head_sha"] != SOURCE_COMMIT
            or artifact["digest"] != "sha256:" + ARCHIVE_SHA256
            or artifact["name"] != "sdk8-apple-native-candidate"):
        raise ValueError("Unexpected native artifact provenance")


def recheck(output):
    if platform.system() != "Darwin":
        raise ValueError("Native Swift recheck requires macOS")
    tool = Path(__file__).resolve().parent
    manifest, base = load_manifest(tool / "collaboration-sdk-patches")
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env["RUSTUP_TOOLCHAIN"] = base["rust"]
    started = time.monotonic()
    record = {"startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "sourceRun": SOURCE_RUN, "sourceJob": SOURCE_JOB, "sourceCommit": SOURCE_COMMIT,
              "artifactId": ARTIFACT_ID, "archiveSha256": ARCHIVE_SHA256,
              "candidateOnly": True, "published": False, "passed": False,
              "nativeRebuilt": False, "version": manifest["version"]}
    try:
        def gh(*args):
            return json.loads(subprocess.check_output(["gh", *args], text=True, timeout=90))
        origin = gh("run", "view", str(SOURCE_RUN), "--repo", REPOSITORY,
                    "--json", "headSha,status,conclusion,jobs")
        artifact = gh("api", f"repos/{REPOSITORY}/actions/artifacts/{ARTIFACT_ID}")
        verify_origin(origin, artifact)
        (output / "origin.json").write_text(json.dumps({"run": origin, "artifact": artifact}, indent=2) + "\n")
        archive = output / "completed-native.zip"
        with archive.open("wb") as content:
            subprocess.run(["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{ARTIFACT_ID}/zip"],
                           stdout=content, check=True, timeout=600)
        if sha(archive) != ARCHIVE_SHA256:
            raise ValueError("Completed native archive digest changed")
        with zipfile.ZipFile(archive) as candidate:
            previous = json.loads(candidate.read("result.json"))
            if (previous["passed"] or not previous["candidateOnly"] or previous["published"]
                    or previous["manifestSha256"] != sha(tool / "collaboration-sdk-patches/apple-incoming-hints-build.json")
                    or previous["baseManifestSha256"] != manifest["baseManifestSha256"]
                    or previous["rust"] != base["rust"] or previous["upstream"] != base["upstream"]
                    or previous["targets"] != manifest["targets"] or previous["version"] != manifest["version"]):
                raise ValueError("Completed framework does not match current SDK inputs")
            files = {prefix + name: digest for prefix, key in [("sdk/", "packageFiles"), ("proof/", "proofFiles")]
                     for name, digest in previous[key].items()}
            for name in checked_members(candidate, files):
                destination = output / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                with candidate.open(name) as source, destination.open("wb") as target:
                    shutil.copyfileobj(source, target)
        record["completedBuild"] = previous
        record["packageFiles"] = previous["packageFiles"]
        proof = output / "proof"
        for source in (tool / "collaboration-apple-proof").rglob("*.swift"):
            relative = source.relative_to(tool / "collaboration-apple-proof")
            if relative.parts[0] in ("Sources", "Tests") and sha(source) != sha(proof / relative):
                raise ValueError("Original raw-key proof changed")
        if sha(proof / "Package.swift") != sha(tool / "collaboration-apple-sdk-proof/Package.swift"):
            raise ValueError("Original proof package changed")
        shutil.copy2(tool / "collaboration-apple-sdk-proof/SDK8NativeTests.swift",
                     proof / "Tests/HrisCollaborationAppleProofTests/SDK8NativeTests.swift")
        record["proofFiles"] = {str(p.relative_to(proof)): sha(p) for p in sorted(proof.rglob("*")) if p.is_file()}
        with (output / "build.log").open("w") as log:
            run(["rustup", "toolchain", "install", base["rust"], "--profile", "minimal", "--component", "llvm-tools-preview"],
                cwd=proof, env=env, log=log, timeout=900)
            reader, record["symbolReader"] = resolve_symbol_reader(env, base["rust"])
            record["slices"] = verify_framework(output / "sdk/bindings/apple/generated/MatrixSDKFFI.xcframework", base, reader)
            if record["slices"] != previous["slices"]:
                raise ValueError("Completed native slices changed")
            run(["swift", "test", "--jobs", str(base["jobs"]), "--xunit-output", str(output / "swift-tests.xml")],
                cwd=proof, env=env, log=log, timeout=1800)
            record["nativeTests"] = verify_swift_tests(output / "swift-tests.xml")
            for name, target in [("device", "generic/platform=iOS"), ("simulator", "generic/platform=iOS Simulator")]:
                run(["xcodebuild", "-scheme", "HrisCollaborationAppleProof", "-destination", target,
                     "-derivedDataPath", str(output / "derived" / name), "-jobs", str(base["jobs"]),
                     "CODE_SIGNING_ALLOWED=NO", "build"], cwd=proof, env=env, log=log, timeout=1800)
                record["ios" + name.title() + "Linked"] = True
            for prefix, key in [("sdk/", "packageFiles"), ("proof/", "proofFiles")]:
                if any(sha(output / prefix / name) != expected for name, expected in record[key].items()):
                    raise ValueError("Frozen native or proof source changed while testing")
            record.update(passed=True, sourcesUnchanged=True)
    finally:
        record["elapsedSeconds"] = round(time.monotonic() - started, 3)
        (output / "result.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({k: v for k, v in record.items() if k not in ("completedBuild", "packageFiles", "proofFiles")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    recheck(parser.parse_args().output)
