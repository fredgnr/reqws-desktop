import {
  spawn,
  type ChildProcessWithoutNullStreams,
  type SpawnOptionsWithoutStdio,
} from 'node:child_process';
import path from 'node:path';

import { isSafeRepositoryUrl } from '../../shared/repository-utils';

export const GIT_OUTPUT_LIMIT_BYTES = 1024 * 1024;
export const GIT_LS_REMOTE_TIMEOUT_MS = 30_000;
export const GIT_DEFAULT_TIMEOUT_MS = 5 * 60_000;
export const GIT_CLONE_IDLE_TIMEOUT_MS = 15 * 60_000;

const GIT_RESOLUTION_TIMEOUT_MS = 5_000;
const OUTPUT_TRUNCATION_MARKER = Buffer.from(
  '[... output truncated; showing final output ...]\n',
  'utf8',
);

const FIXED_GIT_CANDIDATES = [
  '/usr/bin/git',
  '/opt/homebrew/bin/git',
  '/usr/local/bin/git',
] as const;

function gitCandidates(searchPath: string | undefined): string[] {
  const candidates = [
    ...(searchPath ?? '')
      .split(path.delimiter)
      // Empty PATH entries mean cwd to POSIX lookup. Do not discover a Git
      // executable from the app's mutable working directory.
      .filter((directory) => directory.length > 0)
      .map((directory) => path.resolve(directory, 'git')),
    ...FIXED_GIT_CANDIDATES,
  ];
  const seen = new Set<string>();
  return candidates.filter((candidate) => {
    if (seen.has(candidate)) return false;
    seen.add(candidate);
    return true;
  });
}

export type GitErrorCode =
  | 'INVALID_INPUT'
  | 'GIT_NOT_FOUND'
  | 'GIT_PROCESS_FAILED'
  | 'GIT_PROCESS_TIMEOUT'
  | 'INVALID_BRANCH_NAME'
  | 'REPOSITORY_UNREACHABLE'
  | 'CLONE_FAILED'
  | 'DEFAULT_BRANCH_NOT_FOUND'
  | 'FEATURE_BRANCH_CHECKOUT_FAILED'
  | 'REPOSITORY_PATH_CONFLICT';

export interface GitRunOptions {
  cwd?: string;
  timeoutMs?: number;
  idleTimeoutMs?: number;
  env?: NodeJS.ProcessEnv;
  onStdout?: (chunk: string) => void;
  onStderr?: (chunk: string) => void;
}

export interface GitRunnerOptions {
  activityGate?: import('./application-activity-gate').ApplicationActivityGate;
  /** Test-only escape hatch for isolated local bare-repository fixtures. */
  allowLocalRepositoryPaths?: boolean;
  /** Test seams for termination and long clone inactivity windows. */
  cloneIdleTimeoutMs?: number;
  signalProcess?: (child: ChildProcessWithoutNullStreams, signal: NodeJS.Signals) => void;
}

export interface GitRunResult {
  exitCode: number;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

export interface GitErrorOptions {
  detail?: string;
  stage?: string;
  cause?: unknown;
}

/**
 * A stable service-layer error. IPC may serialize this directly or normalize it
 * into the shared ReqWS error payload without interpreting Git's prose.
 */
export class GitServiceError extends Error {
  readonly code: GitErrorCode;
  readonly detail?: string;
  readonly stage?: string;

  constructor(code: GitErrorCode, message: string, options: GitErrorOptions = {}) {
    super(message, { cause: options.cause });
    this.name = 'GitServiceError';
    this.code = code;
    this.detail = options.detail;
    this.stage = options.stage;
  }
}

export type SpawnGitProcess = (
  command: string,
  args: readonly string[],
  options: SpawnOptionsWithoutStdio,
) => ChildProcessWithoutNullStreams;

class TailBuffer {
  private buffer = Buffer.alloc(0);
  private truncated = false;

  constructor(private readonly limitBytes: number) {}

  append(chunk: Buffer): void {
    const availableTailBytes = Math.max(
      0,
      this.limitBytes - OUTPUT_TRUNCATION_MARKER.length,
    );
    const combined = Buffer.concat([this.buffer, chunk]);

    if (!this.truncated && combined.length <= this.limitBytes) {
      this.buffer = combined;
      return;
    }

    this.truncated = true;
    this.buffer = combined.subarray(
      Math.max(0, combined.length - availableTailBytes),
    );
  }

