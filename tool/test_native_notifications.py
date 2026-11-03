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
                if self.failure == 'install-rejected':
                    raise subprocess.CalledProcessError(1, command, output='', stderr='INSTALL_PARSE_FAILED')
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
        with patch.object(proof.subprocess, 'run', side_effect=self.command), \
                patch.object(proof, 'input_recovery', return_value={'complete': True}):
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

    def test_rejected_install_keeps_original_failure_and_verifies_absence(self):
        self.failure = 'install-rejected'
        with self.assertRaises(subprocess.CalledProcessError):
            self.execute()
        result = self.result()
        self.assertTrue(result['fixtureAbsent'])
        self.assertFalse(result['fixtureUninstalled'])
        self.assertNotIn('cleanupFailureCategory', result)
        self.assertEqual('INSTALL_PARSE_FAILED', (self.args.output/'install-error.log').read_text())

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


class NotificationInputProcessOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.files = {}
        self.live = set()
        self.next_pid = 100
        self.main_pid = None
        self.commands = []
        self.killed = []
        self.wrong_process = False
        self.bad_capture = False
        self.bad_review = False

    def process(self):
        if self.main_pid not in self.live:
            self.next_pid += 1
            self.main_pid = self.next_pid
            self.live.add(self.main_pid)
        return self.main_pid

    def adb(self, *command, **kwargs):
        self.commands.append(command)
        if command[:4] == ('shell', 'am', 'start', '-W'):
            if command[5].endswith('NotificationInputProofActivity'):
                self.files['reply-staged'] = {'pid': self.process(), 'complete': True}
            else:
                mode = command[-1]
                count = self.files.get('reply-callback', {}).get('count', 0) + 1
                self.files['reply-callback'] = {'pid': self.process(), 'count': count,
                    'saved': mode == 'reply' and not self.bad_capture}
                self.files['reply-sent'] = {'pid': 900, 'mode': mode}
            return ''
        if command[:3] == ('shell', 'am', 'instrument'):
            self.files['instrumentation'] = {'complete': True}
            self.files['reply-verified'] = {'complete': True, 'cleanedUp': True,
                'pid': self.process(), 'protectedRowsBeforeRecovery': 1,
                'reviewRowsAfterRecovery': 1, 'wrongAccountPreservedInput': True,
                'oldFamilyRequiresReview': not self.bad_review, 'httpCalls': 0,
                'commandRetained': False}
            return 'INSTRUMENTATION_CODE: 0'
        if command[:4] == ('shell', 'run-as', proof.PACKAGE, 'cat'):
            if command[-1].startswith('/proc/'):
                return ('foreign.process' if self.wrong_process else proof.PACKAGE) + '\x00'
            name = Path(command[-1]).stem
            if name not in self.files:
                raise subprocess.CalledProcessError(1, command)
            return json.dumps(self.files[name])
        if command[:5] == ('shell', 'run-as', proof.PACKAGE, 'kill', '-9'):
            pid = int(command[-1])
            self.assertIn(pid, self.live)
            self.live.remove(pid)
            self.killed.append(pid)
            return ''
        if command[:3] == ('shell', 'ps', '-A'):
            return 'PID NAME\n' + '\n'.join(f'{p} {proof.PACKAGE}' for p in self.live)
        self.fail('Unexpected process-proof command: ' + repr(command))

    def test_verified_receiver_processes_are_killed_without_force_stopping_package(self):
        result = proof.input_recovery(self.adb, Path(self.temp.name))
        self.assertEqual(3, result['ordinaryProcessKills'])
        self.assertEqual(3, len(set(self.killed)))
        self.assertEqual(4, len(set(result['processIds'])))
        self.assertFalse(any('force-stop' in c for c in self.commands))
        self.assertTrue(result['oldFamilyRequiresReview'])

    def test_reused_pid_for_different_process_is_not_signalled(self):
        self.wrong_process = True
        with self.assertRaisesRegex(AssertionError, 'different process'):
            proof.input_recovery(self.adb, Path(self.temp.name))
        self.assertEqual([], self.killed)

    def test_failed_capture_is_not_accepted_as_persisted_input(self):
        self.bad_capture = True
        with self.assertRaises(AssertionError):
            proof.input_recovery(self.adb, Path(self.temp.name))
        self.assertEqual(1, len(self.killed))

    def test_missing_old_family_review_is_not_accepted(self):
        self.bad_review = True
        with self.assertRaises(AssertionError):
            proof.input_recovery(self.adb, Path(self.temp.name))


if __name__ == '__main__':
    unittest.main()
