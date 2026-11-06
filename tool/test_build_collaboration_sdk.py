"""The SDK build driver owns and reaps its subprocess when a build times out."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from build_collaboration_sdk import run


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


if __name__ == "__main__":
    unittest.main()
