import { mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { desktopFocusSchema, editorLaunchSchema, linkRequestSchema, publishLinkJson, readLinkJson, waitLinkRequest } from '../e2e/fixtures/desktop-link';
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
    expect(linkRequestSchema.parse({ ...request, name: 'coverage' })).toEqual({ ...request, name: 'coverage' });
    for (const invalid of [
      { ...request, path: '/another/workspace' }, { ...request, operation: 'exec' },
      { ...request, name: '../escape' }, { ...request, operation: 'select', selected: ['repo-a', 'repo-a'] },
      { ...request, operation: 'select', selected: ['other'] },
      { ...request, operation: 'select', name: 'coverage', selected: ['repo-c'] },
    ]) expect(linkRequestSchema.safeParse(invalid).success).toBe(false);
  });

  it('fails promptly on peer abort and on a missing request deadline', async () => {
    await expect(waitLinkRequest(root, sessionId, 1, 1)).rejects.toThrow('timed out');
    await publishLinkJson(path.join(root, 'abort.json'), { sessionId });
    await expect(waitLinkRequest(root, sessionId, 1, 1_000)).rejects.toThrow('aborted');
  });

  it('records only the fixed GoLand OS spawn boundary with shell disabled', () => {
    const launch = { command: '/usr/bin/open', args: ['-a', '/fixture/home/Applications/GoLand.app', '/fixture/workspaces/selection/.reqws/ide/goland'],
      shell: false, boundary: 'os-spawn-only' };
    expect(editorLaunchSchema.parse(launch)).toEqual(launch);
    for (const invalid of [{ ...launch, shell: true }, { ...launch, command: '/bin/sh' },
      { ...launch, args: ['-a', launch.args[1]] }, { ...launch, args: ['-R', launch.args[1], launch.args[2]] },
      { ...launch, boundary: 'real-native-launch' }]) {
      expect(editorLaunchSchema.safeParse(invalid).success).toBe(false);
    }
  });

  it('restricts external-edit focus to the first selection revision and requires observed native focus', () => {
    const focusRequest = { ...request, operation: 'focus-external-edit', phase: 'late-files', revision: 1 };
    expect(linkRequestSchema.parse(focusRequest)).toEqual(focusRequest);
    for (const invalid of [{ ...focusRequest, name: 'coverage' }, { ...focusRequest, phase: 'projection' },
      { ...focusRequest, revision: 2 }, { ...focusRequest, target: '/another/app' },
      { ...focusRequest, selected: ['repo-a'] }]) expect(linkRequestSchema.safeParse(invalid).success).toBe(false);
    const focus = { name: 'selection', phase: 'late-files', revision: 1, workspaceId: 'workspace',
      bindingId: randomUUID(), desktopPid: 99, windowId: 1, focused: true };
    expect(desktopFocusSchema.parse(focus)).toEqual(focus);
    for (const invalid of [{ ...focus, focused: false }, { ...focus, desktopPid: 0 },
      { ...focus, desktopPid: true }, { ...focus, windowId: -1 }, { ...focus, revision: 2 }]) {
      expect(desktopFocusSchema.safeParse(invalid).success).toBe(false);
    }
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
