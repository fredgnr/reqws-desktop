import { spawn } from 'node:child_process';
import { createHash, randomBytes, X509Certificate } from 'node:crypto';
import { chmod, lstat, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { z } from 'zod';

import { buildProfile, releaseCertificatePath, repositoryRoot, validateReleaseCertificate } from './macos-build-profile.mts';
import { runReleaseStage, startReleaseStage, type ReleaseStage } from './release-log.mts';

export type SigningRunner = (command: string, args: string[], env?: NodeJS.ProcessEnv) => Promise<string>;

type SigningStage = ReleaseStage<'signing'>;

class SigningFailure extends Error {
  readonly stage: SigningStage;

  constructor(stage: SigningStage) {
    super(`macOS signing failed at ${stage}. Check the protected job setup and cleanup step.`);
    this.stage = stage;
  }
}

export function signingFailureMessage(error: unknown): string {
  // Only our fixed stage labels may cross this boundary. Native errors can
  // contain a password, command arguments, subprocess output, or private paths.
  return error instanceof SigningFailure
    ? new SigningFailure(error.stage).message
    : 'macOS signing failed. Check the protected job setup and cleanup step.';
}

// No command-line or stderr interpolation: security arguments and diagnostics
// can contain passwords. Only the trusted packaging child inherits stdio.
export function createSigningRunner(timeoutSeconds = 60): SigningRunner {
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 1 || timeoutSeconds > 60) {
    throw new Error('Signing system command timeout must be between 1 and 60 seconds.');
  }
  return async (command, args, env) => await new Promise((resolve, reject) => {
    // alarm survives exec. For sudo, install it inside the privileged process:
    // an unprivileged parent cannot reliably kill a hung root security command.
    const alarm = ['-e', 'alarm shift; exec { $ARGV[0] } @ARGV; exit 127;', String(timeoutSeconds)];
    let binary = '/usr/bin/perl';
    let parameters = [...alarm, command, ...args];
    if (command === '/usr/bin/sudo') {
      if (args[0] !== '-n' || args[1] !== '/usr/bin/security') {
        reject(new Error('Only non-interactive security commands may run with elevated signing privileges.'));
        return;
      }
      binary = command;
      parameters = ['-n', '/usr/bin/perl', ...alarm, ...args.slice(1)];
    }
    const child = spawn(binary, parameters, { shell: false, env, stdio: ['ignore', 'pipe', 'ignore'] });
    let startFailed = false;
    let output = '';
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk: string) => { output = (output + chunk).slice(-128 * 1024); });
    child.once('error', () => {
      startFailed = true;
      console.error('[release][signing-command] status=start-failed');
      reject(new Error('Signing system command could not start.'));
    });
    child.once('close', (code, signal) => {
      if (startFailed) return;
      if (signal === 'SIGALRM' || code === 128 + 14) {
        console.error('[release][signing-command] status=timed-out');
        reject(new Error('Signing system command timed out.'));
        return;
      }
      if (code === 0) resolve(output);
      else {
        console.error(`[release][signing-command] status=failed exit_code=${code ?? 'signal'}`);
        reject(new Error(`Signing system command failed (exit ${code ?? 'signal'}).`));
      }
    });
  });
}

export const runSigningCommand = createSigningRunner();

export function assertSigningContext(environment: NodeJS.ProcessEnv, platform = process.platform) {
  if (platform !== 'darwin' || buildProfile(environment) !== 'personal-release'
    || environment.GITHUB_ACTIONS !== 'true' || environment.RUNNER_ENVIRONMENT !== 'github-hosted'
    || environment.RUNNER_OS !== 'macOS' || environment.GITHUB_REPOSITORY !== 'fredgnr/reqws-desktop'
    || environment.GITHUB_EVENT_NAME !== 'push'
    || !/^refs\/tags\/v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/u.test(environment.GITHUB_REF ?? '')) {
    throw new Error('Signing wrapper requires a personal-release tag job on a GitHub-hosted macOS runner.');
  }
  if (!environment.RUNNER_TEMP || !path.isAbsolute(environment.RUNNER_TEMP)) {
    throw new Error('RUNNER_TEMP must be an absolute directory.');
  }
}