  toString(): string {
    const output = this.truncated
      ? Buffer.concat([OUTPUT_TRUNCATION_MARKER, this.buffer])
      : this.buffer;
    return output.toString('utf8');
  }
}

/** Redact the common credential forms that Git may echo in an error. */
export function redactGitOutput(value: string): string {
  return value
    .replace(
      /(\b(?:authorization|proxy-authorization)\s*:\s*)(?:Bearer|Basic)\s+[^\s]+/giu,
      '$1<redacted>',
    )
    .replace(
      /([A-Za-z][A-Za-z0-9+.-]{0,31}:\/\/)[^\s/@]+@/gu,
      '$1<redacted>@',
    )
    .replace(
      /([?&](?:access[_-]?token|api[_-]?key|auth|authorization|credential|oauth[_-]?token|password|private[_-]?(?:key|token)|secret|token)=)[^\s&#]*/giu,
      '$1<redacted>',
    )
    .replace(
      /((?:authorization|oauth[_-]?token|password|private[_-]?token|token)\s*[:=]\s*)[^\s&#]+/giu,
      '$1<redacted>',
    );
}

function hasUnsafeGitArgumentCharacters(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint < 0x20 || codePoint === 0x7f;
  });
}

function isSafeBranchArgument(branch: string): boolean {
  return (
    branch.length > 0 &&
    !branch.startsWith('-') &&
    !hasUnsafeGitArgumentCharacters(branch)
  );
}

function isSafeLocalRepositoryPath(url: string): boolean {
  const normalized = url.normalize('NFC').trim();
  return (
    normalized === url &&
    path.isAbsolute(normalized) &&
    !normalized.startsWith('-') &&
    !hasUnsafeGitArgumentCharacters(normalized)
  );
}

function assertSafeRepositoryUrl(
  url: string,
  allowLocalRepositoryPaths: boolean,
): void {
  if (
    !isSafeRepositoryUrl(url) &&
    !(allowLocalRepositoryPaths && isSafeLocalRepositoryPath(url))
  ) {
    throw new GitServiceError(
      'INVALID_INPUT',
      'Repository URL must use credential-free HTTPS or SSH.',
      { stage: 'validating' },
    );
  }
}

/**
 * Git inherits dozens of repository-routing variables from its parent. Strip
 * every GIT_* value and set only our non-interactive policy so a launcher
 * environment cannot redirect metadata outside the repository just verified.
 */
export function sanitizeGitEnvironment(
  source: NodeJS.ProcessEnv,
): NodeJS.ProcessEnv {
  const environment = { ...source };
  for (const key of Object.keys(environment)) {
    if (key.toLocaleUpperCase('en-US').startsWith('GIT_')) {
      delete environment[key];
    }
  }
  environment.GIT_TERMINAL_PROMPT = '0';
  return environment;
}

function resultDetail(result: GitRunResult): string | undefined {
  const detail = redactGitOutput(result.stderr.trim() || result.stdout.trim());
  return detail.length > 0 ? detail : undefined;
}

function spawnNodeProcess(
  command: string,
  args: readonly string[],
  options: SpawnOptionsWithoutStdio,
): ChildProcessWithoutNullStreams {
  return spawn(command, args, options);
}

export class GitRunner {
  private constructor(
    readonly gitPath: string,
    private readonly spawnProcess: SpawnGitProcess,
    private readonly options: Readonly<GitRunnerOptions> = {},
  ) {}

  static async create(
    spawnProcess: SpawnGitProcess = spawnNodeProcess,
    options: GitRunnerOptions = {},
  ): Promise<GitRunner> {
    const gitPath = await GitRunner.resolveGitPath(spawnProcess);
    return new GitRunner(gitPath, spawnProcess, options);
  }

  static async fromPath(
    gitPath: string,
    spawnProcess: SpawnGitProcess = spawnNodeProcess,
    options: GitRunnerOptions = {},
  ): Promise<GitRunner> {
    const runner = new GitRunner(gitPath, spawnProcess, options);
    try {
      const result = await runner.run(['--version'], {
        timeoutMs: GIT_RESOLUTION_TIMEOUT_MS,
      });
      if (!result.timedOut && result.exitCode === 0) return runner;

      throw new GitServiceError('GIT_NOT_FOUND', 'Git executable is unavailable.', {
        detail: resultDetail(result),
      });
    } catch (error) {
      if (error instanceof GitServiceError && error.code === 'GIT_NOT_FOUND') {
        throw error;
      }
      throw new GitServiceError('GIT_NOT_FOUND', 'Git executable is unavailable.');
    }
  }

  static async resolveGitPath(spawnProcess: SpawnGitProcess = spawnNodeProcess): Promise<string> {
    for (const candidate of gitCandidates(process.env.PATH)) {
      const runner = new GitRunner(candidate, spawnProcess);
      try {
        const result = await runner.run(['--version'], {
          timeoutMs: GIT_RESOLUTION_TIMEOUT_MS,
        });
        if (!result.timedOut && result.exitCode === 0) return candidate;
      } catch {
        // ENOENT and other candidate-specific failures are expected here.
      }
    }

    throw new GitServiceError(
      'GIT_NOT_FOUND',
      'Git was not found in PATH or the supported macOS install locations.',
    );
  }

