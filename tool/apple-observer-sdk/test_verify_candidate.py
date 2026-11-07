"""Acceptance guards for reusing an immutable native Apple candidate."""
import hashlib
import json
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile

from verify_candidate import sha, verify


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        config_path = Path(__file__).parent / "build.json"
        config = json.loads(config_path.read_text())
        base_path = config_path.parent.parent / "collaboration-sdk-patches" / config["baseManifest"]
        base = json.loads(base_path.read_text())
        self.record = {
            "passed": True, "sourcesUnchanged": True, "swiftCompiled": True,
            "objectOwnerTestsPassed": True, "manifestSha256": sha(config_path),
            "baseManifestSha256": sha(base_path), "upstream": base["upstream"],
            "targets": config["targets"],
            "profile": config["profile"], "rust": base["rust"],
            "sourceSha256": {**base["patchedSources"], **config["patchedSources"]},
            "patches": {**base["patches"], config["patch"]: config["patchSha256"]},
            "generated": {"synthetic.swift": hashlib.sha256(b"fixture").hexdigest()},
            "cargoLockSha256": hashlib.sha256(b"lock").hexdigest(),
        }

    def archive(self, extra=None, generated=b"fixture"):
        path = self.root / "candidate.zip"
        with zipfile.ZipFile(path, "w") as bundle:
            bundle.writestr("result.json", json.dumps(self.record))
            bundle.writestr("bindings/apple/generated/synthetic.swift", generated)
            bundle.writestr("Cargo.lock", b"lock")
            if extra:
                bundle.writestr(extra, b"invalid")
        return path

    def test_matching_source_and_generated_hashes_are_required(self):
        archive = self.archive()
        result = verify(archive, sha(archive), self.root / "sdk")
        self.assertEqual(1, result["generatedFilesVerified"])
        self.assertFalse(result["nativeRecompiled"])

    def test_digest_mismatch_never_creates_destination(self):
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            verify(self.archive(), "0" * 64, self.root / "sdk")
        self.assertFalse((self.root / "sdk").exists())

    def test_source_mismatch_never_extracts(self):
        self.record["sourceSha256"]["Cargo.lock"] = "0" * 64
        archive = self.archive()
        with self.assertRaisesRegex(ValueError, "source mismatch"):
            verify(archive, sha(archive), self.root / "sdk")
        self.assertFalse((self.root / "sdk").exists())

    def test_changed_generated_bytes_are_rejected(self):
        archive = self.archive(generated=b"changed")
        with self.assertRaisesRegex(ValueError, "file mismatch"):
            verify(archive, sha(archive), self.root / "sdk")

    def test_parent_path_cannot_write_outside_destination(self):
        archive = self.archive(extra="../escaped")
        with self.assertRaisesRegex(ValueError, "archive entry"):
            verify(archive, sha(archive), self.root / "sdk")
        self.assertFalse((self.root / "escaped").exists())

    def test_symlink_entry_is_rejected(self):
        entry = zipfile.ZipInfo("link")
        entry.create_system = 3
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive = self.archive(extra=entry)
        with self.assertRaisesRegex(ValueError, "archive entry"):
            verify(archive, sha(archive), self.root / "sdk")

    def test_existing_destination_is_not_overwritten(self):
        target = self.root / "sdk"
        target.mkdir()
        (target / "retained").write_text("original")
        archive = self.archive()
        with self.assertRaises(FileExistsError):
            verify(archive, sha(archive), target)
        self.assertEqual("original", (target / "retained").read_text())


if __name__ == "__main__":
    unittest.main()
