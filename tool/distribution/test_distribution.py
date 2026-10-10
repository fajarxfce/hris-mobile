import base64
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import tempfile
import unittest

from apple_signing import validate_profile
from configuration import configuration
from credentials import private_file, prepare_android
from firebase_distribute import FirebaseDistribution, NoRedirects


class DistributionConfigurationTest(unittest.TestCase):
    def test_inputs_reject_injection_and_unsafe_remote_origins(self):
        values = dict(RELEASE_ENVIRONMENT='staging', RELEASE_VERSION='1.2.3', RELEASE_BUILD='42', RELEASE_BACKEND='demo')
        self.assertEqual('dev.fajar.fluent.starter.staging', configuration(values)['identifier'])
        for key, bad in [('RELEASE_ENVIRONMENT', '../prod'), ('RELEASE_BUILD', '42\nsecret=1'),
                         ('RELEASE_VERSION', '$(command)'), ('RELEASE_BACKEND', 'unknown')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                configuration({**values, key: bad})
        for url in ['http://api.example', 'https://secret@api.example', 'https://api.example?token=secret']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                configuration({**values, 'RELEASE_BACKEND': 'remote', 'API_BASE_URL': url})

    def test_credential_files_are_private_and_existing_files_are_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'credential'
            private_file(path, b'first')
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                private_file(path, b'second')
            self.assertEqual(b'first', path.read_bytes())

    def test_android_config_must_match_the_flavor_and_application(self):
        config = {'client': [{'client_info': {'android_client_info': {'package_name': 'wrong.package'},
                                             'mobilesdk_app_id': '1:123:android:abc'}}]}
        with tempfile.TemporaryDirectory() as folder, self.assertRaises(ValueError):
            root = Path(folder)
            prepare_android(root, root, dict(RELEASE_ENVIRONMENT='staging', FIREBASE_ANDROID_APP_ID='1:123:android:abc',
                                           FIREBASE_ANDROID_CONFIG_BASE64=base64.b64encode(json.dumps(config).encode()).decode()))
        # No keystore write or upload occurs before the registration is validated.

    def test_apple_profile_rejects_expiry_debug_and_other_accounts(self):
        identifier, team = 'dev.fajar.fluent.starter.staging', 'AAAAAAAAAA'
        profile = {'UUID': '12345678-1234-1234-1234-123456789012', 'TeamIdentifier': [team],
                   'ExpirationDate': datetime.now(timezone.utc) + timedelta(days=1),
                   'Entitlements': {'application-identifier': team + '.' + identifier, 'get-task-allow': False}}
        self.assertEqual(profile['UUID'], validate_profile(profile, identifier, team))
        for changes in [{'ProvisionedDevices': ['device']}, {'ProvisionsAllDevices': True},
                        {'ExpirationDate': datetime.now(timezone.utc) - timedelta(days=1)},
                        {'TeamIdentifier': ['BBBBBBBBBB']},
                        {'Entitlements': {**profile['Entitlements'], 'get-task-allow': True}}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_profile({**profile, **changes}, identifier, team)


class FirebaseDistributionTest(unittest.TestCase):
    def test_upload_is_streamed_and_only_the_completed_release_is_distributed(self):
        prefix = 'projects/123/apps/1:123:android:abc/'
        operation = prefix + 'releases/release/operations/upload'
        release = prefix + 'releases/release'
        responses = [{'name': operation}, {'name': operation, 'done': True, 'response': {'release': {'name': release}}}, {}, {}]
        calls = []

        def request(value, timeout):
            calls.append(value)
            self.assertEqual(300, timeout)
            self.assertEqual('Bearer test-only-token', value.headers['Authorization'])
            if len(calls) == 1:
                self.assertTrue(hasattr(value.data, 'read'))
                self.assertEqual(b'apk fixture', value.data.read(32))
            return io.BytesIO(json.dumps(responses.pop(0)).encode())

        client = FirebaseDistribution('1:123:android:abc', 'test-only-token', request=request, sleep=lambda _: None)
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'app.apk'
            apk.write_bytes(b'apk fixture')
            self.assertEqual(release, client.distribute(apk, ['internal'], 'Version 1.0.0'))
        self.assertEqual(['POST', 'GET', 'PATCH', 'POST'], [call.method for call in calls])
        self.assertEqual({'groupAliases': ['internal']}, json.loads(calls[-1].data))
        self.assertTrue(calls[0].data.closed)

    def test_rejected_or_untrusted_upload_cannot_distribute(self):
        calls = []

        def request(value, timeout):
            calls.append(value)
            return io.BytesIO(json.dumps({'done': True, 'error': {'code': 400}}).encode())

        client = FirebaseDistribution('1:123:android:abc', 'test', request=request)
        for resource in ['https://other.example', 'projects/another/app', client.prefix + '../../elsewhere']:
            with self.subTest(resource=resource), self.assertRaises(ValueError):
                client.resource(resource)
        with tempfile.TemporaryDirectory() as folder, self.assertRaises(ValueError):
            apk = Path(folder) / 'app.apk'
            apk.write_bytes(b'fixture')
            client.distribute(apk, ['internal'], '')
        self.assertEqual(1, len(calls))
        self.assertIsNone(NoRedirects().redirect_request(None, None, 307, '', None, 'https://other.example'))


if __name__ == '__main__':
    unittest.main()