export function signingChildEnvironment(environment: NodeJS.ProcessEnv, identity: string, keychain: string): NodeJS.ProcessEnv {
  const result: NodeJS.ProcessEnv = {};
  for (const name of [
    'PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'CI', 'RUNNER_TEMP',
    'npm_execpath', 'npm_node_execpath', 'REQWS_NODE24', 'MAC_SIGNING_CERT_SHA256',
  ]) {
    if (environment[name] !== undefined) result[name] = environment[name];
  }
  return {
    ...result,
    REQWS_BUILD_PROFILE: 'personal-release',
    REQWS_SIGNING_IDENTITY: identity,
    REQWS_SIGNING_KEYCHAIN: keychain,
  };
}

export function parseKeychainList(output: string): string[] {
  const entries = output.split('\n').map((line) => line.trim()).filter(Boolean);
  return entries.map((line) => {
    if (!/^"\/[^"\r\n]+"$/u.test(line)) throw new Error('Unrecognized keychain search list.');
    return line.slice(1, -1);
  });
}

export async function importSigningIdentity(
  p12: string,
  password: string,
  keychain: string,
  certificateBytes: Buffer,
  run: SigningRunner = runSigningCommand,
) {
  // macOS auto-detection misreads modern OpenSSL 3 PKCS12 containers and reports
  // a MAC/password failure. Select the format explicitly; keep AES/SHA-256 intact.
  await run('/usr/bin/security', ['import', p12, '-k', keychain, '-f', 'pkcs12', '-P', password, '-T', '/usr/bin/codesign']);
  const imported = await run('/usr/bin/security', ['find-certificate', '-a', '-p', keychain]);
  const certificates = imported.match(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/gu) ?? [];
  if (certificates.length !== 1 || !new X509Certificate(certificates[0]!).raw.equals(certificateBytes)) {
    throw new Error('P12 must contain exactly the pinned public signing certificate.');
  }
}

export async function revokeSigningTrust(certificatePath: string, identity: string, run = runSigningCommand) {
  const bytes = await readFile(certificatePath);
  if (createHash('sha1').update(bytes).digest('hex').toUpperCase() !== identity) {
    throw new Error('Cleanup certificate does not match the managed signing identity.');
  }
  // Removing the last admin entry can wait for GUI authorization on hosted
  // macOS. Revoke only this certificate's Code Signing trust instead. The deny
  // record remains until this disposable runner is recycled; no trust is granted.
  await run('/usr/bin/sudo', ['-n', '/usr/bin/security', 'add-trusted-cert', '-d', '-r', 'deny', '-p', 'codeSign', certificatePath]);
  // An interrupted export may leave a public snapshot behind. Reserve a fresh
  // name so always() can retry; final directory cleanup removes stale snapshots.
  const snapshot = path.join(path.dirname(certificatePath), `revoked-trust-${randomBytes(8).toString('hex')}.plist`);
  await writeFile(snapshot, '', { flag: 'wx', mode: 0o600 });
  try {
    await run('/usr/bin/security', ['trust-settings-export', '-d', snapshot]);
    await run('/usr/bin/python3', [path.join(repositoryRoot, 'scripts/verify-signing-trust.py'), '--settings', snapshot, '--identity', identity]);
  } finally {
    await rm(snapshot, { force: true });
  }
}

const cleanupSchema = z.strictObject({
  directory: z.string(),
  certificateIdentity: z.string().regex(/^[A-F0-9]{40}$/u),
  originalSearchList: z.array(z.string().startsWith('/')),
  keychainCreated: z.boolean(),
  trustAdded: z.boolean(),
  searchListChanged: z.boolean(),
});
type CleanupState = z.infer<typeof cleanupSchema>;

async function cleanupSigningState(state: CleanupState, run: SigningRunner) {
  const errors: unknown[] = [];
  // Attempt every cleanup even when an earlier command fails. Keep the public
  // certificate and journal for --cleanup if trust/keychain cleanup needs retry.
  const attempt = async (stage: SigningStage, operation: () => Promise<unknown>) => {
    try { await runReleaseStage('signing', stage, operation); } catch (error) { errors.push(error); }
  };
  if (state.searchListChanged) await attempt('restore-search-list', async () => {
    await run('/usr/bin/security', ['list-keychains', '-d', 'user', '-s', ...state.originalSearchList]);
    state.searchListChanged = false;
  });
  if (state.trustAdded) await attempt('revoke-code-signing-trust', async () => {
    await revokeSigningTrust(path.join(state.directory, 'public.cer'), state.certificateIdentity, run);
    state.trustAdded = false;
  });
  if (state.keychainCreated) await attempt('delete-keychain', async () => {
    await run('/usr/bin/security', ['delete-keychain', path.join(state.directory, 'signing.keychain-db')]);
    state.keychainCreated = false;
  });
  await attempt('delete-p12', async () => await rm(path.join(state.directory, 'identity.p12'), { force: true }));
  if (errors.length > 0) throw new Error('Signing cleanup failed; run the job always() cleanup before leaving the runner.');
  await runReleaseStage('signing', 'delete-temporary-directory', async () => await rm(state.directory, { recursive: true }));
}

