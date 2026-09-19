import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { chmod, lstat, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { z } from 'zod';

import { buildProfile, releaseCertificatePath, validateReleaseCertificate } from './macos-build-profile.mts';

export type SigningRunner = (command: string, args: string[], env?: NodeJS.ProcessEnv) => Promise<string>;

// No command-line or stderr interpolation: security arguments and diagnostics
// can contain passwords. Only the trusted packaging child inherits stdio.
export const runSigningCommand: SigningRunner = async (command, args, env) => await new Promise((resolve, reject) => {
  const child = spawn(command, args, { shell: false, env, stdio: ['ignore', 'pipe', 'ignore'] });
  let output = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', (chunk: string) => { output = (output + chunk).slice(-128 * 1024); });
  child.once('error', () => reject(new Error('Signing system command could not start.')));
  child.once('close', (code) => {
    if (code === 0) resolve(output);
    else reject(new Error(`Signing system command failed (exit ${code ?? 'signal'}).`));
  });
});

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

const cleanupSchema = z.strictObject({
  directory: z.string(),
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
  const attempt = async (operation: () => Promise<unknown>) => {
    try { await operation(); } catch (error) { errors.push(error); }
  };
  if (state.searchListChanged) await attempt(async () => {
    await run('/usr/bin/security', ['list-keychains', '-d', 'user', '-s', ...state.originalSearchList]);
    state.searchListChanged = false;
  });
  if (state.trustAdded) await attempt(async () => {
    await run('/usr/bin/sudo', ['-n', '/usr/bin/security', 'remove-trusted-cert', '-d', path.join(state.directory, 'public.cer')]);
    state.trustAdded = false;
  });
  if (state.keychainCreated) await attempt(async () => {
    await run('/usr/bin/security', ['delete-keychain', path.join(state.directory, 'signing.keychain-db')]);
    state.keychainCreated = false;
  });
  await attempt(async () => await rm(path.join(state.directory, 'identity.p12'), { force: true }));
  if (errors.length > 0) throw new Error('Signing cleanup failed; run the job always() cleanup before leaving the runner.');
  await rm(state.directory, { recursive: true });
}

export async function cleanupMacosSigning(environment = process.env, run = runSigningCommand) {
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
  assertSigningContext(environment);
  if (!command[0]) throw new Error('A packaging command is required after --.');
  const encoded = environment.MAC_SIGNING_P12_BASE64;
  const password = environment.MAC_SIGNING_P12_PASSWORD;
  if (!encoded || !password || encoded.length > 48 * 1024
    || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/u.test(encoded)) {
    throw new Error('A valid Base64 P12 and its password are required.');
  }
  const certificateBytes = await readFile(publicCertificate);
  const certificate = validateReleaseCertificate(certificateBytes, environment.MAC_SIGNING_CERT_SHA256);
  if (certificate.expiresAt - Date.now() < 90 * 24 * 60 * 60 * 1000) {
    console.warn('::warning::The signing certificate expires within 90 days; plan a verified identity migration.');
  }
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
    directory, originalSearchList: [], keychainCreated: false, trustAdded: false, searchListChanged: false,
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
    state.originalSearchList = parseKeychainList(await system('/usr/bin/security', ['list-keychains', '-d', 'user']));
    // create-keychain itself can add the new keychain to the search list.
    state.searchListChanged = true;
    state.keychainCreated = true;
    await save();
    await system('/usr/bin/security', ['create-keychain', '-p', keychainPassword, keychain]);
    await system('/usr/bin/security', ['set-keychain-settings', '-lut', '21600', keychain]);
    await system('/usr/bin/security', ['unlock-keychain', '-p', keychainPassword, keychain]);
    await system('/usr/bin/security', ['import', p12, '-k', keychain, '-P', password, '-T', '/usr/bin/codesign']);
    // Check every imported certificate before importing trust. Importing a P12
    // with an extra or substituted identity is a hard failure, including same-CN keys.
    const imported = await system('/usr/bin/security', ['find-certificate', '-a', '-p', keychain]);
    const certificates = imported.match(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/gu) ?? [];
    const { X509Certificate } = await import('node:crypto');
    if (certificates.length !== 1 || !new X509Certificate(certificates[0]!).raw.equals(certificateBytes)) {
      throw new Error('P12 must contain exactly the pinned public signing certificate.');
    }
    await system('/usr/bin/security', ['set-key-partition-list', '-S', 'apple-tool:,apple:', '-s', '-k', keychainPassword, keychain]);
    state.searchListChanged = true;
    await save();
    await system('/usr/bin/security', ['list-keychains', '-d', 'user', '-s', ...state.originalSearchList, keychain]);
    state.trustAdded = true;
    await save();
    await system('/usr/bin/sudo', ['-n', '/usr/bin/security', 'add-trusted-cert', '-d', '-r', 'trustRoot', '-p', 'codeSign', '-k', keychain, certificatePath]);
    const identities = await system('/usr/bin/security', ['find-identity', '-v', '-p', 'codesigning', keychain]);
    const found = [...identities.matchAll(/^\s*\d+\) ([A-F0-9]{40}) /gmu)].map((match) => match[1]);
    if (found.length !== 1 || found[0] !== certificate.identity) throw new Error('The pinned signing identity is not uniquely trusted.');
    if (interrupted) throw new Error('Signing was interrupted.');
    const childEnvironment = signingChildEnvironment(environment, certificate.identity, keychain);
    if (execute) await execute(command, childEnvironment);
    else await new Promise<void>((resolve, reject) => {
      child = spawn(command[0]!, command.slice(1), { shell: false, env: childEnvironment, stdio: 'inherit' });
      child.once('error', () => reject(new Error('Packaging command could not start.')));
      child.once('close', (code) => code === 0 && !interrupted ? resolve() : reject(new Error('Signed packaging failed or was interrupted.')));
    });
  } catch (error) {
    operationError = error;
  } finally {
    try {
      await cleanupSigningState(state, run);
      await rm(journal);
    } catch (error) {
      await save();
      cleanupError = error;
    } finally {
      process.removeListener('SIGINT', onSignal);
      process.removeListener('SIGTERM', onSignal);
    }
  }
  if (cleanupError) throw cleanupError;
  if (operationError) throw operationError;
}
