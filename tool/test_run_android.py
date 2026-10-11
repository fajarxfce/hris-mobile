import contextlib
import io
import unittest
from pathlib import Path
from unittest.mock import patch

import run_android


class RunAndroidTest(unittest.TestCase):
    devices = "List of devices attached\nemulator-5560\tdevice\n10.0.0.2:5555\tdevice\n"

    @patch.object(Path, "is_file", return_value=True)
    @patch.object(run_android.subprocess, "run")
    @patch.object(run_android.subprocess, "check_output", return_value=devices)
    def test_build_only_then_install_and_launch_on_selected_device(self, devices, run, exists):
        run_android.main(["emulator-5560"])
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(commands[0][1:], [":apps:android:assembleDevDebug"])
        self.assertEqual(commands[1][1:5], ["-s", "emulator-5560", "install", "-r"])
        self.assertEqual(commands[2][1:7], ["-s", "emulator-5560", "shell", "am", "start", "-n"])

    @patch.object(Path, "is_file", return_value=True)
    @patch.object(run_android.subprocess, "run")
    @patch.object(run_android.subprocess, "check_output", return_value=devices)
    def test_install_only_preserves_data_and_does_not_build_or_launch(self, devices, run, exists):
        run_android.main(["10.0.0.2:5555", "--install-only", "--skip-build"])
        run.assert_called_once()
        self.assertEqual(run.call_args.args[0][1:5], ["-s", "10.0.0.2:5555", "install", "-r"])

    @patch.object(run_android.subprocess, "run")
    @patch.object(run_android.subprocess, "check_output", return_value=devices)
    def test_ambiguous_or_disconnected_target_never_installs(self, devices, run):
        for arguments in ([], ["emulator-5554"]):
            with self.subTest(arguments=arguments), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                run_android.main(arguments)
        run.assert_not_called()

    @patch.object(Path, "is_file", return_value=False)
    @patch.object(run_android.subprocess, "run")
    @patch.object(run_android.subprocess, "check_output", return_value=devices)
    def test_missing_artifact_never_installs(self, devices, run, exists):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            run_android.main(["emulator-5560", "--skip-build"])
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
