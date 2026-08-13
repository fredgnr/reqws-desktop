#!/usr/bin/env node

import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import {
  access,
  constants,
  lstat,
  mkdir,
  open,
  readFile,
  readdir,
  realpath,
  rename,
  rm,
  stat,
} from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const REQWS_BUNDLE_ID = 'com.reqws.desktop';
export const LEGACY_REQWS_BUNDLE_ID = 'com.electron.reqws';
export const REQWS_APP_NAME = 'ReqWS.app';

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const REPOSITORY_ROOT = path.resolve(path.dirname(SCRIPT_PATH), '..');
const DEFAULT_APPLICATIONS_DIRECTORY = '/Applications';
const GLOBAL_LOCK_DIRECTORY = '/tmp';
const SUPPORTED_ARCHITECTURES = new Set(['arm64', 'x64']);

export interface CliOptions {
  arch: 'arm64' | 'x64';
  dryRun: boolean;
  help: boolean;
  installDirectory: string;
  noLaunch: boolean;
  packageOnly: boolean;
  skipCheck: boolean;
  skipCi: boolean;
}

interface CommandOptions {
  capture?: boolean;
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  quiet?: boolean;
}

interface CommandResult {
  exitCode: number;
  stderr: string;
  stdout: string;
}

export interface InstallOperations {
  copyBundle: (source: string, destination: string) => Promise<void>;
  move: (source: string, destination: string) => Promise<void>;
  remove: (target: string) => Promise<void>;
}

export interface ReplaceAppBundleOptions {
  assertNotRunning?: () => Promise<void>;
  installDirectory: string;
  launch?: (installedApp: string) => Promise<void>;
  noLaunch?: boolean;
  operations: InstallOperations;
  sourceApp: string;
  transientId?: string;
  validate: (appBundle: string) => Promise<void>;
  validateExisting: (appBundle: string) => Promise<void>;
}

interface BundleExpectation {
  arch: 'arm64' | 'x64';
  version: string;
}

interface LockMetadata {
  pid: number;
  startedAt: string;
  token: string;
}

function envFlag(value: string | undefined): boolean {
  return value !== undefined && /^(1|true|yes)$/iu.test(value);
}

function requireArgument(
  args: readonly string[],
  index: number,
  option: string,
): string {
  const value = args[index + 1];
  if (!value || value.startsWith('--')) {
    throw new Error(`${option} requires a value.`);
  }
  return value;
}

export function parseCliOptions(
  args: readonly string[],
  environment: NodeJS.ProcessEnv = process.env,
  runtimeArch: string = process.arch,
): CliOptions {
  if (!SUPPORTED_ARCHITECTURES.has(runtimeArch)) {
    throw new Error(`Unsupported native architecture: ${runtimeArch}.`);
  }

  let arch = runtimeArch as CliOptions['arch'];
  let installDirectory = environment.REQWS_APPLICATIONS_DIR
    ?? DEFAULT_APPLICATIONS_DIRECTORY;
  let dryRun = false;
  let help = false;
  let noLaunch = envFlag(environment.REQWS_SKIP_LAUNCH);
  let packageOnly = false;
  let skipCheck = envFlag(environment.REQWS_SKIP_CHECK);
  let skipCi = envFlag(environment.REQWS_SKIP_CI);

  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === '--arch') {
      arch = requireArgument(args, index, argument) as CliOptions['arch'];
      index += 1;
    } else if (argument?.startsWith('--arch=')) {
      arch = argument.slice('--arch='.length) as CliOptions['arch'];
    } else if (argument === '--dry-run') {
      dryRun = true;
    } else if (argument === '--help' || argument === '-h') {
      help = true;
    } else if (argument === '--install-dir') {
      installDirectory = requireArgument(args, index, argument);
      index += 1;
    } else if (argument?.startsWith('--install-dir=')) {
      installDirectory = argument.slice('--install-dir='.length);
    } else if (argument === '--no-launch') {
      noLaunch = true;
    } else if (argument === '--package-only') {
      packageOnly = true;
    } else if (argument === '--skip-check') {
      skipCheck = true;
    } else if (argument === '--skip-ci') {
      skipCi = true;
    } else {
      throw new Error(`Unknown option: ${argument ?? ''}`);
    }
  }

  if (!SUPPORTED_ARCHITECTURES.has(arch)) {
    throw new Error(`Unsupported architecture: ${arch}. Use arm64 or x64.`);
  }
  if (!path.isAbsolute(installDirectory)) {
    throw new Error('The install directory must be an absolute path.');
  }
  if (path.basename(path.resolve(installDirectory)).toLocaleLowerCase('en-US').endsWith('.app')) {
    throw new Error('The install directory must be a parent directory, not an .app bundle path.');
  }

  return {
    arch,
    dryRun,
    help,
    installDirectory: path.resolve(installDirectory),
    noLaunch,
    packageOnly,
    skipCheck,
    skipCi,
  };
}

