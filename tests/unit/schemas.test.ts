import { describe, expect, it } from 'vitest';
import {
  appStateSchema,
  createRepositoryInputSchema,
  createWorkspaceInputSchema,
  testRepositoryInputSchema,
  workspaceManifestSchema,
} from '../../src/shared/schemas';

describe('IPC and state schemas', () => {
  it('rejects unsafe repository names', () => {
    expect(
      createRepositoryInputSchema.safeParse({
        name: '../repo',
        url: 'https://example.test/repo.git',
        defaultBranch: 'main',
      }).success,
    ).toBe(false);
  });

  it('rejects credential-bearing and remote-helper repository URLs', () => {
    for (const url of [
      'https://user:secret@example.test/team/repo.git',
      'ext::sh -c exploit',
      'https://example.test/repo.git?oauth_token=secret',
    ]) {
      expect(createRepositoryInputSchema.safeParse({
        name: 'repo',
        url,
        defaultBranch: 'main',
      }).success).toBe(false);
      expect(testRepositoryInputSchema.safeParse({ url }).success).toBe(false);
    }
  });

  it('accepts only credential-free HTTPS and SSH repository URLs', () => {
    for (const url of [
      'https://example.test/team/repo.git',
      'ssh://git@example.test/team/repo.git',
      'git@example.test:team/repo.git',
    ]) {
      expect(testRepositoryInputSchema.safeParse({ url }).success).toBe(true);
    }
    for (const url of [
      'http://example.test/repo.git',
      'git://example.test/repo.git',
      'file:///tmp/repo.git',
      '/tmp/repo.git',
    ]) {
      expect(testRepositoryInputSchema.safeParse({ url }).success).toBe(false);
    }
  });

  it('rejects unsafe persisted repository URLs in state and manifests', () => {
    const unsafeRepository = {
      id: 'repo_1',
      name: 'repo',
      url: 'https://user:secret@example.test/repo.git',
      defaultBranch: 'main',
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z',
    };
    expect(appStateSchema.safeParse({
      schemaVersion: 1,
      settings: {},
      repositories: [unsafeRepository],
      workspaces: [],
    }).success).toBe(false);
    expect(workspaceManifestSchema.safeParse({
      schemaVersion: 1,
      id: 'ws_1',
      name: 'workspace',
      featureBranch: 'feature/workspace',
      rootPath: '/tmp/workspace',
      workspaceFilePath: '/tmp/workspace.code-workspace',
      repositories: [{
        catalogRepositoryId: unsafeRepository.id,
        name: unsafeRepository.name,
        url: unsafeRepository.url,
        defaultBranch: unsafeRepository.defaultBranch,
        relativePath: unsafeRepository.name,
      }],
      createdAt: unsafeRepository.createdAt,
      updatedAt: unsafeRepository.updatedAt,
    }).success).toBe(false);
  });

  it('requires at least one workspace repository', () => {
    expect(
      createWorkspaceInputSchema.safeParse({
        name: 'FEAT-1',
        featureBranch: 'feature/FEAT-1',
        rootPath: '/tmp/FEAT-1',
        workspaceFileDirectory: '/tmp/workspaces',
        repositoryIds: [],
      }).success,
    ).toBe(false);
  });

  it('accepts the empty schema-v1 state', () => {
    expect(
      appStateSchema.parse({
        schemaVersion: 1,
        settings: {},
        repositories: [],
        workspaces: [],
      }),
    ).toBeTruthy();
  });

  it('rejects duplicate semantic identities in state', () => {
    const repository = {
      id: 'repo_1',
      name: 'Order-API',
      url: 'https://example.test/order-api.git',
      defaultBranch: 'main',
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z',
    };
    const workspace = {
      id: 'ws_1',
      name: 'Feature One',
      featureBranch: 'feature/one',
      rootPath: '/Users/rose/Features/One',
      workspaceFilePath: '/Users/rose/Workspaces/One.code-workspace',
      repositoryNames: ['Order-API'],
      repositoryIds: ['repo_1'],
      status: 'ready' as const,
      createdAt: repository.createdAt,
      updatedAt: repository.updatedAt,
    };

    expect(appStateSchema.safeParse({
      schemaVersion: 1,
      settings: {},
      repositories: [
        repository,
        { ...repository, id: 'repo_2', name: 'order-api' },
      ],
      workspaces: [],
    }).success).toBe(false);
    expect(appStateSchema.safeParse({
      schemaVersion: 1,
      settings: {},
      repositories: [repository],
      workspaces: [
        workspace,
        {
          ...workspace,
          id: 'ws_2',
          rootPath: '/users/rose/features/one',
          workspaceFilePath: '/users/rose/workspaces/one.code-workspace',
        },
      ],
    }).success).toBe(false);
  });

  it('rejects duplicate or path-divergent manifest repositories', () => {
    const repository = {
      catalogRepositoryId: 'repo_1',
      name: 'order-api',
      url: 'https://example.test/order-api.git',
      defaultBranch: 'main',
      relativePath: 'order-api',
    };
    const manifest = {
      schemaVersion: 1 as const,
      id: 'ws_1',
      name: 'workspace',
      featureBranch: 'feature/workspace',
      rootPath: '/tmp/workspace',
      workspaceFilePath: '/tmp/workspace.code-workspace',
      repositories: [repository],
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z',
    };

    expect(workspaceManifestSchema.safeParse({
      ...manifest,
      repositories: [repository, { ...repository, name: 'Order-API' }],
    }).success).toBe(false);
    expect(workspaceManifestSchema.safeParse({
      ...manifest,
      repositories: [{ ...repository, relativePath: 'other-path' }],
    }).success).toBe(false);
  });
});
