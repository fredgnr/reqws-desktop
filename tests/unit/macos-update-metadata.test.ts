import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { findFile, parseUpdateInfo, resolveFiles } from 'electron-updater/out/providers/Provider';

import { createUpdateMetadata, prepareMacosUpdate, verifyUpdateMetadata } from '../../scripts/prepare-macos-update.mts';
import { parseUpdateConfig } from '../../src/shared/update-config';

const directories: string[] = [];
async function fixture() {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-metadata-'));
  directories.push(directory);
  const bytes = Buffer.from('Final Desktop ZIP bytes used only by metadata contract tests.');
  const filename = 'ReqWS-1.2.3-macos-arm64.zip';
  await writeFile(path.join(directory, filename), bytes);
  await writeFile(path.join(directory, 'ReqWS-1.2.3-goland-plugin.zip'), 'plugin ZIP');
  return { directory, filename, bytes };
}
afterEach(async () => { await Promise.all(directories.splice(0).map(async (directory) => await rm(directory, { recursive: true, force: true }))); });

describe('macOS update metadata', () => {
  it('matches final bytes and is parsed and selected by the pinned updater', async () => {
    const { directory, bytes, filename } = await fixture();
    await prepareMacosUpdate(['--write', directory, '1.2.3']);
    const contents = await readFile(path.join(directory, 'latest-mac.yml'), 'utf8');
    const parsed = parseUpdateInfo(contents, 'latest-mac.yml', new URL('https://example.invalid/latest-mac.yml'));
    expect(parsed.files).toEqual([{
      url: filename, sha512: createHash('sha512').update(bytes).digest('base64'), size: bytes.length,
    }]);
    expect(findFile(resolveFiles(parsed, new URL('https://example.invalid/')), 'zip')?.url.pathname).toBe(`/${filename}`);
    await expect(prepareMacosUpdate(['--verify', directory, '1.2.3'])).resolves.toBeUndefined();
    await expect(prepareMacosUpdate(['--write', directory, '1.2.3'])).rejects.toThrow();
  });

  it.each(['../1.2.3', '1.2.3/other', 'v1.2.3', '1.2.3-beta', '01.2.3', '1.2.3+build'])('rejects an unsafe or unsupported version %s', async (version) => {
    const { directory } = await fixture();
    await expect(createUpdateMetadata(directory, version)).rejects.toThrow();
  });

  it.each(['https://example.invalid/evil.zip', '/absolute.zip', '../evil.zip', 'ReqWS-1.2.3-goland-plugin.zip'])('rejects unexpected feed files %s', async (filename) => {
    const { directory } = await fixture();
    const metadata = await createUpdateMetadata(directory, '1.2.3');
    metadata.files[0]!.url = filename;
    metadata.path = filename;
    await expect(verifyUpdateMetadata(directory, '1.2.3', metadata)).rejects.toThrow();
  });

  it('rejects missing, extra and duplicate files, fields, version, size and checksum mismatches', async () => {
    const { directory } = await fixture();
    const metadata = await createUpdateMetadata(directory, '1.2.3');
    for (const invalid of [
      { ...metadata, files: [] }, { ...metadata, files: [...metadata.files, ...metadata.files] },
      { ...metadata, token: 'forbidden' }, { ...metadata, version: '1.2.4' },
      { ...metadata, files: [{ ...metadata.files[0], size: 1 }] },
      { ...metadata, sha512: createHash('sha512').update('wrong').digest('base64') },
      { ...metadata, sha512: createHash('sha512').update('wrong').digest('hex') },
      { ...metadata, releaseDate: 'not a date' },
    ]) await expect(verifyUpdateMetadata(directory, '1.2.3', invalid)).rejects.toThrow();
  });

  it('rejects replacing ZIP bytes after metadata generation and symlink ZIPs', async () => {
    const { directory, filename } = await fixture();
    const metadata = await createUpdateMetadata(directory, '1.2.3');
    await writeFile(path.join(directory, filename), 'changed');
    await expect(verifyUpdateMetadata(directory, '1.2.3', metadata)).rejects.toThrow();
    await rm(path.join(directory, filename));
    await symlink(path.join(directory, 'ReqWS-1.2.3-goland-plugin.zip'), path.join(directory, filename));
    await expect(createUpdateMetadata(directory, '1.2.3')).rejects.toThrow();
  });

  it('accepts only the signed fixed GitHub configuration', async () => {
    const raw = await readFile(path.resolve('build/update/app-update.yml'), 'utf8');
    const config = parseUpdateConfig(raw);
    for (const invalid of [
      { ...config, token: 'forbidden' }, { ...config, url: 'https://example.invalid' },
      { ...config, provider: 'generic' }, { ...config, owner: 'other' },
      { ...config, repo: 'other' }, { ...config, private: true },
      { ...config, updaterCacheDirName: '../other' },
    ]) expect(() => parseUpdateConfig(JSON.stringify(invalid))).toThrow();
  });
});
