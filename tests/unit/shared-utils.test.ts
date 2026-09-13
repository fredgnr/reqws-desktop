import { readFileSync } from 'node:fs';
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
const repositoryUrlContract = JSON.parse(readFileSync(path.resolve(
  import.meta.dirname,
  '../../integrations/goland/src/test/resources/contracts/repository-url-safety.json',
), 'utf8')) as {
  schemaVersion: number;
  cases: Array<{ name: string; url: string; safe: boolean }>;
};

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

  it('keeps the shared repository URL contract versioned and complete', () => {
    expect(repositoryUrlContract.schemaVersion).toBe(1);
    expect(new Set(repositoryUrlContract.cases.map(({ name }) => name)).size)
      .toBe(repositoryUrlContract.cases.length);
    expect(repositoryUrlContract.cases.map(({ name }) => name)).toEqual(
      expect.arrayContaining([
        'legacy-compatible malformed percent path text',
        'legacy-compatible backslash path',
        'legacy-compatible HTTPS empty port',
        'legacy-compatible HTTPS empty userinfo',
        'legacy-compatible percent-encoded UTF-8 HTTPS host',
        'legacy-compatible interior empty DNS label',
        'encoded credential query key',
        'alphabetic port',
        'HTTPS IPv6 authority',
        'HTTPS IPv4-embedded IPv6 authority',
        'HTTPS userinfo on IPv6 authority',
        'HTTPS internationalized domain',
        'SSH percent-encoded colon username',
        'SSH multiple-at username',
        'legacy-compatible HTTPS empty password boundary',
        'legacy-compatible SSH empty password boundary',
        'legacy-compatible named SSH empty password boundary',
        'zero width joiner in authority host',
        'invalid percent-encoded UTF-8 authority host',
        'out-of-range numeric IPv4 authority',
      ]),
    );
  });

  it.each(repositoryUrlContract.cases)(
    'applies shared repository URL case: $name',
    ({ url, safe }) => {
      expect(isSafeRepositoryUrl(url)).toBe(safe);
    },
  );
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
