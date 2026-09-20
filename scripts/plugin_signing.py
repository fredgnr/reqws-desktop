"""Pinned ZIP Signer verification and isolated production signing entry point."""

import argparse
import json
import shutil
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import urllib.request

from plugin_release import regular_file, validate_version

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE = ROOT / 'build/jetbrains/reqws-plugin-chain.crt'
SIGNER_VERSION = '0.1.43'
SIGNER_URL = (f'https://github.com/JetBrains/marketplace-zip-signer/releases/download/'
              f'{SIGNER_VERSION}/marketplace-zip-signer-cli-{SIGNER_VERSION}.jar')


def java():
    return str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'


def openssl():
    return os.environ.get('REQWS_OPENSSL', 'openssl')


def run_checked(args, *, env=None, input=None, timeout=120):
    result = subprocess.run([str(arg) for arg in args], input=input, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, env=env, timeout=timeout, check=False)
    if result.returncode:
        # Never include a crypto/Gradle command line, private input or raw stderr in an exception.
        raise ValueError('Signing identity or archive verification failed')
    return result.stdout


class SignerRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        from urllib.parse import urlsplit
        url = urlsplit(newurl)
        if url.scheme != 'https' or url.hostname not in {
                'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'}:
            raise ValueError('Unexpected signer download redirect')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download_signer(destination):
    destination = Path(destination)
    if destination.exists():
        raise ValueError('Refusing to replace a signer download')
    destination.parent.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener(SignerRedirect())
    try:
        with opener.open(SIGNER_URL, timeout=60) as response, destination.open('xb') as target:
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > 50_000_000:
                    raise ValueError('Signer download exceeds size limit')
                target.write(chunk)
        if total == 0:
            raise ValueError('Empty signer download')
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def verify_certificate(certificate):
    certificate = regular_file(certificate)
    run_checked([openssl(), 'x509', '-in', certificate, '-noout', '-checkend', '0'])
    run_checked([openssl(), 'verify', '-CAfile', certificate, certificate])


def preflight(key, certificate, password):
    if not password or not key:
        raise ValueError('Encrypted plugin private key and password are required')
    key = regular_file(key)
    if b'ENCRYPTED' not in key.read_bytes():
        raise ValueError('The plugin private key must be encrypted at rest')
    verify_certificate(certificate)
    env = {**os.environ, 'REQWS_KEY_PASSWORD': password}
    run_checked([openssl(), 'pkey', '-in', key, '-passin', 'env:REQWS_KEY_PASSWORD',
                 '-check', '-noout'], env=env)
    key_public = run_checked([openssl(), 'pkey', '-in', key, '-passin', 'env:REQWS_KEY_PASSWORD',
                              '-pubout'], env=env)
    cert_public = run_checked([openssl(), 'x509', '-in', certificate, '-pubkey', '-noout'])
    if key_public != cert_public:
        raise ValueError('Plugin private key does not match the reviewed certificate')


def verify_signature(archive, certificate, signer):
    verify_certificate(certificate)
    run_checked([java(), '-jar', regular_file(signer), 'verify', '-in', regular_file(archive),
                 '-cert', regular_file(certificate)])


def sign_release(version, certificate=CERTIFICATE):
    validate_version(version)
    private_key = os.environ.get('JETBRAINS_PLUGIN_PRIVATE_KEY', '')
    password = os.environ.get('JETBRAINS_PLUGIN_PRIVATE_KEY_PASSWORD', '')
    if not private_key or not password:
        raise ValueError('Production plugin signing credentials are required')
    root = Path(os.environ.get('RUNNER_TEMP', tempfile.gettempdir())).resolve()
    marker = root / 'reqws-plugin-signing-state.json'
    if marker.exists():
        raise ValueError('Previous signing cleanup must complete before signing again')
    with tempfile.TemporaryDirectory(prefix='reqws-plugin-signing-', dir=root) as temporary:
        marker.write_text(json.dumps({'directory': temporary}))
        marker.chmod(0o600)
        key = Path(temporary).resolve() / 'private.pem'
        key.write_text(private_key)
        key.chmod(0o600)
        preflight(key, certificate, password)
        env = dict(os.environ)
        env.pop('JETBRAINS_PLUGIN_PRIVATE_KEY', None)
        env['REQWS_PLUGIN_PRIVATE_KEY_FILE'] = str(key)
        env['REQWS_PLUGIN_CERTIFICATE_FILE'] = str(certificate)
        # No configuration cache may serialize providers carrying production credentials.
        result = subprocess.run([
            str(ROOT / 'integrations/goland/gradlew'), '-p', str(ROOT / 'integrations/goland'),
            'exportPluginArchivePath', '-PrequirePluginSigning=true', f'-PreleaseVersion={version}',
            '--no-daemon', '--no-configuration-cache', '--no-build-cache', '--console=plain',
        ], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=1800, check=False)
        output = result.stdout.decode('utf-8', errors='replace')
        for secret in [private_key, password, *private_key.splitlines()]:
            if secret:
                output = output.replace(secret, '[REDACTED]')
        # Prevent subprocess text from introducing GitHub workflow commands.
        print('\n'.join('[plugin-signing] ' + line for line in output.splitlines()))
        if result.returncode:
            raise ValueError('Plugin signing task failed')
    marker.unlink(missing_ok=True)


def cleanup_signing_state():
    root = Path(os.environ.get('RUNNER_TEMP', tempfile.gettempdir())).resolve()
    marker = root / 'reqws-plugin-signing-state.json'
    if not marker.exists():
        return
    if marker.is_symlink():
        raise ValueError('Unsafe cleanup marker')
    directory = Path(json.loads(marker.read_text())['directory'])
    if directory.parent != root or not directory.name.startswith('reqws-plugin-signing-') or directory.is_symlink():
        raise ValueError('Unsafe signing cleanup path')
    if directory.exists():
        shutil.rmtree(directory)
    marker.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['sign', 'preflight', 'verify', 'download', 'cleanup'])
    parser.add_argument('--version')
    parser.add_argument('--input', type=Path)
    parser.add_argument('--certificate', type=Path, default=CERTIFICATE)
    parser.add_argument('--signer', type=Path)
    args = parser.parse_args()
    def cancelled(_signum, _frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, cancelled)
    try:
        if args.command == 'cleanup':
            cleanup_signing_state()
        elif args.command == 'download':
            download_signer(args.signer)
        elif args.command == 'verify':
            verify_signature(args.input, args.certificate, args.signer)
        elif args.command == 'preflight':
            preflight(os.environ.get('REQWS_PLUGIN_PRIVATE_KEY_FILE'), args.certificate,
                      os.environ.get('JETBRAINS_PLUGIN_PRIVATE_KEY_PASSWORD'))
        else:
            sign_release(args.version, args.certificate)
        return 0
    except (Exception, KeyboardInterrupt):
        print('Plugin signing/verification failed; production release is blocked.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
