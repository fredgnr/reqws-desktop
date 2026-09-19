import { execFile } from 'node:child_process';
import { X509Certificate } from 'node:crypto';
import { copyFile, mkdir, mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { describe, expect, it } from 'vitest';

describe.skipIf(process.platform !== 'darwin')('native macOS certificate extraction', () => {
  it('extracts the leaf certificate to the supplied prefix for paths containing spaces', async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-certificate-regression-'));
    try {
      const source = '/usr/bin/codesign';
      // Copy an existing system-signed executable, without running or resigning
      // it. The test neither creates identities nor changes keychains or trust.
      const nested = path.join(directory, 'Fixture App.app/Contents/Helpers/Nested Helper.app/Contents/MacOS');
      await mkdir(nested, { recursive: true });
      const target = path.join(nested, 'Signed Helper');
      await copyFile(source, target);
      const output = path.join(directory, 'Certificate Output');
      await mkdir(output);
      const baselinePrefix = path.join(output, 'baseline-');
      const actualPrefix = path.join(output, 'nested certificate-');
      // Run the real module with plain Node. A disposable cwd also contains any
      // default codesign0 files if the optional-argument regression returns.
      await promisify(execFile)(process.execPath, ['--input-type=module', '-e', `
        const { extractSigningCertificate } = await import(process.argv[1]);
        await extractSigningCertificate(process.argv[2], process.argv[3]);
        await extractSigningCertificate(process.argv[4], process.argv[5]);
      `, new URL('../../scripts/verify-macos-signature.mts', import.meta.url).href, source, baselinePrefix, target, actualPrefix], { cwd: directory });
      const baseline = await readFile(`${baselinePrefix}0`);
      const actual = await readFile(`${actualPrefix}0`);
      expect(actual.equals(baseline)).toBe(true);
      expect(new X509Certificate(actual).raw.equals(actual)).toBe(true);
      expect(await readdir(output)).toContain('nested certificate-0');
      expect((await readdir(directory)).sort()).toEqual(['Certificate Output', 'Fixture App.app']);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  });
});
