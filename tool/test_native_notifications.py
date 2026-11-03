import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import check_native_notifications as proof


class NotificationDriverOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        apk = self.root / 'proof.apk'
        apk.write_bytes(b'fictional-apk')
        self.args = argparse.Namespace(serial='emulator-5560', apk=apk, output=self.root/'out',
                                       adb='owned-adb', aapt2='owned-aapt2')
        self.present = False
        self.qemu = '1'
        self.avd = 'hris-mobile-dev'
        self.package = proof.PACKAGE
        self.failure = None
        self.commands = []
        self.scenario = None

    def command(self, command, **kwargs):
        self.commands.append(command)
        if command[0] == 'owned-aapt2':
            output = f"package: name='{self.package}' versionCode='400'\napplication-debuggable\n"
        else:
            self.assertEqual(command[:3], ['owned-adb', '-s', self.args.serial])
            c = command[3:]
            if c[:2] == ['shell', 'getprop']:
                output = {'ro.kernel.qemu': self.qemu, 'sys.boot_completed': '1', 'ro.build.version.sdk': '35'}[c[2]]
            elif c == ['emu', 'avd', 'name']:
                output = self.avd + '\nOK\n'
            elif c[:4] == ['shell', 'pm', 'list', 'packages']:
                output = 'package:' + proof.PACKAGE if self.present else ''
            elif c[0] == 'install':
                self.assertFalse(self.present)
                self.assertNotIn('-r', c)
                self.assertNotIn('-d', c)
                self.present = True
                if self.failure == 'install-response':
                    raise subprocess.TimeoutExpired(command, 180)
                output = 'Success'
            elif c[:3] == ['shell', 'am', 'force-stop']:
                if self.failure == 'force-stop':
                    raise subprocess.CalledProcessError(1, command)
                output = ''
            elif c[0] == 'uninstall':
                self.assertEqual(c[1], proof.PACKAGE)
                if self.failure == 'uninstall':
                    raise subprocess.CalledProcessError(1, command)
                self.present = False
                output = 'Success'
            elif c[:3] == ['shell', 'am', 'instrument']:
                self.scenario = c[c.index('scenario')+1]
                if self.failure == 'instrumentation':
                    raise subprocess.CalledProcessError(1, command)
                output = 'INSTRUMENTATION_CODE: 0'
            elif c[:2] == ['shell', 'run-as']:
                if c[-1].endswith('/instrumentation.json'):
                    output = json.dumps({'complete': True})
                else:
                    count = 1 if self.scenario.endswith('denied') else 15
                    output = json.dumps({'complete': True, 'cleanedUp': True, 'cases': list(range(count))})
            elif c[:2] == ['shell', 'pm'] and c[2] in ('grant', 'revoke'):
                output = ''
            else:
                self.fail('Unexpected command: ' + repr(c))
        return subprocess.CompletedProcess(command, 0, output, '')

    def execute(self):
        with patch.object(proof.subprocess, 'run', side_effect=self.command):
            return proof.run(self.args)

    def result(self):
        return json.loads((self.args.output/'result.json').read_text())

    def assert_no_mutation(self):
        self.assertFalse(any(c[3:4] in (['install'], ['uninstall']) or
                             c[3:6] == ['shell', 'am', 'force-stop'] for c in self.commands))

    def test_success_releases_package_for_following_sdk_fixture(self):
        result = self.execute()
        self.assertTrue(result['complete'])
        self.assertTrue(result['fixtureUninstalled'])
        self.assertTrue(result['fixtureDataCleared'])
        self.assertEqual(16, sum(len(s['cases']) for s in result['scenarios']))
        self.assertFalse(self.present)
        # A sequential run must acquire an absent package again, never replace existing data.
        self.args.output = self.root/'second'
        self.assertTrue(self.execute()['complete'])

    def test_existing_fixture_is_not_replaced_or_deleted(self):
        self.present = True
        with self.assertRaisesRegex(AssertionError, 'unowned'):
            self.execute()
        self.assertTrue(self.present)
        self.assert_no_mutation()

    def test_other_avd_and_physical_device_are_rejected_before_mutation(self):
        for name in ['qemu', 'avd']:
            with self.subTest(name=name):
                self.args.output = self.root/name
                self.qemu = '0' if name == 'qemu' else '1'
                self.avd = 'another-project' if name == 'avd' else 'hris-mobile-dev'
                with self.assertRaises(AssertionError): self.execute()
                self.assert_no_mutation()

    def test_wrong_application_artifact_is_never_installed(self):
        self.package = 'dev.fajar.hris.mobile'
        with self.assertRaises(AssertionError): self.execute()
        self.assert_no_mutation()

    def test_lost_install_response_still_removes_owned_fixture(self):
        self.failure = 'install-response'
        with self.assertRaises(subprocess.TimeoutExpired): self.execute()
        self.assertFalse(self.present)
        self.assertTrue(self.result()['fixtureUninstalled'])
        self.assertNotEqual(True, self.result().get('complete'))

    def test_failed_scenario_still_releases_package(self):
        self.failure = 'instrumentation'
        with self.assertRaises(subprocess.CalledProcessError): self.execute()
        self.assertFalse(self.present)
        self.assertTrue(self.result()['fixtureUninstalled'])

    def test_force_stop_failure_does_not_skip_uninstall_or_claim_success(self):
        self.failure = 'force-stop'
        with self.assertRaises(RuntimeError): self.execute()
        self.assertFalse(self.present)
        self.assertTrue(self.result()['fixtureUninstalled'])
        self.assertFalse(self.result()['complete'])

    def test_uninstall_failure_is_recorded_and_never_claims_cleanup(self):
        self.failure = 'uninstall'
        with self.assertRaises(RuntimeError): self.execute()
        self.assertTrue(self.present)
        self.assertFalse(self.result()['complete'])
        self.assertNotEqual(True, self.result().get('fixtureUninstalled'))
        self.assertIn('cleanupFailureCategory', self.result())


if __name__ == '__main__':
    unittest.main()
