import {
  execFile,
  spawn,
  type ChildProcess,
  type SpawnOptions,
} from 'node:child_process';
import { constants as fsConstants } from 'node:fs';
import {
  access,
  lstat,
  readFile,
  realpath,
} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { ReqwsError } from '../../shared/errors';
import type {
  AvailabilityItem,
  SystemAvailability,
} from '../../shared/types';
import { GitRunner } from './git-runner';

const OPEN_PATH = '/usr/bin/open';
const PLUTIL_PATH = '/usr/bin/plutil';
const VSCODE_APP_NAME = 'Visual Studio Code';
const CURSOR_APP_NAME = 'Cursor';
const GOLAND_APP_NAME = 'GoLand';
const GOLAND_BUNDLE_IDENTIFIER = 'com.jetbrains.goland';
const GOLAND_EXECUTABLE_NAME = 'goland';
const TOOLBOX_STATE_MAX_BYTES = 1_048_576;
const TOOLBOX_STATE_MAX_RECORDS = 1_024;
const TOOLBOX_GOLAND_MAX_CANDIDATES = 64;
const GOLAND_CANDIDATE_VALIDATION_CONCURRENCY = 4;
const TOOLBOX_STATE_RELATIVE_PATH = path.join(
  'Library',
  'Application Support',
  'JetBrains',
  'Toolbox',
  'state.json',
);
const CURSOR_CLI_RELATIVE_PATH = path.join(
  'Contents',
  'Resources',
  'app',
  'bin',
  'cursor',
);
const CODE_CLI_RELATIVE_PATH = path.join(
  'Contents',
  'Resources',
  'app',
  'bin',
  'code',
);

export interface WorkspacePaths {
  workspaceFilePath: string;
  rootPath: string;
}

export type ResolveWorkspacePaths = (
  workspaceId: string,
) => Promise<WorkspacePaths>;

export type AccessPath = (filePath: string, mode?: number) => Promise<void>;

export interface PathMetadata {
  size: number;
  isDirectory(): boolean;
  isFile(): boolean;
  isSymbolicLink(): boolean;
}

export type InspectPath = (filePath: string) => Promise<PathMetadata>;
export type RealpathPath = (filePath: string) => Promise<string>;
export type ReadTextFile = (filePath: string) => Promise<string>;
export type ReadPlistValue = (
  plistPath: string,
  key: 'CFBundleExecutable' | 'CFBundleIdentifier' | 'CFBundleShortVersionString',
) => Promise<string>;

export type SpawnOpenProcess = (
  command: string,
  args: readonly string[],
  options: SpawnOptions,
) => ChildProcess;

export interface EditorLauncherDependencies {
  accessPath?: AccessPath;
  inspectPath?: InspectPath;
  realpathPath?: RealpathPath;
  readTextFile?: ReadTextFile;
  readPlistValue?: ReadPlistValue;
  spawnProcess?: SpawnOpenProcess;
  homeDirectory?: string;
  systemApplicationsDirectory?: string;
  processEnvironment?: NodeJS.ProcessEnv;
  resolveGitPath?: () => Promise<string>;
  resolveGoLandWorkspacePaths?: ResolveWorkspacePaths;
}

interface GoLandCandidate {
  appPath: string;
  sourceRank: number;
  releaseLabel?: string;
  advertisedLauncher?: string;
}

interface ValidatedGoLandCandidate {
  appPath: string;
  sourceRank: number;
  version: string;
  releaseLabel: string;
}

function spawnOpenProcess(
  command: string,
  args: readonly string[],
  options: SpawnOptions,
): ChildProcess {
  return spawn(command, args, options);
}

function plistValue(
  plistPath: string,
  key: 'CFBundleExecutable' | 'CFBundleIdentifier' | 'CFBundleShortVersionString',
): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    execFile(
      PLUTIL_PATH,
      ['-extract', key, 'raw', '-o', '-', plistPath],
      {
        encoding: 'utf8',
        maxBuffer: 16_384,
        shell: false,
      },
      (error, stdout) => {
        if (error) {
          reject(error);
          return;
        }
        resolve(stdout.trim());
      },
    );
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function toolboxAppPath(launchCommand: string): string | null {
  if (!path.isAbsolute(launchCommand) || launchCommand.includes('\0')) {
    return null;
  }
  const marker = `${path.sep}Contents${path.sep}MacOS${path.sep}`;
  const markerIndex = launchCommand.lastIndexOf(marker);
  if (markerIndex <= 0) return null;
  const appPath = launchCommand.slice(0, markerIndex);
  return path.extname(appPath).toLocaleLowerCase('en-US') === '.app'
    ? appPath
    : null;
}

