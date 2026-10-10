#!/usr/bin/env python3
"""Upload an APK and distribute it to configured Firebase groups using the official REST API."""
import http.client
import json
import os
from pathlib import Path
import re
import time
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, HTTPRedirectHandler, build_opener

ORIGIN = 'https://firebaseappdistribution.googleapis.com'


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        return None


class FirebaseDistribution:
    def __init__(self, app_id, token, request=None, sleep=time.sleep, monotonic=time.monotonic):
        if not re.fullmatch(r'1:[0-9]+:android:[a-zA-Z0-9]+', app_id):
            raise ValueError('Invalid Firebase Android app ID.')
        if not token:
            raise ValueError('Firebase access token is missing.')
        self.prefix = f'projects/{app_id.split(":")[1]}/apps/{app_id}/'
        self.token, self.sleep, self.monotonic = token, sleep, monotonic
        self.request = request or build_opener(NoRedirects()).open

    def call(self, method, url, data=None, headers=None):
        request = Request(url, data=data, method=method, headers={
            'Authorization': 'Bearer ' + self.token, **(headers or {})})
        with self.request(request, timeout=300) as response:
            content = response.read(1_048_577)
            if len(content) > 1_048_576:
                raise ValueError('Firebase response exceeded its size limit.')
            return json.loads(content) if content else {}

    def resource(self, name):
        if (not isinstance(name, str) or not name.startswith(self.prefix)
                or len(name) > 1024 or not re.fullmatch(r'[a-zA-Z0-9:/._-]+', name)
                or '..' in name.split('/')):
            raise ValueError('Firebase returned an invalid resource name.')
        return ORIGIN + '/v1/' + quote(name, safe='/:')

    def distribute(self, apk, groups, notes):
        if not groups or any(not re.fullmatch(r'[a-zA-Z0-9_-]+', group) for group in groups):
            raise ValueError('Firebase tester group aliases are required.')
        if len(notes) > 4000:
            raise ValueError('Release notes exceed 4,000 characters.')
        with apk.open('rb') as content:
            # urllib/http.client streams file objects in bounded blocks; no full APK allocation.
            operation = self.call('POST', ORIGIN + '/upload/v1/' + self.prefix + 'releases:upload', content, {
                'Content-Type': 'application/octet-stream', 'Content-Length': str(apk.stat().st_size),
                'X-Goog-Upload-Protocol': 'raw', 'X-Goog-Upload-File-Name': apk.name})
        deadline = self.monotonic() + 600
        while not operation.get('done'):
            if self.monotonic() >= deadline:
                raise TimeoutError('Firebase upload processing timed out.')
            self.sleep(5)
            operation = self.call('GET', self.resource(operation['name']))
        if operation.get('error'):
            raise ValueError('Firebase rejected the uploaded build.')
        release = operation['response']['release']['name']
        url = self.resource(release)
        if not release.startswith(self.prefix + 'releases/') or '/' in release.removeprefix(self.prefix + 'releases/'):
            raise ValueError('Firebase returned an invalid release name.')
        if notes:
            self.call('PATCH', url + '?updateMask=releaseNotes.text',
                      json.dumps({'releaseNotes': {'text': notes}}).encode(), {'Content-Type': 'application/json'})
        self.call('POST', url + ':distribute', json.dumps({'groupAliases': groups}).encode(),
                  {'Content-Type': 'application/json'})
        return release


if __name__ == '__main__':
    try:
        client = FirebaseDistribution(os.environ['FIREBASE_ANDROID_APP_ID'], os.environ['FIREBASE_ACCESS_TOKEN'])
        client.distribute(Path(os.environ['RELEASE_APK']), os.environ['FIREBASE_TESTER_GROUPS'].split(','),
                          Path('build/distribution/release-notes.txt').read_text())
        print('Build uploaded to the configured Firebase tester groups.')
    except HTTPError as error:
        raise SystemExit(f'Firebase distribution failed (HTTP {error.code}).') from None
    except (KeyError, ValueError, OSError, TimeoutError, http.client.HTTPException):
        raise SystemExit('Firebase distribution failed. Check the build, group aliases and service account access.') from None
