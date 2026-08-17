import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

import { ReqwsError } from '../../src/shared/errors';
import { AppStateStore } from '../../src/main/services/app-state-store';

const temporaryDirectories: string[] = [];

async function createStore(): Promise<{ store: AppStateStore; filePath: string }> {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-state-'));
  temporaryDirectories.push(directory);
  const filePath = path.join(directory, 'state.v1.json');
  return { store: new AppStateStore(filePath), filePath };
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe('AppStateStore', () => {
  it('uses the empty schema-v1 state by default', async () => {
    const { store } = await createStore();
    expect(await store.read()).toEqual({
      schemaVersion: 1,
      settings: {
        localePreference: 'system',
        workspaceParentDirectory: null,
        workspaceFileDirectory: null,
      },
      repositories: [],
      workspaces: [],
    });
  });

  it('serializes asynchronous updates so concurrent callers do not lose changes', async () => {
    const { store } = await createStore();
    const first = store.update(async (state) => {
      await new Promise<void>((resolve) => setTimeout(resolve, 20));
      return {
        ...state,
        settings: {
          ...state.settings,
          workspaceParentDirectory: '/first',
        },
      };
    });
    const second = store.update((state) => ({
      ...state,
      settings: {
        ...state.settings,
        workspaceFileDirectory: '/second',
      },
    }));

    await Promise.all([first, second]);

    expect((await store.read()).settings).toEqual({
      localePreference: 'system',
      workspaceParentDirectory: '/first',
      workspaceFileDirectory: '/second',
    });
  });

  it('defaults a missing settings subtree without blocking startup', async () => {
    const { store, filePath } = await createStore();
    await writeFile(
      filePath,
      JSON.stringify({ schemaVersion: 1, repositories: [], workspaces: [] }),
      'utf8',
    );

    expect((await store.read()).settings).toEqual({
      localePreference: 'system',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
    });
  });

  it('normalizes invalid settings fields and migrates valid legacy paths', async () => {
    const { store, filePath } = await createStore();
    await writeFile(
      filePath,
      JSON.stringify({
        schemaVersion: 1,
        settings: {
          localePreference: 'fr-FR',
          workspaceParentDirectory: 42,
          lastWorkspaceFileDirectory: '/legacy/workspace-files',
        },
        repositories: [],
        workspaces: [],
      }),
      'utf8',
    );

    expect((await store.read()).settings).toEqual({
      localePreference: 'system',
      workspaceParentDirectory: null,
      workspaceFileDirectory: '/legacy/workspace-files',
    });
  });

  it('reads a legacy-only credential-free URL without resetting the state file', async () => {
    const { store, filePath } = await createStore();
    const timestamp = '2026-08-12T00:00:00.000Z';
    const legacyUrl = 'ssh://git@a%ZZb.example/team/order-api.git';
    await writeFile(
      filePath,
      JSON.stringify({
        schemaVersion: 1,
        settings: {},
        repositories: [{
          id: 'repo_legacy',
          name: 'order-api',
          url: legacyUrl,
          defaultBranch: 'main',
          createdAt: timestamp,
          updatedAt: timestamp,
        }],
        workspaces: [],
      }),
      'utf8',
    );

    expect((await store.read()).repositories[0]?.url).toBe(legacyUrl);
  });

  it('does not overwrite corrupt state and surfaces STATE_CORRUPT', async () => {
    const { store, filePath } = await createStore();
    await writeFile(filePath, '{}', 'utf8');

    const error = await store.read().catch((reason: unknown) => reason);
    expect(error).toBeInstanceOf(ReqwsError);
    expect((error as ReqwsError).code).toBe('STATE_CORRUPT');
  });

  it('treats duplicate semantic identities as corrupt state', async () => {
    const { store, filePath } = await createStore();
    const timestamp = '2026-08-12T00:00:00.000Z';
    const repository = {
      id: 'repo_1',
      name: 'Order-API',
      url: 'https://example.test/order-api.git',
      defaultBranch: 'main',
      createdAt: timestamp,
      updatedAt: timestamp,
    };
    await writeFile(
      filePath,
      JSON.stringify({
        schemaVersion: 1,
        settings: {},
        repositories: [
          repository,
          { ...repository, id: 'repo_2', name: 'order-api' },
        ],
        workspaces: [],
      }),
      'utf8',
    );

    await expect(store.read()).rejects.toMatchObject({ code: 'STATE_CORRUPT' });
  });
});
