import path from 'node:path';

import type { VitePlugin } from '@electron-forge/plugin-vite';
import { describe, expect, it } from 'vitest';

import forgeConfig from '../../forge.config';
import rendererConfig from '../../vite.renderer.config.mjs';

describe('Electron build configuration', () => {
  it('uses a stable macOS identity and a valid local signing configuration', () => {
    expect(forgeConfig.packagerConfig).toMatchObject({
      appBundleId: 'com.reqws.desktop',
      appCategoryType: 'public.app-category.developer-tools',
      osxSign: {
        identity: '-',
        identityValidation: false,
        preAutoEntitlements: false,
        preEmbedProvisioningProfile: false,
      },
    });
    const signing = forgeConfig.packagerConfig?.osxSign;
    expect(typeof signing).toBe('object');
    if (typeof signing !== 'object' || signing === null) return;
    expect(signing.optionsForFile?.('ReqWS.app')).toMatchObject({
      hardenedRuntime: false,
      timestamp: 'none',
    });
  });

  it('uses distinct main and preload output names', () => {
    const vitePlugin = forgeConfig.plugins?.[0] as VitePlugin;

    expect(vitePlugin.config.build.map(({ entry }) => entry)).toEqual([
      { main: 'src/main/index.ts' },
      { preload: 'src/preload/index.ts' },
    ]);
  });

  it('places renderer output beside the main-process build directory', () => {
    expect(rendererConfig).not.toBeTypeOf('function');
    if (typeof rendererConfig === 'function') return;

    expect(rendererConfig.root).toBe('src/renderer');
    expect(
      path.resolve(rendererConfig.root ?? '', rendererConfig.build?.outDir ?? ''),
    ).toBe(path.resolve('.vite/renderer/main_window'));
  });
});
