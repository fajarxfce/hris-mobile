"""The SDK build driver owns and reaps its subprocess when a build times out."""
import os
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from build_collaboration_sdk import build, run, verify_native_library


class SdkBuildProcessTest(unittest.TestCase):
    def test_timeout_reaps_the_owned_process(self):
        owned = []
        spawn = subprocess.Popen

        def capture(*args, **kwargs):
            process = spawn(*args, **kwargs)
            owned.append(process)
            return process

        with tempfile.TemporaryFile() as log, patch("build_collaboration_sdk.subprocess.Popen", side_effect=capture):
            with self.assertRaises(subprocess.TimeoutExpired):
                run([sys.executable, "-c", "import time; time.sleep(30)"],
                    cwd=Path.cwd(), env=os.environ.copy(), log=log, timeout=0.1)
        self.assertEqual(1, len(owned))
        self.assertIsNotNone(owned[0].returncode)
        with self.assertRaises(ProcessLookupError):
            os.kill(owned[0].pid, 0)

    def test_failed_build_preserves_its_exit_code(self):
        with tempfile.TemporaryFile() as log, self.assertRaises(subprocess.CalledProcessError) as failed:
            run([sys.executable, "-c", "raise SystemExit(7)"],
                cwd=Path.cwd(), env=os.environ.copy(), log=log, timeout=5)
        self.assertEqual(7, failed.exception.returncode)


class SdkBuildEnvironmentTest(unittest.TestCase):
    def test_ambient_flags_cannot_bypass_the_pinned_cpu_baseline(self):
        manifest = json.loads((Path(__file__).parent / "collaboration-sdk-patches/android-build.json").read_text())
        for name in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                sdk = root / "sdk"
                sdk.mkdir()
                args = SimpleNamespace(sdk=sdk, output=root / "output")
                with patch.dict(os.environ, {name: "-C target-feature=+sha2"}), patch(
                    "build_collaboration_sdk.subprocess.check_output",
                    side_effect=[manifest["upstream"], ""],
                ), self.assertRaisesRegex(ValueError, "ambient Rust flags"):
                    build(args)


class SdkNativeSymbolsTest(unittest.TestCase):
    def test_observer_requires_exact_stop_reader_and_client_symbols(self):
        existing = ["uniffi_matrix_sdk_ffi_fn_method_room_" + name for name in
                    ["load_or_fetch_raw_event", "queued_message_events", "settle_confirmed_queue_event"]]
        required = ["client_observe_http_requests", "httpobservationsubscription_next_batch",
                    "httpobservationsubscription_stop"]
        methods = ["uniffi_matrix_sdk_ffi_fn_method_" + name for name in required]
        with tempfile.TemporaryDirectory() as directory:
            native = Path(directory) / "candidate.so"
            native.write_bytes(b"fixture")
            for missing in range(len(methods)):
                incomplete = methods[:missing] + methods[missing + 1:] + [methods[missing] + "_checksum"]
                with self.subTest(missing=required[missing]), patch(
                    "build_collaboration_sdk.subprocess.check_output", side_effect=[
                        "ELF64 AArch64", "LOAD fixture 0x4000\nLOAD fixture 0x4000",
                        "\n".join(existing + incomplete + ["uniffi_matrix_sdk_ffi_fn_method_httpobservationsubscription_close"]),
                    ],
                ), self.assertRaisesRegex(ValueError, "Missing SDK method"):
                    verify_native_library(native, "arm64-v8a", Path(directory), required_methods=required)
            with patch("build_collaboration_sdk.subprocess.check_output", side_effect=[
                "ELF64 AArch64", "LOAD fixture 0x4000\nLOAD fixture 0x4000", "\n".join(existing + methods),
            ]):
                self.assertTrue(verify_native_library(native, "arm64-v8a", Path(directory),
                                                      required_methods=required)["requiredSymbolsPresent"])

    def test_candidate_requires_its_exact_function_and_all_existing_recovery_methods(self):
        methods = ["load_or_fetch_raw_event", "queued_message_events", "settle_confirmed_queue_event"]
        existing = ["uniffi_matrix_sdk_ffi_fn_method_room_" + name for name in methods]
        function = "uniffi_matrix_sdk_ffi_fn_func_read_existing_crypto_identity"
        cases = [existing, existing + [function + "_checksum"], existing[1:] + [function]]
        with tempfile.TemporaryDirectory() as directory:
            native = Path(directory) / "candidate.so"
            native.write_bytes(b"fixture")
            for symbols in cases:
                with self.subTest(symbols=symbols), patch(
                    "build_collaboration_sdk.subprocess.check_output",
                    side_effect=["ELF64 AArch64", "LOAD fixture 0x4000\nLOAD fixture 0x4000",
                                 "\n".join(symbols)],
                ), self.assertRaises(ValueError):
                    verify_native_library(native, "arm64-v8a", Path(directory),
                                          ["read_existing_crypto_identity"])
            with patch("build_collaboration_sdk.subprocess.check_output", side_effect=[
                "ELF64 AArch64", "LOAD fixture 0x4000\nLOAD fixture 0x4000",
                "\n".join(existing + [function]),
            ]):
                self.assertTrue(verify_native_library(
                    native, "arm64-v8a", Path(directory), ["read_existing_crypto_identity"]
                )["requiredSymbolsPresent"])


if __name__ == "__main__":
    unittest.main()
