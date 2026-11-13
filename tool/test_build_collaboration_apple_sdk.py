"""Reject incomplete or substituted native evidence before accepting an Apple SDK package."""
import copy
import io
import json
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import xml.etree.ElementTree as ET

from build_collaboration_apple_sdk import (
    PRIVATE_CRATE_TESTS, SLICE_ARCHITECTURES, SWIFT_TESTS, build, contained_file, load_manifest,
    probe_private_symbol_crates, probe_symbol_reader, resolve_symbol_reader, verify_framework, verify_swift_tests,
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
            self.assertEqual("/pinned-rust/bin/llvm-nm", command[0])
            self.assertEqual(["--defined-only", "--extern-only", "--format=just-symbols"], command[1:4])
            self.assertIn(command[4], {"--arch=" + arch for arch in item["SupportedArchitectures"]})
            symbols = self.symbols if not missing_symbol else self.symbols[:-1] + [self.symbols[-1] + "_checksum"]
            return "\n".join("_" + symbol for symbol in symbols)

        with patch("build_collaboration_apple_sdk.subprocess.check_output", side_effect=output):
            return verify_framework(self.root, self.base, Path("/pinned-rust/bin/llvm-nm"))

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


class AppleSymbolReaderTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "rust"
        self.reader = self.root / "lib/rustlib/x86_64-apple-darwin/bin/llvm-nm"
        self.reader.parent.mkdir(parents=True)
        self.reader.write_bytes(b"pinned-reader")
        self.compiler = "rustc 1.96.0 (fixture)\nrelease: 1.96.0\nhost: x86_64-apple-darwin\nLLVM version: 22.1.2\n"
        self.version = "llvm-nm, compatible with GNU nm\nLLVM version 22.1.2-rust-1.96.0-stable\n"
        self.env = {"RUSTUP_TOOLCHAIN": "1.96.0"}
        self.commands = []

    def output(self, command, **kwargs):
        self.commands.append(command)
        self.assertEqual(self.env, kwargs["env"])
        if command == ["rustc", "-vV"]:
            return self.compiler
        if command == ["rustc", "--print", "sysroot"]:
            return str(self.root)
        self.assertEqual([str(self.reader.resolve()), "--version"], command)
        return self.version

    def inspect(self):
        with patch("build_collaboration_apple_sdk.subprocess.check_output", side_effect=self.output):
            return resolve_symbol_reader(self.env, "1.96.0")

    def test_reader_comes_from_exact_rust_host_and_records_matching_llvm(self):
        reader, evidence = self.inspect()
        self.assertEqual(self.reader.resolve(), reader)
        self.assertEqual("22.1.2", evidence["llvmVersion"])
        self.assertEqual("x86_64-apple-darwin", evidence["host"])
        self.assertEqual("1.96.0", evidence["rust"])
        self.assertEqual(64, len(evidence["sha256"]))

    def test_missing_reader_is_rejected_before_any_framework_build(self):
        self.reader.unlink()
        with self.assertRaisesRegex(ValueError, "Install llvm-tools-preview"):
            self.inspect()
        self.assertEqual([["rustc", "-vV"], ["rustc", "--print", "sysroot"]], self.commands)

    def test_old_or_unidentified_llvm_reader_is_rejected(self):
        for version in ("Apple LLVM version 17.0.0", "llvm-nm", "LLVM version 22.1.1"):
            self.version = version
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "LLVM version differs"):
                self.inspect()

    def test_foreign_compiler_host_release_or_version_is_rejected(self):
        original = self.compiler
        for compiler in (original.replace("1.96.0", "1.95.0"),
                         original.replace("x86_64-apple-darwin", "x86_64-unknown-linux-gnu"),
                         original.replace("LLVM version: 22.1.2", "LLVM version: unknown")):
            self.compiler = compiler
            self.commands.clear()
            with self.subTest(compiler=compiler), self.assertRaisesRegex(ValueError, "compiler provenance"):
                self.inspect()
            self.assertEqual([["rustc", "-vV"]], self.commands)

    def test_reader_symlink_cannot_escape_the_compiler_sysroot(self):
        outside = self.root.parent / "unrelated-nm"
        outside.write_bytes(b"unrelated")
        self.reader.unlink()
        self.reader.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "escapes the pinned Rust sysroot"):
            self.inspect()