function quoteArgument(value: string): string {
  return /^[A-Za-z0-9_./:=+-]+$/u.test(value)
    ? value
    : JSON.stringify(value);
}

function formatCommand(command: string, args: readonly string[]): string {
  return [command, ...args].map(quoteArgument).join(' ');
}

async function runCommand(
  command: string,
  args: readonly string[],
  options: CommandOptions = {},
): Promise<CommandResult> {
  if (!options.quiet) console.log(`$ ${formatCommand(command, args)}`);

  return await new Promise<CommandResult>((resolve, reject) => {
    const child = spawn(command, [...args], {
      cwd: options.cwd,
      env: options.env,
      shell: false,
      stdio: options.capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
    });
    let stdout = '';
    let stderr = '';
    child.stdout?.setEncoding('utf8');
    child.stderr?.setEncoding('utf8');
    child.stdout?.on('data', (chunk: string) => { stdout += chunk; });
    child.stderr?.on('data', (chunk: string) => { stderr += chunk; });
    child.once('error', reject);
    child.once('close', (code) => {
      resolve({ exitCode: code ?? 1, stderr, stdout });
    });
  });
}

async function runChecked(
  command: string,
  args: readonly string[],
  options: CommandOptions = {},
): Promise<CommandResult> {
  const result = await runCommand(command, args, options);
  if (result.exitCode !== 0) {
    const detail = result.stderr.trim() || result.stdout.trim();
    throw new Error(
      `Command failed (${result.exitCode}): ${formatCommand(command, args)}`
      + (detail ? `\n${detail}` : ''),
    );
  }
  return result;
}

async function pathExists(target: string): Promise<boolean> {
  try {
    await lstat(target);
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return false;
    throw error;
  }
}

function processIsRunning(pid: number): boolean {
  if (!Number.isSafeInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code === 'ESRCH') return false;
    if (code === 'EPERM') return true;
    throw error;
  }
}

function isSameOrContained(parent: string, candidate: string): boolean {
  const relative = path.relative(parent, candidate);
  return relative === '' || (
    relative !== '..'
    && !relative.startsWith(`..${path.sep}`)
    && !path.isAbsolute(relative)
  );
}

function parseLockMetadata(value: string, lockPath: string): LockMetadata {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new Error(`Install lock is unreadable; inspect it before removing: ${lockPath}`);
  }
  if (
    !parsed
    || typeof parsed !== 'object'
    || !Number.isSafeInteger((parsed as Partial<LockMetadata>).pid)
    || ((parsed as Partial<LockMetadata>).pid ?? 0) <= 0
    || typeof (parsed as Partial<LockMetadata>).startedAt !== 'string'
    || typeof (parsed as Partial<LockMetadata>).token !== 'string'
    || !/^[0-9a-f-]{36}$/iu.test((parsed as Partial<LockMetadata>).token ?? '')
  ) {
    throw new Error(`Install lock has invalid metadata; inspect it before removing: ${lockPath}`);
  }
  return parsed as LockMetadata;
}

export async function acquireExclusiveLock(
  lockPath: string,
): Promise<() => Promise<void>> {
  const token = randomUUID();
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      const handle = await open(lockPath, 'wx', 0o600);
      try {
        await handle.writeFile(JSON.stringify({
          pid: process.pid,
          startedAt: new Date().toISOString(),
          token,
        } satisfies LockMetadata));
        await handle.sync();
      } finally {
        await handle.close();
      }

      return async () => {
        try {
          const current = parseLockMetadata(await readFile(lockPath, 'utf8'), lockPath);
          if (current.token === token) await rm(lockPath, { force: true });
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
        }
      };
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;

      let existing: LockMetadata;
      try {
        existing = parseLockMetadata(await readFile(lockPath, 'utf8'), lockPath);
      } catch (readError) {
        if ((readError as NodeJS.ErrnoException).code === 'ENOENT') continue;
        throw readError;
      }
      const status = processIsRunning(existing.pid) ? 'running' : 'possibly stale';
      throw new Error(
        `Another ReqWS build/install lock is ${status} (pid ${existing.pid}, `
        + `started ${existing.startedAt}). If no install is active, inspect and `
        + `remove this lock manually: ${lockPath}`,
        { cause: error },
      );
    }
  }
  throw new Error(`Could not acquire install lock: ${lockPath}`);
}

