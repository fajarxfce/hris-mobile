"""Exercise the real shell entry points without signing or uploading a release."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


@unittest.skipIf(os.name == 'nt', 'Xcode and Android distribution run on Unix hosts.')
class ReleaseBuildTest(unittest.TestCase):
    def arguments(self, script, arguments=(), extra=None):
        with tempfile.TemporaryDirectory(prefix='release build ') as folder:
            root = Path(folder)
            target = root / script
            target.parent.mkdir(parents=True)
            shutil.copyfile(ROOT / script, target)
            (root / 'apps/ios').mkdir(parents=True, exist_ok=True)
            gradle = root / 'gradlew'
            gradle.write_text('#!/usr/bin/env python3\nimport json, os, sys\n'
                              'open(os.environ["ARGUMENT_CAPTURE"], "a").write(json.dumps(sys.argv[1:])+"\\n")\n')
            gradle.chmod(0o700)
            xvfb = root / 'xvfb-run'
            xvfb.write_text('#!/bin/sh\nshift\nexec "$@"\n')
            xvfb.chmod(0o700)
            capture = root / 'arguments.json'
            values = {**os.environ, 'PATH': str(root) + os.pathsep + os.environ['PATH'],
                      'SRCROOT': str(root / 'apps/ios'), 'ARGUMENT_CAPTURE': str(capture),
                      'RELEASE_ENVIRONMENT': 'dev', 'RELEASE_VERSION': '2.3.4', 'RELEASE_BUILD': '73',
                      'RELEASE_BACKEND': 'remote', 'API_BASE_URL': 'https://release.example.invalid/api/v1/',
                      'MARKETING_VERSION': '2.3.4', 'CURRENT_PROJECT_VERSION': '73'}
            values.pop('DISTRIBUTION_BACKEND', None)
            values.update(extra or {})
            result = subprocess.run(['sh', str(target), *arguments], env=values,
                                    capture_output=True, timeout=10)
            if result.returncode:
                self.assertFalse(capture.exists())
                return None
            calls = [json.loads(line) for line in capture.read_text().splitlines()]
            if script == 'tool/distribution/build_android.sh':
                self.assertEqual(2, len(calls))
                self.assertEqual([':check'], calls[0])
            else:
                self.assertEqual(1, len(calls))
            return calls[-1]

    def test_android_release_pins_the_selected_flavor_after_default_checks(self):
        args = self.arguments('tool/distribution/build_android.sh')
        self.assertIn('-PapiBaseUrl.dev=https://release.example.invalid/api/v1/', args)
        self.assertIn('-PapiBaseUrl=https://release.example.invalid/api/v1/', args)
        self.assertFalse(any(arg.startswith('-PapiBaseUrl.staging=') for arg in args))
        self.assertIn('-Pbackend=remote', args)
        self.assertIn('-PappVersionCode=73', args)
        self.assertIn(':apps:android:assembleDevRelease', args)
        self.assertIn(':apps:android:bundleDevRelease', args)
        self.assertIn('-PrequireSigning=true', args)

    def test_xcode_distribution_carries_selected_endpoint_backend_and_version(self):
        for environment in ('dev', 'staging', 'prod'):
            with self.subTest(environment=environment):
                args = self.arguments('apps/ios/build_framework.sh', [environment],
                                      {'DISTRIBUTION_BACKEND': 'remote'})
                self.assertIn(f'-PapiBaseUrl.{environment}=https://release.example.invalid/api/v1/', args)
                self.assertIn('-PapiBaseUrl=https://release.example.invalid/api/v1/', args)
                self.assertIn('-Pbackend=remote', args)
                self.assertIn('-PappVersion=2.3.4', args)
                self.assertIn('-PappVersionCode=73', args)
                self.assertIn(':apps:shared:embedAndSignAppleFrameworkForXcode', args)

    def test_regular_xcode_keeps_project_endpoint_and_remote_requires_input(self):
        args = self.arguments('apps/ios/build_framework.sh', ['dev'])
        self.assertFalse(any(arg.startswith(('-PapiBaseUrl', '-Pbackend=')) for arg in args))
        self.assertIsNone(self.arguments('apps/ios/build_framework.sh', ['dev'],
                                        {'DISTRIBUTION_BACKEND': 'remote', 'API_BASE_URL': ''}))
        self.assertIsNone(self.arguments('tool/distribution/build_android.sh', extra={'API_BASE_URL': ''}))
        self.assertIsNone(self.arguments('apps/ios/build_framework.sh', ['../prod']))
        self.assertIsNone(self.arguments('tool/distribution/build_android.sh', extra={'RELEASE_ENVIRONMENT': '../prod'}))

    def test_portable_demo_build_does_not_acquire_a_release_endpoint(self):
        args = self.arguments('tool/distribution/build_android.sh', extra={'RELEASE_BACKEND': 'demo', 'API_BASE_URL': ''})
        self.assertIn('-Pbackend=demo', args)
        self.assertFalse(any(arg.startswith('-PapiBaseUrl') for arg in args))
        args = self.arguments('apps/ios/build_framework.sh', ['dev'], {'DISTRIBUTION_BACKEND': 'demo', 'API_BASE_URL': ''})
        self.assertIn('-Pbackend=demo', args)
        self.assertFalse(any(arg.startswith('-PapiBaseUrl') for arg in args))

    def test_endpoint_remains_one_literal_argument(self):
        endpoint = 'https://release.example.invalid/$(touch SHOULD_NOT_EXIST); value'
        # Configuration rejects this URL before distribution. The shell still cannot
        # execute it or split it if called directly with unvalidated environment input.
        for script, positional, extra in [
                ('tool/distribution/build_android.sh', [], {}),
                ('apps/ios/build_framework.sh', ['dev'], {'DISTRIBUTION_BACKEND': 'remote'})]:
            args = self.arguments(script, positional, {**extra, 'API_BASE_URL': endpoint})
            self.assertIn('-PapiBaseUrl.dev=' + endpoint, args)


if __name__ == '__main__':
    unittest.main()