function goLandCandidateKey(candidate: GoLandCandidate): string {
  return JSON.stringify([
    candidate.sourceRank,
    path.resolve(candidate.appPath),
    candidate.advertisedLauncher
      ? path.resolve(candidate.advertisedLauncher)
      : null,
  ]);
}

function deduplicateGoLandCandidates(
  candidates: readonly GoLandCandidate[],
): GoLandCandidate[] {
  const unique = new Map<string, GoLandCandidate>();
  for (const candidate of candidates) {
    const key = goLandCandidateKey(candidate);
    if (!unique.has(key)) unique.set(key, candidate);
  }
  return [...unique.values()];
}

function versionParts(value: string): number[] {
  return Array.from(value.matchAll(/\d+/gu), ([part]) => Number(part));
}

function compareVersionPartsDescending(left: string, right: string): number {
  const leftParts = versionParts(left);
  const rightParts = versionParts(right);
  const count = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < count; index += 1) {
    const difference = (rightParts[index] ?? 0) - (leftParts[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
}

function isPreviewCandidate(candidate: ValidatedGoLandCandidate): boolean {
  return /(?:^|[^a-z])(?:alpha|beta|eap|preview|rc)(?:[^a-z]|$)/iu.test(
    `${candidate.version} ${candidate.releaseLabel}`,
  );
}

function compareGoLandCandidates(
  left: ValidatedGoLandCandidate,
  right: ValidatedGoLandCandidate,
): number {
  const previewDifference = Number(isPreviewCandidate(left))
    - Number(isPreviewCandidate(right));
  if (previewDifference !== 0) return previewDifference;
  const versionDifference = compareVersionPartsDescending(
    left.version,
    right.version,
  );
  if (versionDifference !== 0) return versionDifference;
  if (left.sourceRank !== right.sourceRank) {
    return left.sourceRank - right.sourceRank;
  }
  if (left.appPath < right.appPath) return -1;
  if (left.appPath > right.appPath) return 1;
  return 0;
}

export class EditorLauncher {
  private readonly accessPath: AccessPath;
  private readonly inspectPath: InspectPath;
  private readonly realpathPath: RealpathPath;
  private readonly readTextFile: ReadTextFile;
  private readonly readPlistValue: ReadPlistValue;
  private readonly spawnProcess: SpawnOpenProcess;
  private readonly homeDirectory: string;
  private readonly systemApplicationsDirectory: string;
  private readonly processEnvironment: NodeJS.ProcessEnv;
  private readonly resolveGitPath: () => Promise<string>;
  private readonly resolveGoLandWorkspacePaths: ResolveWorkspacePaths;

  constructor(
    private readonly resolveWorkspacePaths: ResolveWorkspacePaths,
    dependencies: EditorLauncherDependencies = {},
  ) {
    this.accessPath = dependencies.accessPath ?? access;
    this.inspectPath = dependencies.inspectPath ?? lstat;
    this.realpathPath = dependencies.realpathPath ?? realpath;
    this.readTextFile = dependencies.readTextFile
      ?? ((filePath) => readFile(filePath, 'utf8'));
    this.readPlistValue = dependencies.readPlistValue ?? plistValue;
    this.spawnProcess = dependencies.spawnProcess ?? spawnOpenProcess;
    this.homeDirectory = dependencies.homeDirectory ?? os.homedir();
    this.systemApplicationsDirectory =
      dependencies.systemApplicationsDirectory ?? '/Applications';
    this.processEnvironment = {
      ...(dependencies.processEnvironment ?? process.env),
    };
    this.resolveGitPath =
      dependencies.resolveGitPath ?? (() => GitRunner.resolveGitPath());
    this.resolveGoLandWorkspacePaths =
      dependencies.resolveGoLandWorkspacePaths ?? resolveWorkspacePaths;
  }

  async getAvailability(): Promise<SystemAvailability> {
    const [git, vscode, cursor, goland] = await Promise.all([
      this.detectGit(),
      this.detectApplication(VSCODE_APP_NAME),
      this.detectApplication(CURSOR_APP_NAME),
      this.detectGoLand(),
    ]);
    return { git, vscode, cursor, goland };
  }

  async openVSCode(workspaceId: string): Promise<void> {
    const paths = await this.resolveWorkspacePaths(workspaceId);
    await this.ensurePathExists(paths.workspaceFilePath, 'workspace file');
    await this.ensureApplicationAvailable(VSCODE_APP_NAME);
    await this.runOpen(['-a', VSCODE_APP_NAME, paths.workspaceFilePath]);
  }

  async openCursor(workspaceId: string): Promise<void> {
    const paths = await this.resolveWorkspacePaths(workspaceId);
    await this.ensurePathExists(paths.workspaceFilePath, 'workspace file');
    await this.openCursorTarget(paths.workspaceFilePath);
  }

  async openCursorRoot(workspaceId: string): Promise<void> {
    const paths = await this.resolveWorkspacePaths(workspaceId);
    await this.ensurePathExists(paths.rootPath, 'workspace root');
    await this.openCursorTarget(paths.rootPath);
  }

  async openGoLand(workspaceId: string): Promise<void> {
    const paths = await this.resolveGoLandWorkspacePaths(workspaceId);
    await this.ensureCanonicalDirectory(paths.rootPath, 'workspace root');
    await this.ensureRegularFile(
      path.join(paths.rootPath, '.reqws', 'workspace.json'),
      'workspace manifest',
    );
    const applicationPath = await this.ensureGoLandAvailable();
    await this.runOpen(['-a', applicationPath, paths.rootPath]);
  }

  async revealInFinder(workspaceId: string): Promise<void> {
    const paths = await this.resolveWorkspacePaths(workspaceId);
    await this.ensurePathExists(paths.rootPath, 'workspace root');
    await this.runOpen(['-R', paths.rootPath]);
  }

  private async detectGit(): Promise<AvailabilityItem> {
    try {
      return { available: true, path: await this.resolveGitPath() };
    } catch {
      return {
        available: false,
        reasonCode: 'NOT_FOUND',
        reason: 'Git was not found in PATH or a supported macOS location.',
      };
    }
  }

  private async detectApplication(appName: string): Promise<AvailabilityItem> {
    for (const applicationPath of this.applicationPaths(appName)) {
      try {
        await this.accessPath(applicationPath, fsConstants.F_OK);
        return { available: true, path: applicationPath };
      } catch {
        // Continue with the per-user Applications directory.
      }
    }

    return {
      available: false,
      reasonCode: 'NOT_FOUND',
      reason: `${appName} was not found in /Applications or ~/Applications.`,
    };
  }

  private async detectGoLand(): Promise<AvailabilityItem> {
    const toolboxCandidates = await this.toolboxGoLandCandidates();
    const rawCandidates = deduplicateGoLandCandidates([
      {
        appPath: path.join(
          this.systemApplicationsDirectory,
          `${GOLAND_APP_NAME}.app`,
        ),
        sourceRank: 0,
      },
      {
        appPath: path.join(
          this.homeDirectory,
          'Applications',
          `${GOLAND_APP_NAME}.app`,
        ),
        sourceRank: 1,
      },
      ...toolboxCandidates,
    ]);
    const validated = (
      await this.validateGoLandCandidates(
        rawCandidates.map((candidate) => ({
          ...candidate,
          appPath: path.resolve(candidate.appPath),
        })),
      )
    ).filter(
      (candidate): candidate is ValidatedGoLandCandidate => candidate !== null,
    );
    const canonicalCandidates = new Map<string, ValidatedGoLandCandidate>();
    for (const candidate of validated) {
      const existing = canonicalCandidates.get(candidate.appPath);
      if (!existing || compareGoLandCandidates(candidate, existing) < 0) {
        canonicalCandidates.set(candidate.appPath, candidate);
      }
    }
    const selected = [...canonicalCandidates.values()]
      .sort(compareGoLandCandidates)[0];
    if (selected) return { available: true, path: selected.appPath };

    return {
      available: false,
      reasonCode: 'NOT_FOUND',
      reason:
        'GoLand was not found in /Applications, ~/Applications, or JetBrains Toolbox.',
    };
  }

  private async toolboxGoLandCandidates(): Promise<GoLandCandidate[]> {
    const statePath = path.join(
      this.homeDirectory,
      TOOLBOX_STATE_RELATIVE_PATH,
    );
    try {
      const metadata = await this.inspectPath(statePath);
      if (
        !metadata.isFile()
        || metadata.isSymbolicLink()
        || metadata.size > TOOLBOX_STATE_MAX_BYTES
      ) {
        return [];
      }
      const contents = await this.readTextFile(statePath);
      if (Buffer.byteLength(contents, 'utf8') > TOOLBOX_STATE_MAX_BYTES) {
        return [];
      }
      const state: unknown = JSON.parse(contents);
      if (!isRecord(state) || !Array.isArray(state.tools)) return [];
      if (state.tools.length > TOOLBOX_STATE_MAX_RECORDS) return [];
      const candidates: GoLandCandidate[] = [];
      const candidateKeys = new Set<string>();
      for (const tool of state.tools) {
        if (!isRecord(tool)) continue;
        const productCode = tool.productCode;
        const toolId = tool.toolId;
        if (
          productCode !== 'GO'
          && !(typeof toolId === 'string' && toolId.toLowerCase() === 'goland')
        ) {
          continue;
        }
        if (typeof tool.launchCommand !== 'string') continue;
        const appPath = toolboxAppPath(tool.launchCommand);
        if (!appPath) continue;
        const labels = [tool.displayVersion, tool.channelId]
          .filter((value): value is string => typeof value === 'string')
          .join(' ');
        const candidate: GoLandCandidate = {
          appPath,
          sourceRank: 2,
          releaseLabel: labels,
          advertisedLauncher: tool.launchCommand,
        };
        const candidateKey = goLandCandidateKey(candidate);
        if (candidateKeys.has(candidateKey)) continue;
        if (candidates.length >= TOOLBOX_GOLAND_MAX_CANDIDATES) return [];
        candidateKeys.add(candidateKey);
        candidates.push(candidate);
      }
      return candidates;
    } catch {
      return [];
    }
  }

  private async validateGoLandCandidates(
    candidates: readonly GoLandCandidate[],
  ): Promise<Array<ValidatedGoLandCandidate | null>> {
    const results: Array<ValidatedGoLandCandidate | null | undefined> =
      new Array(candidates.length);
    let nextIndex = 0;
    const workerCount = Math.min(
      GOLAND_CANDIDATE_VALIDATION_CONCURRENCY,
      candidates.length,
    );
    const worker = async (): Promise<void> => {
      while (nextIndex < candidates.length) {
        const index = nextIndex;
        nextIndex += 1;
        const candidate = candidates[index];
        if (!candidate) continue;
        results[index] = await this.validateGoLandCandidate(candidate);
      }
    };
    await Promise.all(
      Array.from({ length: workerCount }, () => worker()),
    );
    return results.filter(
      (result): result is ValidatedGoLandCandidate | null =>
        result !== undefined,
    );
  }

  private async validateGoLandCandidate(
    candidate: GoLandCandidate,
  ): Promise<ValidatedGoLandCandidate | null> {
    try {
      const applicationPath = await this.realpathPath(candidate.appPath);
      if (
        !path.isAbsolute(applicationPath)
        || path.extname(applicationPath).toLocaleLowerCase('en-US') !== '.app'
      ) {
        return null;
      }
      const applicationMetadata = await this.inspectPath(applicationPath);
      if (
        !applicationMetadata.isDirectory()
        || applicationMetadata.isSymbolicLink()
      ) {
        return null;
      }

      const infoPlistPath = path.join(
        applicationPath,
        'Contents',
        'Info.plist',
      );
      const canonicalInfoPlistPath = await this.realpathPath(infoPlistPath);
      const infoPlistMetadata = await this.inspectPath(infoPlistPath);
      if (
        canonicalInfoPlistPath !== infoPlistPath
        || !infoPlistMetadata.isFile()
        || infoPlistMetadata.isSymbolicLink()
      ) {
        return null;
      }

      const [bundleIdentifier, executableName] = await Promise.all([
        this.readPlistValue(infoPlistPath, 'CFBundleIdentifier'),
        this.readPlistValue(infoPlistPath, 'CFBundleExecutable'),
      ]);
      if (
        bundleIdentifier !== GOLAND_BUNDLE_IDENTIFIER
        || executableName !== GOLAND_EXECUTABLE_NAME
      ) {
        return null;
      }

      const executablePath = path.join(
        applicationPath,
        'Contents',
        'MacOS',
        executableName,
      );
      const canonicalExecutablePath = await this.realpathPath(executablePath);
      const executableMetadata = await this.inspectPath(executablePath);
      if (
        canonicalExecutablePath !== executablePath
        || !executableMetadata.isFile()
        || executableMetadata.isSymbolicLink()
      ) {
        return null;
      }
      await this.accessPath(executablePath, fsConstants.X_OK);
      if (candidate.advertisedLauncher) {
        const advertisedLauncher = await this.realpathPath(
          candidate.advertisedLauncher,
        );
        if (advertisedLauncher !== canonicalExecutablePath) return null;
      }

      let version = '';
      try {
        version = await this.readPlistValue(
          infoPlistPath,
          'CFBundleShortVersionString',
        );
      } catch {
        // Version only affects deterministic preference, not bundle identity.
      }
      return {
        appPath: applicationPath,
        sourceRank: candidate.sourceRank,
        version,
        releaseLabel: candidate.releaseLabel ?? '',
      };
    } catch {
      return null;
    }
  }

  private async ensureApplicationAvailable(appName: string): Promise<string> {
    const availability = await this.detectApplication(appName);
    if (availability.available && availability.path) return availability.path;
    throw new ReqwsError({
      code: 'EDITOR_NOT_FOUND',
      message: `${appName} is not installed.`,
      detail: availability.reason,
      stage: 'launching',
    });
  }

  private async ensureGoLandAvailable(): Promise<string> {
    const availability = await this.detectGoLand();
    if (availability.available && availability.path) return availability.path;
    throw new ReqwsError({
      code: 'EDITOR_NOT_FOUND',
      message: 'GoLand is not installed.',
      detail: availability.reason,
      stage: 'launching',
    });
  }

  private async openCursorTarget(targetPath: string): Promise<void> {
    const applicationPath = await this.ensureApplicationAvailable(
      CURSOR_APP_NAME,
    );
    const cursorCliPath = path.join(
      applicationPath,
      CURSOR_CLI_RELATIVE_PATH,
    );
    if (await this.isExecutable(cursorCliPath)) {
      await this.runProcess(
        cursorCliPath,
        ['editor', '--new-window', targetPath],
        this.cursorProcessEnvironment(),
      );
      return;
    }

    const codeCliPath = path.join(applicationPath, CODE_CLI_RELATIVE_PATH);
    if (await this.isExecutable(codeCliPath)) {
      await this.runProcess(
        codeCliPath,
        ['--new-window', targetPath],
        this.cursorProcessEnvironment(),
      );
      return;
    }

    await this.runOpen(['-a', CURSOR_APP_NAME, targetPath]);
  }

  private async isExecutable(filePath: string): Promise<boolean> {
    try {
      await this.accessPath(filePath, fsConstants.X_OK);
      return true;
    } catch {
      return false;
    }
  }

  private cursorProcessEnvironment(): NodeJS.ProcessEnv {
    const environment = { ...this.processEnvironment };
    delete environment.VSCODE_IPC_HOOK_CLI;
    return environment;
  }

  private async ensurePathExists(
    targetPath: string,
    description: string,
  ): Promise<void> {
    if (!path.isAbsolute(targetPath)) {
      throw new ReqwsError({
        code: 'WORKSPACE_PATH_MISSING',
        message: `The ${description} path is invalid.`,
        detail: targetPath,
        stage: 'launching',
      });
    }

    try {
      await this.accessPath(targetPath, fsConstants.F_OK);
    } catch (error) {
      throw new ReqwsError(
        {
          code: 'WORKSPACE_PATH_MISSING',
          message: `The ${description} does not exist.`,
          detail: targetPath,
          stage: 'launching',
        },
        { cause: error },
      );
    }
  }

  private async ensureCanonicalDirectory(
    targetPath: string,
    description: string,
  ): Promise<void> {
    if (!path.isAbsolute(targetPath)) {
      throw this.workspacePathError(
        targetPath,
        `The ${description} path is invalid.`,
      );
    }
    try {
      const [metadata, canonicalPath] = await Promise.all([
        this.inspectPath(targetPath),
        this.realpathPath(targetPath),
      ]);
      if (
        !metadata.isDirectory()
        || metadata.isSymbolicLink()
        || !(await this.isCanonicalOrSafeTopLevelAlias(targetPath, canonicalPath))
      ) {
        throw new Error('Path is not a canonical directory.');
      }
    } catch (error) {
      throw this.workspacePathError(
        targetPath,
        `The ${description} does not exist or is unsafe.`,
        error,
      );
    }
  }

  private async ensureRegularFile(
    targetPath: string,
    description: string,
  ): Promise<void> {
    try {
      const [metadata, canonicalPath] = await Promise.all([
        this.inspectPath(targetPath),
        this.realpathPath(targetPath),
      ]);
      if (
        !metadata.isFile()
        || metadata.isSymbolicLink()
        || !(await this.isCanonicalOrSafeTopLevelAlias(targetPath, canonicalPath))
      ) {
        throw new Error('Path is not a canonical regular file.');
      }
    } catch (error) {
      throw this.workspacePathError(
        targetPath,
        `The ${description} does not exist or is unsafe.`,
        error,
      );
    }
  }

  private workspacePathError(
    targetPath: string,
    message: string,
    cause?: unknown,
  ): ReqwsError {
    return new ReqwsError(
      {
        code: 'WORKSPACE_PATH_MISSING',
        message,
        detail: targetPath,
        stage: 'launching',
      },
      { cause },
    );
  }

  /**
   * Match PathService's macOS policy for immutable top-level aliases such as
   * /tmp -> /private/tmp and /var -> /private/var. Symlinks below the first
   * path component remain visible and are rejected.
   */
  private async isCanonicalOrSafeTopLevelAlias(
    targetPath: string,
    canonicalPath: string,
  ): Promise<boolean> {
    const normalizedTarget = path.resolve(targetPath);
    if (canonicalPath === normalizedTarget) return true;

    const root = path.parse(normalizedTarget).root;
    const firstSegment = path.relative(root, normalizedTarget).split(path.sep)[0];
    if (!firstSegment) return false;
    const topLevelPath = path.join(root, firstSegment);
    try {
      const topLevelMetadata = await this.inspectPath(topLevelPath);
      if (!topLevelMetadata.isSymbolicLink()) return false;
      const canonicalTopLevelPath = await this.realpathPath(topLevelPath);
      const relative = path.relative(canonicalTopLevelPath, canonicalPath);
      if (
        relative === '..'
        || relative.startsWith(`..${path.sep}`)
        || path.isAbsolute(relative)
      ) {
        return false;
      }
      return path.resolve(topLevelPath, relative) === normalizedTarget;
    } catch {
      return false;
    }
  }

  private applicationPaths(appName: string): string[] {
    const appBundle = `${appName}.app`;
    return [
      path.join(this.systemApplicationsDirectory, appBundle),
      path.join(this.homeDirectory, 'Applications', appBundle),
    ];
  }

  private async runOpen(args: readonly string[]): Promise<void> {
    await this.runProcess(OPEN_PATH, args);
  }

  private async runProcess(
    command: string,
    args: readonly string[],
    environment?: NodeJS.ProcessEnv,
  ): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      let settled = false;
      let child: ChildProcess;
      try {
        const options: SpawnOptions = {
          shell: false,
          stdio: 'ignore',
          windowsHide: true,
        };
        if (environment) options.env = environment;
        child = this.spawnProcess(command, args, options);
      } catch (error) {
        reject(this.launchError(command, error));
        return;
      }

      child.once('error', (error) => {
        if (settled) return;
        settled = true;
        reject(this.launchError(command, error));
      });
      child.once('close', (exitCode) => {
        if (settled) return;
        settled = true;
        if (exitCode === 0) resolve();
        else reject(this.launchError(command, undefined, exitCode));
      });
    });
  }

  private launchError(
    command: string,
    error?: unknown,
    exitCode?: number | null,
  ): ReqwsError {
    const detail =
      exitCode === undefined
        ? `Unable to start ${command}.`
        : `${command} exited with code ${String(exitCode)}.`;
    return new ReqwsError(
      {
        code: 'EDITOR_NOT_FOUND',
        message: 'The requested macOS application could not be opened.',
        detail,
        stage: 'launching',
      },
      { cause: error },
    );
  }
}
