#!/usr/bin/env python3
"""Verify signed build outputs and assemble the exact files retained by the workflow."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def checked(*args):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def package(root, platform):
    directory = root / 'build/distribution'
    metadata = json.loads((directory / 'build.json').read_text())
    environment = metadata['environment']
    destination = directory / 'artifacts'
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir()
    if platform == 'android':
        apk = root / f'apps/android/build/outputs/apk/{environment}/release/android-{environment}-release.apk'
        bundle = root / f'apps/android/build/outputs/bundle/{environment}Release/android-{environment}-release.aab'
        sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
        signers = sorted(sdk.glob('build-tools/*/apksigner'), key=lambda p: tuple(int(x) for x in p.parent.name.split('.') if x.isdigit()))
        checked(str(signers[-1]), 'verify', str(apk))
        result = checked('jarsigner', '-J-Duser.language=en', '-J-Duser.country=US', '-verify', str(bundle))
        if b'jar verified.' not in result:
            raise ValueError('The Android bundle is not signed.')
        shutil.copyfile(apk, destination / apk.name)
        shutil.copyfile(bundle, destination / bundle.name)
        shutil.copyfile(root / 'apps/android/build/reports/sbom/android.cdx.json', destination / 'android.cdx.json')
        mapping = root / f'apps/android/build/outputs/mapping/{environment}Release'
        if mapping.exists():
            shutil.copytree(mapping, destination / 'mapping')
    elif platform == 'ios':
        archive = directory / 'FluentStarter.xcarchive'
        checked('codesign', '--verify', '--deep', '--strict', str(archive / 'Products/Applications/FluentStarter.app'))
        ipa = directory / 'ipa/FluentStarter.ipa'
        shutil.copyfile(ipa, destination / ipa.name)
        if (archive / 'dSYMs').exists():
            shutil.copytree(archive / 'dSYMs', destination / 'dSYMs')
    else:
        raise ValueError('Unsupported platform.')
    metadata['commit'] = checked('git', '-C', str(root), 'rev-parse', 'HEAD').decode().strip()
    metadata['platform'] = platform
    (destination / 'build.json').write_text(json.dumps(metadata, indent=2) + '\n')
    files = sorted(p for p in destination.rglob('*') if p.is_file())
    with (destination / 'SHA256SUMS').open('w') as sums:
        for path in files:
            with path.open('rb') as content:
                digest = hashlib.file_digest(content, 'sha256').hexdigest()
            sums.write(f'{digest}  {path.relative_to(destination).as_posix()}\n')
    print('Signed artifacts and checksums prepared.')


if __name__ == '__main__':
    package(Path(__file__).resolve().parents[2], sys.argv[1])