export async function cleanupMacosSigning(environment = process.env, run = runSigningCommand) {
  try {
    await runReleaseStage('signing', 'cleanup', async () => await performMacosSigningCleanup(environment, run));
  } catch {
    throw new SigningFailure('cleanup');
  }
}

async function performMacosSigningCleanup(environment: NodeJS.ProcessEnv, run: SigningRunner) {
  assertSigningContext(environment);
  const root = await realpath(environment.RUNNER_TEMP!);
  const journal = path.join(root, 'reqws-signing-cleanup.json');
  let status;
  try { status = await lstat(journal); } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return;
    throw error;
  }
  if (!status.isFile() || status.isSymbolicLink() || status.uid !== process.getuid?.()) {
    throw new Error('Signing cleanup journal is not an owned regular file.');
  }
  const state = cleanupSchema.parse(JSON.parse(await readFile(journal, 'utf8')) as unknown);
  if (path.dirname(state.directory) !== root
    || !/^reqws-signing-[A-Za-z0-9]+$/u.test(path.basename(state.directory))
    || await realpath(state.directory) !== state.directory) {
    throw new Error('Signing cleanup directory is outside its managed temporary scope.');
  }
  try { await cleanupSigningState(state, run); } catch (error) {
    await writeFile(journal, JSON.stringify(state), { mode: 0o600 });
    throw error;
  }
  await rm(journal);
}

export async function withMacosSigning(
  command: string[],
  environment = process.env,
  run = runSigningCommand,
  execute?: (command: string[], env: NodeJS.ProcessEnv) => Promise<void>,
  publicCertificate = releaseCertificatePath,
) {
  let stage: SigningStage = 'context';
  let finish = startReleaseStage('signing', stage);
  try {
    await performMacosSigning(command, environment, run, execute, publicCertificate, (next) => {
      finish('success');
      stage = next;
      finish = startReleaseStage('signing', next);
    }, (status) => finish(status));
  } catch (error) {
    finish('failed');
    throw error instanceof SigningFailure ? error : new SigningFailure(stage);
  }
}

