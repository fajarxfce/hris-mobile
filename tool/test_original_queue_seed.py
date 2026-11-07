import argparse
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
from urllib.parse import quote
import zipfile

import check_space_queue_adoption as proof
from original_queue_seed import OriginalQueueSeed, ROOM, USER, seed_route_name


class OriginalQueueSeedTest(unittest.TestCase):
    def test_owned_account_data_is_absent_without_exposing_its_identifiers(self):
        with OriginalQueueSeed() as seed:
            path = "_matrix/client/v3/user/" + quote(USER, safe="") + "/account_data/m.secret_storage.default_key"
            with self.assertRaises(urllib.error.HTTPError) as failed:
                urllib.request.urlopen(seed.base_url + path, timeout=5)
            try:
                self.assertEqual(404, failed.exception.code)
                self.assertEqual("M_NOT_FOUND", json.load(failed.exception)["errcode"])
            finally:
                failed.exception.close()
            self.assertEqual({"GET /_matrix/client/v3/user/*/account_data/*": 1}, seed.counts)
            self.assertEqual({}, seed.unexpected_routes)

    def test_account_data_writes_other_users_and_malformed_types_are_rejected(self):
        with OriginalQueueSeed() as seed:
            own = "user/" + quote(USER, safe="") + "/account_data/"
            for method, path in [("PUT", own + "m.secret_storage.default_key"),
                                 ("GET", "user/other/account_data/m.secret_storage.default_key"),
                                 ("GET", own + "malformed%2Fevent")]:
                request = urllib.request.Request(seed.base_url + "_matrix/client/v3/" + path,
                    data=b"{}" if method == "PUT" else None, method=method)
                with self.assertRaises(urllib.error.HTTPError) as failed:
                    urllib.request.urlopen(request, timeout=5)
                try:
                    self.assertEqual(400, failed.exception.code)
                finally:
                    failed.exception.close()
            self.assertEqual({"unexpected": 3}, seed.counts)

    def test_route_diagnostics_drop_identifiers_and_query_values(self):
        value = seed_route_name("PUT", "/_matrix/client/v3/rooms/%21private%3Aexample/send/m.room.encrypted/private-transaction?access_token=secret")
        self.assertEqual("PUT /_matrix/client/v3/rooms/*/send/m.room.encrypted/*", value)
        self.assertNotIn("private", value)
        self.assertNotIn("secret", value)
        self.assertLessEqual(len(seed_route_name("private-method", "/rooms" * 200)), 256)

    def test_rejected_routes_record_only_bounded_endpoint_categories(self):
        with OriginalQueueSeed() as seed:
            request = urllib.request.Request(seed.base_url + "_matrix/client/v3/rooms/private-room/send/m.room.encrypted/private-transaction", data=b"{}", method="PUT")
            with self.assertRaises(urllib.error.HTTPError) as failed:
                urllib.request.urlopen(request, timeout=5)
            failed.exception.close()
            self.assertEqual({"PUT /_matrix/client/v3/rooms/*/send/m.room.encrypted/*": 1}, seed.unexpected_routes)

    def test_sync_seeds_only_one_owned_encrypted_room_without_messages(self):
        with OriginalQueueSeed() as seed:
            with urllib.request.urlopen(seed.base_url + "_matrix/client/v3/sync?timeout=1000", timeout=5) as response:
                value = json.load(response)
            self.assertEqual([ROOM], list(value["rooms"]["join"]))
            room = value["rooms"]["join"][ROOM]
            self.assertEqual([], room["timeline"]["events"])
            members = [v for v in room["state"]["events"] if v["type"] == "m.room.member"]
            self.assertEqual([USER], [v["state_key"] for v in members])
            encrypted = [v for v in room["state"]["events"] if v["type"] == "m.room.encryption"]
            self.assertEqual("m.megolm.v1.aes-sha2", encrypted[0]["content"]["algorithm"])

    def test_send_and_join_requests_never_receive_success(self):
        with OriginalQueueSeed() as seed:
            for method, path in [("PUT", "rooms/owned/send/m.room.encrypted/transaction"),
                                 ("POST", "join/owned")]:
                request = urllib.request.Request(seed.base_url + "_matrix/client/v3/" + path,
                                                 data=b"{}", method=method)
                with self.assertRaises(urllib.error.HTTPError) as failed:
                    urllib.request.urlopen(request, timeout=5)
                try:
                    self.assertEqual(400, failed.exception.code)
                finally:
                    failed.exception.close()
            self.assertEqual(2, seed.counts["unexpected"])

    def test_oversized_key_upload_is_rejected_without_waiting_for_its_body(self):
        with OriginalQueueSeed() as seed:
            request = urllib.request.Request(seed.base_url + "_matrix/client/v3/keys/upload",
                                             data=b"{}", headers={"Content-Length": "524289"})
            with self.assertRaises(urllib.error.HTTPError) as failed:
                urllib.request.urlopen(request, timeout=5)
            try:
                self.assertEqual(400, failed.exception.code)
            finally:
                failed.exception.close()

    def test_shutdown_releases_listener_and_same_instance_cannot_restart(self):
        seed = OriginalQueueSeed()
        with seed:
            port = seed.port
            self.assertGreaterEqual(port, 1024)
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", port), timeout=1)
        with self.assertRaises(RuntimeError):
            seed.__enter__()
        seed.__exit__(None, None, None)


