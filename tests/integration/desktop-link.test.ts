import { mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { linkRequestSchema, publishLinkJson, readLinkJson, waitLinkRequest } from '../e2e/fixtures/desktop-link';
import { assertProcessRegistryPath } from '../e2e/fixtures/process-registry';

describe('Desktop/IDE private message protocol', () => {
  let root: string;
  const sessionId = randomUUID();
  const request = { schemaVersion: 1, sessionId, sequence: 1, operation: 'create', name: 'selection' };
  beforeEach(async () => { root = await realpath(await mkdtemp(path.join(tmpdir(), 'reqws-link-test-'))); });
  afterEach(async () => { vi.unstubAllEnvs(); await rm(root, { recursive: true }); });

  it('publishes a complete immutable message without replacing an existing step', async () => {
    const filename = path.join(root, 'request-1.json');
    await publishLinkJson(filename, request);
    expect(await waitLinkRequest(root, sessionId, 1, 1_000)).toEqual(request);
    await expect(publishLinkJson(filename, { ...request, name: 'trust' })).rejects.toMatchObject({ code: 'EEXIST' });
    expect(await readLinkJson(filename)).toEqual(request);
  });

  it('rejects wrong sessions and reordered requests instead of replaying stale work', async () => {
    await publishLinkJson(path.join(root, 'request-1.json'), request);
    await expect(waitLinkRequest(root, randomUUID(), 1, 1_000)).rejects.toThrow('Stale or reordered');
    await writeFile(path.join(root, 'request-1.json'), JSON.stringify({ ...request, sequence: 2 }));
    await expect(waitLinkRequest(root, sessionId, 1, 1_000)).rejects.toThrow('Stale or reordered');
  });

  it('does not follow symbolic links or accept oversized control files', async () => {
    const sentinel = path.join(root, 'sentinel.json');
    await writeFile(sentinel, JSON.stringify(request));
    await symlink(sentinel, path.join(root, 'request-1.json'));
    await expect(waitLinkRequest(root, sessionId, 1, 1_000)).rejects.toThrow();
    expect(await readFile(sentinel, 'utf8')).toBe(JSON.stringify(request));
    await writeFile(sentinel, ' '.repeat(65_537));
    await expect(readLinkJson(sentinel)).rejects.toThrow('oversized');
  });

  it('limits operations to fixed scenarios and repository selections, never paths or shell commands', () => {
    for (const invalid of [
      { ...request, path: '/another/workspace' }, { ...request, operation: 'exec' },
      { ...request, name: '../escape' }, { ...request, operation: 'select', selected: ['repo-a', 'repo-a'] },
      { ...request, operation: 'select', selected: ['other'] },
    ]) expect(linkRequestSchema.safeParse(invalid).success).toBe(false);
  });

  it('fails promptly on peer abort and on a missing request deadline', async () => {
    await expect(waitLinkRequest(root, sessionId, 1, 1)).rejects.toThrow('timed out');
    await publishLinkJson(path.join(root, 'abort.json'), { sessionId });
    await expect(waitLinkRequest(root, sessionId, 1, 1_000)).rejects.toThrow('aborted');
  });

  it('allows only the current linked session registry and rejects reused identities or symlinks', async () => {
    await mkdir(path.join(root, 'desktop-link'));
    await publishLinkJson(path.join(root, 'desktop-link/session.json'), { schemaVersion: 1, purpose: 'reqws-desktop-ide-link', sessionId });
    const registry = path.join(root, 'desktop-processes.jsonl');
    await writeFile(registry, '');
    vi.stubEnv('REQWS_LOCAL_IDE_RUN_ROOT', root);
    vi.stubEnv('REQWS_DESKTOP_IDE_SESSION', sessionId);
    expect(() => assertProcessRegistryPath(registry)).not.toThrow();
    vi.stubEnv('REQWS_DESKTOP_IDE_SESSION', randomUUID());
    expect(() => assertProcessRegistryPath(registry)).toThrow('another session');
    vi.stubEnv('REQWS_DESKTOP_IDE_SESSION', sessionId);
    await rm(registry);
    await writeFile(path.join(root, 'other.jsonl'), 'keep');
    await symlink(path.join(root, 'other.jsonl'), registry);
    expect(() => assertProcessRegistryPath(registry)).toThrow('Unsafe');
    expect(await readFile(path.join(root, 'other.jsonl'), 'utf8')).toBe('keep');
  });
});
