import { execFile, spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { once } from 'node:events';
import { lstat, mkdir, readFile, rename, stat, symlink, unlink, writeFile } from 'node:fs/promises';
import { get, request as httpsRequest } from 'node:https';
import path from 'node:path';
import { promisify } from 'node:util';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { GitRunner } from '../../src/main/services/git-runner';
import { createGitOrigin, type GitOrigin } from '../e2e/fixtures/git-origin';
import { createIsolation, isolatedGitSpawn, type Isolation } from '../e2e/fixtures/isolation';
import { assertOwnedProcessesExited } from '../e2e/fixtures/process-registry';

describe('isolated E2E HTTPS Git origin', () => {
  let isolations: Isolation[];
  let origins: GitOrigin[];

  beforeEach(() => {
    isolations = [];
    origins = [];
  });

  afterEach(async () => {
    for (const origin of origins) await origin.close();
    for (const isolation of isolations) await isolation.dispose();
  });

  const isolate = async (): Promise<Isolation> => {
    const isolation = await createIsolation();
    isolations.push(isolation);
    return isolation;
  };

  const startOrigin = async (isolation: Isolation): Promise<GitOrigin> => {
    const origin = await createGitOrigin(isolation);
    origins.push(origin);
    return origin;
  };

  it('uses production URL checks and real HTTPS ls-remote, clone, fetch and exact origin matching', async () => {
    const isolation = await isolate();
    const origin = await startOrigin(isolation);
    const one = await origin.addRepository('one');
    const two = await origin.addRepository('two');
    const git = await GitRunner.create(isolatedGitSpawn(isolation));
    await expect(git.lsRemote(one.barePath)).rejects.toMatchObject({ code: 'INVALID_INPUT' });
    await expect(git.lsRemote(`http://${one.url.slice('https://'.length)}`)).rejects.toMatchObject({ code: 'INVALID_INPUT' });
    const remote = await git.lsRemote(one.url);
    expect(remote.stdout).toContain('ref: refs/heads/main\tHEAD');

    const destination = path.join(isolation.workspaceRoot, 'one');
    await git.clone(one.url, destination);
    expect((await lstat(path.join(destination, '.git'))).isDirectory()).toBe(true);
    expect(await readFile(path.join(destination, 'README.txt'), 'utf8')).toBe('ReqWS Git fixture: one\n');
    expect((await git.run(['remote', 'get-url', 'origin'], { cwd: destination })).stdout.trim()).toBe(one.url);
    expect(await git.originUrlMatches(destination, one.url)).toBe(true);
    expect(await git.originUrlMatches(destination, two.url)).toBe(false);
    expect((await git.run(['branch', '--show-current'], { cwd: destination })).stdout.trim()).toBe('main');

    const published = await git.run(['update-ref', 'refs/heads/feature/e2e', 'HEAD'], { cwd: one.barePath });
    expect(published.exitCode).toBe(0);
    await git.fetch(destination);
    expect(await git.refExists(destination, 'refs/remotes/origin/feature/e2e')).toBe(true);
    const ca = await git.run(['config', '--get-urlmatch', 'http.sslCAInfo', one.url]);
    expect(ca.stdout.trim()).toBe(path.join(isolation.originRoot, '.tls', 'ca.pem'));
    expect((await git.run(['config', '--get-urlmatch', 'http.sslVerify', one.url])).stdout.trim()).toBe('true');
  });

  it('rejects the temporary certificate when the isolated Git client does not trust its CA', async () => {
    const isolation = await isolate();
    const origin = await startOrigin(isolation);
    const repository = await origin.addRepository('one');
    const untrusted = await isolate();
    const git = await GitRunner.create(isolatedGitSpawn(untrusted));

    await expect(git.lsRemote(repository.url)).rejects.toMatchObject({
      code: 'REPOSITORY_UNREACHABLE',
      detail: expect.stringMatching(/certificate|SSL|issuer/iu),
    });
  });

  it('reports transport failure and succeeds after the origin becomes available again', async () => {
    const isolation = await isolate();
    const origin = await startOrigin(isolation);
    const repository = await origin.addRepository('one');
    const git = await GitRunner.create(isolatedGitSpawn(isolation));
    origin.setUnavailable(true);
    await expect(git.lsRemote(repository.url)).rejects.toMatchObject({ code: 'REPOSITORY_UNREACHABLE' });
    await expect(git.clone(repository.url, path.join(isolation.workspaceRoot, 'failed'))).rejects.toMatchObject({ code: 'CLONE_FAILED' });
    origin.setUnavailable(false);
    expect((await git.lsRemote(repository.url)).exitCode).toBe(0);
    await git.clone(repository.url, path.join(isolation.workspaceRoot, 'retry'));
  });

  it('exports only registered read-only endpoints and refuses traversal or duplicate names', async () => {
    const isolation = await isolate();
    const origin = await startOrigin(isolation);
    const repository = await origin.addRepository('one');
    const git = await GitRunner.create(isolatedGitSpawn(isolation));
    await expect(origin.addRepository('../outside')).rejects.toThrow('single lowercase ASCII slugs');
    await expect(origin.addRepository('one')).rejects.toThrow('already exists');
    await expect(git.lsRemote(origin.url('missing'))).rejects.toMatchObject({ code: 'REPOSITORY_UNREACHABLE' });
    const ca = await readFile(path.join(isolation.originRoot, '.tls', 'ca.pem'));
    const base = new URL(repository.url);
    for (const endpoint of ['/one.git/git-receive-pack', '/%2e%2e/one.git/info/refs?service=git-upload-pack']) {
      const status = await new Promise<number | undefined>((resolve, reject) => {
        const request = get({ hostname: base.hostname, port: base.port, path: endpoint, ca }, (response) => {
          response.resume();
          response.once('end', () => resolve(response.statusCode));
        });
        request.once('error', reject);
      });
      expect(status).toBe(404);
    }
  });

  it('excludes inherited Git routing, credential and proxy environment values', async () => {
    const isolation = await isolate();
    const origin = await startOrigin(isolation);
    const repository = await origin.addRepository('one');
    isolation.env.GIT_DIR = '/outside/git';
    isolation.env.GIT_CONFIG_SYSTEM = '/outside/config';
    isolation.env.HTTPS_PROXY = 'http://127.0.0.1:1';
    isolation.env.SSH_AUTH_SOCK = '/outside/agent';
    const git = await GitRunner.create(isolatedGitSpawn(isolation));
    const result = await git.run(['ls-remote', '--symref', '--', repository.url, 'HEAD'], {
      env: { HOME: '/outside/home', GIT_DIR: '/outside/git', GIT_CONFIG_SYSTEM: '/outside/config' },
    });
    expect(result.exitCode).toBe(0);
    const config = await git.run(['config', '--show-origin', '--list']);
    expect(config.stdout).toContain(`file:${path.join(isolation.home, '.gitconfig')}`);
    expect(config.stdout).not.toContain('/outside/');
    expect(config.stdout).not.toContain('file:.git/config');
  });

  it('closes the listener and an incomplete upload-pack request before fixture cleanup', async () => {
    const isolation = await isolate();
    const origin = await startOrigin(isolation);
    const repository = await origin.addRepository('one');
    const ca = await readFile(path.join(isolation.originRoot, '.tls', 'ca.pem'));
    const request = httpsRequest(`${repository.url}/git-upload-pack`, {
      ca,
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-git-upload-pack-request',
        'Content-Length': '20',
        Expect: '100-continue',
      },
    });
    request.on('error', () => undefined);
    const accepted = new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Git fixture did not accept the incomplete request.')), 5_000);
      request.once('continue', () => { clearTimeout(timer); resolve(); });
      request.once('error', (error) => { clearTimeout(timer); reject(error); });
    });
    request.flushHeaders();
    try {
      await accepted;
      await origin.close();
      await origin.close();
      expect(await readFile(path.join(isolation.logs, 'git-http-backend.log'), 'utf8'))
        .toContain('POST /one.git/git-upload-pack exit=');
      const git = await GitRunner.create(isolatedGitSpawn(isolation));
      await expect(git.lsRemote(repository.url)).rejects.toMatchObject({ code: 'REPOSITORY_UNREACHABLE' });
    } finally {
      request.destroy();
    }
  });

  it('removes only the owned canonical root and preserves directories reached through symlinks', async () => {
    const isolation = await isolate();
    const outside = await isolate();
    const sentinel = path.join(outside.workspaceRoot, 'keep.txt');
    await writeFile(sentinel, 'preserve external fixture');
    await symlink(outside.workspaceRoot, path.join(isolation.workspaceRoot, 'external'));
    const displaced = `${isolation.root}-displaced`;
    await rename(isolation.root, displaced);
    try {
      await symlink(outside.root, isolation.root);
      await expect(isolation.dispose()).rejects.toThrow('directory identity changed');
      expect(await readFile(sentinel, 'utf8')).toBe('preserve external fixture');
    } finally {
      await unlink(isolation.root);
      await rename(displaced, isolation.root);
    }
    const markerPath = path.join(isolation.root, '.reqws-e2e-owner');
    const marker = await readFile(markerPath, 'utf8');
    try {
      await writeFile(markerPath, 'unowned');
      await expect(isolation.dispose()).rejects.toThrow('ownership marker');
    } finally {
      await writeFile(markerPath, marker);
    }
    await isolation.dispose();
    await isolation.dispose();
    await expect(stat(isolation.root)).rejects.toMatchObject({ code: 'ENOENT' });
    expect(await readFile(sentinel, 'utf8')).toBe('preserve external fixture');
  });

  it('preserves a live Git fixture and lets the runner terminate only its registered detached group', async () => {
    const isolation = await isolate();
    const scripts = path.resolve('scripts');
    const registry = path.join(isolation.root, 'test-results', 'source-processes.jsonl');
    await mkdir(path.dirname(registry));
    await writeFile(registry, '');
    const cwd = vi.spyOn(process, 'cwd').mockReturnValue(isolation.root);
    vi.stubEnv('REQWS_E2E_PROCESS_REGISTRY', registry);
    let child: ChildProcessWithoutNullStreams | undefined;
    const unrelated = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
      detached: true, shell: false, stdio: 'pipe',
    });
    const unrelatedClosed = once(unrelated, 'close');
    let operation: ReturnType<GitRunner['run']> | undefined;
    try {
      const spawnGit = isolatedGitSpawn(isolation);
      const git = await GitRunner.create((command, args, options) => {
        child = spawnGit(command, args, options);
        return child;
      });
      operation = git.run(['hash-object', '--stdin']);
      const blocked = child!;
      expect(blocked.pid).toBeGreaterThan(1);
      const records = (await readFile(registry, 'utf8')).trim().split('\n').map((line) => JSON.parse(line));
      expect(records).toContainEqual(expect.objectContaining({
        event: 'start', pid: blocked.pid, scope: isolation.home,
        signature: expect.stringMatching(new RegExp(`^${blocked.pid}\\s+${blocked.pid}\\s+`, 'u')),
      }));
      expect(() => assertOwnedProcessesExited(path.join(isolation.root, 'another-home'))).not.toThrow();
      await expect(isolation.dispose()).rejects.toThrow('preserving its fixture');
      expect((await stat(isolation.root)).isDirectory()).toBe(true);
      const result = await promisify(execFile)('python3', ['-c',
        'import json, pathlib, sys; sys.path.insert(0, sys.argv[1]); from desktop_e2e import cleanup_owned_processes; print(json.dumps(cleanup_owned_processes(pathlib.Path(sys.argv[2]))))',
        scripts, registry,
      ], { timeout: 10_000 });
      expect(JSON.parse(result.stdout)).toEqual([blocked.pid]);
      expect((await operation).exitCode).not.toBe(0);
      expect(blocked.signalCode).toBe('SIGKILL');
      expect(() => process.kill(unrelated.pid!, 0)).not.toThrow();
      expect(() => assertOwnedProcessesExited(isolation.home)).not.toThrow();
      await isolation.dispose();
      await expect(stat(isolation.root)).rejects.toMatchObject({ code: 'ENOENT' });
    } finally {
      if (child?.pid && child.exitCode === null && child.signalCode === null) process.kill(-child.pid, 'SIGKILL');
      if (unrelated.pid && unrelated.exitCode === null && unrelated.signalCode === null) process.kill(-unrelated.pid, 'SIGKILL');
      await Promise.allSettled([operation, unrelatedClosed]);
      cwd.mockRestore();
      vi.unstubAllEnvs();
    }
  });
});
