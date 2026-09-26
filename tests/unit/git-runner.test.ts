import { EventEmitter } from 'node:events';
import path from 'node:path';
import { PassThrough } from 'node:stream';
import type {
  ChildProcessWithoutNullStreams,
  SpawnOptionsWithoutStdio,
} from 'node:child_process';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApplicationActivityGate } from '../../src/main/services/application-activity-gate';

import {
  GIT_OUTPUT_LIMIT_BYTES,
  GIT_CLONE_IDLE_TIMEOUT_MS,
  GitRunner,
  redactGitOutput,
  type SpawnGitProcess,
} from '../../src/main/services/git-runner';

interface SpawnCall {
  command: string;
  args: readonly string[];
  options: SpawnOptionsWithoutStdio;
}

function fakeChild(): ChildProcessWithoutNullStreams {
  const process = Object.assign(new EventEmitter(), {
    stdout: new PassThrough(),
    stderr: new PassThrough(),
    stdin: new PassThrough(),
    stdio: [new PassThrough(), new PassThrough(), new PassThrough()],
    kill: vi.fn(() => true),
  });
  return process as unknown as ChildProcessWithoutNullStreams;
}

function successfulSpawn(
  calls: SpawnCall[],
  outputForCall?: (index: number) => { stdout?: Buffer | string; stderr?: Buffer | string },
): SpawnGitProcess {
  return (command, args, options) => {
    const index = calls.length;
    calls.push({ command, args, options });
    const child = fakeChild();
    queueMicrotask(() => {
      const output = outputForCall?.(index);
      if (output?.stdout) child.stdout.emit('data', output.stdout);
      if (output?.stderr) child.stderr.emit('data', output.stderr);
      child.emit('close', 0, null);
    });
    return child;
  };
}

