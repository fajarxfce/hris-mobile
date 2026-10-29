#!/usr/bin/env python3
"""Validate manual release inputs."""
import json
import os
from pathlib import Path
import re
from urllib.parse import urlparse


def configuration(values):
    environment = values.get('RELEASE_ENVIRONMENT', '')
    version, build = values.get('RELEASE_VERSION', ''), values.get('RELEASE_BUILD', '')
    backend = values.get('RELEASE_BACKEND', '')
    if environment not in {'dev', 'staging', 'prod'}:
        raise ValueError('Unsupported release environment.')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError('Version must have three numeric components.')
    if not re.fullmatch(r'[1-9][0-9]{0,8}', build):
        raise ValueError('Build number must be a positive integer with at most nine digits.')
    if backend != 'remote':
        raise ValueError('HRIS distribution requires the remote backend.')
    endpoint = values.get('API_BASE_URL', '')
    if backend == 'remote':
        if not endpoint or len(endpoint) > 2048 or any(ord(c) < 33 or ord(c) > 126 for c in endpoint) or '\\' in endpoint:
            raise ValueError('A remote endpoint requires a bounded ASCII HTTPS URL.')
        url = urlparse(endpoint)
        if (url.scheme != 'https' or not url.hostname or url.username is not None or url.password is not None
                or '?' in endpoint or '#' in endpoint or url.port == 0):
            raise ValueError('A remote build requires an HTTPS API_BASE_URL without credentials or query.')
    identifier = 'dev.fajar.hris.mobile' + ('' if environment == 'prod' else f'.{environment}')
    return {'environment': environment, 'variant': environment.capitalize(),
            'identifier': identifier, 'version': version, 'build': build, 'backend': backend}


if __name__ == '__main__':
    try:
        values = configuration(os.environ)
        root = Path(__file__).resolve().parents[2]
        notes = os.environ.get('RELEASE_NOTES', '')
        if len(notes) > 4000:
            raise ValueError('Release notes exceed 4,000 characters.')
        destination = root / 'build/distribution'
        destination.mkdir(parents=True, exist_ok=True)
        (destination / 'release-notes.txt').write_text(notes)
        (destination / 'build.json').write_text(json.dumps(values, indent=2) + '\n')
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.writelines(f'{key}={value}\n' for key, value in values.items())
        print('Release inputs validated.')
    except (ValueError, KeyError):
        raise SystemExit('Invalid release inputs. Check the distribution guide.') from None