  async run(args: readonly string[], options: GitRunOptions = {}): Promise<GitRunResult> {
    const result = await this.runProcess(args, options);
    return { ...result, stdout: redactGitOutput(result.stdout) };
  }

  /** Raw stdout stays inside this service, before command-specific validation. */
  private async runProcess(args: readonly string[], options: GitRunOptions): Promise<GitRunResult> {
    const release = this.options.activityGate?.enter();
    let childStarted = false;
    let childClosed = false;
    return new Promise<GitRunResult>((resolve, reject) => {
      const stdout = new TailBuffer(GIT_OUTPUT_LIMIT_BYTES);
      const stderr = new TailBuffer(GIT_OUTPUT_LIMIT_BYTES);
      let timedOut = false;
      let settled = false;
      let killTimer: NodeJS.Timeout | undefined;
      let timeout: NodeJS.Timeout | undefined;
      let idleTimer: NodeJS.Timeout | undefined;
      let terminating = false;
      let processFailure: unknown;
      let child: ChildProcessWithoutNullStreams;

      try {
        child = this.spawnProcess(this.gitPath, args, {
          cwd: options.cwd,
          env: sanitizeGitEnvironment(options.env ?? process.env),
          shell: false,
          stdio: 'pipe',
          windowsHide: true,
          detached: process.platform !== 'win32',
        });
        childStarted = child.pid !== undefined;
      } catch (error) {
        reject(
          new GitServiceError('GIT_PROCESS_FAILED', 'Unable to start Git.', {
            cause: error,
          }),
        );
        return;
      }

      const cleanupTimers = (): void => {
        if (timeout) clearTimeout(timeout);
        if (killTimer) clearTimeout(killTimer);
        if (idleTimer) clearTimeout(idleTimer);
      };

      const rejectOnce = (error: unknown): void => {
        if (settled) return;
        settled = true;
        cleanupTimers();
        reject(
          error instanceof GitServiceError
            ? error
            : new GitServiceError('GIT_PROCESS_FAILED', 'Unable to start Git.', {
                cause: error,
              }),
        );
      };

      const signal = (value: NodeJS.Signals): void => {
        try {
          if (this.options.signalProcess) this.options.signalProcess(child, value);
          else if (process.platform !== 'win32' && child.pid !== undefined) process.kill(-child.pid, value);
          else child.kill(value);
        } catch (error) {
          processFailure ??= error;
        }
      };

      const terminate = (): void => {
        if (terminating || childClosed) return;
        terminating = true;
        if (timeout) clearTimeout(timeout);
        if (idleTimer) clearTimeout(idleTimer);
        killTimer = setTimeout(() => signal('SIGKILL'), 2_000);
        killTimer.unref();
        signal('SIGTERM');
      };

      const resetIdleTimeout = (): void => {
        if (options.idleTimeoutMs === undefined || terminating || childClosed) return;
        if (idleTimer) clearTimeout(idleTimer);
        idleTimer = setTimeout(() => {
          timedOut = true;
          terminate();
        }, options.idleTimeoutMs);
        idleTimer.unref();
      };

      child.stdout.on('data', (chunk: Buffer | string) => {
        resetIdleTimeout();
        const value = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        stdout.append(value);
        options.onStdout?.(redactGitOutput(value.toString('utf8')));
      });

      child.stderr.on('data', (chunk: Buffer | string) => {
        resetIdleTimeout();
        const value = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
        stderr.append(value);
        options.onStderr?.(redactGitOutput(value.toString('utf8')));
      });

      child.on('error', (error) => {
        if (!childStarted) {
          rejectOnce(error);
          return;
        }
        processFailure ??= error;
        terminate();
      });
      child.once('close', (code) => {
        childClosed = true;
        release?.();
        if (settled) return;
        if (processFailure && !timedOut) {
          rejectOnce(processFailure);
          return;
        }
        settled = true;
        cleanupTimers();
        resolve({
          exitCode: code ?? (timedOut ? 124 : 1),
          stdout: stdout.toString(),
          stderr: redactGitOutput(stderr.toString()),
          timedOut,
        });
      });

      if (options.timeoutMs !== undefined) {
        timeout = setTimeout(() => {
          timedOut = true;
          terminate();
        }, options.timeoutMs);
        timeout.unref();
      }
      resetIdleTimeout();
    }).finally(() => {
      // A failed kill/send can emit error while Git is still alive. Keep its
      // lease until close; only failed spawns have no child left to protect.
      if (!childStarted || childClosed) release?.();
    });
  }

