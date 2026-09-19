import { createHash } from 'node:crypto';
import { mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import { revokeSigningTrust, runSigningCommand } from '../../scripts/with-macos-signing.mts';
import { releaseCertificatePath, repositoryRoot } from '../../scripts/macos-build-profile.mts';

describe.skipIf(process.platform !== 'darwin')('offline macOS trust read-back format', () => {
  it('accepts an actual Code Signing deny plist without changing live trust', async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-offline-trust-'));
    try {
      const output = path.join(directory, 'settings.plist');
      await runSigningCommand('/usr/bin/security', ['add-trusted-cert', '-r', 'deny', '-p', 'codeSign', '-o', output, releaseCertificatePath]);
      const identity = createHash('sha1').update(await readFile(releaseCertificatePath)).digest('hex').toUpperCase();
      await expect(runSigningCommand('/usr/bin/python3', [path.join(repositoryRoot, 'scripts/verify-signing-trust.py'), '--settings', output, '--identity', identity])).resolves.toContain('revocation verified');
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});

// Admin trust is exercised only on disposable GitHub-hosted macOS runners.
// Never set these variables to make this test run on a developer's Mac.
const hostedMac = process.platform === 'darwin' && process.env.GITHUB_ACTIONS === 'true'
  && process.env.RUNNER_ENVIRONMENT === 'github-hosted' && process.env.RUNNER_OS === 'macOS';

describe.skipIf(!hostedMac)('hosted macOS Code Signing trust cleanup', () => {
  it('revokes a disposable trusted certificate and verifies the denial without changing authorization rules', async () => {
    const directory = await realpath(await mkdtemp(path.join(os.tmpdir(), 'reqws-trust-regression-')));
    const certificate = path.join(directory, 'public.cer');
    let trustAttempted = false;
    let identity: string | undefined;
    try {
      const config = path.join(directory, 'openssl.cnf');
      await writeFile(config, '[req]\ndistinguished_name=dn\nx509_extensions=extensions\nprompt=no\n[dn]\nCN=ReqWS Disposable Trust Test\n[extensions]\nkeyUsage=critical,digitalSignature,keyCertSign\nextendedKeyUsage=codeSigning\nbasicConstraints=critical,CA:TRUE\n', { mode: 0o600 });
      await runSigningCommand('/usr/bin/openssl', ['req', '-new', '-x509', '-nodes', '-newkey', 'rsa:4096', '-sha256', '-days', '1', '-config', config, '-outform', 'DER', '-out', certificate, '-keyout', path.join(directory, 'disposable.key')]);
      identity = createHash('sha1').update(await readFile(certificate)).digest('hex').toUpperCase();
      trustAttempted = true;
      await runSigningCommand('/usr/bin/sudo', ['-n', '/usr/bin/security', 'add-trusted-cert', '-d', '-r', 'trustRoot', '-p', 'codeSign', certificate]);
      const verify = ['/usr/bin/security', ['verify-cert', '-c', certificate, '-p', 'codeSign', '-l', '-L']] as const;
      await runSigningCommand(verify[0], [...verify[1]]);
      await revokeSigningTrust(certificate, identity);
      trustAttempted = false;
      await expect(runSigningCommand(verify[0], [...verify[1]])).rejects.toThrow('Signing system command failed');
    } finally {
      try {
        if (trustAttempted && identity) await revokeSigningTrust(certificate, identity);
      } finally {
        await rm(directory, { recursive: true, force: true });
      }
    }
  }, 180_000);
});