function scopedLockPath(scope: string): string {
  const digest = createHash('sha256').update(scope).digest('hex').slice(0, 20);
  return path.join(GLOBAL_LOCK_DIRECTORY, `reqws-install-${digest}.lock`);
}

async function aliasAwareRealPath(target: string): Promise<string> {
  const resolved = path.resolve(target);
  const canonical = await realpath(resolved);
  if (canonical === resolved) return resolved;

  // macOS exposes root-owned aliases such as /var -> /private/var. Preserve
  // only that first path component; symlinks anywhere deeper remain visible
  // and are rejected by assertRealDirectory.
  const root = path.parse(resolved).root;
  const firstSegment = path.relative(root, resolved).split(path.sep)[0];
  if (!firstSegment) return canonical;
  const topLevelPath = path.join(root, firstSegment);
  try {
    const topLevelStat = await lstat(topLevelPath);
    if (!topLevelStat.isSymbolicLink()) return canonical;
    const canonicalTopLevel = await realpath(topLevelPath);
    const relative = path.relative(canonicalTopLevel, canonical);
    if (
      relative === '..'
      || relative.startsWith(`..${path.sep}`)
      || path.isAbsolute(relative)
    ) return canonical;
    return path.resolve(topLevelPath, relative);
  } catch {
    return canonical;
  }
}

async function assertRealDirectory(target: string, label: string): Promise<void> {
  const stat = await lstat(target);
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new Error(`${label} must be a real directory: ${target}`);
  }
  if (await aliasAwareRealPath(target) !== path.resolve(target)) {
    throw new Error(`${label} must not traverse symbolic links: ${target}`);
  }
}

function transientName(kind: 'backup' | 'failed' | 'install', id: string): string {
  return `.reqws-${kind}-${id}.app`;
}

function assertTransientPath(target: string, installDirectory: string): void {
  const relative = path.relative(installDirectory, target);
  if (
    path.dirname(relative) !== '.'
    || !/^\.reqws-(?:backup|failed|install)-[A-Za-z0-9-]+\.app$/u.test(relative)
  ) {
    throw new Error(`Refusing to remove unmanaged path: ${target}`);
  }
}

