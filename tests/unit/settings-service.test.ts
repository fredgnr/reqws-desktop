import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  AppStateStore,
  createDefaultAppState,
} from '../../src/main/services/app-state-store';
import {
  DefaultSettingsService,
  resolveEffectiveLocale,
} from '../../src/main/services/settings-service';
import type { GlobalSettings } from '../../src/shared/types';

const temporaryDirectories: string[] = [];

async function createFixture(
  languages: readonly string[] = ['en-US'],
): Promise<{
  directory: string;
  filePath: string;
  store: AppStateStore;
  service: DefaultSettingsService;
}> {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-settings-'));
  temporaryDirectories.push(directory);
  const filePath = path.join(directory, 'state.v1.json');
  const store = new AppStateStore(filePath);
  return {
    directory,
    filePath,
    store,
    service: new DefaultSettingsService(store, () => languages),
  };
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe('DefaultSettingsService', () => {
  it('returns defaults when settings are missing', async () => {
    const { service } = await createFixture();

    await expect(service.get()).resolves.toEqual({
      localePreference: 'system',
      effectiveLocale: 'en-US',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
    });
  });

  it('resolves system locale from the injected preferred language list', () => {
    expect(resolveEffectiveLocale('system', ['en-US', 'zh-Hans-CN'])).toBe(
      'zh-CN',
    );
    expect(resolveEffectiveLocale('system', ['ja-JP', 'en-US'])).toBe('en-US');
    expect(resolveEffectiveLocale('zh-CN', ['en-US'])).toBe('zh-CN');
  });

  it('migrates valid legacy directory settings and tolerates bad fields', async () => {
    const { directory, filePath, service } = await createFixture(['zh-Hans-CN']);
    const legacyDirectory = path.join(directory, 'legacy-features');
    await mkdir(legacyDirectory);
    await writeFile(
      filePath,
      JSON.stringify({
        schemaVersion: 1,
        settings: {
          localePreference: 'fr-FR',
          lastWorkspaceParentDirectory: legacyDirectory,
          lastWorkspaceFileDirectory: 42,
        },
        repositories: [],
        workspaces: [],
      }),
      'utf8',
    );

    await expect(service.get()).resolves.toEqual({
      localePreference: 'system',
      effectiveLocale: 'zh-CN',
      workspaceParentDirectory: legacyDirectory,
      workspaceFileDirectory: null,
    });
  });

  it('treats persisted directories that are no longer usable as unset', async () => {
    const { directory, store, service } = await createFixture();
    const filePath = path.join(directory, 'not-a-directory');
    await writeFile(filePath, 'content', 'utf8');
    await store.replace({
      ...createDefaultAppState(),
      settings: {
        localePreference: 'zh-CN',
        workspaceParentDirectory: path.join(directory, 'deleted-directory'),
        workspaceFileDirectory: filePath,
      },
    });

    await expect(service.get()).resolves.toEqual({
      localePreference: 'zh-CN',
      effectiveLocale: 'zh-CN',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
      invalidDirectoryFields: [
        'workspaceParentDirectory',
        'workspaceFileDirectory',
      ],
    });
  });

  it('saves valid directories without overwriting repository or workspace data', async () => {
    const { directory, store, service } = await createFixture();
    const featureDirectory = path.join(directory, 'features');
    const workspaceFileDirectory = path.join(directory, 'workspace-files');
    await Promise.all([
      mkdir(featureDirectory),
      mkdir(workspaceFileDirectory),
    ]);
    const timestamp = '2026-08-12T00:00:00.000Z';
    const repository = {
      id: 'repo_1',
      name: 'order-api',
      url: 'https://example.test/order-api.git',
      defaultBranch: 'main',
      createdAt: timestamp,
      updatedAt: timestamp,
    };
    const workspace = {
      id: 'ws_1',
      name: 'feature-one',
      featureBranch: 'feature/one',
      rootPath: '/features/feature-one',
      workspaceFilePath: '/workspace-files/feature-one.code-workspace',
      repositoryNames: ['order-api'],
      repositoryIds: ['repo_1'],
      status: 'ready' as const,
      createdAt: timestamp,
      updatedAt: timestamp,
    };
    await store.replace({
      ...createDefaultAppState(),
      repositories: [repository],
      workspaces: [workspace],
    });

    await expect(service.save({
      localePreference: 'en-US',
      workspaceParentDirectory: featureDirectory,
      workspaceFileDirectory,
    })).resolves.toEqual({
      localePreference: 'en-US',
      effectiveLocale: 'en-US',
      workspaceParentDirectory: featureDirectory,
      workspaceFileDirectory,
    });

    const state = await store.read();
    expect(state.repositories).toEqual([repository]);
    expect(state.workspaces).toEqual([workspace]);
    expect(state.settings).toEqual({
      localePreference: 'en-US',
      workspaceParentDirectory: featureDirectory,
      workspaceFileDirectory,
    });
  });

  it('rejects unknown locales, relative paths, and extra fields', async () => {
    const { service } = await createFixture();
    const defaults: GlobalSettings = {
      localePreference: 'system',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
    };

    await expect(service.save({
      ...defaults,
      localePreference: 'fr-FR',
    } as unknown as GlobalSettings)).rejects.toMatchObject({
      code: 'SETTINGS_INVALID_LOCALE',
    });
    await expect(service.save({
      ...defaults,
      workspaceParentDirectory: 'relative/features',
    })).rejects.toMatchObject({ code: 'INVALID_INPUT' });
    await expect(service.save({
      ...defaults,
      stateFilePath: '/tmp/attacker-state.json',
    } as GlobalSettings)).rejects.toMatchObject({ code: 'INVALID_INPUT' });
  });

  it('distinguishes missing directories from paths that are not directories', async () => {
    const { directory, service } = await createFixture();
    const missing = path.join(directory, 'missing');
    const filePath = path.join(directory, 'not-a-directory');
    await writeFile(filePath, 'content', 'utf8');

    await expect(service.save({
      localePreference: 'system',
      workspaceParentDirectory: missing,
      workspaceFileDirectory: null,
    })).rejects.toMatchObject({ code: 'SETTINGS_DIRECTORY_NOT_FOUND' });
    await expect(service.save({
      localePreference: 'system',
      workspaceParentDirectory: filePath,
      workspaceFileDirectory: null,
    })).rejects.toMatchObject({ code: 'SETTINGS_DIRECTORY_NOT_DIRECTORY' });
  });

  it('maps state read and write failures to settings-specific error codes', async () => {
    const state = createDefaultAppState();
    const update = vi.fn().mockRejectedValue(new Error('injected write failure'));
    const service = new DefaultSettingsService(
      {
        read: vi.fn().mockResolvedValue(state),
        update,
      },
      () => ['en-US'],
    );

    await expect(service.save({
      localePreference: 'system',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
    })).rejects.toMatchObject({ code: 'SETTINGS_WRITE_FAILED' });
    expect(state.settings).toEqual(createDefaultAppState().settings);

    const readFailure = new DefaultSettingsService(
      {
        read: vi.fn().mockRejectedValue(new Error('injected read failure')),
        update: vi.fn(),
      },
      () => ['en-US'],
    );
    await expect(readFailure.get()).rejects.toMatchObject({
      code: 'SETTINGS_READ_FAILED',
    });
  });

  it('keeps the previously committed state file intact when a save is rejected', async () => {
    const { filePath, store, service } = await createFixture();
    await store.replace(createDefaultAppState());
    const before = await readFile(filePath, 'utf8');

    await expect(service.save({
      localePreference: 'system',
      workspaceParentDirectory: '/does/not/exist',
      workspaceFileDirectory: null,
    })).rejects.toMatchObject({ code: 'SETTINGS_DIRECTORY_NOT_FOUND' });

    expect(await readFile(filePath, 'utf8')).toBe(before);
  });
});