class AppleSymbolReaderProbeTest(unittest.TestCase):
    def test_real_archive_probe_requires_exact_defined_symbol_from_both_host_architectures(self):
        for host, architecture in (("aarch64-apple-darwin", "arm64"), ("x86_64-apple-darwin", "x86_64")):
            with self.subTest(host=host), tempfile.TemporaryDirectory() as directory:
                output = Path(directory)
                reader = Path("/pinned-rust/bin/llvm-nm")
                env = {"RUSTUP_TOOLCHAIN": "1.96.0"}
                def compile_probe(command, **kwargs):
                    self.assertEqual("rustc", command[0])
                    self.assertEqual(host, command[command.index("--target") + 1])
                    self.assertIn("--crate-type=staticlib", command)
                    self.assertEqual(env, kwargs["env"])
                    Path(command[-1]).write_bytes(b"compiled-host-archive")
                def read(command, **kwargs):
                    self.assertEqual(str(reader), command[0])
                    self.assertEqual(["--defined-only", "--extern-only", "--format=just-symbols"], command[1:4])
                    self.assertEqual("--arch=" + architecture, command[4])
                    self.assertEqual(env, kwargs["env"])
                    return "_sdk8_symbol_reader_probe\n"
                with patch("build_collaboration_apple_sdk.run", side_effect=compile_probe), patch(
                    "build_collaboration_apple_sdk.subprocess.check_output", side_effect=read
                ):
                    result = probe_symbol_reader(reader, {"host": host}, output, env, io.StringIO())
                self.assertTrue(result["passed"])
                self.assertEqual(architecture, result["architecture"])
                self.assertEqual(64, len(result["archiveSha256"]))

    def test_missing_or_unreadable_probe_symbol_never_falls_back_to_apple_nm(self):
        for response in ("_sdk8_symbol_reader_probe_checksum\n", subprocess.CalledProcessError(1, ["llvm-nm"])):
            with self.subTest(response=response), tempfile.TemporaryDirectory() as directory:
                def compile_probe(command, **_):
                    Path(command[-1]).write_bytes(b"compiled-host-archive")
                effect = response if isinstance(response, Exception) else lambda *_args, **_kwargs: response
                with patch("build_collaboration_apple_sdk.run", side_effect=compile_probe), patch(
                    "build_collaboration_apple_sdk.subprocess.check_output", side_effect=effect
                ) as reader, self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    probe_symbol_reader(Path("/pinned-rust/bin/llvm-nm"), {"host": "x86_64-apple-darwin"},
                                        Path(directory), {}, io.StringIO())
                self.assertEqual(1, reader.call_count)


class ApplePrivateCrateProbeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sdk = self.root / "sdk"
        self.output = self.root / "output"
        self.output.mkdir()
        self.source = self.sdk / "xtask/src/swift.rs"
        self.source.parent.mkdir(parents=True)
        methods = "\n".join("#[test] fn " + name + "() {}" for name in sorted(PRIVATE_CRATE_TESTS))
        self.source.write_text("fn crate_of(member: &str) -> &str { member }\n" + methods +
                               "\n/// The path of the built library for a specific target and profile.\n")
        self.env = {"RUSTUP_TOOLCHAIN": "1.96.0"}

    def test_exact_selector_tests_compile_and_execute_before_recording_success(self):
        commands = []
        def run(command, **kwargs):
            commands.append(command)
            self.assertEqual(self.env, kwargs["env"])
            if command[0] == "rustc":
                self.assertIn("--test", command)
                self.assertIn("--edition=2024", command)
                Path(command[-1]).write_bytes(b"compiled-tests")
            else:
                self.assertEqual("--test-threads=1", command[1])
        with patch("build_collaboration_apple_sdk.run", side_effect=run):
            result = probe_private_symbol_crates(self.sdk, self.output, self.env, io.StringIO())
        self.assertEqual(2, len(commands))
        self.assertTrue(result["passed"])
        self.assertEqual(8, result["tests"])
        self.assertEqual(sorted(PRIVATE_CRATE_TESTS), result["methods"])
        self.assertEqual(64, len(result["sourceSha256"]))

    def test_missing_or_duplicate_case_is_rejected_before_compilation(self):
        original = self.source.read_text()
        name = sorted(PRIVATE_CRATE_TESTS)[0]
        second = sorted(PRIVATE_CRATE_TESTS)[1]
        for changed in (original.replace("#[test] fn " + name, "fn " + name),
                        original.replace("fn " + name + "()", "fn " + second + "()")):
            self.source.write_text(changed)
            with self.subTest(changed=changed), patch("build_collaboration_apple_sdk.run") as run, self.assertRaisesRegex(
                ValueError, "Missing or unexpected private crate"
            ):
                probe_private_symbol_crates(self.sdk, self.output, self.env, io.StringIO())
            run.assert_not_called()

    def test_failed_rust_test_never_records_success(self):
        def run(command, **_):
            if command[0] == "rustc":
                Path(command[-1]).write_bytes(b"compiled-tests")
            else:
                raise subprocess.CalledProcessError(101, command)
        with patch("build_collaboration_apple_sdk.run", side_effect=run), self.assertRaises(subprocess.CalledProcessError):
            probe_private_symbol_crates(self.sdk, self.output, self.env, io.StringIO())


class AppleSwiftEvidenceTest(unittest.TestCase):
    def test_missing_failed_skipped_or_duplicate_contract_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "tests.xml"
            root = ET.Element("testsuite")
            for name in sorted(SWIFT_TESTS):
                ET.SubElement(root, "testcase", name=name)
            ET.ElementTree(root).write(report)
            self.assertEqual(5, verify_swift_tests(report)["tests"])
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