export async function replaceAppBundle(
  options: ReplaceAppBundleOptions,
): Promise<string> {
  const installDirectory = path.resolve(options.installDirectory);
  await assertRealDirectory(options.sourceApp, 'Source app');
  await assertRealDirectory(installDirectory, 'Install directory');

  const id = options.transientId ?? `${process.pid}-${randomUUID()}`;
  if (!/^[A-Za-z0-9-]+$/u.test(id)) throw new Error('Invalid transient id.');

  const targetApp = path.join(installDirectory, REQWS_APP_NAME);
  const stagingApp = path.join(installDirectory, transientName('install', id));
  const backupApp = path.join(installDirectory, transientName('backup', id));
  const failedApp = path.join(installDirectory, transientName('failed', id));

  const sourceResolved = path.resolve(options.sourceApp);
  if (
    path.basename(installDirectory).toLocaleLowerCase('en-US').endsWith('.app')
    || isSameOrContained(sourceResolved, installDirectory)
    || sourceResolved === targetApp
  ) {
    throw new Error(
      'Install directory must be a separate parent directory outside the source app bundle.',
    );
  }

  const interruptedArtifacts = (await readdir(installDirectory)).filter((entry) => (
    /^\.reqws-(?:backup|failed|install)-[A-Za-z0-9-]+\.app$/u.test(entry)
    && ![path.basename(stagingApp), path.basename(backupApp), path.basename(failedApp)]
      .includes(entry)
  ));
  if (interruptedArtifacts.length > 0) {
    throw new Error(
      'A previous interrupted ReqWS install left transaction artifacts. '
      + `Inspect them before retrying: ${interruptedArtifacts.join(', ')}`,
    );
  }

  for (const transientPath of [stagingApp, backupApp, failedApp]) {
    if (await pathExists(transientPath)) {
      throw new Error(`Transient install path already exists: ${transientPath}`);
    }
  }

  const validateTargetIfPresent = async (): Promise<boolean> => {
    let targetStat;
    try {
      targetStat = await lstat(targetApp);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return false;
      throw error;
    }
    if (!targetStat.isDirectory() || targetStat.isSymbolicLink()) {
      throw new Error(`Installed app must be a real directory: ${targetApp}`);
    }
    await options.validateExisting(targetApp);
    return true;
  };

  const targetInitiallyExisted = await validateTargetIfPresent();

  let backupCreated = false;
  let newAppPublished = false;
  const cleanupTransient = async (target: string): Promise<void> => {
    if (!(await pathExists(target))) return;
    assertTransientPath(target, installDirectory);
    await options.operations.remove(target);
  };

  try {
    await options.assertNotRunning?.();
    await options.operations.copyBundle(options.sourceApp, stagingApp);
    await options.validate(stagingApp);
    await options.assertNotRunning?.();

    const targetExistsBeforePublish = await validateTargetIfPresent();
    if (targetInitiallyExisted && !targetExistsBeforePublish) {
      throw new Error(
        `The installed app changed during staging and is now missing: ${targetApp}`,
      );
    }
    if (targetExistsBeforePublish) {
      await options.operations.move(targetApp, backupApp);
      backupCreated = true;
    }

    await options.operations.move(stagingApp, targetApp);
    newAppPublished = true;
    await options.validate(targetApp);
  } catch (error) {
    const rollbackErrors: string[] = [];

    if (newAppPublished && await pathExists(targetApp)) {
      try {
        await options.operations.move(targetApp, failedApp);
        newAppPublished = false;
      } catch (rollbackError) {
        rollbackErrors.push(`could not quarantine failed app: ${String(rollbackError)}`);
      }
    }

    if (backupCreated && !(await pathExists(targetApp))) {
      try {
        await options.operations.move(backupApp, targetApp);
        backupCreated = false;
      } catch (rollbackError) {
        rollbackErrors.push(`could not restore previous app: ${String(rollbackError)}`);
      }
    }

    for (const transientPath of [stagingApp, failedApp]) {
      try {
        await cleanupTransient(transientPath);
      } catch (cleanupError) {
        rollbackErrors.push(`could not clean ${transientPath}: ${String(cleanupError)}`);
      }
    }

    if (rollbackErrors.length > 0) {
      throw new Error(
        `${String(error)}\nRollback was incomplete:\n- ${rollbackErrors.join('\n- ')}`,
        { cause: error },
      );
    }
    throw error;
  }

  if (backupCreated) {
    try {
      await cleanupTransient(backupApp);
    } catch (error) {
      console.warn(`Installed successfully, but the backup remains at ${backupApp}: ${String(error)}`);
    }
  }

  if (!options.noLaunch) {
    try {
      await options.launch?.(targetApp);
    } catch (error) {
      console.warn(`Installed successfully, but launch failed: ${String(error)}`);
    }
  }
  return targetApp;
}

async function plistValue(appBundle: string, key: string): Promise<string> {
  const result = await runChecked(
    '/usr/bin/plutil',
    ['-extract', key, 'raw', '-o', '-', path.join(appBundle, 'Contents', 'Info.plist')],
    { capture: true, quiet: true },
  );
  return result.stdout.trim();
}

export function parseCodeSigningDetails(
  value: string,
): { adHoc: boolean; hardenedRuntime: boolean } {
  const flagsMatch = value.match(/flags=0x[0-9a-f]+\(([^)]*)\)/iu);
  const flags = new Set(
    (flagsMatch?.[1] ?? '')
      .split(',')
      .map((flag) => flag.trim())
      .filter(Boolean),
  );
  return {
    adHoc: /^Signature=adhoc$/mu.test(value),
    hardenedRuntime: flags.has('runtime'),
  };
}

export function sanitizeElectronLaunchEnvironment(
  source: NodeJS.ProcessEnv,
): NodeJS.ProcessEnv {
  const environment = { ...source };
  // Coding tools and Electron-based terminals may export this for their own
  // subprocesses. Passing it through LaunchServices makes ReqWS run as plain
  // Node.js and exit without creating a window.
  delete environment.ELECTRON_RUN_AS_NODE;
  return environment;
}

