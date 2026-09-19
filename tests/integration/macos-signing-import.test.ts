import { randomBytes, X509Certificate } from 'node:crypto';
import { chmod, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { importSigningIdentity, parseKeychainList, runSigningCommand } from '../../scripts/with-macos-signing.mts';

// No production credentials, codesigning, trust changes, or CI-context spoofing.
// This exercises the actual security import path that mocks cannot validate.
describe.skipIf(process.platform !== 'darwin')('native macOS PKCS12 import', () => {
  let directory: string;
  let certificate: Buffer;
  const password = randomBytes(32).toString('base64');

  beforeAll(async () => {
    let openssl: string | undefined;
    for (const candidate of [process.env.REQWS_OPENSSL, '/opt/homebrew/opt/openssl@3/bin/openssl', '/usr/local/opt/openssl@3/bin/openssl', 'openssl']) {
      if (!candidate) continue;
      try {
        if ((await runSigningCommand(candidate, ['version'])).startsWith('OpenSSL 3.')) {
          openssl = candidate;
          break;
        }
      } catch { /* Try the next installed OpenSSL; never install a system tool. */ }
    }
    if (!openssl) throw new Error('Native P12 regression requires installed OpenSSL 3; set REQWS_OPENSSL to its executable.');
    directory = await realpath(await mkdtemp(path.join(os.tmpdir(), 'reqws-p12-regression-')));
    const config = path.join(directory, 'openssl.cnf');
    await writeFile(config, '[req]\ndistinguished_name=dn\nx509_extensions=extensions\nprompt=no\n[dn]\nCN=ReqWS Disposable Import Test\n[extensions]\nkeyUsage=critical,digitalSignature,keyCertSign\nextendedKeyUsage=codeSigning\nbasicConstraints=critical,CA:TRUE\n', { mode: 0o600 });
    await runSigningCommand(openssl, ['req', '-new', '-x509', '-nodes', '-newkey', 'rsa:4096', '-sha256', '-days', '1', '-config', config, '-out', path.join(directory, 'certificate.pem'), '-keyout', path.join(directory, 'private.key')]);
    certificate = new X509Certificate(await readFile(path.join(directory, 'certificate.pem'))).raw;
    await runSigningCommand(openssl, ['pkcs12', '-export', '-inkey', path.join(directory, 'private.key'), '-in', path.join(directory, 'certificate.pem'), '-out', path.join(directory, 'identity.p12'), '-keypbe', 'AES-256-CBC', '-certpbe', 'AES-256-CBC', '-macalg', 'sha256', '-iter', '100000', '-passout', 'env:REQWS_P12_FIXTURE_PASSWORD'], { ...process.env, REQWS_P12_FIXTURE_PASSWORD: password });
    await chmod(path.join(directory, 'identity.p12'), 0o600);
  }, 30_000);

  afterAll(async () => { if (directory) await rm(directory, { recursive: true, force: true }); });

  it.each(['correct', 'incorrect'])('imports the encrypted identity only with the %s password', async (scenario) => {
    const security = async (args: string[]) => await runSigningCommand('/usr/bin/security', args);
    const original = await security(['list-keychains', '-d', 'user']);
    const keychain = path.join(directory, `${scenario}.keychain-db`);
    const keychainPassword = randomBytes(32).toString('hex');
    let created = false;
    try {
      await security(['create-keychain', '-p', keychainPassword, keychain]);
      created = true;
      await security(['unlock-keychain', '-p', keychainPassword, keychain]);
      const importing = importSigningIdentity(path.join(directory, 'identity.p12'), scenario === 'correct' ? password : 'incorrect-fixture-password', keychain, certificate);
      if (scenario === 'correct') {
        await importing;
        // Without -v, security reports the certificate/private-key pair even
        // though this disposable certificate is deliberately not trusted.
        const identities = await security(['find-identity', '-p', 'codesigning', keychain]);
        expect(identities).toContain(new X509Certificate(certificate).fingerprint.replaceAll(':', ''));
      } else {
        await expect(importing).rejects.toThrow('Signing system command failed');
        expect(await security(['find-certificate', '-a', '-p', keychain])).toBe('');
      }
    } finally {
      try {
        await security(['list-keychains', '-d', 'user', '-s', ...parseKeychainList(original)]);
      } finally {
        if (created) await security(['delete-keychain', keychain]);
      }
      expect(await security(['list-keychains', '-d', 'user'])).toBe(original);
    }
  }, 30_000);
});
