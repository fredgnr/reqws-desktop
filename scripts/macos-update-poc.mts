import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cp, mkdir, mkdtemp, readFile, realpath, writeFile } from 'node:fs/promises';
import { builtinModules } from 'node:module';
import path from 'node:path';
import { promisify } from 'node:util';
import packager from '@electron/packager';
import { listPackage } from '@electron/asar';
import { build, mergeConfig } from 'vite';

import mainConfig from '../vite.main.config.mts';
import { assertTrustedSigningIdentity, repositoryRoot, validateReleaseCertificate } from './macos-build-profile.mts';
import { prepareMacosUpdate } from './prepare-macos-update.mts';
import { sanitizeElectronLaunchEnvironment } from './install-macos.mts';

const execute = promisify(execFile);
const args = process.argv.slice(2);
const smoke = args.length === 1 && args[0] === '--smoke';
const signed = args.length === 3 && args[0] === '--signed';

async function main() {
  if (process.platform !== 'darwin' || process.arch !== 'arm64' || (!smoke && !signed)) {
    throw new Error('Usage on arm64 macOS: node scripts/macos-update-poc.mts --smoke | --signed TEST_CERTIFICATE TEST_KEYCHAIN');
  }
  const root = await realpath(await mkdtemp('/private/tmp/reqws-update-poc-'));
  const source = path.join(root, 'source');
  await mkdir(source);
  const versions = smoke ? ['0.0.1'] : ['0.0.1', '0.0.2'];
  let identity = '-';
  if (signed) {
    const certificate = await readFile(path.resolve(args[1]!));
    identity = validateReleaseCertificate(certificate, createHash('sha256').update(certificate).digest('hex').toUpperCase()).identity;
    assertTrustedSigningIdentity(identity, path.resolve(args[2]!), undefined, true);
  }
  const pocConfig = path.join(root, 'poc.json');
  const feed = path.join(root, 'app-update.yml');
  await writeFile(pocConfig, JSON.stringify({ root, smoke }));
  await writeFile(feed, JSON.stringify({
    provider: 'generic', url: 'http://127.0.0.1:17843/', updaterCacheDirName: 'reqws-isolated-poc-updater',
  }));
  await build(mergeConfig(mainConfig, {
    configFile: false,
    root: repositoryRoot,
    resolve: { conditions: ['node'], mainFields: ['module', 'jsnext:main', 'jsnext'] },
    build: {
      emptyOutDir: false, outDir: source, minify: true,
      lib: { entry: path.join(repositoryRoot, 'tests/fixtures/macos-update-poc/main.ts'), formats: ['cjs'], fileName: () => 'main.js' },
      rollupOptions: { external: ['electron', 'electron/main', 'electron/common', ...builtinModules.flatMap((name) => [name, `node:${name}`])] },
    },
  }));
  // packager 18.4.4 implements continueOnError but omits it from OsxSignOptions.
  const signing = {
    identity, keychain: signed ? path.resolve(args[2]!) : undefined,
    identityValidation: false, continueOnError: false, preAutoEntitlements: false, preEmbedProvisioningProfile: false,
    optionsForFile: () => ({
      hardenedRuntime: signed, timestamp: 'none',
      ...(signed ? { entitlements: path.join(repositoryRoot, 'build/entitlements.personal-release.plist') } : {}),
    }),
  };
  const appPaths: string[] = [];
  for (const version of versions) {
    await writeFile(path.join(source, 'package.json'), JSON.stringify({
      name: 'reqws-isolated-update-poc', productName: 'ReqWS Update PoC', version, main: 'main.js',
    }));
    const outputs = await packager({
      dir: source, out: path.join(root, version), name: 'ReqWS Update PoC',
      appBundleId: 'com.reqws.update-poc', appVersion: version, buildVersion: version,
      electronVersion: '43.4.0', platform: 'darwin', arch: 'arm64', asar: true,
      extraResource: [pocConfig, feed],
      osxSign: signing,
    });
    const appPath = path.join(outputs[0]!, 'ReqWS Update PoC.app');
    appPaths.push(appPath);
    const entries = listPackage(path.join(appPath, 'Contents/Resources/app.asar'), { isPack: false });
    if (entries.some((entry) => entry.includes('node_modules')) || !entries.includes('/main.js')) {
      throw new Error('PoC must contain only the bundled runtime, without repository node_modules.');
    }
    await execute('/usr/bin/codesign', ['--verify', '--deep', '--strict', '--all-architectures', appPath]);
  }
  if (smoke) {
    await execute(path.join(appPaths[0]!, 'Contents/MacOS/ReqWS Update PoC'), [], {
      cwd: root, env: sanitizeElectronLaunchEnvironment({ ...process.env, HOME: path.join(root, 'home') }), timeout: 30_000,
    });
    const report = JSON.parse(await readFile(path.join(root, 'result.json'), 'utf8')) as { stage?: string };
    if (report.stage !== 'offline-smoke-passed') throw new Error('Packaged updater smoke did not finish.');
  } else {
    const install = path.join(root, 'Applications');
    const release = path.join(root, 'feed');
    await mkdir(install);
    await mkdir(release);
    await cp(appPaths[0]!, path.join(install, 'ReqWS Update PoC.app'), { recursive: true, verbatimSymlinks: true });
    await execute('/usr/bin/ditto', ['-c', '-k', '--sequesterRsrc', '--keepParent', appPaths[1]!, path.join(release, 'ReqWS-0.0.2-macos-arm64.zip')]);
    await prepareMacosUpdate(['--write', release, '0.0.2']);
    // Building never launches the signed PoC or changes keychain/trust settings.
    console.log(`Serve ${release} at 127.0.0.1:17843, then open ${install}/ReqWS Update PoC.app after authorization.`);
  }
  console.log(`PoC artifacts and evidence: ${root}`);
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : 'PoC failed.');
  process.exitCode = 1;
});