async function performMacosSigning(
  command: string[],
  environment: NodeJS.ProcessEnv,
  run: SigningRunner,
  execute: ((command: string[], env: NodeJS.ProcessEnv) => Promise<void>) | undefined,
  publicCertificate: string,
  setStage: (stage: SigningStage) => void,
  finishStage: (status: 'success' | 'failed') => void,
) {
  assertSigningContext(environment);
  if (!command[0]) throw new Error('A packaging command is required after --.');
  setStage('credentials');
  const encoded = environment.MAC_SIGNING_P12_BASE64;
  const password = environment.MAC_SIGNING_P12_PASSWORD;
  if (!encoded || !password || encoded.length > 48 * 1024
    || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/u.test(encoded)) {
    throw new Error('A valid Base64 P12 and its password are required.');
  }
  setStage('public-certificate');
  const certificateBytes = await readFile(publicCertificate);
  const certificate = validateReleaseCertificate(certificateBytes, environment.MAC_SIGNING_CERT_SHA256);
  if (certificate.expiresAt - Date.now() < 90 * 24 * 60 * 60 * 1000) {
    console.warn('::warning::The signing certificate expires within 90 days; plan a verified identity migration.');
  }
  setStage('temporary-files');
  const root = await realpath(environment.RUNNER_TEMP!);
  const journal = path.join(root, 'reqws-signing-cleanup.json');
  // Reserve before creating any keychain or trust; an interrupted run must be
  // inspected/cleaned rather than overwritten by the next attempt.
  await writeFile(journal, '{}', { flag: 'wx', mode: 0o600 });
  let directory: string;
  try { directory = await mkdtemp(path.join(root, 'reqws-signing-')); } catch (error) {
    await rm(journal);
    throw error;
  }
  const state: CleanupState = {
    directory, certificateIdentity: certificate.identity,
    originalSearchList: [], keychainCreated: false, trustAdded: false, searchListChanged: false,
  };
  const save = async () => await writeFile(journal, JSON.stringify(state), { mode: 0o600 });
  const keychain = path.join(directory, 'signing.keychain-db');
  const certificatePath = path.join(directory, 'public.cer');
  const p12 = path.join(directory, 'identity.p12');
  const keychainPassword = randomBytes(32).toString('hex');
  console.log(`::add-mask::${keychainPassword}`);
  let interrupted = false;
  let child: ReturnType<typeof spawn> | undefined;
  const onSignal = () => { interrupted = true; child?.kill('SIGTERM'); };
  process.on('SIGINT', onSignal);
  process.on('SIGTERM', onSignal);
  const system: SigningRunner = async (binary, args) => {
    if (interrupted) throw new Error('Signing was interrupted.');
    return await run(binary, args);
  };
  let operationError: unknown;
  let cleanupError: unknown;
  try {
    await chmod(directory, 0o700);
    await save();
    await writeFile(p12, Buffer.from(encoded, 'base64'), { flag: 'wx', mode: 0o600 });
    await writeFile(certificatePath, certificateBytes, { flag: 'wx', mode: 0o600 });
    setStage('keychain-search-list');
    state.originalSearchList = parseKeychainList(await system('/usr/bin/security', ['list-keychains', '-d', 'user']));
    // create-keychain itself can add the new keychain to the search list.
    state.searchListChanged = true;
    state.keychainCreated = true;
    await save();
    setStage('create-keychain');
    await system('/usr/bin/security', ['create-keychain', '-p', keychainPassword, keychain]);
    await system('/usr/bin/security', ['set-keychain-settings', '-lut', '21600', keychain]);
    setStage('unlock-keychain');
    await system('/usr/bin/security', ['unlock-keychain', '-p', keychainPassword, keychain]);
    // Check every imported certificate before importing trust. Importing a P12
    // with an extra or substituted identity is a hard failure, including same-CN keys.
    setStage('import-p12');
    await importSigningIdentity(p12, password, keychain, certificateBytes, system);
    setStage('key-access');
    await system('/usr/bin/security', ['set-key-partition-list', '-S', 'apple-tool:,apple:', '-s', '-k', keychainPassword, keychain]);
    state.searchListChanged = true;
    await save();
    setStage('keychain-search-list');
    await system('/usr/bin/security', ['list-keychains', '-d', 'user', '-s', ...state.originalSearchList, keychain]);
    state.trustAdded = true;
    await save();
    setStage('code-signing-trust');
    await system('/usr/bin/sudo', ['-n', '/usr/bin/security', 'add-trusted-cert', '-d', '-r', 'trustRoot', '-p', 'codeSign', '-k', keychain, certificatePath]);
    setStage('trusted-identity');
    const identities = await system('/usr/bin/security', ['find-identity', '-v', '-p', 'codesigning', keychain]);
    const found = [...identities.matchAll(/^\s*\d+\) ([A-F0-9]{40}) /gmu)].map((match) => match[1]);
    if (found.length !== 1 || found[0] !== certificate.identity) throw new Error('The pinned signing identity is not uniquely trusted.');
    if (interrupted) throw new Error('Signing was interrupted.');
    setStage('packaging');
    const childEnvironment = signingChildEnvironment(environment, certificate.identity, keychain);
    if (execute) await execute(command, childEnvironment);
    else await new Promise<void>((resolve, reject) => {
      child = spawn(command[0]!, command.slice(1), { shell: false, env: childEnvironment, stdio: 'inherit' });
      child.once('error', () => reject(new Error('Packaging command could not start.')));
      child.once('close', (code) => code === 0 && !interrupted ? resolve() : reject(new Error('Signed packaging failed or was interrupted.')));
    });
    finishStage('success');
  } catch (error) {
    finishStage('failed');
    operationError = error;
  } finally {
    try {
      await runReleaseStage('signing', 'cleanup', async () => {
        await cleanupSigningState(state, run);
        await rm(journal);
      });
    } catch {
      // Even a journal write failure must remain a cleanup failure, rather than
      // being attributed to an earlier packaging/import stage.
      cleanupError = new SigningFailure('cleanup');
      await save().catch(() => undefined);
    } finally {
      process.removeListener('SIGINT', onSignal);
      process.removeListener('SIGTERM', onSignal);
    }
  }
  if (cleanupError) throw cleanupError;
  if (operationError) throw operationError;
}