export async function validateAppBundle(
  appBundle: string,
  expectation: BundleExpectation,
): Promise<void> {
  await assertRealDirectory(appBundle, 'App bundle');

  const [bundleId, executableName, version] = await Promise.all([
    plistValue(appBundle, 'CFBundleIdentifier'),
    plistValue(appBundle, 'CFBundleExecutable'),
    plistValue(appBundle, 'CFBundleShortVersionString'),
  ]);
  if (bundleId !== REQWS_BUNDLE_ID) {
    throw new Error(`Unexpected bundle id ${bundleId}; expected ${REQWS_BUNDLE_ID}.`);
  }
  if (executableName !== 'ReqWS') {
    throw new Error(`Unexpected bundle executable: ${executableName}.`);
  }
  if (version !== expectation.version) {
    throw new Error(`Unexpected app version ${version}; expected ${expectation.version}.`);
  }

  const executable = path.join(appBundle, 'Contents', 'MacOS', executableName);
  await access(executable, constants.X_OK);
  const architectures = (
    await runChecked('/usr/bin/lipo', ['-archs', executable], {
      capture: true,
      quiet: true,
    })
  ).stdout.trim().split(/\s+/u);
  const expectedMachArchitecture = machArchitecture(expectation.arch);
  if (!architectures.includes(expectedMachArchitecture)) {
    throw new Error(
      `App executable does not contain ${expectedMachArchitecture}; `
      + `found ${architectures.join(', ')}.`,
    );
  }

  await runChecked(
    '/usr/bin/codesign',
    ['--verify', '--deep', '--strict', '--verbose=2', appBundle],
    { capture: true, quiet: true },
  );

  const signingTargets = [
    appBundle,
    path.join(
      appBundle,
      'Contents/Frameworks/Electron Framework.framework/Versions/A/Electron Framework',
    ),
    ...[
      'ReqWS Helper.app',
      'ReqWS Helper (Renderer).app',
      'ReqWS Helper (GPU).app',
      'ReqWS Helper (Plugin).app',
    ].map((helper) => path.join(appBundle, 'Contents/Frameworks', helper)),
  ];
  for (const signingTarget of signingTargets) {
    const signature = await runChecked(
      '/usr/bin/codesign',
      ['--display', '--verbose=4', signingTarget],
      { capture: true, quiet: true },
    );
    const signingDetails = parseCodeSigningDetails(
      `${signature.stdout}\n${signature.stderr}`,
    );
    if (signingDetails.adHoc && signingDetails.hardenedRuntime) {
      throw new Error(
        `The local ad-hoc signing target uses Hardened Runtime: ${signingTarget}. `
        + 'macOS library validation can reject the separately signed Electron Framework at launch.',
      );
    }
  }
}

export function machArchitecture(architecture: 'arm64' | 'x64'): 'arm64' | 'x86_64' {
  return architecture === 'x64' ? 'x86_64' : 'arm64';
}

export async function validateExistingReqwsBundle(appBundle: string): Promise<void> {
  await assertRealDirectory(appBundle, 'Installed app');
  const [bundleId, executableName] = await Promise.all([
    plistValue(appBundle, 'CFBundleIdentifier'),
    plistValue(appBundle, 'CFBundleExecutable'),
  ]);
  if (
    ![REQWS_BUNDLE_ID, LEGACY_REQWS_BUNDLE_ID].includes(bundleId)
    || executableName !== 'ReqWS'
  ) {
    throw new Error(
      `Refusing to replace ${appBundle}: it is not an installed ReqWS bundle `
      + `(id=${bundleId}, executable=${executableName}).`,
    );
  }
}

async function assertReqwsNotRunning(repositoryRoot: string): Promise<void> {
  const exactName = await runCommand('/usr/bin/pgrep', ['-x', 'ReqWS'], {
    capture: true,
    quiet: true,
  });
  const devExecutable = path.join(
    repositoryRoot,
    'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron',
  );
  const escapedDevExecutable = devExecutable.replace(/[\\^$.*+?()[\]{}|]/gu, '\\$&');
  const development = await runCommand(
    '/usr/bin/pgrep',
    ['-f', escapedDevExecutable],
    { capture: true, quiet: true },
  );

  for (const result of [exactName, development]) {
    if (result.exitCode !== 0 && result.exitCode !== 1) {
      throw new Error(`Unable to inspect running ReqWS processes: ${result.stderr.trim()}`);
    }
  }
  if (exactName.exitCode === 0 || development.exitCode === 0) {
    throw new Error('ReqWS is running. Quit ReqWS, then run the install command again.');
  }
}

