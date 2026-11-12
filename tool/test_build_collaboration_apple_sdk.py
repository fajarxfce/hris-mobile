"""Reject incomplete or substituted native evidence before accepting an Apple SDK package."""
import copy
import json
import os
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import xml.etree.ElementTree as ET

from build_collaboration_apple_sdk import (
    SLICE_ARCHITECTURES, SWIFT_TESTS, build, contained_file, load_manifest, verify_framework, verify_swift_tests,
)


class AppleSDKManifestTest(unittest.TestCase):
    def test_exact_sdk8_patch_chain_and_build_only_extension(self):
        manifest, base = load_manifest(Path(__file__).parent / "collaboration-sdk-patches")
        self.assertEqual("26.10.23-hris.8", manifest["version"])
        self.assertEqual(9, len(base["patches"]))
        self.assertEqual("xtask/src/swift.rs", manifest["builderSource"])
        self.assertNotIn(manifest["builderSource"], base["patchedSources"])
        self.assertEqual(5, len(set(manifest["targets"])))

    def test_changed_android_manifest_is_rejected_before_build(self):
        patches = Path(__file__).parent / "collaboration-sdk-patches"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = json.loads((patches / "apple-incoming-hints-build.json").read_text())
            (root / "apple-incoming-hints-build.json").write_text(json.dumps(manifest))
            (root / manifest["baseManifest"]).write_text("{}")
            with self.assertRaisesRegex(ValueError, "source manifest changed"):
                load_manifest(root)

    def test_dirty_or_wrong_checkout_is_rejected_without_mutation(self):
        _, base = load_manifest(Path(__file__).parent / "collaboration-sdk-patches")
        for revision, status in ((base["upstream"], " M retained.rs"), ("unrelated", "")):
            with self.subTest(revision=revision), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                sdk = root / "sdk"
                sdk.mkdir()
                output = root / "output"
                with patch("build_collaboration_apple_sdk.platform.system", return_value="Darwin"), patch(
                    "build_collaboration_apple_sdk.subprocess.check_output", side_effect=[revision, status]
                ), self.assertRaisesRegex(ValueError, "fresh, clean checkout"):
                    build(SimpleNamespace(sdk=sdk, output=output, bootstrap=False))
                self.assertFalse(output.exists())
                self.assertEqual([], list(sdk.iterdir()))

    def test_ambient_compiler_flags_cannot_override_verified_inputs(self):
        _, base = load_manifest(Path(__file__).parent / "collaboration-sdk-patches")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sdk = root / "sdk"
            sdk.mkdir()
            for name in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_TARGET_AARCH64_APPLE_IOS_RUSTFLAGS"):
                with self.subTest(name=name), patch.dict(os.environ, {name: "-Ctarget-cpu=native"}), patch(
                    "build_collaboration_apple_sdk.platform.system", return_value="Darwin"
                ), patch("build_collaboration_apple_sdk.subprocess.check_output", side_effect=[base["upstream"], ""]), self.assertRaisesRegex(
                    ValueError, "Ambient Rust flags"
                ):
                    build(SimpleNamespace(sdk=sdk, output=root / "output", bootstrap=False))
                self.assertFalse((root / "output").exists())


class AppleNativeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "sdk.xcframework"
        self.root.mkdir()
        self.base = {"requiredFunctions": ["read_existing_crypto_identity"],
                     "requiredMethods": ["client_stored_sync_position_v2", "encryption_subscribe_to_room_key_changes"]}
        self.entries = []
        self.symbols = ["uniffi_matrix_sdk_ffi_fn_method_room_" + name for name in
                        ("load_or_fetch_raw_event", "queued_message_events", "settle_confirmed_queue_event")]
        self.symbols += ["uniffi_matrix_sdk_ffi_fn_func_" + name for name in self.base["requiredFunctions"]]
        self.symbols += ["uniffi_matrix_sdk_ffi_fn_method_" + name for name in self.base["requiredMethods"]]
        for (platform, variant), architectures in SLICE_ARCHITECTURES.items():
            name = platform + ("-" + variant if variant else "")
            (self.root / name).mkdir()
            (self.root / name / "native.a").write_bytes(b"fixture")
            self.entries.append({"SupportedPlatform": platform, "SupportedPlatformVariant": variant,
                                 "SupportedArchitectures": sorted(architectures), "LibraryIdentifier": name,
                                 "LibraryPath": "native.a"})

    def inspect(self, entries=None, missing_symbol=False, wrong_architecture=False):
        (self.root / "Info.plist").write_bytes(plistlib.dumps({"AvailableLibraries": entries or self.entries}))

        def output(command, **_):
            name = Path(command[-1]).parent.name
            item = next(item for item in self.entries if item["LibraryIdentifier"] == name)
            if command[1] == "lipo":
                return "armv7" if wrong_architecture else " ".join(item["SupportedArchitectures"])
            symbols = self.symbols if not missing_symbol else self.symbols[:-1] + [self.symbols[-1] + "_checksum"]
            return "\n".join("000000 T _" + symbol for symbol in symbols)

        with patch("build_collaboration_apple_sdk.subprocess.check_output", side_effect=output):
            return verify_framework(self.root, self.base)

    def test_all_five_architectures_require_exact_entry_points(self):
        self.assertEqual(5, sum(len(item["architectures"]) for item in self.inspect()))
        with self.assertRaisesRegex(ValueError, "entry point missing"):
            self.inspect(missing_symbol=True)

    def test_missing_or_duplicate_platform_is_rejected(self):
        for entries in (self.entries[:2], self.entries + [self.entries[0]]):
            with self.subTest(entries=len(entries)), self.assertRaises(ValueError):
                self.inspect(entries)

    def test_actual_binary_architecture_must_match_declaration(self):
        with self.assertRaisesRegex(ValueError, "Native architectures differ"):
            self.inspect(wrong_architecture=True)

    def test_declared_architecture_cannot_omit_an_intel_slice(self):
        entries = copy.deepcopy(self.entries)
        entries[1]["SupportedArchitectures"] = ["arm64"]
        with self.assertRaisesRegex(ValueError, "Incomplete declared"):
            self.inspect(entries)

    def test_parent_paths_and_symlinks_cannot_substitute_an_external_binary(self):
        outside = self.root.parent / "external.a"
        outside.write_bytes(b"external")
        (self.root / "link.a").symlink_to(outside)
        for relative in ("../external.a", "link.a"):
            with self.subTest(relative=relative), self.assertRaisesRegex(ValueError, "escapes"):
                contained_file(self.root, relative)


class AppleSwiftEvidenceTest(unittest.TestCase):
    def test_missing_failed_skipped_or_duplicate_contract_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "tests.xml"
            root = ET.Element("testsuite")
            for name in sorted(SWIFT_TESTS):
                ET.SubElement(root, "testcase", name=name)
            ET.ElementTree(root).write(report)
            self.assertEqual(4, verify_swift_tests(report)["tests"])
            for tag in ("failure", "error", "skipped"):
                failed = copy.deepcopy(root)
                ET.SubElement(failed[0], tag)
                ET.ElementTree(failed).write(report)
                with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "failed or was skipped"):
                    verify_swift_tests(report)
            for altered in (list(root)[:-1], list(root)[:-1] + [root[0]]):
                changed = ET.Element("testsuite")
                changed.extend(altered)
                ET.ElementTree(changed).write(report)
                with self.assertRaisesRegex(ValueError, "Missing or unexpected"):
                    verify_swift_tests(report)


if __name__ == "__main__":
    unittest.main()
