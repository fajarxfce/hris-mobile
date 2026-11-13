"""A consumer must retain the tested package bytes, paths, license and publication boundary."""
import hashlib
import io
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile

from prepare_collaboration_apple_sdk import extract_verified, prepare, validate_archive


class AppleConsumerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.files = {"Package.swift": b"fixture", "LICENSE": b"license",
                      "bindings/swift/test.swift": b"fictional code"}
        self.records = {n: hashlib.sha256(body).hexdigest() for n, body in self.files.items()}
        self.expanded = sum(map(len, self.files.values()))

    def archive(self, files=None, mode=stat.S_IFREG | 0o644):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            for name, body in (self.files if files is None else files).items():
                entry = zipfile.ZipInfo(name)
                entry.create_system = 3
                entry.external_attr = mode << 16
                archive.writestr(entry, body)
        return zipfile.ZipFile(io.BytesIO(data.getvalue()))

    def test_exact_files_are_prepared_without_logs_or_metadata(self):
        output = self.root / "sdk"
        with self.archive() as archive:
            extract_verified(archive, output, self.records, self.expanded)
        files = {str(p.relative_to(output)): p.read_bytes() for p in output.rglob("*") if p.is_file()}
        self.assertEqual(self.files, files)

    def test_changed_file_is_rejected_before_creating_output(self):
        with self.archive(self.files | {"LICENSE": b"changed"}) as archive:
            with self.assertRaisesRegex(ValueError, "content differs"):
                extract_verified(archive, self.root / "sdk", self.records, self.expanded)
        self.assertFalse((self.root / "sdk").exists())

    def test_extra_missing_or_duplicate_member_is_rejected(self):
        for files in [self.files | {"extra.swift": b"extra"}, {"Package.swift": b"fixture"}]:
            with self.archive(files) as archive, self.assertRaisesRegex(ValueError, "inventory"):
                validate_archive(archive, self.records, self.expanded)
        with self.archive() as original:
            data = io.BytesIO()
            with zipfile.ZipFile(data, "w") as target:
                for entry in original.infolist():
                    target.writestr(entry, original.read(entry))
                with self.assertWarns(UserWarning):
                    target.writestr(original.getinfo("LICENSE"), b"license")
            with zipfile.ZipFile(io.BytesIO(data.getvalue())) as archive:
                with self.assertRaisesRegex(ValueError, "inventory"):
                    validate_archive(archive, self.records, self.expanded)

    def test_links_and_special_files_are_never_extracted(self):
        for mode in [stat.S_IFLNK | 0o777, stat.S_IFIFO | 0o600, stat.S_IFDIR | 0o755]:
            with self.subTest(mode=mode), self.archive(mode=mode) as archive:
                with self.assertRaisesRegex(ValueError, "non-file"):
                    extract_verified(archive, self.root / "sdk", self.records, self.expanded)
                self.assertFalse((self.root / "sdk").exists())

    def test_even_recorded_noncanonical_paths_are_rejected(self):
        for name in ["../escape", "/absolute", "bindings//double", "./relative", "bindings\\bad"]:
            files = self.files | {name: b"invalid"}
            records = {n: hashlib.sha256(body).hexdigest() for n, body in files.items()}
            with self.subTest(name=name), self.archive(files) as archive:
                with self.assertRaisesRegex(ValueError, "redirected"):
                    validate_archive(archive, records, sum(map(len, files.values())))

    def test_expanded_size_and_license_are_part_of_acceptance(self):
        with self.archive() as archive:
            for size in [0, self.expanded + 1, 2 * 1024**3 + 1]:
                with self.subTest(size=size), self.assertRaisesRegex(ValueError, "size"):
                    validate_archive(archive, self.records, size)
        files = {"Package.swift": b"fixture"}
        with self.archive(files) as archive:
            with self.assertRaisesRegex(ValueError, "license"):
                validate_archive(archive, {"Package.swift": self.records["Package.swift"]}, 7)

    def test_existing_package_is_preserved(self):
        output = self.root / "sdk"
        output.mkdir()
        (output / "retained").write_bytes(b"keep")
        with self.archive() as archive, self.assertRaises(FileExistsError):
            extract_verified(archive, output, self.records, self.expanded)
        self.assertEqual(["retained"], [p.name for p in output.iterdir()])
        self.assertEqual(b"keep", (output / "retained").read_bytes())

    def test_unaccepted_archive_never_becomes_a_consumer_dependency(self):
        source = self.root / "unaccepted.zip"
        source.write_bytes(b"unaccepted")
        with self.assertRaisesRegex(ValueError, "not the accepted"):
            prepare(source, self.root / "sdk")
        self.assertFalse((self.root / "sdk").exists())


if __name__ == "__main__":
    unittest.main()
