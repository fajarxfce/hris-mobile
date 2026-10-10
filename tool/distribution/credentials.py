#!/usr/bin/env python3
"""CI-only credential files. Existing files are never overwritten; output contains no secrets."""
import base64
import json
import os
from pathlib import Path
import shutil
import sys


def private_file(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        output.write(content)


def required(values, key):
    value = values.get(key)
    if not value:
        raise ValueError('A required distribution secret is missing.')
    return value


def decode(values, key):
    return base64.b64decode(required(values, key), validate=True)


def prepare_android(root, directory, values):
    owned = []
    def save(path, content):
        private_file(path, content)
        owned.append(str(path))
        (directory / 'owned.json').write_text(json.dumps(owned))

    environment = required(values, 'RELEASE_ENVIRONMENT')
    if environment not in {'dev', 'staging', 'prod'}:
        raise ValueError('Invalid environment.')
    config = decode(values, 'FIREBASE_ANDROID_CONFIG_BASE64')
    parsed = json.loads(config)
    identifier = 'dev.fajar.fluent.starter' + ('' if environment == 'prod' else '.' + environment)
    apps = [client['client_info'] for client in parsed['client']]
    app_id = required(values, 'FIREBASE_ANDROID_APP_ID')
    if not any(app['android_client_info']['package_name'] == identifier and app['mobilesdk_app_id'] == app_id for app in apps):
        raise ValueError('Firebase app does not match the release flavor.')
    save(directory / 'android.jks', decode(values, 'ANDROID_KEYSTORE_BASE64'))
    save(root / f'apps/android/src/{environment}/google-services.json', config)
    if values.get('OIDC_CONFIG_BASE64'):
        save(root / f'config/oidc/{environment}.json', decode(values, 'OIDC_CONFIG_BASE64'))


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[2]
    directory = Path(required(os.environ, 'RUNNER_TEMP')) / 'fluent-distribution'
    try:
        if sys.argv[1] == 'android':
            directory.mkdir(mode=0o700, exist_ok=False)
            prepare_android(root, directory, os.environ)
        elif sys.argv[1] == 'clean':
            owned = directory / 'owned.json'
            if owned.exists():
                for name in json.loads(owned.read_text()):
                    Path(name).unlink(missing_ok=True)
            shutil.rmtree(directory, ignore_errors=True)
        else:
            raise ValueError('Unsupported credential action.')
    except Exception:
        raise SystemExit('Distribution credential preparation failed. Check environment secrets and flavor registrations.') from None
