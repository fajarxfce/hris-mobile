#!/usr/bin/env python3
"""Prepare and remove temporary Apple signing resources on a GitHub-hosted macOS runner."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import plistlib
import re
import secrets
import shlex
import shutil
import subprocess
import sys

from credentials import private_file, required, decode


def validate_profile(profile, identifier, team):
    entitlements = profile['Entitlements']
    expires = profile['ExpirationDate'].replace(tzinfo=timezone.utc)
    uuid = profile['UUID']
    if (not re.fullmatch(r'[A-Fa-f0-9-]{36}', uuid)
            or profile['TeamIdentifier'] != [team]
            or entitlements.get('application-identifier') != team + '.' + identifier
            or entitlements.get('get-task-allow', False)
            or profile.get('ProvisionedDevices') or profile.get('ProvisionsAllDevices')
            or expires <= datetime.now(timezone.utc)):
        raise ValueError('Use an unexpired App Store profile for this team and bundle ID.')
    return uuid


def command(*args):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def prepare(root, directory, values):
    environment = required(values, 'RELEASE_ENVIRONMENT')
    if environment not in {'dev', 'staging', 'prod'}:
        raise ValueError('Invalid environment.')
    identifier = 'dev.fajar.fluent.starter' + ('' if environment == 'prod' else '.' + environment)
    team = required(values, 'APPLE_TEAM_ID')
    key_id = required(values, 'APPLE_API_KEY_ID')
    issuer = required(values, 'APPLE_API_ISSUER_ID')
    if not re.fullmatch(r'[A-Z0-9]{10}', team) or not re.fullmatch(r'[A-Z0-9]{10}', key_id) or not re.fullmatch(r'[a-fA-F0-9-]{36}', issuer):
        raise ValueError('Invalid Apple identifiers.')
    directory.mkdir(mode=0o700, exist_ok=False)
    owned = []

    def save(path, data):
        private_file(path, data)
        owned.append(str(path))
        (directory / 'owned.json').write_text(json.dumps(owned))

    save(directory / 'certificate.p12', decode(values, 'APPLE_CERTIFICATE_BASE64'))
    save(directory / 'profile.mobileprovision', decode(values, 'APPLE_PROFILE_BASE64'))
    profile = plistlib.loads(command('security', 'cms', '-D', '-i', str(directory / 'profile.mobileprovision')))
    uuid = validate_profile(profile, identifier, team)
    installed = Path.home() / 'Library/MobileDevice/Provisioning Profiles' / (uuid + '.mobileprovision')
    save(installed, (directory / 'profile.mobileprovision').read_bytes())
    save(directory / 'private_keys' / f'AuthKey_{key_id}.p8', decode(values, 'APPLE_API_KEY_BASE64'))
    if values.get('FIREBASE_IOS_CONFIG_BASE64'):
        config = decode(values, 'FIREBASE_IOS_CONFIG_BASE64')
        if plistlib.loads(config).get('BUNDLE_ID') != identifier:
            raise ValueError('Firebase iOS registration does not match this flavor.')
        save(root / f'apps/ios/Configuration/{environment}/GoogleService-Info.plist', config)
    if values.get('OIDC_CONFIG_BASE64'):
        save(root / f'config/oidc/{environment}.json', decode(values, 'OIDC_CONFIG_BASE64'))
    previous = shlex.split(command('security', 'list-keychains', '-d', 'user').decode())
    (directory / 'keychains.json').write_text(json.dumps(previous))
    password = secrets.token_urlsafe(32)
    keychain = str(directory / 'signing.keychain-db')
    command('security', 'create-keychain', '-p', password, keychain)
    command('security', 'set-keychain-settings', '-lut', '3600', keychain)
    command('security', 'unlock-keychain', '-p', password, keychain)
    command('security', 'import', str(directory / 'certificate.p12'), '-P', required(values, 'APPLE_CERTIFICATE_PASSWORD'),
            '-k', keychain, '-T', '/usr/bin/codesign', '-T', '/usr/bin/security')
    command('security', 'set-key-partition-list', '-S', 'apple-tool:,apple:,codesign:', '-s', '-k', password, keychain)
    command('security', 'list-keychains', '-d', 'user', '-s', keychain, *previous)
    options = {'method': 'app-store-connect', 'destination': 'export', 'signingStyle': 'manual',
               'teamID': team, 'provisioningProfiles': {identifier: uuid}, 'stripSwiftSymbols': True}
    private_file(directory / 'ExportOptions.plist', plistlib.dumps(options))
    with open(values['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'profile={uuid}\n')


def cleanup(directory):
    keychain = directory / 'signing.keychain-db'
    previous = directory / 'keychains.json'
    if previous.exists():
        subprocess.run(['security', 'list-keychains', '-d', 'user', '-s', *json.loads(previous.read_text())],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    if keychain.exists():
        subprocess.run(['security', 'delete-keychain', str(keychain)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    owned = directory / 'owned.json'
    if owned.exists():
        for name in json.loads(owned.read_text()):
            Path(name).unlink(missing_ok=True)
    shutil.rmtree(directory, ignore_errors=True)


if __name__ == '__main__':
    try:
        directory = Path(required(os.environ, 'RUNNER_TEMP')) / 'fluent-distribution'
        if sys.argv[1] == 'prepare':
            prepare(Path(__file__).resolve().parents[2], directory, os.environ)
        elif sys.argv[1] == 'clean':
            cleanup(directory)
        else:
            raise ValueError('Unknown action.')
    except Exception:
        raise SystemExit('Apple signing setup failed. Check the team, App Store profile, certificate and API key secrets.') from None
