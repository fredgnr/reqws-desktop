import path from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  deriveRepositoryName,
  isSafeRepositoryUrl,
  isValidRepositoryName,
  repositoryNameKey,
} from '../../src/shared/repository-utils';
import { matchesRepository, matchesWorkspace } from '../../src/shared/search';
import type { Repository, WorkspaceSummary } from '../../src/shared/types';
import {
  addManifestRepository,
  buildCodeWorkspace,
  removeManifestRepository,
  workspaceFileName,
  workspaceSlug,
} from '../../src/shared/workspace-utils';

const now = '2026-08-12T00:00:00.000Z';

describe('repository utilities', () => {
  it.each([
    ['https://example.com/team/order-api.git', 'order-api'],
    ['git@example.com:team/payment-api.git', 'payment-api'],
    ['ssh://git@example.com/team/account-sdk/', 'account-sdk'],
  ])('derives %s', (url, expected) => {
    expect(deriveRepositoryName(url)).toBe(expected);
  });

  it('validates directory-safe names and compares names like default macOS volumes', () => {
    expect(isValidRepositoryName('order-api')).toBe(true);
    expect(isValidRepositoryName('../order-api')).toBe(false);
    expect(isValidRepositoryName('order/api')).toBe(false);
    expect(repositoryNameKey(' Équipe ')).toBe(repositoryNameKey('E\u0301QUIPE'));
  });

  it.each([
    'https://example.com/team/order-api.git',
    'ssh://git@example.com/team/order-api.git',
    'git@example.com:team/order-api.git',
  ])('accepts credential-free Git remote %s', (url) => {
    expect(isSafeRepositoryUrl(url)).toBe(true);
  });

  it.each([
    'https://rose:super-secret@example.com/order-api.git',
    'https://rose@example.com/order-api.git',
    'ssh://git:super-secret@example.com/order-api.git',
    'git://rose@example.com/order-api.git',
    'http://example.com/order-api.git',
    'file:///tmp/origin.git',
    '/tmp/origin.git',
    'https://example.com/order-api.git?access_token=super-secret',
    'https://example.com/order-api.git#private_token=super-secret',
    'git@example.com:team/order-api.git\n--upload-pack=evil',
    'git@example.com:team/order-api.git\0evil',
    '--upload-pack=evil',
    'ext::sh -c evil',
    'user:password@example.com:team/order-api.git',
  ])('rejects credential-bearing or unsafe Git remote input', (url) => {
    expect(isSafeRepositoryUrl(url)).toBe(false);
  });
});

describe('workspace utilities', () => {
  const repository = {
    catalogRepositoryId: 'repo-1',
    name: 'order-api',
    url: 'https://example.com/order-api.git',
    defaultBranch: 'main',
    relativePath: 'order-api',
  };
  const manifest = {
    schemaVersion: 1 as const,
    id: 'ws-1',
    name: 'FEAT 123',
    featureBranch: 'feature/FEAT-123',
    rootPath: '/tmp/features/FEAT-123',
    workspaceFilePath: '/tmp/workspaces/FEAT-123.code-workspace',
    repositories: [],
    createdAt: now,
    updatedAt: now,
  };

  it('creates stable slugs and workspace filenames', () => {
    expect(workspaceSlug(' FEAT 123 / refund ')).toBe('FEAT-123-refund');
    expect(workspaceFileName(' FEAT 123 ')).toBe('FEAT-123.code-workspace');
  });

  it('builds absolute folder paths in manifest order', () => {
    const file = buildCodeWorkspace(manifest.rootPath, [repository]);
    expect(file.folders).toEqual([
      { name: 'order-api', path: path.resolve(manifest.rootPath, 'order-api') },
    ]);
    expect(file.extensions.recommendations).toContain('golang.go');
  });

  it('adds and removes immutable manifest snapshots', () => {
    const added = addManifestRepository(manifest, repository, now);
    expect(added.repositories).toEqual([repository]);
    expect(addManifestRepository(added, repository, now)).toBe(added);
    expect(removeManifestRepository(added, 'repo-1', now).repositories).toEqual([]);
  });
});

describe('search', () => {
  const repository: Repository = {
    id: 'repo-1',
    name: 'order-api',
    url: 'git@example.com:order/order-api.git',
    defaultBranch: 'develop',
    createdAt: now,
    updatedAt: now,
  };
  const workspace: WorkspaceSummary = {
    id: 'ws-1',
    name: 'FEAT-123-payment-refund',
    featureBranch: 'feature/FEAT-123',
    rootPath: '/Users/rose/Developer/features/FEAT-123',
    workspaceFilePath: '/Users/rose/Workspaces/FEAT-123.code-workspace',
    repositoryNames: ['order-api', 'payment-api'],
    status: 'ready',
    createdAt: now,
    updatedAt: now,
  };

  it('matches all repository fields', () => {
    expect(matchesRepository(repository, 'ORDER-API')).toBe(true);
    expect(matchesRepository(repository, 'develop')).toBe(true);
    expect(matchesRepository(repository, 'missing')).toBe(false);
  });

  it('matches workspace name, branch, repository, and paths', () => {
    for (const query of ['refund', 'feature/FEAT-123', 'payment-api', 'Developer', 'Workspaces']) {
      expect(matchesWorkspace(workspace, query)).toBe(true);
    }
  });
});