describe('GitRunner', () => {
  it('holds update admission through a running Git child and rejects new Git after shutdown admission', async () => {
    const gate = new ApplicationActivityGate();
    let pending!: ChildProcessWithoutNullStreams;
    const spawnProcess: SpawnGitProcess = (_command, args) => {
      const child = fakeChild();
      if (args[0] === '--version') queueMicrotask(() => child.emit('close', 0, null));
      else pending = child;
      return child;
    };
    const runner = await GitRunner.fromPath('/usr/bin/git', spawnProcess, { activityGate: gate });
    const operation = runner.run(['fetch']);
    expect(() => gate.acquireShutdown()).toThrow();
    pending.emit('close', 0, null);
    await operation;
    const release = gate.acquireShutdown();
    await expect(runner.run(['clone'])).rejects.toMatchObject({ code: 'UPDATE_BUSY' });
    expect(pending.kill).not.toHaveBeenCalled();
    release();
  });

  afterEach(() => { vi.unstubAllEnvs(); vi.useRealTimers(); });

  it('retains a live child lease after an error until its close event', async () => {
    const gate = new ApplicationActivityGate();
    let pending!: ChildProcessWithoutNullStreams;
    const spawnProcess: SpawnGitProcess = (_command, args) => {
      const child = Object.assign(fakeChild(), { pid: 12345 });
      if (args[0] === '--version') queueMicrotask(() => child.emit('close', 0, null));
      else pending = child;
      return child;
    };
    const runner = await GitRunner.fromPath('/usr/bin/git', spawnProcess, { activityGate: gate, signalProcess: (child, signal) => { child.kill(signal); } });
    const operation = runner.run(['fetch']);
    let settled = false;
    void operation.catch(() => { settled = true; });
    pending.emit('error', new Error('Unable to signal child'));
    await Promise.resolve();
    expect(settled).toBe(false);
    expect(() => gate.acquireShutdown()).toThrow();
    pending.emit('close', 1, null);
    await expect(operation).rejects.toMatchObject({ code: 'GIT_PROCESS_FAILED' });
    gate.acquireShutdown()();
  });

  it('allows long progressing clones, escalates stalled clones, and releases only after close', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const gate = new ApplicationActivityGate();
    let pending!: ChildProcessWithoutNullStreams;
    const spawnProcess: SpawnGitProcess = (_command, args) => {
      const child = Object.assign(fakeChild(), { pid: 12345 });
      if (args[0] === '--version') queueMicrotask(() => child.emit('close', 0, null));
      else pending = child;
      return child;
    };
    const runner = await GitRunner.fromPath('/git', spawnProcess, {
      activityGate: gate,
      signalProcess: (child, signal) => { child.kill(signal); },
    });
    const operation = runner.clone('ssh://git@example.test/repo.git', '/staging/repo');
    const rejected = expect(operation).rejects.toMatchObject({ code: 'GIT_PROCESS_TIMEOUT', stage: 'cloning' });
    let settled = false;
    void operation.catch(() => { settled = true; });
    for (let interval = 0; interval < 4; interval++) {
      await vi.advanceTimersByTimeAsync(GIT_CLONE_IDLE_TIMEOUT_MS - 1);
      pending.stderr.emit('data', 'Receiving objects');
    }
    expect(pending.kill).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(GIT_CLONE_IDLE_TIMEOUT_MS);
    expect(pending.kill).toHaveBeenCalledWith('SIGTERM');
    pending.emit('error', new Error('signal failed'));
    await vi.advanceTimersByTimeAsync(2_000);
    expect(pending.kill).toHaveBeenCalledWith('SIGKILL');
    expect(settled).toBe(false);
    expect(() => gate.acquireShutdown()).toThrow();
    pending.emit('close', null, 'SIGKILL');
    await rejected;
    gate.acquireShutdown()();
    expect(vi.getTimerCount()).toBe(0);
    const next = runner.clone('ssh://git@example.test/repo.git', '/staging/next');
    pending.emit('close', 0, null);
    await next;
    expect(vi.getTimerCount()).toBe(0);
  });

  it('resolves PATH Git to an absolute path and validates it with git --version', async () => {
    const calls: SpawnCall[] = [];
    vi.stubEnv('PATH', ['/custom/bin', '/other/bin'].join(path.delimiter));
    const runner = await GitRunner.create(successfulSpawn(calls));

    expect(runner.gitPath).toBe('/custom/bin/git');
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({
      command: '/custom/bin/git',
      args: ['--version'],
      options: { shell: false, stdio: 'pipe' },
    });
  });

  it('tries the fixed macOS fallbacks in the documented order', async () => {
    const calls: SpawnCall[] = [];
    vi.stubEnv('PATH', '/custom/bin');
    const spawnProcess: SpawnGitProcess = (command, args, options) => {
      calls.push({ command, args, options });
      const child = fakeChild();
      queueMicrotask(() => {
        if (command === '/usr/local/bin/git') child.emit('close', 0, null);
        else child.emit('error', Object.assign(new Error('not found'), { code: 'ENOENT' }));
      });
      return child;
    };

    const runner = await GitRunner.create(spawnProcess);

    expect(runner.gitPath).toBe('/usr/local/bin/git');
    expect(calls.map((call) => call.command)).toEqual([
      '/custom/bin/git',
      '/usr/bin/git',
      '/opt/homebrew/bin/git',
      '/usr/local/bin/git',
    ]);
  });

  it('ignores empty PATH entries instead of resolving Git from cwd', async () => {
    const calls: SpawnCall[] = [];
    vi.stubEnv('PATH', ['', '/custom/bin', ''].join(path.delimiter));

    const runner = await GitRunner.create(successfulSpawn(calls));

    expect(runner.gitPath).toBe('/custom/bin/git');
    expect(calls.map((call) => call.command)).toEqual(['/custom/bin/git']);
  });

  it('makes relative PATH entries absolute and removes duplicate candidates', async () => {
    const calls: SpawnCall[] = [];
    const relativeGit = path.resolve('relative-bin', 'git');
    vi.stubEnv('PATH', [
      'relative-bin',
      '/usr/bin',
      'relative-bin',
    ].join(path.delimiter));
    const spawnProcess: SpawnGitProcess = (command, args, options) => {
      calls.push({ command, args, options });
      const child = fakeChild();
      queueMicrotask(() => {
        if (command === '/usr/local/bin/git') child.emit('close', 0, null);
        else child.emit('error', Object.assign(new Error('not found'), { code: 'ENOENT' }));
      });
      return child;
    };

    await GitRunner.create(spawnProcess);

    expect(calls.map((call) => call.command)).toEqual([
      relativeGit,
      '/usr/bin/git',
      '/opt/homebrew/bin/git',
      '/usr/local/bin/git',
    ]);
  });

  it('always passes individual arguments and shell false', async () => {
    const calls: SpawnCall[] = [];
    const runner = await GitRunner.fromPath('/trusted/git', successfulSpawn(calls));
    await runner.run(['ls-remote', '--symref', 'repo; touch /tmp/nope', 'HEAD']);

    expect(calls[1]).toMatchObject({
      command: '/trusted/git',
      args: ['ls-remote', '--symref', 'repo; touch /tmp/nope', 'HEAD'],
      options: { shell: false, stdio: 'pipe' },
    });
  });

  it('validates origin stdout without exposing unsafe raw output or weakening general redaction', async () => {
    const calls: SpawnCall[] = [];
    let url = 'ssh://git@example.test/team/repo.git';
    const runner = await GitRunner.fromPath('/trusted/git', successfulSpawn(calls, () => ({ stdout: url })));
    expect(await runner.originUrlMatches('/repo', url)).toBe(true);
    expect((await runner.run(['remote', 'get-url', 'origin'])).stdout).toContain('<redacted>');
    for (const unsafe of ['https://rose:secret@example.test/repo.git', 'ssh://git:secret@example.test/repo.git', 'https://example.test/repo?token=secret']) {
      url = unsafe;
      expect(await runner.originUrlMatches('/repo', unsafe)).toBe(false);
      expect(JSON.stringify(await runner.run(['remote', 'get-url', 'origin']))).not.toContain('secret');
    }
  });

  it('caps each output stream, marks truncation, and retains the tail', async () => {
    const calls: SpawnCall[] = [];
    const oversized = Buffer.concat([
      Buffer.alloc(GIT_OUTPUT_LIMIT_BYTES + 256, 'x'),
      Buffer.from('FINAL-TAIL'),
    ]);
    const runner = await GitRunner.fromPath(
      '/trusted/git',
      successfulSpawn(calls, (index) =>
        index === 1 ? { stdout: oversized, stderr: oversized } : {},
      ),
    );

    const result = await runner.run(['status']);

    expect(Buffer.byteLength(result.stdout)).toBeLessThanOrEqual(
      GIT_OUTPUT_LIMIT_BYTES,
    );
    expect(Buffer.byteLength(result.stderr)).toBeLessThanOrEqual(
      GIT_OUTPUT_LIMIT_BYTES,
    );
    expect(result.stdout).toContain('output truncated');
    expect(result.stdout).toMatch(/FINAL-TAIL$/u);
    expect(result.stderr).toMatch(/FINAL-TAIL$/u);
  });

  it('redacts credentials from output instead of persisting or returning them', () => {
    expect(
      redactGitOutput(
        'fatal https://rose:secret@example.test/repo.git?access_token=abc123 token=xyz',
      ),
    ).toBe(
      'fatal https://<redacted>@example.test/repo.git?access_token=<redacted> token=<redacted>',
    );
  });

  it('redacts full authorization values and common OAuth query keys', () => {
    expect(redactGitOutput(
      'Authorization: Bearer abc123 https://example.test/repo?oauth_token=xyz&private_token=def',
    )).toBe(
      'Authorization: <redacted> https://example.test/repo?oauth_token=<redacted>&private_token=<redacted>',
    );
  });

  it('terminates options before user-controlled remote and ref arguments', async () => {
    const calls: SpawnCall[] = [];
    const runner = await GitRunner.fromPath('/trusted/git', successfulSpawn(calls));

    await runner.lsRemote('git@example.test:team/order-api.git');
    await runner.clone('git@example.test:team/order-api.git', '/tmp/-destination');
    await runner.refExists('/tmp/repo', 'refs/remotes/origin/feature/safe');

    expect(calls[1]?.args).toEqual([
      'ls-remote',
      '--symref',
      '--',
      'git@example.test:team/order-api.git',
      'HEAD',
    ]);
    expect(calls[2]?.args).toEqual([
      'clone',
      '--no-hardlinks',
      '--progress',
      '--',
      'git@example.test:team/order-api.git',
      '/tmp/-destination',
    ]);
    expect(calls[3]?.args).toEqual([
      'show-ref',
      '--verify',
      '--quiet',
      '--',
      'refs/remotes/origin/feature/safe',
    ]);
  });

  it.each([
    'https://rose:super-secret@example.test/order-api.git',
    '--upload-pack=evil',
    'git@example.test:order-api.git\n--config=evil',
    'ext::sh -c evil',
  ])('rejects an unsafe remote before spawning Git', async (url) => {
    const calls: SpawnCall[] = [];
    const runner = await GitRunner.fromPath('/trusted/git', successfulSpawn(calls));

    const error = await runner.clone(url, '/tmp/destination').catch(
      (reason: unknown) => reason,
    );

    expect(error).toMatchObject({ code: 'INVALID_INPUT', stage: 'validating' });
    expect(JSON.stringify(error)).not.toContain('super-secret');
    expect(calls).toHaveLength(1);
  });

  it('rejects option-like and control-character branch/ref input before spawning Git', async () => {
    const calls: SpawnCall[] = [];
    const runner = await GitRunner.fromPath('/trusted/git', successfulSpawn(calls));

    expect(await runner.checkBranchName('--config=core.sshCommand=evil')).toBe(false);
    expect(await runner.checkBranchName('feature/safe\n--config=evil')).toBe(false);
    await expect(runner.refExists('/tmp/repo', '--head')).rejects.toMatchObject({
      code: 'INVALID_INPUT',
    });
    expect(calls).toHaveLength(1);
  });

  it('strips inherited Git routing variables while preserving the system environment', async () => {
    const calls: SpawnCall[] = [];
    const runner = await GitRunner.fromPath('/trusted/git', successfulSpawn(calls));

    await runner.run(['status'], {
      env: {
        HOME: '/safe/home',
        SSH_AUTH_SOCK: '/safe/agent.sock',
        GIT_DIR: '/outside/git-dir',
        GIT_WORK_TREE: '/outside/work-tree',
        GIT_COMMON_DIR: '/outside/common',
        GIT_OBJECT_DIRECTORY: '/outside/objects',
        GIT_ALTERNATE_OBJECT_DIRECTORIES: '/outside/alternates',
        GIT_INDEX_FILE: '/outside/index',
        GIT_SSH_COMMAND: 'malicious-command',
      },
    });

    expect(calls[1]?.options.env).toMatchObject({
      HOME: '/safe/home',
      SSH_AUTH_SOCK: '/safe/agent.sock',
      GIT_TERMINAL_PROMPT: '0',
    });
    expect(
      Object.keys(calls[1]?.options.env ?? {}).filter((key) =>
        key.startsWith('GIT_') && key !== 'GIT_TERMINAL_PROMPT',
      ),
    ).toEqual([]);
  });

  it('rejects local paths in production and permits only the isolated test runner', async () => {
    const productionCalls: SpawnCall[] = [];
    const production = await GitRunner.fromPath(
      '/trusted/git',
      successfulSpawn(productionCalls),
    );
    await expect(production.clone('/tmp/local.git', '/tmp/clone')).rejects
      .toMatchObject({ code: 'INVALID_INPUT' });
    expect(productionCalls).toHaveLength(1);

    const testCalls: SpawnCall[] = [];
    const fixtureRunner = await GitRunner.fromPath(
      '/trusted/git',
      successfulSpawn(testCalls),
      { allowLocalRepositoryPaths: true },
    );
    await fixtureRunner.clone('/tmp/local.git', '/tmp/clone');
    expect(testCalls[1]?.args).toEqual([
      'clone',
      '--no-hardlinks',
      '--progress',
      '--',
      '/tmp/local.git',
      '/tmp/clone',
    ]);
  });
});