class OriginalQueueDriverOwnershipTest(unittest.TestCase):
    def test_phone_is_rejected_before_apk_or_adb_access(self):
        args = argparse.Namespace(serial="10.77.77.3:5555")
        with patch.object(proof, "validate_apk") as validate:
            with self.assertRaises(ValueError):
                proof.run(args)
            validate.assert_not_called()

    def test_product_apk_is_rejected_before_zip_or_device_access(self):
        with patch.object(proof.subprocess, "check_output", return_value="package: name='dev.fajar.hris.mobile.dev'\napplication-debuggable"):
            with self.assertRaises(ValueError):
                proof.validate_apk(Path("not-read.apk"), "x86_64", "aapt2")

    def test_wrong_native_library_is_rejected_even_with_valid_fixture_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "wrong.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/x86_64/libmatrix_sdk_ffi.so", b"not-the-immutable-sdk")
            with patch.object(proof.subprocess, "check_output", return_value=f"package: name='{proof.PACKAGE}' versionCode='1'\napplication-debuggable"):
                with self.assertRaisesRegex(ValueError, "immutable SDK4"):
                    proof.validate_apk(apk, "x86_64", "aapt2")

    def test_preexisting_fixture_cannot_be_installed_over_or_uninstalled(self):
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(serial="emulator-5564", apk=Path(directory)/"fixture.apk",
                abi="x86_64", aapt2="aapt2", adb="adb", output=Path(directory)/"evidence")
            commands = []

            def run(command, **_kwargs):
                commands.append(command)
                tail = command[3:]
                if tail == ["shell", "getprop", "ro.kernel.qemu"]:
                    value = "1\n"
                elif tail == ["shell", "getprop", "sys.boot_completed"]:
                    value = "1\n"
                elif tail == ["emu", "avd", "name"]:
                    value = "hris-mobile-dev\nOK\n"
                elif tail == ["shell", "pm", "list", "packages", proof.PACKAGE]:
                    value = "package:" + proof.PACKAGE + "\n"
                else:
                    raise AssertionError("Unexpected fixture mutation")
                return subprocess.CompletedProcess(command, 0, stdout=value)

            with patch.object(proof, "validate_apk", return_value=hashlib.sha256(b"fixture").hexdigest()), \
                    patch.object(proof.subprocess, "run", side_effect=run), \
                    patch.object(proof, "OriginalQueueSeed") as seed:
                result = proof.run(args)
            self.assertFalse(result["complete"])
            self.assertEqual("AssertionError", result["failureCategory"])
            seed.assert_not_called()
            self.assertEqual(4, len(commands))
            self.assertFalse(any("install" in c or "uninstall" in c for c in commands))


if __name__ == "__main__":
    unittest.main()
