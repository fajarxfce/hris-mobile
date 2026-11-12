"""Run the shared Karma reporter regression as part of the existing root checks."""

import pathlib
import shutil
import subprocess
import unittest


class KarmaReporterTest(unittest.TestCase):
    def test_line_framing_and_failure_propagation(self):
        node = shutil.which("node")
        self.assertIsNotNone(node, "Node is required for Web builds")
        script = pathlib.Path(__file__).with_suffix(".cjs")
        result = subprocess.run(
            [node, "--test", str(script)],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
