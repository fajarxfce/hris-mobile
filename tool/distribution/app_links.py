"""Public app-link inputs and verification of distribution signing identities."""
import base64
import json
import plistlib
import re


MAX_CONFIG = 16_384


def registration(content):
    if len(content) > MAX_CONFIG:
        raise ValueError('App-link configuration exceeds 16 KiB.')
    config = json.loads(content.decode('utf-8'))
    host = config['host']
    if (not isinstance(host, str) or not 3 <= len(host) <= 253
            or not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?', host)
            or any(not re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', part) for part in host.split('.'))
            or '.' not in host or not host.rsplit('.', 1)[1][0].isalpha()):
        raise ValueError('App-link host requires an exact lowercase DNS name.')
    # Gradle owns complete environment/schema validation before building. This boundary
    # additionally bounds decoded input and the filesystem host used when packaging.
    return config


def prepare_app_links(save, root, environment, values, profile=None):
    encoded = values.get('APP_LINKS_CONFIG_BASE64', '')
    if not encoded:
        return
    if len(encoded) > 4 * ((MAX_CONFIG + 2) // 3):
        raise ValueError('App-link configuration exceeds 16 KiB.')
    content = base64.b64decode(encoded, validate=True)
    config = registration(content)
    if profile is not None:
        entitlements = profile['Entitlements']
        if config.get('ios', {}).get('applicationId') != entitlements.get('application-identifier'):
            raise ValueError('App-link Apple identity does not match the signing profile.')
        domains = entitlements.get('com.apple.developer.associated-domains', [])
        if not isinstance(domains, list) or not any(
                value in domains for value in ('*', 'applinks:*', 'applinks:' + config['host'])):
            raise ValueError('The signing profile does not permit the associated domain.')
    save(root / f'config/app-links/{environment}.json', content)


def package_app_links(root, destination, metadata, platform, signing_output):
    environment = metadata['environment']
    path = root / f'config/app-links/{environment}.json'
    if not path.exists():
        return None
    with path.open('rb') as source:
        config = registration(source.read(MAX_CONFIG + 1))
    identifier = metadata['identifier']
    android = config['android']
    if android['applicationId'] != identifier:
        raise ValueError('App-link Android identity does not match the build.')
    if platform == 'android':
        digests = re.findall(rb'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\r?$', signing_output, re.MULTILINE)
        fingerprints = {':'.join(digest.decode().upper()[i:i+2] for i in range(0, 64, 2)) for digest in digests}
        if not fingerprints or not fingerprints.issubset(set(android['sha256CertFingerprints'])):
            raise ValueError('The APK signing certificate is absent from the public app-link registration.')
    elif platform == 'ios':
        entitlements = plistlib.loads(signing_output)
        if (config.get('ios', {}).get('applicationId') != entitlements.get('application-identifier')
                or entitlements.get('com.apple.developer.associated-domains') != ['applinks:' + config['host']]):
            raise ValueError('Signed Apple entitlements do not match the public app-link registration.')
    else:
        raise ValueError('Unsupported platform.')
    site = root / 'core/common/build/generated/app-links/sites' / config['host'] / '.well-known'
    contents = {}
    for name in ('assetlinks.json', 'apple-app-site-association'):
        path = site / name
        if not path.is_file() or path.is_symlink():
            raise ValueError('Generated public app-link files are missing or invalid.')
        with path.open('rb') as source:
            content = source.read(128 * 1024 + 1)
        if len(content) > 128 * 1024:
            raise ValueError('Generated public app-link files exceed their bound.')
        contents[name] = content
    android_statement = {'relation': ['delegate_permission/common.handle_all_urls'], 'target': {
        'namespace': 'android_app', 'package_name': identifier,
        'sha256_cert_fingerprints': android['sha256CertFingerprints']}}
    statements = json.loads(contents['assetlinks.json'])
    apple = json.loads(contents['apple-app-site-association'])['applinks']['details']
    if not isinstance(statements, list) or not 1 <= len(statements) <= 3 or statements.count(android_statement) != 1:
        raise ValueError('Generated Android association does not match the selected registration.')
    if not isinstance(apple, list) or len(apple) > 3:
        raise ValueError('Invalid generated Apple association.')
    if config.get('ios'):
        expected = {'appIDs': [config['ios']['applicationId']], 'components': [{'/': f'/app/{environment}/*'}]}
        if apple.count(expected) != 1:
            raise ValueError('Generated Apple association does not match the selected registration.')
    retained = destination / 'app-links' / config['host'] / '.well-known'
    retained.mkdir(parents=True)
    for name, content in contents.items():
        (retained / name).write_bytes(content)
    return {'host': config['host'], 'pathPrefix': f'/app/{environment}/', 'signingIdentityVerified': True}
