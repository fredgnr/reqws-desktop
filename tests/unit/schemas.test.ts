import { describe, expect, it } from 'vitest';
import {
  appStateSchema,
  createRepositoryInputSchema,
  createWorkspaceInputSchema,
  globalSettingsSchema,
  systemAvailabilitySchema,
  testRepositoryInputSchema,
  workspaceSummarySchema,
  workspaceManifestSchema,
} from '../../src/shared/schemas';

describe('IPC and state schemas', () => {
  it('validates the complete editor availability response contract', () => {
    expect(systemAvailabilitySchema.safeParse({
      git: { available: true, path: '/usr/bin/git' },
      vscode: { available: false, reasonCode: 'NOT_FOUND' },
      cursor: { available: true, path: '/Applications/Cursor.app' },
      goland: {
        available: true,
        path: '/Users/rose/Applications/GoLand.app',
      },
    }).success).toBe(true);
    expect(systemAvailabilitySchema.safeParse({
      git: { available: true, path: '/usr/bin/git' },
      vscode: { available: false },
      cursor: { available: false },
    }).success).toBe(false);
    expect(systemAvailabilitySchema.safeParse({
      git: { available: true, path: 'relative/git' },
      vscode: { available: false },
      cursor: { available: false },
      goland: { available: false },
    }).success).toBe(false);
    expect(systemAvailabilitySchema.safeParse({
      git: { available: true, path: '/usr/bin/git' },
      vscode: { available: true },
      cursor: { available: false },
      goland: { available: false },
    }).success).toBe(false);
    expect(systemAvailabilitySchema.safeParse({
      git: { available: true, path: '/usr/bin/git' },
      vscode: { available: false, path: '/Applications/Code.app' },
      cursor: { available: false },
      goland: { available: false },
    }).success).toBe(false);
    expect(systemAvailabilitySchema.safeParse({
      git: { available: true, path: '/usr/bin/git' },
      vscode: {
        available: true,
        path: '/Applications/Visual Studio Code.app',
        reasonCode: 'NOT_FOUND',
      },
      cursor: { available: false },
      goland: { available: false },
    }).success).toBe(false);
  });

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
    expect(appStateSchema.parse({
      schemaVersion: 1,
      settings: {},
      repositories: [],
      workspaces: [],
    }).settings).toEqual({
      localePreference: 'system',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
    });
  });

  it('quarantines legacy-only repository URLs to persisted state reads', () => {
    const timestamp = '2026-08-12T00:00:00.000Z';
    const legacyUrl = 'ssh://git@a%ZZb.example/team/order-api.git';
    const repository = {
      id: 'repo_legacy',
      name: 'order-api',
      url: legacyUrl,
      defaultBranch: 'main',
      createdAt: timestamp,
      updatedAt: timestamp,
    };

    expect(createRepositoryInputSchema.safeParse(repository).success).toBe(false);
    expect(appStateSchema.parse({
      schemaVersion: 1,
      settings: {},
      repositories: [repository],
      workspaces: [],
    }).repositories[0]?.url).toBe(legacyUrl);
    expect(workspaceManifestSchema.safeParse({
      schemaVersion: 1,
      id: 'ws_legacy',
      name: 'Legacy URL workspace',
      featureBranch: 'feature/legacy-url',
      rootPath: '/tmp/legacy-url',
      workspaceFilePath: '/tmp/legacy-url.code-workspace',
      repositories: [{
        catalogRepositoryId: repository.id,
        name: repository.name,
        url: repository.url,
        defaultBranch: repository.defaultBranch,
        relativePath: repository.name,
      }],
      createdAt: timestamp,
      updatedAt: timestamp,
    }).success).toBe(false);
  });

  it('accepts only stable missing-workspace artifact identifiers', () => {
    const summary = {
      id: 'ws_1',
      name: 'Feature One',
      featureBranch: 'feature/one',
      rootPath: '/Users/rose/Features/One',
      workspaceFilePath: '/Users/rose/Workspaces/One.code-workspace',
      repositoryNames: ['order-api'],
      status: 'missing',
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z',
    };

    expect(workspaceSummarySchema.safeParse({
      ...summary,
      missingArtifacts: ['workspace-root', 'manifest', 'workspace-file'],
    }).success).toBe(true);
    expect(workspaceSummarySchema.safeParse({
      ...summary,
      missingArtifacts: ['unknown-artifact'],
    }).success).toBe(false);
    expect(workspaceSummarySchema.safeParse(summary).success).toBe(true);
  });

  it('normalizes missing, malformed, and legacy persisted settings', () => {
    const base = { schemaVersion: 1 as const, repositories: [], workspaces: [] };
    expect(appStateSchema.parse(base).settings).toEqual({
      localePreference: 'system',
      workspaceParentDirectory: null,
      workspaceFileDirectory: null,
    });
    expect(appStateSchema.parse({
      ...base,
      settings: {
        localePreference: 'unknown',
        workspaceParentDirectory: '../relative',
        lastWorkspaceFileDirectory: '/legacy/workspaces',
      },
    }).settings).toEqual({
      localePreference: 'system',
      workspaceParentDirectory: null,
      workspaceFileDirectory: '/legacy/workspaces',
    });
  });

  it('uses a strict schema for settings save requests', () => {
    const settings = {
      localePreference: 'zh-CN',
      workspaceParentDirectory: '/features',
      workspaceFileDirectory: null,
    };
    expect(globalSettingsSchema.safeParse(settings).success).toBe(true);
    expect(globalSettingsSchema.safeParse({
      ...settings,
      localePreference: 'fr-FR',
    }).success).toBe(false);
    expect(globalSettingsSchema.safeParse({
      ...settings,
      stateFilePath: '/tmp/attacker-state.json',
    }).success).toBe(false);
    expect(globalSettingsSchema.safeParse({
      ...settings,
      workspaceParentDirectory: 'relative/path',
    }).success).toBe(false);
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
    expect(workspaceManifestSchema.safeParse({
      ...manifest,
      repositories: [{
        ...repository,
        name: 'order\0api',
        relativePath: 'order\0api',
      }],
    }).success).toBe(false);
    expect(workspaceManifestSchema.safeParse({
      ...manifest,
      rootPath: '/tmp/workspace\0other',
    }).success).toBe(false);
    expect(workspaceManifestSchema.safeParse({
      ...manifest,
      workspaceFilePath: '/tmp/workspace\0other.code-workspace',
    }).success).toBe(false);
  });
});
