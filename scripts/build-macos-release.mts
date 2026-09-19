import { execFile, spawn } from 'node:child_process';
import { mkdir, mkdtemp, open, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { repositoryRoot, buildProfile } from './macos-build-profile.mts';
import { validateAppBundle } from './install-macos.mts';
import { fileDigest, prepareMacosUpdate, releaseVersionSchema } from './prepare-macos-update.mts';
import { runReleaseStage } from './release-log.mts';

const execute = promisify(execFile);

async function main() {
  const [version] = process.argv.slice(2);
  await runReleaseStage('macos-package', 'preflight', async () => {
    if (process.argv.length !== 3 || buildProfile() !== 'personal-release' || process.platform !== 'darwin') {
      throw new Error('Usage inside the signing wrapper: build-macos-release.mts VERSION');
    }
    releaseVersionSchema.parse(version);
    const packageJson = JSON.parse(await readFile(path.join(repositoryRoot, 'package.json'), 'utf8')) as { version: string };
    const lock = JSON.parse(await readFile(path.join(repositoryRoot, 'package-lock.json'), 'utf8')) as { version: string; packages: Record<string, { version: string }> };
    if (packageJson.version !== version || lock.version !== version || lock.packages['']?.version !== version) {
      throw new Error('Release version must match package.json and both lockfile versions.');
    }
  });
  // The signing wrapper strips secrets before this process starts. Stream the
  // trusted build's output so Forge progress and failures remain visible live.
  await runReleaseStage('macos-package', 'forge-package', async () => await new Promise<void>((resolve, reject) => {
    const child = spawn(process.execPath, [path.join(repositoryRoot, 'scripts/run-install-macos.mjs'), '--package-only', '--skip-ci', '--skip-check', '--arch', 'arm64'], {
      cwd: repositoryRoot, env: process.env, shell: false, stdio: ['ignore', 'inherit', 'inherit'],
    });
    child.once('error', () => reject(new Error('Forge packaging command could not start.')));
    child.once('close', (code) => code === 0 ? resolve() : reject(new Error(`Forge packaging failed (exit ${code ?? 'signal'}).`)));
  }));
  const expectation = { arch: 'arm64' as const, version: version!, profile: 'personal-release' as const, certificatePin: process.env.MAC_SIGNING_CERT_SHA256 };
  const app = path.join(repositoryRoot, 'out/ReqWS-darwin-arm64/ReqWS.app');
  await runReleaseStage('macos-package', 'verify-bundle', async () => await validateAppBundle(app, expectation));
  const output = path.join(repositoryRoot, 'dist/release');
  const filename = `ReqWS-${version}-macos-arm64.zip`;
  const archive = path.join(output, filename);
  await runReleaseStage('macos-package', 'create-zip', async () => {
    await mkdir(output, { recursive: true });
    const reserved = await open(archive, 'wx');
    await reserved.close();
    await execute('/usr/bin/ditto', ['-c', '-k', '--sequesterRsrc', '--keepParent', app, archive]);
  });
  const extracted = await mkdtemp(path.join(os.tmpdir(), 'reqws-release-verify-'));
  try {
    await runReleaseStage('macos-package', 'extract-zip', async () => await execute('/usr/bin/ditto', ['-x', '-k', archive, extracted]));
    await runReleaseStage('macos-package', 'verify-extracted-bundle', async () => await validateAppBundle(path.join(extracted, 'ReqWS.app'), expectation));
  } finally {
    await runReleaseStage('macos-package', 'remove-extracted-bundle', async () => await rm(extracted, { recursive: true, force: true }));
  }
  await runReleaseStage('macos-package', 'update-metadata', async () => await prepareMacosUpdate(['--write', output, version!]));
  await runReleaseStage('macos-package', 'checksums', async () => {
    for (const asset of [filename, 'latest-mac.yml']) {
      await writeFile(path.join(output, `${asset}.sha256`), `${await fileDigest(path.join(output, asset), 'sha256')}  ${asset}\n`, { flag: 'wx' });
    }
  });
  console.log('Signed arm64 bundle, extracted archive, identity and update metadata verified.');
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : 'Signed release packaging failed.');
  process.exitCode = 1;
});