async function assertReqwsLaunched(): Promise<void> {
  await new Promise<void>((resolve) => {
    setTimeout(resolve, 2_000);
  });
  const result = await runCommand('/usr/bin/pgrep', ['-x', 'ReqWS'], {
    capture: true,
    quiet: true,
  });
  if (result.exitCode === 0) return;
  if (result.exitCode === 1) {
    throw new Error(
      'ReqWS exited during its launch probe. Inspect the newest ReqWS report in '
      + '~/Library/Logs/DiagnosticReports.',
    );
  }
  throw new Error(`Unable to verify the launched ReqWS process: ${result.stderr.trim()}`);
}

async function directoryIsWritable(directory: string): Promise<boolean> {
  try {
    await access(directory, constants.W_OK);
    return true;
  } catch {
    return false;
  }
}

async function prepareInstallDirectory(directory: string): Promise<boolean> {
  if (!(await pathExists(directory))) {
    const parent = path.dirname(directory);
    await assertRealDirectory(parent, 'Install directory parent');
    await mkdir(directory);
  }
  await assertRealDirectory(directory, 'Install directory');

  if (await directoryIsWritable(directory)) return false;
  if (directory !== DEFAULT_APPLICATIONS_DIRECTORY) {
    throw new Error(
      `Install directory is not writable: ${directory}. `
      + 'Only /Applications may use scoped sudo operations.',
    );
  }
  await runChecked('/usr/bin/sudo', ['-v']);
  return true;
}

async function destinationLockPath(installDirectory: string): Promise<string> {
  const identity = await stat(installDirectory);
  return scopedLockPath(
    `destination:${identity.dev}:${identity.ino}:${REQWS_APP_NAME}`,
  );
}

function createInstallOperations(privileged: boolean): InstallOperations {
  const systemOperation = async (command: string, args: readonly string[]): Promise<void> => {
    if (privileged) {
      await runChecked('/usr/bin/sudo', ['--', command, ...args]);
    } else {
      await runChecked(command, args);
    }
  };

  return {
    copyBundle: async (source, destination) => {
      await systemOperation('/usr/bin/ditto', ['--noqtn', source, destination]);
    },
    move: async (source, destination) => {
      if (privileged) await systemOperation('/bin/mv', [source, destination]);
      else await rename(source, destination);
    },
    remove: async (target) => {
      if (privileged) await systemOperation('/bin/rm', ['-rf', target]);
      else await rm(target, { force: true, recursive: true });
    },
  };
}

function npmInvocation(args: readonly string[]): { args: string[]; command: string } {
  const npmExecPath = process.env.npm_execpath;
  return npmExecPath
    ? { command: process.execPath, args: [npmExecPath, ...args] }
    : { command: 'npm', args: [...args] };
}

function printHelp(): void {
  console.log(`ReqWS macOS build/package/install scaffold

Usage:
  npm run install:macos
  npm run install:macos -- [options]
  npm run package:macos

Options:
  --arch arm64|x64       Package architecture (default: current Node architecture)
  --install-dir PATH     Install parent, not an .app path (default: /Applications)
  --no-launch            Do not launch the installed app
  --package-only         Build the .app without installing it
  --skip-ci              Reuse the current node_modules
  --skip-check           Skip typecheck, lint, and tests
  --dry-run              Print the plan without changing anything
  -h, --help             Show this help

Environment:
  REQWS_APPLICATIONS_DIR  Alternative install parent
  REQWS_SKIP_LAUNCH=1     Same as --no-launch
  REQWS_SKIP_CI=1         Same as --skip-ci
  REQWS_SKIP_CHECK=1      Same as --skip-check`);
}

function printPlan(options: CliOptions, sourceApp: string): void {
  console.log('ReqWS macOS install plan:');
  console.log(`- repository: ${REPOSITORY_ROOT}`);
  console.log(`- architecture: ${options.arch}`);
  console.log(`- npm ci: ${options.skipCi ? 'skip' : 'run'}`);
  console.log(`- npm run check: ${options.skipCheck ? 'skip' : 'run'}`);
  console.log(`- package: ${sourceApp}`);
  console.log(options.packageOnly
    ? '- install: skip (package only)'
    : `- install: ${path.join(options.installDirectory, REQWS_APP_NAME)}`);
  console.log(`- launch: ${options.packageOnly || options.noLaunch ? 'skip' : 'run'}`);
}

