#!/usr/bin/env python3
"""Package a verified four-ABI SDK candidate and Ivy descriptor; never publish it."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

from build_collaboration_sdk import TARGETS, run, sha, verify_native_library


def require(condition, message):
    if not condition:
        raise ValueError(message)


def write_ivy(pom, distribution, version, output):
    """Preserve the pinned upstream runtime dependencies, including JNA's AAR type."""
    root = ET.fromstring(pom)
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    ivy = ET.Element("ivy-module", version="2.0")
    info = ET.SubElement(ivy, "info", organisation=distribution["group"],
                         module=distribution["module"], revision=version, status="release")
    ET.SubElement(info, "license", name="Apache-2.0", url="https://www.apache.org/licenses/LICENSE-2.0.txt")
    ET.SubElement(info, "description", homepage="https://github.com/matrix-org/matrix-rust-sdk").text = (
        "Matrix Rust SDK Android bindings with HRIS raw-event and confirmed-queue recovery patches."
    )
    ET.SubElement(ET.SubElement(ivy, "configurations"), "conf", name="default", visibility="public")
    ET.SubElement(ET.SubElement(ivy, "publications"), "artifact", name=distribution["module"],
                  type="aar", ext="aar", conf="default")
    dependencies = ET.SubElement(ivy, "dependencies")
    source = root.findall("m:dependencies/m:dependency", ns)
    require(len(source) == 4, "Unexpected upstream dependency set")
    for node in source:
        value = lambda name: node.findtext("m:" + name, namespaces=ns)
        require(value("scope") in ("compile", "runtime"), "Unsupported dependency scope")
        attributes = dict(org=value("groupId"), name=value("artifactId"), rev=value("version"),
                          conf="default->default")
        require(all(attributes.values()), "Incomplete upstream dependency")
        exclusions = node.findall("m:exclusions/m:exclusion", ns)
        if exclusions:
            require(len(exclusions) == 1 and all(
                exclusions[0].findtext("m:" + key, namespaces=ns) == "*"
                for key in ("groupId", "artifactId")
            ), "Unsupported upstream exclusions")
            attributes["transitive"] = "false"
        dependency = ET.SubElement(dependencies, "dependency", **attributes)
        if value("type"):
            require(value("type") == "aar", "Unsupported upstream artifact type")
            ET.SubElement(dependency, "artifact", name=value("artifactId"), type="aar", ext="aar")
    ET.indent(ivy)
    ET.ElementTree(ivy).write(output, encoding="utf-8", xml_declaration=True)


