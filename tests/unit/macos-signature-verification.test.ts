import { createHash } from 'node:crypto';
import { lstat, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const commands = vi.hoisted(() => ({
  execute: vi.fn<(command: string, args: string[]) => Promise<{ stdout: string; stderr: string }>>(),
}));
vi.mock('node:child_process', () => ({
  execFile: Object.assign(vi.fn(), { [Symbol.for('nodejs.util.promisify.custom')]: commands.execute }),
}));

import { signingTargets, verifyReleaseSignature } from '../../scripts/verify-macos-signature.mts';

let directory: string;
let app: string;
let nestedBinary: string;
let certificateFile: string;
let certificate: Buffer;
let pin: string;
let extracted: string[];
let scenario: 'valid' | 'wrong-nested-certificate' | 'adhoc' | 'no-runtime' | 'missing-leaf' | 'invalid-signature';

beforeAll(async () => {
  directory = await realpath(await mkdtemp(path.join(os.tmpdir(), 'reqws-signature-unit-')));
  const native = await vi.importActual<typeof import('node:child_process')>('node:child_process');
  const config = path.join(directory, 'openssl.cnf');
  await writeFile(config, '[req]\ndistinguished_name=dn\nx509_extensions=extensions\nprompt=no\n[dn]\nCN=ReqWS Disposable Verification Test\n[extensions]\nkeyUsage=critical,digitalSignature,keyCertSign\nextendedKeyUsage=codeSigning\nbasicConstraints=critical,CA:TRUE\n');
  certificateFile = path.join(directory, 'public.cer');
  await promisify(native.execFile)('/usr/bin/openssl', ['req', '-new', '-x509', '-nodes', '-newkey', 'rsa:4096', '-sha256', '-days', '2', '-config', config, '-outform', 'DER', '-out', certificateFile, '-keyout', path.join(directory, 'disposable.key')]);
  certificate = await readFile(certificateFile);
  pin = createHash('sha256').update(certificate).digest('hex').toUpperCase();
  app = path.join(directory, 'Fixture App.app');
  const resources = path.join(app, 'Contents/Resources');
  nestedBinary = path.join(app, 'Contents/Helpers/Nested.app/Contents/MacOS/Helper');
  await mkdir(resources, { recursive: true });
  await mkdir(path.dirname(nestedBinary), { recursive: true });
  await mkdir(path.join(app, 'Contents/MacOS'));
  for (const target of [nestedBinary, path.join(app, 'Contents/MacOS/Main')]) {
    await writeFile(target, Buffer.from('cffaedfe00000000', 'hex'));
  }
  await writeFile(path.join(resources, 'app-update.yml'), await readFile(path.resolve('build/update/app-update.yml')));
});

afterAll(async () => { if (directory) await rm(directory, { recursive: true, force: true }); });

beforeEach(() => {
  scenario = 'valid';
  extracted = [];
  commands.execute.mockImplementation(async (command, args) => {
    expect(command).toBe('/usr/bin/codesign');
    const target = args.at(-1)!;
    if (args[0] === '--verify') {
      if (scenario === 'invalid-signature') throw new Error('Fixture signature verification failed.');
      return { stdout: '', stderr: '' };
    }
    if (args.includes('--verbose=4')) {
      const signature = target === nestedBinary && scenario === 'adhoc' ? 'Signature=adhoc\n' : '';
      const flags = target === nestedBinary && scenario === 'no-runtime' ? 'flags=0x0(none)' : 'flags=0x10000(runtime)';
      return { stdout: '', stderr: `${signature}CodeDirectory ${flags}\n` };
    }
    const prefix = args.find((arg) => arg.startsWith('--extract-certificates='))?.split('=', 2)[1];
    if (!prefix) throw new Error('Fixture codesign interpreted the separate prefix as an input file.');
    extracted.push(prefix);
    if (scenario !== 'missing-leaf') {
      const bytes = scenario === 'wrong-nested-certificate' && target === nestedBinary ? Buffer.from('different signing certificate') : certificate;
      await writeFile(`${prefix}0`, bytes);
    }
    return { stdout: '', stderr: '' };
  });
});

async function expectExtractedFilesCleaned() {
  for (const prefix of extracted) {
    await expect(lstat(path.dirname(prefix))).rejects.toMatchObject({ code: 'ENOENT' });
  }
}

describe('complete release signature verification with mocked codesign', () => {
  it('checks every bundle and nested Mach-O certificate and removes extracted files', async () => {
    await verifyReleaseSignature(app, pin, certificateFile);
    const targets = await signingTargets(app);
    expect(targets).toHaveLength(4);
    const checked = commands.execute.mock.calls.filter(([, args]) => args.some((arg) => arg.startsWith('--extract-certificates='))).map(([, args]) => args.at(-1));
    expect(checked).toEqual(targets);
    await expectExtractedFilesCleaned();
  });

  it.each(['wrong-nested-certificate', 'adhoc', 'no-runtime', 'missing-leaf'] as const)('rejects %s and still removes extracted files', async (failure) => {
    scenario = failure;
    const result = verifyReleaseSignature(app, pin, certificateFile);
    if (failure === 'wrong-nested-certificate') await expect(result).rejects.toThrow('does not match the pinned certificate');
    else if (failure === 'missing-leaf') await expect(result).rejects.toMatchObject({ code: 'ENOENT' });
    else await expect(result).rejects.toThrow('certificate and Hardened Runtime');
    expect(extracted.length).toBeGreaterThan(0);
    await expectExtractedFilesCleaned();
  });

  it('stops when native signature verification fails, before extracting certificates', async () => {
    scenario = 'invalid-signature';
    await expect(verifyReleaseSignature(app, pin, certificateFile)).rejects.toThrow('Fixture signature verification failed');
    expect(extracted).toEqual([]);
  });
});
