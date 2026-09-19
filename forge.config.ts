import type { ForgeConfig } from '@electron-forge/shared-types';
import { VitePlugin } from '@electron-forge/plugin-vite';
import path from 'node:path';

import { buildProfile, releaseSigningConfiguration, repositoryRoot } from './scripts/macos-build-profile.mts';

const profile = buildProfile();

const config: ForgeConfig = {
  packagerConfig: {
    asar: true,
    appBundleId: 'com.reqws.desktop',
    appCategoryType: 'public.app-category.developer-tools',
    name: 'ReqWS',
    extraResource: profile === 'personal-release'
      ? [path.join(repositoryRoot, 'build/update/app-update.yml')]
      : [],
    osxSign: profile === 'personal-release' ? releaseSigningConfiguration() : {
      identity: '-',
      identityValidation: false,
      preAutoEntitlements: false,
      preEmbedProvisioningProfile: false,
      // Hardened Runtime library validation treats separately ad-hoc-signed
      // Electron binaries as different identities on macOS 26 and refuses to
      // load Electron Framework at launch. Keep it disabled for this local-only
      // ad-hoc build. A Developer ID distribution build must use its own signing
      // profile, Hardened Runtime, and notarization instead. The personal
      // profile is self-signed and must be validated separately on macOS.
      optionsForFile: () => ({ hardenedRuntime: false, timestamp: 'none' }),
    },
  },
  rebuildConfig: {},
  makers: [],
  plugins: [
    new VitePlugin({
      build: [
        {
          entry: { main: 'src/main/index.ts' },
          config: 'vite.main.config.mts',
          target: 'main',
        },
        {
          entry: { preload: 'src/preload/index.ts' },
          config: 'vite.preload.config.mts',
          target: 'preload',
        },
      ],
      renderer: [
        {
          name: 'main_window',
          config: 'vite.renderer.config.mts',
        },
      ],
    }),
  ],
};

export default config;