def package(args):
    patches = Path(__file__).resolve().parent / "collaboration-sdk-patches"
    manifest = json.loads((patches / "android-build.json").read_text())
    distribution = json.loads((patches / "android-distribution.json").read_text())
    require(re.fullmatch(r"\d+\.\d+\.\d+-hris\.[1-9]\d*", args.version), "Unexpected release version")
    wrapper, sdk = args.wrapper.resolve(), args.sdk.resolve()
    main = wrapper / "sdk/sdk-android/src/main"
    ndk = args.android_sdk.resolve() / "ndk" / manifest["ndk"]
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=wrapper, text=True).strip()
            == distribution["wrapperCommit"], "Unexpected wrapper revision")
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=sdk, text=True).strip()
            == manifest["upstream"], "Unexpected SDK base")
    require(not (wrapper / "local.properties").exists(), "Untracked local Gradle configuration")
    changed = set(subprocess.check_output(["git", "diff", "--name-only", "HEAD", "-z"], cwd=wrapper)
                  .decode().rstrip("\0").split("\0")) - {""}
    require(changed <= {"sdk/sdk-android/src/main/" + name for name in manifest["generatedKotlin"]},
            "Unexpected wrapper source or build configuration change")
    require(not subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "-z"],
                                        cwd=wrapper), "Untracked wrapper inputs")
    require((sdk / "LICENSE").read_bytes() == subprocess.check_output(["git", "show", "HEAD:LICENSE"], cwd=sdk),
            "SDK license differs from the pinned upstream source")
    for name, digest in manifest["patchedSources"].items():
        require(sha(sdk / name) == digest, "Changed SDK source: " + name)
    for name, digest in manifest["patches"].items():
        require(sha(patches / name) == digest, "Changed SDK patch: " + name)
    generated = {str(p.relative_to(main)): sha(p) for p in main.glob("kotlin/**/*.kt")}
    require(generated == manifest["generatedKotlin"], "Generated Kotlin differs from the native pair")
    expected = {"jniLibs/" + abi + "/libmatrix_sdk_ffi.so" for abi in TARGETS}
    require({str(p.relative_to(main)) for p in main.glob("jniLibs/**/*.so")} == expected,
            "Exactly four native ABI libraries are required")
    native = {abi: verify_native_library(main / "jniLibs" / abi / "libmatrix_sdk_ffi.so", abi, ndk)
              for abi in TARGETS}
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=wrapper).decode().split("\0")
    fingerprint = lambda: {name: sha(wrapper / name) for name in tracked if name and (wrapper / name).is_file()}
    before = fingerprint()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    record = {"startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "version": args.version, "group": distribution["group"], "module": distribution["module"],
              "sdkBase": manifest["upstream"], "wrapperCommit": distribution["wrapperCommit"],
              "passed": False, "published": False, "native": native, "generatedKotlin": generated,
              "patchedSources": manifest["patchedSources"], "patches": manifest["patches"],
              "packagingDriverSha256": sha(Path(__file__).resolve()),
              "nativeVerifierSha256": sha(patches.parent / "build_collaboration_sdk.py"),
              "androidBuildManifestSha256": sha(patches / "android-build.json"),
              "distributionManifestSha256": sha(patches / "android-distribution.json")}
    started = time.monotonic()
    try:
        env = os.environ.copy()
        java = args.java_home.resolve()
        env.update(JAVA_HOME=str(java), ANDROID_HOME=str(args.android_sdk.resolve()),
                   ANDROID_SDK_ROOT=str(args.android_sdk.resolve()), NDK_VERSION=manifest["ndk"])
        env["PATH"] = str(java / "bin") + os.pathsep + env["PATH"]
        for key in ("OSSRH_USERNAME", "OSSRH_PASSWORD", "SONATYPE_STAGING_PROFILE_ID",
                    "SIGNING_KEY_ID", "SIGNING_PASSWORD", "SIGNING_KEY"):
            env.pop(key, None)
        command = ["./gradlew", ":sdk:sdk-android:assembleRelease", "--stacktrace", "--no-daemon",
                   "--no-parallel", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx2048m",
                   "-Pkotlin.compiler.execution.strategy=in-process"]
        with (out / "build.log").open("w") as log:
            run(command, cwd=wrapper, env=env, log=log, timeout=1500)
        require(before == fingerprint(), "Wrapper inputs changed during packaging")
        require(all(sha(main / name) == digest for name, digest in generated.items()), "Kotlin changed")
        require(all(sha(main / "jniLibs" / abi / "libmatrix_sdk_ffi.so") == data["nativeSha256"]
                    for abi, data in native.items()), "Native input changed")
        aar = out / (distribution["module"] + "-" + args.version + ".aar")
        shutil.copy2(wrapper / "sdk/sdk-android/build/outputs/aar/sdk-android-release.aar", aar)
        with zipfile.ZipFile(aar) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "Duplicate archive entries")
            require({name for name in names if name.endswith(".so")}
                    == {name.replace("jniLibs/", "jni/", 1) for name in expected}, "Unexpected packaged JNI")
            for abi, data in native.items():
                with archive.open("jni/" + abi + "/libmatrix_sdk_ffi.so") as stream:
                    require(hashlib.file_digest(stream, "sha256").hexdigest() == data["nativeSha256"],
                            "Packaged native binary differs: " + abi)
            require('android:minSdkVersion="21"' in archive.read("AndroidManifest.xml").decode(),
                    "Unexpected SDK minimum Android version")
            require(archive.read("proguard.txt").strip()
                    == (wrapper / "sdk/sdk-android/consumer-rules.pro").read_bytes().strip(),
                    "Consumer rules differ from wrapper")
            classes = out / "classes.jar"
            classes.write_bytes(archive.read("classes.jar"))
        for name, members in {
            "Room": ["loadOrFetchRawEvent", "queuedMessageEvents", "settleConfirmedQueueEvent"],
            "RoomInterface": ["loadOrFetchRawEvent", "queuedMessageEvents", "settleConfirmedQueueEvent"],
            "QueuedMessageEvent": ["getTransactionId", "getEventType", "getContentJson"],
            "ConfirmedQueueEventResult": ["SETTLED", "MISSING", "QUEUE_ENABLED", "IN_FLIGHT",
                                           "CONTENT_MISMATCH", "NOT_AN_EVENT", "DEPENDENT_WORK"],
            "RawTimelineEvent": ["getJson", "getEncryption"],
            "RawEventEncryption": ["NOT_DECRYPTED", "DECRYPTED", "UNABLE_TO_DECRYPT"],
        }.items():
            declaration = subprocess.check_output([str(java / "bin/javap"), "-classpath", str(classes),
                                                   "org.matrix.rustcomponents.sdk." + name], text=True, timeout=30)
            require(all(member in declaration for member in members), "Missing SDK API: " + name)
        with urllib.request.urlopen(distribution["upstreamPom"], timeout=30) as response:
            pom = response.read(128 * 1024)
        require(hashlib.sha256(pom).hexdigest() == distribution["upstreamPomSha256"], "Upstream POM changed")
        descriptor = out / (distribution["module"] + "-" + args.version + ".ivy.xml")
        write_ivy(pom, distribution, args.version, descriptor)
        bundle = out / (distribution["module"] + "-" + args.version + "-sources.tar.gz")
        with tarfile.open(bundle, "w:gz") as archive:
            for name in ["LICENSE", "Cargo.lock"]:
                archive.add(sdk / name, arcname=name)
            for name in ["android-build.json", "android-distribution.json", *manifest["patches"]]:
                archive.add(patches / name, arcname="patches/" + name)
            for name in generated:
                archive.add(main / name, arcname=name)
        license_file = out / "LICENSE"
        shutil.copy2(sdk / "LICENSE", license_file)
        record.update(passed=True, inputsUnchanged=True, classesSha256=sha(classes),
                      aarSha256=sha(aar), aarBytes=aar.stat().st_size, ivySha256=sha(descriptor),
                      upstreamPomSha256=distribution["upstreamPomSha256"], sourcesSha256=sha(bundle),
                      releaseFiles=[p.name for p in [aar, descriptor, bundle, license_file]])
    finally:
        record["elapsedSeconds"] = round(time.monotonic() - started, 3)
        (out / "sdk-build.json").write_text(json.dumps(record, indent=2) + "\n")
    release_files = [out / name for name in record["releaseFiles"]] + [out / "sdk-build.json"]
    (out / "SHA256SUMS").write_text("".join(sha(p) + "  " + p.name + "\n" for p in release_files))
    print(json.dumps({key: record[key] for key in ("version", "passed", "aarSha256", "aarBytes", "elapsedSeconds")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wrapper", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--android-sdk", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    package(parser.parse_args())
