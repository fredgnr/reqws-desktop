import { execFile } from 'node:child_process';
import { createHash, X509Certificate } from 'node:crypto';
import { lstat, mkdir, mkdtemp, readFile, readdir, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import { assertTrustedSigningIdentity, buildProfile, releaseSigningConfiguration, validateReleaseCertificate } from '../../scripts/macos-build-profile.mts';
import { signingTargets } from '../../scripts/verify-macos-signature.mts';
import { assertSigningContext, cleanupMacosSigning, parseKeychainList, signingChildEnvironment, withMacosSigning, type SigningRunner } from '../../scripts/with-macos-signing.mts';

const execute = promisify(execFile);
let directory: string;
let certificatePath: string;
let certificate: Buffer;
let alternateCertificate: Buffer;
let pin: string;
let identity: string;

beforeAll(async () => {
  directory = await realpath(await mkdtemp(path.join(os.tmpdir(), 'reqws-signing-unit-')));
  const config = path.join(directory, 'openssl.cnf');
  await writeFile(config, '[req]\ndistinguished_name=dn\nx509_extensions=extensions\nprompt=no\n[dn]\nCN=ReqWS Disposable Test Code Signing\n[extensions]\nkeyUsage=critical,digitalSignature,keyCertSign\nextendedKeyUsage=codeSigning\nbasicConstraints=critical,CA:TRUE\n');
  const generate = async (name: string) => {
    const target = path.join(directory, `${name}.cer`);
    await execute('/usr/bin/openssl', ['req', '-new', '-x509', '-nodes', '-newkey', 'rsa:4096', '-sha256', '-days', '2', '-config', config, '-outform', 'DER', '-out', target, '-keyout', path.join(directory, `${name}.key`)]);
    return await readFile(target);
  };
  certificate = await generate('identity');
  alternateCertificate = await generate('alternate');
  certificatePath = path.join(directory, 'identity.cer');
  pin = createHash('sha256').update(certificate).digest('hex').toUpperCase();
  identity = validateReleaseCertificate(certificate, pin).identity;
}, 30_000);
afterAll(async () => { if (directory) await rm(directory, { recursive: true, force: true }); });

function environment(root: string): NodeJS.ProcessEnv {
  return {
    REQWS_BUILD_PROFILE: 'personal-release', GITHUB_ACTIONS: 'true', RUNNER_ENVIRONMENT: 'github-hosted',
    RUNNER_OS: 'macOS', GITHUB_REPOSITORY: 'fredgnr/reqws-desktop', GITHUB_REF: 'refs/tags/v1.2.3', GITHUB_EVENT_NAME: 'push',
    RUNNER_TEMP: root, MAC_SIGNING_CERT_SHA256: pin,
    MAC_SIGNING_P12_BASE64: Buffer.from('test encrypted container').toString('base64'), MAC_SIGNING_P12_PASSWORD: 'test-password',
    GH_TOKEN: 'must-not-reach-child', GITHUB_TOKEN: 'must-not-reach-child', NODE_OPTIONS: '--inspect',
    HOME: directory, PATH: process.env.PATH,
  };
}

describe('personal release trust boundary', () => {
  it('requires a pinned hash identity and real keychain and preserves release protections', async () => {
    const keychain = path.join(directory, 'fixture.keychain-db');
    await writeFile(keychain, 'test only; not a real keychain');
    const env = { ...environment(directory), REQWS_SIGNING_IDENTITY: identity, REQWS_SIGNING_KEYCHAIN: keychain };
    const query = vi.fn(() => `  1) ${identity} "ReqWS Disposable Test Code Signing"\n  1 valid identities found\n`);
    const options = releaseSigningConfiguration(env, certificatePath, query);
    expect(query).toHaveBeenCalledWith(keychain);
    expect(options).toMatchObject({ identity, keychain, identityValidation: false, continueOnError: false, preAutoEntitlements: false, preEmbedProvisioningProfile: false });
    expect(options.optionsForFile()).toMatchObject({ hardenedRuntime: true, timestamp: 'none' });
    expect(() => releaseSigningConfiguration({ ...env, REQWS_SIGNING_IDENTITY: 'ReqWS Disposable Test Code Signing' }, certificatePath)).toThrow('exact pinned');
    expect(() => releaseSigningConfiguration({ ...env, REQWS_SIGNING_KEYCHAIN: undefined }, certificatePath)).toThrow('absolute');
    expect(() => releaseSigningConfiguration(env, path.join(directory, 'absent.cer'))).toThrow();
    const alias = path.join(directory, 'alias.keychain-db');
    await symlink(keychain, alias);
    expect(() => releaseSigningConfiguration({ ...env, REQWS_SIGNING_KEYCHAIN: alias }, certificatePath)).toThrow('regular file');
    expect(() => releaseSigningConfiguration(env, certificatePath, () => '0 valid identities found')).toThrow('Code Signing');
    expect(() => assertTrustedSigningIdentity(identity, keychain, () => `1) ${'A'.repeat(40)} "Other"\n`)).toThrow();
    expect(() => assertTrustedSigningIdentity(identity, keychain, () => `${query()}2) ${'A'.repeat(40)} "Other"\n`)).toThrow();
    expect(() => assertTrustedSigningIdentity(identity, keychain, () => { throw new Error('system validation failed'); })).toThrow('system validation failed');
  });

  it('enumerates nested Mach-O code and rejects escaping symlinks and private material', async () => {
    const root = await mkdtemp(path.join(directory, 'bundle-'));
    const nested = path.join(root, 'Contents/Frameworks/Helper.app');
    await mkdir(nested, { recursive: true });
    const executable = path.join(nested, 'binary');
    await writeFile(executable, Buffer.from('cffaedfe00000000', 'hex'));
    await symlink(nested, path.join(root, 'internal-alias'));
    expect(await signingTargets(root)).toEqual([root, nested, executable]);
    const escape = path.join(root, 'escape');
    await symlink(directory, escape);
    await expect(signingTargets(root)).rejects.toThrow('escaping');
    await rm(escape);
    await writeFile(path.join(root, 'private.p12'), 'fixture');
    await expect(signingTargets(root)).rejects.toThrow('forbidden');
  });

  it('defaults to local and rejects unknown profiles', () => {
    expect(buildProfile({})).toBe('local');
    expect(buildProfile({ REQWS_BUILD_PROFILE: 'personal-release' })).toBe('personal-release');
    expect(() => buildProfile({ REQWS_BUILD_PROFILE: '' })).toThrow();
    expect(() => buildProfile({ REQWS_BUILD_PROFILE: 'release' })).toThrow();
  });
  it('pins actual certificate bytes, rejects same-name replacements and checks validity', () => {
    expect(validateReleaseCertificate(certificate, pin).identity).toMatch(/^[A-F0-9]{40}$/u);
    expect(new X509Certificate(certificate).subject).toBe(new X509Certificate(alternateCertificate).subject);
    expect(() => validateReleaseCertificate(alternateCertificate, pin)).toThrow('does not match');
    expect(() => validateReleaseCertificate(certificate, pin.toLowerCase())).toThrow();
    expect(() => validateReleaseCertificate(certificate, undefined)).toThrow();
    expect(() => validateReleaseCertificate(certificate, pin, 0)).toThrow('currently valid');
    expect(() => validateReleaseCertificate(certificate, pin, Date.now() + 10 * 24 * 60 * 60 * 1000)).toThrow('currently valid');
  });
  it.each([
    { GITHUB_EVENT_NAME: 'pull_request' }, { GITHUB_EVENT_NAME: 'pull_request_target' },
    { GITHUB_REF: 'refs/heads/main' }, { GITHUB_REPOSITORY: 'other/repository' },
    { RUNNER_ENVIRONMENT: 'self-hosted' }, { REQWS_BUILD_PROFILE: 'local' },
  ])('rejects an unauthorized signing context %j', (override) => {
    expect(() => assertSigningContext({ ...environment(directory), ...override }, 'darwin')).toThrow();
  });
  it('passes only public configuration to packaging and parses search lists without shell evaluation', () => {
    const child = signingChildEnvironment(environment(directory), identity, '/tmp/test.keychain-db');
    expect(child).toMatchObject({ REQWS_SIGNING_IDENTITY: identity, REQWS_BUILD_PROFILE: 'personal-release' });
    for (const key of ['MAC_SIGNING_P12_BASE64', 'MAC_SIGNING_P12_PASSWORD', 'GH_TOKEN', 'GITHUB_TOKEN', 'NODE_OPTIONS']) expect(child[key]).toBeUndefined();
    expect(parseKeychainList('    "/a keychain"\n    "/another"\n')).toEqual(['/a keychain', '/another']);
    expect(() => parseKeychainList('$(unexpected)')).toThrow();
  });
});

describe('temporary signing lifecycle (mock system commands, no keychain mutations)', () => {
  it.each(['success', 'child failure', 'import failure', 'wrong certificate'])('cleans the temporary material and restores search/trust after %s', async (scenario) => {
    const root = await mkdtemp(path.join(directory, 'runner-'));
    const calls: string[][] = [];
    const run: SigningRunner = async (command, args) => {
      calls.push([command, ...args]);
      if (args[0] === 'list-keychains' && !args.includes('-s')) return '"/original/login.keychain-db"\n';
      if (args[0] === 'import') {
        expect((await lstat(args[1]!)).mode & 0o777).toBe(0o600);
        expect((await lstat(path.dirname(args[1]!))).mode & 0o777).toBe(0o700);
        if (scenario === 'import failure') throw new Error('test import failure');
      }
      if (args[0] === 'find-certificate') return new X509Certificate(scenario === 'wrong certificate' ? alternateCertificate : certificate).toString();
      if (args[0] === 'find-identity') return `  1) ${identity} "ReqWS Disposable Test Code Signing"\n  1 valid identities found\n`;
      return '';
    };
    const child = vi.fn(async (_command: string[], env: NodeJS.ProcessEnv) => {
      expect(env.MAC_SIGNING_P12_PASSWORD).toBeUndefined();
      if (scenario === 'child failure') throw new Error('test packaging failure');
    });
    const result = withMacosSigning(['npm', 'run', 'package:macos'], environment(root), run, child, certificatePath);
    if (scenario === 'success') await expect(result).resolves.toBeUndefined();
    else await expect(result).rejects.toThrow();
    expect(await readdir(root)).toEqual([]);
    expect(calls.some((call) => call[1] === 'delete-keychain')).toBe(true);
    expect(calls.some((call) => call.includes('-A'))).toBe(false);
    if (scenario === 'success' || scenario === 'child failure') {
      expect(child).toHaveBeenCalledOnce();
      expect(calls).toContainEqual(['/usr/bin/security', 'list-keychains', '-d', 'user', '-s', '/original/login.keychain-db']);
      expect(calls.some((call) => call.includes('remove-trusted-cert'))).toBe(true);
    } else expect(child).not.toHaveBeenCalled();
  });
  it('keeps a public cleanup journal on cleanup failure and retries only the remaining operations', async () => {
    const root = await mkdtemp(path.join(directory, 'runner-'));
    let failCleanup = true;
    const run: SigningRunner = async (_command, args) => {
      if (args[0] === 'list-keychains' && !args.includes('-s')) return '"/original/login.keychain-db"\n';
      if (args[0] === 'find-certificate') return new X509Certificate(certificate).toString();
      if (args[0] === 'find-identity') return `1) ${identity} "Test"\n`;
      if (args.includes('remove-trusted-cert') && failCleanup) throw new Error('test trust cleanup failure');
      return '';
    };
    await expect(withMacosSigning(['npm'], environment(root), run, async () => {}, certificatePath)).rejects.toThrow('cleanup');
    const journal = JSON.parse(await readFile(path.join(root, 'reqws-signing-cleanup.json'), 'utf8'));
    expect(await readdir(journal.directory)).toEqual(['public.cer']);
    expect(journal).toMatchObject({ trustAdded: true, keychainCreated: false, searchListChanged: false });
    failCleanup = false;
    await cleanupMacosSigning(environment(root), run);
    expect(await readdir(root)).toEqual([]);
  });
});
