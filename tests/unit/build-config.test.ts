import path from 'node:path';

import type { VitePlugin } from '@electron-forge/plugin-vite';
import type { ResolvedForgeConfig } from '@electron-forge/shared-types';
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

  it('lets the Vite plugin exclude Gradle sources and outputs from Electron packages', async () => {
    expect(forgeConfig.packagerConfig?.ignore).toBeUndefined();
    const vitePlugin = forgeConfig.plugins?.[0] as VitePlugin;
    const resolved = await vitePlugin.resolveForgeConfig({
      packagerConfig: { ...forgeConfig.packagerConfig },
    } as ResolvedForgeConfig);
    const ignore = resolved.packagerConfig.ignore;

    expect(ignore).toBeTypeOf('function');
    if (typeof ignore !== 'function') return;
    expect(ignore('')).toBe(false);
    expect(ignore('/.vite/build/main.js')).toBe(false);
    expect(ignore('/integrations/goland/build/distributions/plugin.zip'))
      .toBe(true);
    expect(ignore('/integrations/goland/src/main/plugin.xml')).toBe(true);
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