  async checkBranchName(branch: string): Promise<boolean> {
    if (!isSafeBranchArgument(branch)) return false;
    const result = await this.run(['check-ref-format', '--branch', branch], {
      timeoutMs: GIT_DEFAULT_TIMEOUT_MS,
    });
    return !result.timedOut && result.exitCode === 0;
  }

  async lsRemote(url: string): Promise<GitRunResult> {
    assertSafeRepositoryUrl(
      url,
      this.options.allowLocalRepositoryPaths === true,
    );
    const result = await this.run(['ls-remote', '--symref', '--', url, 'HEAD'], {
      timeoutMs: GIT_LS_REMOTE_TIMEOUT_MS,
    });
    if (result.timedOut || result.exitCode !== 0) {
      throw new GitServiceError(
        'REPOSITORY_UNREACHABLE',
        result.timedOut
          ? 'Repository connection test timed out.'
          : 'Repository could not be reached.',
        { detail: resultDetail(result), stage: 'ls-remote' },
      );
    }
    return result;
  }

  async clone(url: string, destination: string): Promise<void> {
    assertSafeRepositoryUrl(
      url,
      this.options.allowLocalRepositoryPaths === true,
    );
    const result = await this.run([
      'clone',
      '--no-hardlinks',
      '--progress',
      '--',
      url,
      destination,
    ], { idleTimeoutMs: this.options.cloneIdleTimeoutMs ?? GIT_CLONE_IDLE_TIMEOUT_MS });
    if (result.timedOut) {
      throw new GitServiceError('GIT_PROCESS_TIMEOUT', 'Repository clone stopped after prolonged inactivity.', {
        detail: resultDetail(result),
        stage: 'cloning',
      });
    }
    if (result.exitCode !== 0) {
      throw new GitServiceError('CLONE_FAILED', 'Repository clone failed.', {
        detail: resultDetail(result),
        stage: 'cloning',
      });
    }
  }

  async fetch(repositoryPath: string): Promise<void> {
    const result = await this.run(['fetch', 'origin', '--prune'], {
      cwd: repositoryPath,
      timeoutMs: GIT_DEFAULT_TIMEOUT_MS,
    });
    if (result.timedOut || result.exitCode !== 0) {
      throw new GitServiceError(
        'FEATURE_BRANCH_CHECKOUT_FAILED',
        'Fetching repository refs failed.',
        { detail: resultDetail(result), stage: 'fetching' },
      );
    }
  }

  async refExists(repositoryPath: string, ref: string): Promise<boolean> {
    if (!ref || ref.startsWith('-') || hasUnsafeGitArgumentCharacters(ref)) {
      throw new GitServiceError(
        'INVALID_INPUT',
        'Git reference contains unsafe characters.',
        { stage: 'validating' },
      );
    }
    const result = await this.run(['show-ref', '--verify', '--quiet', '--', ref], {
      cwd: repositoryPath,
      timeoutMs: GIT_DEFAULT_TIMEOUT_MS,
    });
    if (result.timedOut || (result.exitCode !== 0 && result.exitCode !== 1)) {
      throw new GitServiceError(
        'FEATURE_BRANCH_CHECKOUT_FAILED',
        'Unable to inspect repository refs.',
        { detail: resultDetail(result), stage: 'switching' },
      );
    }
    return result.exitCode === 0;
  }

  private async getOriginUrl(repositoryPath: string): Promise<string | null> {
    const result = await this.runProcess(['remote', 'get-url', 'origin'], {
      cwd: repositoryPath,
      timeoutMs: GIT_DEFAULT_TIMEOUT_MS,
    });
    if (result.timedOut || result.exitCode !== 0) return null;
    const url = result.stdout.trim();
    return isSafeRepositoryUrl(url)
      || (this.options.allowLocalRepositoryPaths === true && isSafeLocalRepositoryPath(url))
      ? url
      : null;
  }

  /** Intentionally conservative: no URL rewriting, canonicalization, or guessing. */
  async originUrlMatches(repositoryPath: string, expectedUrl: string): Promise<boolean> {
    const actualUrl = await this.getOriginUrl(repositoryPath);
    return actualUrl !== null && actualUrl === expectedUrl;
  }

  async assertMatchingOrigin(
    repositoryPath: string,
    expectedUrl: string,
  ): Promise<void> {
    if (!(await this.originUrlMatches(repositoryPath, expectedUrl))) {
      throw new GitServiceError(
        'REPOSITORY_PATH_CONFLICT',
        'Existing repository path does not have the expected origin URL.',
      );
    }
  }
}
