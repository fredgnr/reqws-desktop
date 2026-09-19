import { createHash } from 'node:crypto';
import { lstat, mkdtemp, open, readFile, readdir, realpath, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

import { releaseCertificatePath, validateReleaseCertificate } from './macos-build-profile.mts';

const execute = promisify(execFile);
const machMagic = new Set(['feedface', 'feedfacf', 'cefaedfe', 'cffaedfe', 'cafebabe', 'bebafeca', 'cafebabf', 'bfbafeca']);

function contained(root: string, candidate: string) {
  const relative = path.relative(root, candidate);
  return relative === '' || (!path.isAbsolute(relative) && relative !== '..' && !relative.startsWith(`..${path.sep}`));
}

export async function signingTargets(appBundle: string): Promise<string[]> {
  const root = await realpath(appBundle);
  const targets = [root];
  const walk = async (directory: string) => {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const target = path.join(directory, entry.name);
      if (entry.isSymbolicLink()) {
        if (!contained(root, await realpath(target))) throw new Error('App bundle contains an escaping symbolic link.');
      } else if (entry.isDirectory()) {
        if (entry.name.endsWith('.app')) targets.push(target);
        await walk(target);
      } else if (entry.isFile()) {
        // Packages must not accidentally carry signing material or development trees.
        if (/\.(?:p12|pfx|key|keychain|keychain-db)$/iu.test(entry.name)) {
          throw new Error('App bundle contains a forbidden signing material filename.');
        }
        const handle = await open(target, 'r');
        try {
          const header = Buffer.alloc(4);
          const { bytesRead } = await handle.read(header, 0, 4, 0);
          if (bytesRead === 4 && machMagic.has(header.toString('hex'))) targets.push(target);
        } finally { await handle.close(); }
      } else throw new Error('App bundle contains an unsupported filesystem entry.');
    }
  };
  await walk(root);
  return targets;
}

export async function assertNoUpdateFeed(appBundle: string) {
  try {
    await lstat(path.join(appBundle, 'Contents/Resources/app-update.yml'));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return;
    throw error;
  }
  // Even a corrupt feed or a dangling symlink marks the target as protected.
  throw new Error('Refusing to use a local build with an update-enabled app. Use a separate installation directory.');
}

export async function verifyReleaseSignature(
  appBundle: string,
  expectedPin: string | undefined,
  certificateFile = releaseCertificatePath,
) {
  const { parseUpdateConfig } = await import('../src/shared/update-config.ts');
  validateReleaseCertificate(await readFile(certificateFile), expectedPin);
  const feed = path.join(appBundle, 'Contents/Resources/app-update.yml');
  const feedStatus = await lstat(feed);
  if (!feedStatus.isFile() || feedStatus.isSymbolicLink()) throw new Error('The signed update feed must be a regular file.');
  parseUpdateConfig(await readFile(feed, 'utf8'));
  await execute('/usr/bin/codesign', ['--verify', '--deep', '--strict', '--all-architectures', appBundle]);
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'reqws-certificate-check-'));
  try {
    const targets = await signingTargets(appBundle);
    for (const [index, target] of targets.entries()) {
      const details = await execute('/usr/bin/codesign', ['--display', '--verbose=4', target]);
      const signature = `${details.stdout}\n${details.stderr}`;
      if (/^Signature=adhoc$/mu.test(signature)
        || !/flags=0x[0-9a-f]+\([^)]*\bruntime\b[^)]*\)/iu.test(signature)) {
        throw new Error('Release code must use a certificate and Hardened Runtime.');
      }
      const prefix = path.join(temporary, `certificate-${index}-`);
      await execute('/usr/bin/codesign', ['--display', '--extract-certificates', prefix, target]);
      const pin = createHash('sha256').update(await readFile(`${prefix}0`)).digest('hex').toUpperCase();
      if (pin !== expectedPin) throw new Error('A nested code signature does not match the pinned certificate.');
    }
  } finally { await rm(temporary, { recursive: true, force: true }); }
}