async function main(): Promise<void> {
  const options = parseCliOptions(process.argv.slice(2));
  if (options.help) {
    printHelp();
    return;
  }
  if (process.platform !== 'darwin') {
    throw new Error('ReqWS local installation is supported only on macOS.');
  }
  if (Number(process.versions.node.split('.')[0]) !== 24) {
    throw new Error(`Node 24 is required; current version is ${process.version}.`);
  }
  if (typeof process.getuid === 'function' && process.getuid() === 0) {
    throw new Error('Do not run npm or this scaffold with sudo. It elevates only the final install operations when required.');
  }

  const packageJson = JSON.parse(
    await readFile(path.join(REPOSITORY_ROOT, 'package.json'), 'utf8'),
  ) as { version?: unknown };
  if (typeof packageJson.version !== 'string' || !packageJson.version) {
    throw new Error('package.json must contain a version.');
  }

  const sourceApp = path.join(
    REPOSITORY_ROOT,
    `out/ReqWS-darwin-${options.arch}`,
    REQWS_APP_NAME,
  );
  printPlan(options, sourceApp);
  if (options.dryRun) return;

  const releaseRepositoryLock = await acquireExclusiveLock(
    scopedLockPath(`repository:${REPOSITORY_ROOT}`),
  );
  let operationError: unknown;
  try {
    await buildPackageAndInstall(options, sourceApp, packageJson.version);
  } catch (error) {
    operationError = error;
  }
  let releaseError: unknown;
  try {
    await releaseRepositoryLock();
  } catch (error) {
    releaseError = error;
  }
  if (operationError) {
    if (releaseError) {
      console.warn(`Build/install failed and its lock could not be released: ${String(releaseError)}`);
    }
    throw operationError;
  }
  if (releaseError) throw releaseError;
}

async function buildPackageAndInstall(
  options: CliOptions,
  sourceApp: string,
  version: string,
): Promise<void> {

  if (!options.skipCi) {
    const npm = npmInvocation(['ci']);
    await runChecked(npm.command, npm.args, { cwd: REPOSITORY_ROOT });
  }
  if (!options.skipCheck) {
    const npm = npmInvocation(['run', 'check']);
    await runChecked(npm.command, npm.args, { cwd: REPOSITORY_ROOT });
  }

  const forgeCli = path.join(
    REPOSITORY_ROOT,
    'node_modules/@electron-forge/cli/dist/electron-forge.js',
  );
  await runChecked(
    process.execPath,
    [forgeCli, 'package', '--platform', 'darwin', '--arch', options.arch],
    { cwd: REPOSITORY_ROOT },
  );

  const expectation: BundleExpectation = {
    arch: options.arch,
    version,
  };
  await validateAppBundle(sourceApp, expectation);
  console.log(`Packaged and verified: ${sourceApp}`);
  if (options.packageOnly) return;

  const privileged = await prepareInstallDirectory(options.installDirectory);
  const releaseInstallLock = await acquireExclusiveLock(
    await destinationLockPath(options.installDirectory),
  );
  let installError: unknown;
  try {
    const installedApp = await replaceAppBundle({
      assertNotRunning: async () => await assertReqwsNotRunning(REPOSITORY_ROOT),
      installDirectory: options.installDirectory,
      launch: async (target) => {
        await runChecked('/usr/bin/open', ['--', target], {
          env: sanitizeElectronLaunchEnvironment(process.env),
        });
        await assertReqwsLaunched();
      },
      noLaunch: options.noLaunch,
      operations: createInstallOperations(privileged),
      sourceApp,
      validate: async (target) => await validateAppBundle(target, expectation),
      validateExisting: validateExistingReqwsBundle,
    });
    console.log(`Installed and verified: ${installedApp}`);
    console.log('ReqWS user data was not modified by the installer.');
  } catch (error) {
    installError = error;
  }
  let installReleaseError: unknown;
  try {
    await releaseInstallLock();
  } catch (error) {
    installReleaseError = error;
  }
  if (installError) {
    if (installReleaseError) {
      console.warn(`Install failed and its lock could not be released: ${String(installReleaseError)}`);
    }
    throw installError;
  }
  if (installReleaseError) throw installReleaseError;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === SCRIPT_PATH) {
  main().catch((error: unknown) => {
    console.error(`ReqWS install failed: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  });
}
