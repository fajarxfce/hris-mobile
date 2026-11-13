"""Keep native provenance, test gates and runtime-only packaging intact."""
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from build_collaboration_apple_sdk import SWIFT_TESTS, validate_swift_test_report
from package_collaboration_apple_sdk import package, validate_candidate, write_runtime_package
from recheck_collaboration_apple_sdk import ARCHIVE_SHA256, ARTIFACT_ID, SOURCE_COMMIT, SOURCE_JOB, SOURCE_RUN


class AppleDistributionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.files = {"sdk/Package.swift": b"fixture package", "sdk/LICENSE": b"license",
                      "sdk/bindings/apple/generated/native.a": b"fictional native" * 100,
                      "proof/contract.swift": b"fictional test body"}
        self.distribution = {
            "schema": 1, "version": "26.10.23-hris.8", "appleManifestSha256": "manifest",
            "nativeTests": 5, "nativeSourceRun": SOURCE_RUN, "nativeSourceCommit": SOURCE_COMMIT,
            "packageFiles": {n.removeprefix("sdk/"): hashlib.sha256(b).hexdigest()
                             for n, b in self.files.items() if n.startswith("sdk/")},
            "proofFiles": {"contract.swift": hashlib.sha256(self.files["proof/contract.swift"]).hexdigest()},
            "slices": [{"platform": "fictional-test-slice"}],
        }
        suite = ET.Element("testsuite")
        for name in sorted(SWIFT_TESTS):
            ET.SubElement(suite, "testcase", name=name)
        self.report = ET.tostring(suite)
        self.result = {
            "passed": True, "candidateOnly": True, "published": False, "nativeRebuilt": False,
            "sourcesUnchanged": True, "iosDeviceLinked": True, "iosSimulatorLinked": True,
            "version": "26.10.23-hris.8", "sourceRun": SOURCE_RUN, "sourceCommit": SOURCE_COMMIT,
            "completedBuild": {"manifestSha256": "manifest"},
            "fixtureCleanup": {"afterSwiftProcessExit": True, "removed": True},
            "nativeTests": validate_swift_test_report(self.report),
            **{k: self.distribution[k] for k in ("packageFiles", "proofFiles", "slices")},
        }
        self.origin = {
            "run": {"headSha": SOURCE_COMMIT, "status": "completed", "conclusion": "failure",
                    "jobs": [{"databaseId": SOURCE_JOB, "conclusion": "failure"}]},
            "artifact": {"id": ARTIFACT_ID, "expired": False, "name": "sdk8-apple-native-candidate",
                         "workflow_run": {"id": SOURCE_RUN, "head_sha": SOURCE_COMMIT},
                         "digest": "sha256:" + ARCHIVE_SHA256},
        }

    def archive(self, result=None, report=None, files=None, origin=None):
        body = io.BytesIO()
        with zipfile.ZipFile(body, "w") as archive:
            for name, content in (self.files if files is None else files).items():
                archive.writestr(name, content)
            archive.writestr("result.json", json.dumps(self.result if result is None else result))
            archive.writestr("origin.json", json.dumps(self.origin if origin is None else origin))
            archive.writestr("swift-tests.xml", self.report if report is None else report)
            archive.writestr("build.log", "not part of runtime distribution")
        return zipfile.ZipFile(io.BytesIO(body.getvalue()))

    def test_exact_candidate_keeps_real_report_and_all_recorded_gates(self):
        with self.archive() as archive:
            accepted = validate_candidate(archive, self.distribution, "manifest")
        self.assertEqual(self.result, accepted)

    def test_missing_native_gate_or_changed_provenance_is_rejected(self):
        changed = {"passed": False, "candidateOnly": False, "published": True,
                   "nativeRebuilt": True, "sourcesUnchanged": False, "iosDeviceLinked": False,
                   "iosSimulatorLinked": False, "version": "different", "sourceRun": SOURCE_RUN + 1,
                   "sourceCommit": "0" * 40, "slices": [], "packageFiles": {}, "proofFiles": {},
                   "completedBuild": {"manifestSha256": "other"},
                   "fixtureCleanup": {"afterSwiftProcessExit": True, "removed": False}}
        for name, value in changed.items():
            with self.subTest(name=name), self.archive(result=self.result | {name: value}) as archive:
                with self.assertRaises(ValueError):
                    validate_candidate(archive, self.distribution, "manifest")

    def test_changed_runtime_or_proof_bytes_are_rejected_before_packaging(self):
        for name in ["sdk/bindings/apple/generated/native.a", "proof/contract.swift"]:
            with self.subTest(name=name), self.archive(files=self.files | {name: b"changed"}) as archive:
                with self.assertRaisesRegex(ValueError, "content changed"):
                    validate_candidate(archive, self.distribution, "manifest")

    def test_xml_failure_skip_or_missing_case_cannot_be_replaced_by_success_flag(self):
        for mutation in ["failure", "skipped", "missing"]:
            tree = ET.fromstring(self.report)
            if mutation == "missing":
                tree.remove(tree[0])
            else:
                ET.SubElement(tree[0], mutation)
            with self.subTest(mutation=mutation), self.archive(report=ET.tostring(tree)) as archive:
                with self.assertRaises(ValueError):
                    validate_candidate(archive, self.distribution, "manifest")

    def test_different_successful_report_bytes_still_require_the_original_digest(self):
        with self.archive(report=self.report + b"\n") as archive:
            with self.assertRaisesRegex(ValueError, "differs from its accepted record"):
                validate_candidate(archive, self.distribution, "manifest")

    def test_completed_native_origin_is_not_substitutable(self):
        origin = copy.deepcopy(self.origin)
        origin["artifact"]["workflow_run"]["head_sha"] = "0" * 40
        with self.archive(origin=origin) as archive:
            with self.assertRaises(ValueError):
                validate_candidate(archive, self.distribution, "manifest")

    def test_distribution_manifest_and_required_license_are_enforced(self):
        for field, value in [("schema", 2), ("version", "other"), ("nativeTests", 4),
                             ("appleManifestSha256", "other")]:
            with self.subTest(field=field), self.archive() as archive:
                with self.assertRaises(ValueError):
                    validate_candidate(archive, self.distribution | {field: value}, "manifest")
        distribution = copy.deepcopy(self.distribution)
        del distribution["packageFiles"]["LICENSE"]
        files = {k: v for k, v in self.files.items() if k != "sdk/LICENSE"}
        result = self.result | {"packageFiles": distribution["packageFiles"]}
        with self.archive(result=result, files=files) as archive:
            with self.assertRaisesRegex(ValueError, "license"):
                validate_candidate(archive, distribution, "manifest")

    def test_distribution_is_deterministic_and_contains_only_runtime_files(self):
        first, second = self.root / "first.zip", self.root / "second.zip"
        with self.archive() as archive:
            validate_candidate(archive, self.distribution, "manifest")
            write_runtime_package(archive, self.distribution["packageFiles"], first)
            write_runtime_package(archive, self.distribution["packageFiles"], second)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as bundle:
            self.assertEqual(set(self.distribution["packageFiles"]), set(bundle.namelist()))
            for item in bundle.infolist():
                self.assertEqual((1980, 1, 1, 0, 0, 0), item.date_time)
                self.assertEqual(0o100644, item.external_attr >> 16)
                self.assertEqual(self.files["sdk/" + item.filename], bundle.read(item))

    def test_existing_distribution_is_never_overwritten(self):
        destination = self.root / "retained.zip"
        destination.write_bytes(b"keep")
        with self.archive() as archive:
            with self.assertRaises(FileExistsError):
                write_runtime_package(archive, self.distribution["packageFiles"], destination)
        self.assertEqual(b"keep", destination.read_bytes())

    def test_unaccepted_archive_creates_no_output(self):
        source = self.root / "changed.zip"
        source.write_bytes(b"unaccepted")
        with self.assertRaisesRegex(ValueError, "accepted GitHub artifact"):
            package(source, self.root / "output")
        self.assertFalse((self.root / "output").exists())


if __name__ == "__main__":
    unittest.main()
