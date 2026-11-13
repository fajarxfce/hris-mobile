"""A retained native artifact must not substitute files or provenance during a Swift recheck."""
import hashlib
import io
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from recheck_collaboration_apple_sdk import (
    ARCHIVE_SHA256, ARTIFACT_ID, SOURCE_COMMIT, SOURCE_JOB, SOURCE_RUN,
    checked_members, recheck, verify_origin,
)


class CompletedNativeArchiveTest(unittest.TestCase):
    def archive(self, members):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for name, body in members:
                archive.writestr(name, body)
        return zipfile.ZipFile(io.BytesIO(buffer.getvalue()))

    def test_exact_recorded_files_pass_with_unselected_diagnostics(self):
        with self.archive([("sdk/Package.swift", b"package"), ("proof/test.swift", b"test"),
                           ("result.json", b"{}")]) as archive:
            expected = {"sdk/Package.swift": hashlib.sha256(b"package").hexdigest(),
                        "proof/test.swift": hashlib.sha256(b"test").hexdigest()}
            self.assertEqual(sorted(expected), checked_members(archive, expected))

    def test_changed_or_extra_package_files_are_rejected(self):
        with self.archive([("sdk/native.a", b"changed")]) as archive:
            with self.assertRaisesRegex(ValueError, "content changed"):
                checked_members(archive, {"sdk/native.a": hashlib.sha256(b"original").hexdigest()})
            with self.assertRaisesRegex(ValueError, "inventory"):
                checked_members(archive, {})

    def test_duplicate_names_cannot_hide_substituted_content(self):
        with self.assertWarns(UserWarning):
            archive = self.archive([("result.json", b"one"), ("result.json", b"two")])
        with archive, self.assertRaisesRegex(ValueError, "Duplicate"):
            checked_members(archive, {})

    def test_parent_absolute_and_backslash_paths_are_rejected_before_extraction(self):
        for name in ["../outside", "/outside", "sdk/../../outside", "sdk\\outside"]:
            with self.subTest(name=name), self.archive([(name, b"bad")]) as archive:
                with self.assertRaisesRegex(ValueError, "escapes"):
                    checked_members(archive, {})

    def test_links_and_special_files_are_rejected(self):
        for kind in [stat.S_IFLNK, stat.S_IFIFO]:
            info = zipfile.ZipInfo("sdk/link")
            info.create_system = 3
            info.external_attr = (kind | 0o777) << 16
            with self.subTest(kind=kind), self.archive([(info, b"outside")]) as archive:
                with self.assertRaisesRegex(ValueError, "link or special"):
                    checked_members(archive, {})

    def test_expanded_size_is_bounded_before_reading_members(self):
        with self.archive([("result.json", b"small")]) as archive:
            archive.infolist()[0].file_size = 4 * 1024**3 + 1
            with self.assertRaisesRegex(ValueError, "expanded size"):
                checked_members(archive, {})


class CompletedNativeOriginTest(unittest.TestCase):
    def setUp(self):
        self.run = {"headSha": SOURCE_COMMIT, "status": "completed", "conclusion": "failure",
                    "jobs": [{"databaseId": SOURCE_JOB, "conclusion": "failure"}]}
        self.artifact = {"id": ARTIFACT_ID, "expired": False, "name": "sdk8-apple-native-candidate",
                         "workflow_run": {"id": SOURCE_RUN, "head_sha": SOURCE_COMMIT},
                         "digest": "sha256:" + ARCHIVE_SHA256}

    def test_only_the_exact_completed_source_run_is_accepted(self):
        verify_origin(self.run, self.artifact)
        for key, value in [("headSha", "unrelated"), ("status", "in_progress"), ("conclusion", "success"),
                           ("jobs", [{"databaseId": SOURCE_JOB + 1, "conclusion": "failure"}])]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_origin(self.run | {key: value}, self.artifact)

    def test_artifact_identity_digest_and_expiration_are_verified(self):
        for key, value in [("id", ARTIFACT_ID + 1), ("expired", True), ("name", "other"),
                           ("digest", "sha256:" + "0" * 64),
                           ("workflow_run", {"id": SOURCE_RUN + 1, "head_sha": SOURCE_COMMIT}),
                           ("workflow_run", {"id": SOURCE_RUN, "head_sha": "unrelated"})]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_origin(self.run, self.artifact | {key: value})

    def test_non_macos_recheck_creates_no_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            with patch("recheck_collaboration_apple_sdk.platform.system", return_value="Linux"):
                with self.assertRaisesRegex(ValueError, "requires macOS"):
                    recheck(output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
