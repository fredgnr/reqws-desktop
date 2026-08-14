import { spawn, type ChildProcess, type SpawnOptions } from 'node:child_process';
import { constants as fsConstants } from 'node:fs';
import { access } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { ReqwsError } from '../../shared/errors';
import type {
  AvailabilityItem,
  SystemAvailability,
} from '../../shared/types';
import { GitRunner } from './git-runner';

const OPEN_PATH = '/usr/bin/open';
const VSCODE_APP_NAME = 'Visual Studio Code';
const CURSOR_APP_NAME = 'Cursor';
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

interface WorkspacePaths {
  workspaceFilePath: string;
  rootPath: string;
}

export type ResolveWorkspacePaths = (
  workspaceId: string,
) => Promise<WorkspacePaths>;

export type AccessPath = (filePath: string, mode?: number) => Promise<void>;

export type SpawnOpenProcess = (
  command: string,
  args: readonly string[],
  options: SpawnOptions,
) => ChildProcess;

export interface EditorLauncherDependencies {
  accessPath?: AccessPath;
  spawnProcess?: SpawnOpenProcess;
  homeDirectory?: string;
  processEnvironment?: NodeJS.ProcessEnv;
  resolveGitPath?: () => Promise<string>;
}

function spawnOpenProcess(
  command: string,
  args: readonly string[],
  options: SpawnOptions,
): ChildProcess {
  return spawn(command, args, options);
}

export class EditorLauncher {
  private readonly accessPath: AccessPath;
  private readonly spawnProcess: SpawnOpenProcess;
  private readonly homeDirectory: string;
  private readonly processEnvironment: NodeJS.ProcessEnv;
  private readonly resolveGitPath: () => Promise<string>;

  constructor(
    private readonly resolveWorkspacePaths: ResolveWorkspacePaths,
    dependencies: EditorLauncherDependencies = {},
  ) {
    this.accessPath = dependencies.accessPath ?? access;
    this.spawnProcess = dependencies.spawnProcess ?? spawnOpenProcess;
    this.homeDirectory = dependencies.homeDirectory ?? os.homedir();
    this.processEnvironment = {
      ...(dependencies.processEnvironment ?? process.env),
    };
    this.resolveGitPath =
      dependencies.resolveGitPath ?? (() => GitRunner.resolveGitPath());
  }

  async getAvailability(): Promise<SystemAvailability> {
    const [git, vscode, cursor] = await Promise.all([
      this.detectGit(),
      this.detectApplication(VSCODE_APP_NAME),
      this.detectApplication(CURSOR_APP_NAME),
    ]);
    return { git, vscode, cursor };
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

  private applicationPaths(appName: string): string[] {
    const appBundle = `${appName}.app`;
    return [
      path.join('/Applications', appBundle),
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
