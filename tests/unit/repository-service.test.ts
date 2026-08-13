import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

import { ReqwsError } from '../../src/shared/errors';
import { AppStateStore } from '../../src/main/services/app-state-store';
import { RepositoryService } from '../../src/main/services/repository-service';

const temporaryDirectories: string[] = [];

async function services(): Promise<{
  store: AppStateStore;
  repositories: RepositoryService;
}> {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-repos-'));
  temporaryDirectories.push(directory);
  const store = new AppStateStore(path.join(directory, 'state.json'));
  let id = 0;
  return {
    store,
    repositories: new RepositoryService(store, {
      now: () => new Date('2026-08-12T00:00:00.000Z'),
      createId: () => `repo_${++id}`,
    }),
  };
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe('RepositoryService', () => {
  it('normalizes catalog input and enforces case-insensitive NFC name uniqueness', async () => {
    const { repositories } = await services();
    const created = await repositories.create({
      name: '  Cafe\u0301  ',
      url: '  git@example.test:team/cafe.git  ',
      defaultBranch: ' main ',
    });

    expect(created).toMatchObject({
      id: 'repo_1',
      name: 'Café',
      url: 'git@example.test:team/cafe.git',
      defaultBranch: 'main',
    });

    const error = await repositories.create({
      name: 'CAFÉ',
      url: 'https://example.test/other.git',
      defaultBranch: 'main',
    }).catch((reason: unknown) => reason);
    expect(error).toBeInstanceOf(ReqwsError);
    expect((error as ReqwsError).code).toBe('DUPLICATE_REPOSITORY_NAME');
  });

  it('updates only catalog data and preserves workspace snapshots', async () => {
    const { repositories, store } = await services();
    const repository = await repositories.create({
      name: 'order-api',
      url: 'git@example.test:order-api.git',
      defaultBranch: 'main',
    });
    await store.update((state) => ({
      ...state,
      workspaces: [{
        id: 'ws_1',
        name: 'FEAT-1',
        featureBranch: 'feature/1',
        rootPath: '/tmp/FEAT-1',
        workspaceFilePath: '/tmp/FEAT-1.code-workspace',
        repositoryNames: ['order-api'],
        status: 'ready',
        createdAt: '2026-08-12T00:00:00.000Z',
        updatedAt: '2026-08-12T00:00:00.000Z',
      }],
    }));

    await repositories.update({
      id: repository.id,
      name: 'orders',
      url: 'git@example.test:new/orders.git',
      defaultBranch: 'develop',
    });

    const state = await store.read();
    expect(state.repositories[0]).toMatchObject({ name: 'orders', defaultBranch: 'develop' });
    expect(state.workspaces[0]?.repositoryNames).toEqual(['order-api']);
  });

  it('reports usage and requires confirmation before removing a referenced item', async () => {
    const { repositories, store } = await services();
    const repository = await repositories.create({
      name: 'order-api',
      url: 'git@example.test:order-api.git',
      defaultBranch: 'main',
    });
    await store.update((state) => ({
      ...state,
      workspaces: [{
        id: 'ws_1',
        name: 'FEAT-1',
        featureBranch: 'feature/1',
        rootPath: '/tmp/FEAT-1',
        workspaceFilePath: '/tmp/FEAT-1.code-workspace',
        repositoryNames: ['ORDER-API'],
        status: 'ready',
        createdAt: '2026-08-12T00:00:00.000Z',
        updatedAt: '2026-08-12T00:00:00.000Z',
      }],
    }));

    expect(await repositories.list()).toMatchObject([{
      workspaceUsageCount: 1,
      referencedBy: ['FEAT-1'],
    }]);
    const error = await repositories.remove(repository.id).catch(
      (reason: unknown) => reason,
    );
    expect((error as ReqwsError).code).toBe('REPOSITORY_IN_USE');
    expect(await repositories.remove(repository.id, true)).toEqual({
      removed: true,
      referencedBy: ['FEAT-1'],
    });
  });

  it.each([
    'https://rose:super-secret@example.test/order-api.git',
    'https://example.test/order-api.git?token=super-secret',
    'git@example.test:order-api.git\n--config=evil',
  ])('refuses to persist credentials or unsafe URL characters', async (url) => {
    const { repositories, store } = await services();

    const error = await repositories.create({
      name: 'order-api',
      url,
      defaultBranch: 'main',
    }).catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(ReqwsError);
    expect((error as ReqwsError).code).toBe('INVALID_INPUT');
    expect(JSON.stringify((error as ReqwsError).toPayload())).not.toContain(
      'super-secret',
    );
    expect((await store.read()).repositories).toEqual([]);
  });

  it('rejects a credential-bearing update without changing stored catalog data', async () => {
    const { repositories, store } = await services();
    const repository = await repositories.create({
      name: 'order-api',
      url: 'git@example.test:order-api.git',
      defaultBranch: 'main',
    });

    const error = await repositories.update({
      id: repository.id,
      name: 'order-api',
      url: 'https://rose:super-secret@example.test/order-api.git',
      defaultBranch: 'main',
    }).catch((reason: unknown) => reason);

    expect((error as ReqwsError).code).toBe('INVALID_INPUT');
    expect(JSON.stringify((error as ReqwsError).toPayload())).not.toContain(
      'super-secret',
    );
    expect((await store.read()).repositories[0]?.url).toBe(
      'git@example.test:order-api.git',
    );
  });

  it('uses repository IDs for usage after catalog rename and names only for legacy state', async () => {
    const { repositories, store } = await services();
    const repository = await repositories.create({
      name: 'order-api',
      url: 'git@example.test:order-api.git',
      defaultBranch: 'main',
    });
    await store.update((state) => ({
      ...state,
      workspaces: [{
        id: 'ws_1',
        name: 'FEAT-1',
        featureBranch: 'feature/1',
        rootPath: '/tmp/FEAT-1',
        workspaceFilePath: '/tmp/FEAT-1.code-workspace',
        repositoryIds: [repository.id],
        repositoryNames: ['old-snapshot-name'],
        status: 'ready',
        createdAt: '2026-08-12T00:00:00.000Z',
        updatedAt: '2026-08-12T00:00:00.000Z',
      }],
    }));
    await repositories.update({
      id: repository.id,
      name: 'orders-renamed',
      url: repository.url,
      defaultBranch: repository.defaultBranch,
    });

    expect(await repositories.list()).toMatchObject([{
      name: 'orders-renamed',
      workspaceUsageCount: 1,
      referencedBy: ['FEAT-1'],
    }]);
  });
});
