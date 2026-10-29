import base64
import copy
import json
from pathlib import Path
import plistlib
import tempfile
import unittest

from app_links import MAX_CONFIG, package_app_links, prepare_app_links
from credentials import private_file, prepare_android


IDENTIFIER = 'dev.fajar.hris.mobile.dev'
FINGERPRINT = ':'.join(['AB'] * 32)


def config():
    return {'host': 'links.example.invalid', 'android': {'applicationId': IDENTIFIER,
            'sha256CertFingerprints': [FINGERPRINT]}, 'ios': {'applicationId': 'ABCDE12345.' + IDENTIFIER}}


def encoded(value):
    return {'APP_LINKS_CONFIG_BASE64': base64.b64encode(json.dumps(value).encode()).decode()}


class AppLinkDistributionTest(unittest.TestCase):
    def test_optional_input_does_not_enable_links_and_existing_file_survives(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            prepare_app_links(private_file, root, 'dev', {})
            self.assertFalse((root / 'config').exists())
            prepare_app_links(private_file, root, 'dev', encoded(config()))
            path = root / 'config/app-links/dev.json'
            self.assertEqual(config(), json.loads(path.read_text()))
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            replacement = {**config(), 'host': 'other.example.invalid'}
            with self.assertRaises(FileExistsError):
                prepare_app_links(private_file, root, 'dev', encoded(replacement))
            self.assertEqual(config(), json.loads(path.read_text()))

    def test_malformed_oversized_and_unsafe_hosts_never_write(self):
        invalid = ['!', base64.b64encode(b'\xff').decode(), base64.b64encode(b' ' * (MAX_CONFIG + 1)).decode()]
        for host in ('../elsewhere', 'x/../../secret', 'links.example.invalid:443', 'links..invalid', '127.0.0.1', 'LINKS.example.invalid'):
            invalid.append(encoded({**config(), 'host': host})['APP_LINKS_CONFIG_BASE64'])
        for value in invalid:
            with self.subTest(value=value[:24]), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                with self.assertRaises((ValueError, KeyError)):
                    prepare_app_links(private_file, root, 'dev', {'APP_LINKS_CONFIG_BASE64': value})
                self.assertEqual([], list(root.iterdir()))

    def test_android_preparation_tracks_app_link_file_for_cleanup(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            directory = root / 'owned'
            directory.mkdir()
            firebase = {'client': [{'client_info': {'android_client_info': {'package_name': IDENTIFIER},
                        'mobilesdk_app_id': '1:123:android:abc'}}]}
            prepare_android(root, directory, {**encoded(config()), 'RELEASE_ENVIRONMENT': 'dev',
                'FIREBASE_ANDROID_APP_ID': '1:123:android:abc', 'ANDROID_KEYSTORE_BASE64': base64.b64encode(b'fixture').decode(),
                'FIREBASE_ANDROID_CONFIG_BASE64': base64.b64encode(json.dumps(firebase).encode()).decode()})
            paths = json.loads((directory / 'owned.json').read_text())
            self.assertIn(str(root / 'config/app-links/dev.json'), paths)
            self.assertEqual(3, len(paths))

    def test_apple_profile_requires_matching_identity_and_domain_capability(self):
        entitlements = {'application-identifier': config()['ios']['applicationId'],
                        'com.apple.developer.associated-domains': ['applinks:links.example.invalid']}
        for domains in (['*'], ['applinks:*'], entitlements['com.apple.developer.associated-domains']):
            with tempfile.TemporaryDirectory() as folder:
                prepare_app_links(private_file, Path(folder), 'dev', encoded(config()),
                                  {'Entitlements': {**entitlements, 'com.apple.developer.associated-domains': domains}})
        for changes in ({'application-identifier': 'OTHER.' + IDENTIFIER},
                        {'com.apple.developer.associated-domains': []},
                        {'com.apple.developer.associated-domains': 'applinks:links.example.invalid'},
                        {'com.apple.developer.associated-domains': ['applinks:other.example.invalid']}):
            with tempfile.TemporaryDirectory() as folder, self.assertRaises(ValueError):
                prepare_app_links(private_file, Path(folder), 'dev', encoded(config()),
                                  {'Entitlements': {**entitlements, **changes}})

    def package(self, platform, signing, registration=None, generated=True, stale=False):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            prepare_app_links(private_file, root, 'dev', encoded(registration or config()))
            site = root / 'core/common/build/generated/app-links/sites/links.example.invalid/.well-known'
            if generated:
                site.mkdir(parents=True)
                (site / 'assetlinks.json').write_text(json.dumps([{
                    'relation': ['delegate_permission/common.handle_all_urls'], 'target': {
                        'namespace': 'android_app', 'package_name': IDENTIFIER,
                        'sha256_cert_fingerprints': [FINGERPRINT]}}]))
                (site / 'apple-app-site-association').write_text(json.dumps({'applinks': {'apps': [], 'details': [{
                    'appIDs': [config()['ios']['applicationId']], 'components': [{'/': '/app/dev/*'}]}]}}))
                if stale:
                    (site / 'assetlinks.json').write_text('[]')
            target = root / 'artifacts'
            metadata = {'environment': 'dev', 'identifier': IDENTIFIER}
            result = package_app_links(root, target, metadata, platform, signing)
            for name in ('assetlinks.json', 'apple-app-site-association'):
                self.assertEqual((site / name).read_bytes(), (target / 'app-links/links.example.invalid/.well-known' / name).read_bytes())
            return result

    def test_packaging_requires_actual_registered_android_signer(self):
        signing = b'Signer #1 certificate SHA-256 digest: ' + b'ab' * 32 + b'\n'
        self.assertTrue(self.package('android', signing)['signingIdentityVerified'])
        for invalid in (b'', signing.replace(b'ab', b'cd'), signing + b'Signer #2 certificate SHA-256 digest: ' + b'cd' * 32 + b'\n'):
            with self.assertRaises(ValueError):
                self.package('android', invalid)
        with self.assertRaises(ValueError):
            self.package('android', signing, generated=False)
        with self.assertRaises(ValueError):
            self.package('android', signing, stale=True)
        other = copy.deepcopy(config())
        other['android']['applicationId'] = 'other.application'
        with self.assertRaises(ValueError):
            self.package('android', signing, registration=other)

    def test_packaging_requires_signed_apple_identity_and_exact_domain(self):
        entitlements = {'application-identifier': config()['ios']['applicationId'],
                        'com.apple.developer.associated-domains': ['applinks:links.example.invalid']}
        self.assertTrue(self.package('ios', plistlib.dumps(entitlements))['signingIdentityVerified'])
        for changes in ({'application-identifier': 'OTHER.' + IDENTIFIER},
                        {'com.apple.developer.associated-domains': []},
                        {'com.apple.developer.associated-domains': ['applinks:*']}):
            with self.assertRaises(ValueError):
                self.package('ios', plistlib.dumps({**entitlements, **changes}))


if __name__ == '__main__':
    unittest.main()
