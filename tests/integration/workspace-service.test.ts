import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BranchService } from '../../src/main/services/branch-service';
import { GitRunner } from '../../src/main/services/git-runner';
import {
  WorkspaceService,
  manifestPathFor,
  type AppStatePort,
  type WorkspaceFilePort,
} from '../../src/main/services/workspace-service';
import type {
  AppState,
  WorkspaceManifest,
  WorkspaceRepository,
} from '../../src/shared/types';
import { buildCodeWorkspace } from '../../src/shared/workspace-utils';
import {
  createLocalBareRepository,
  type LocalGitFixture,
} from './helpers/git-fixtures';

class MemoryStateStore implements AppStatePort {
  failNextUpdate = false;

  constructor(public state: AppState) {}

  async read(): Promise<AppState> {
    return structuredClone(this.state);
  }

  async update(
    mutator: (state: AppState) => AppState | Promise<AppState>,
  ): Promise<AppState> {
    if (this.failNextUpdate) {
      this.failNextUpdate = false;
      throw new Error('injected state update failure');
    }
    this.state = await mutator(structuredClone(this.state));
    return structuredClone(this.state);
  }
}

class TestWorkspaceFiles implements WorkspaceFilePort {
  failCodeWorkspace = false;
  beforeCodeWorkspaceWrite?: (
    workspaceFilePath: string,
    rootPath: string,
  ) => Promise<void>;
  afterCodeWorkspaceWrite?: (
    workspaceFilePath: string,
    rootPath: string,
  ) => Promise<void>;

  async readManifest(manifestPath: string): Promise<WorkspaceManifest> {
    return JSON.parse(await fs.readFile(manifestPath, 'utf8')) as WorkspaceManifest;
  }

  async writeManifest(
    manifestPath: string,
    manifest: WorkspaceManifest,
  ): Promise<void> {
    await fs.mkdir(path.dirname(manifestPath), { recursive: true });
    await fs.writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  }

  async writeCodeWorkspace(
    workspaceFilePath: string,
    rootPath: string,
    repositories: readonly WorkspaceRepository[],
    options: { overwrite?: boolean } = {},
  ): Promise<void> {
    if (this.failCodeWorkspace) throw new Error('injected workspace write failure');
    await this.beforeCodeWorkspaceWrite?.(workspaceFilePath, rootPath);
    await fs.writeFile(
      workspaceFilePath,
      `${JSON.stringify(buildCodeWorkspace(rootPath, repositories), null, 2)}\n`,
      { encoding: 'utf8', flag: options.overwrite === false ? 'wx' : 'w' },
    );
    await this.afterCodeWorkspaceWrite?.(workspaceFilePath, rootPath);
  }
}

describe('WorkspaceService integration', () => {
  let root: string;
  let git: GitRunner;
  let origin: LocalGitFixture;
  let stateStore: MemoryStateStore;
  let files: TestWorkspaceFiles;
  let service: WorkspaceService;

  beforeEach(async () => {
    root = await fs.mkdtemp(path.join(os.tmpdir(), 'reqws-workspace-test-'));
    git = await GitRunner.create(undefined, {
      allowLocalRepositoryPaths: true,
    });
    origin = await createLocalBareRepository(git);
    stateStore = new MemoryStateStore({
      schemaVersion: 1,
      settings: {},
      repositories: [
        {
          id: 'repo-order',
          name: 'order-api',
          url: origin.originPath,
          defaultBranch: 'main',
          createdAt: '2026-08-12T00:00:00.000Z',
          updatedAt: '2026-08-12T00:00:00.000Z',
        },
      ],
      workspaces: [],
    });
    files = new TestWorkspaceFiles();
    service = new WorkspaceService(
      stateStore,
      files,
      git,
      new BranchService(git),
    );
  });

  afterEach(async () => {
    await Promise.all([
      fs.rm(root, { recursive: true, force: true }),
      origin.cleanup(),
    ]);
  });

  async function createWorkspace() {
    return service.create({
      name: 'FEAT-123-refund',
      featureBranch: 'feature/FEAT-123',
      rootPath: path.join(root, 'features', 'FEAT-123-refund'),
      workspaceFileDirectory: path.join(root, 'workspaces'),
      repositoryIds: ['repo-order'],
    });
  }

  beforeEach(async () => {
    await fs.mkdir(path.join(root, 'features'), { recursive: true });
    await fs.mkdir(path.join(root, 'workspaces'), { recursive: true });
  });

  it('creates a physically independent clone, manifest, workspace file, and index', async () => {
    stateStore.state.settings = {
      localePreference: 'en-US',
      workspaceParentDirectory: '/global/features',
      workspaceFileDirectory: '/global/workspaces',
    };
    const settingsBeforeCreation = structuredClone(stateStore.state.settings);
    const detail = await createWorkspace();
    const repositoryPath = path.join(detail.rootPath, 'order-api');

    await expect(fs.stat(path.join(repositoryPath, '.git'))).resolves.toBeTruthy();
    await expect(fs.stat(manifestPathFor(detail.rootPath))).resolves.toBeTruthy();
    const workspace = JSON.parse(
      await fs.readFile(detail.workspaceFilePath, 'utf8'),
    ) as { folders: Array<{ path: string }> };
    expect(workspace.folders[0]?.path).toBe(repositoryPath);
    expect(stateStore.state.workspaces).toHaveLength(1);
    expect(stateStore.state.settings).toEqual(settingsBeforeCreation);
    const branch = await git.run(['branch', '--show-current'], { cwd: repositoryPath });
    expect(branch.stdout.trim()).toBe('feature/FEAT-123');
  });

  it('retains the atomically published root for recovery if workspace-file writing fails', async () => {
    files.failCodeWorkspace = true;
    const rootPath = path.join(root, 'features', 'FEAT-123-refund');

    await expect(createWorkspace()).rejects.toMatchObject({
      code: 'UNKNOWN',
      detail: expect.stringContaining(rootPath),
    });
    await expect(fs.stat(path.join(rootPath, 'order-api', '.git'))).resolves
      .toBeTruthy();
    expect(stateStore.state.workspaces).toHaveLength(0);
    expect(
      (await fs.readdir(path.join(root, 'features'))).filter((name) =>
        name.startsWith('.reqws-'),
      ),
    ).toEqual([]);
  });

  it('does not overwrite a workspace root created concurrently during staging', async () => {
    const rootPath = path.join(root, 'features', 'FEAT-123-refund');
    const originalClone = git.clone.bind(git);
    vi.spyOn(git, 'clone').mockImplementation(async (url, destination) => {
      await originalClone(url, destination);
      await fs.mkdir(rootPath);
      await fs.writeFile(path.join(rootPath, 'concurrent.txt'), 'keep me', 'utf8');
    });

    await expect(createWorkspace()).rejects.toMatchObject({
      code: 'WORKSPACE_ROOT_EXISTS',
    });
    await expect(
      fs.readFile(path.join(rootPath, 'concurrent.txt'), 'utf8'),
    ).resolves.toBe('keep me');
    expect(stateStore.state.workspaces).toHaveLength(0);
  });

  it('does not overwrite a workspace file created after validation', async () => {
    const workspaceFilePath = path.join(
      root,
      'workspaces',
      'FEAT-123-refund.code-workspace',
    );
    files.beforeCodeWorkspaceWrite = async (targetPath) => {
      await fs.writeFile(targetPath, 'concurrent workspace', {
        encoding: 'utf8',
        flag: 'wx',
      });
    };

    await expect(createWorkspace()).rejects.toBeTruthy();
    await expect(fs.readFile(workspaceFilePath, 'utf8')).resolves.toBe(
      'concurrent workspace',
    );
    await expect(
      fs.stat(path.join(root, 'features', 'FEAT-123-refund', 'order-api', '.git')),
    ).resolves.toBeTruthy();
  });

  it('publishes the complete frozen staging tree in one rename', async () => {
    let publishedEntries: string[] | undefined;
    files.beforeCodeWorkspaceWrite = async (_targetPath, publishedRoot) => {
      publishedEntries = await fs.readdir(publishedRoot);
      await expect(
        fs.stat(path.join(publishedRoot, 'order-api', '.git', 'objects')),
      ).resolves.toBeTruthy();
      await expect(fs.stat(manifestPathFor(publishedRoot))).resolves.toBeTruthy();
    };

    await createWorkspace();

    expect(publishedEntries?.sort()).toEqual(['.reqws', 'order-api']);
  });

  it('retains user additions and all published artifacts when state writing fails', async () => {
    const rootPath = path.join(root, 'features', 'FEAT-123-refund');
    const workspaceFilePath = path.join(
      root,
      'workspaces',
      'FEAT-123-refund.code-workspace',
    );
    files.afterCodeWorkspaceWrite = async (_targetPath, publishedRoot) => {
      await fs.writeFile(
        path.join(publishedRoot, 'user-added.txt'),
        'never delete me',
        'utf8',
      );
      stateStore.failNextUpdate = true;
    };

    const error = await createWorkspace().catch((reason: unknown) => reason);

    expect(error).toMatchObject({
      code: 'UNKNOWN',
      detail: expect.stringContaining('retained for safe recovery'),
    });
    await expect(fs.readFile(path.join(rootPath, 'user-added.txt'), 'utf8'))
      .resolves.toBe('never delete me');
    await expect(fs.stat(workspaceFilePath)).resolves.toBeTruthy();
    expect(stateStore.state.workspaces).toEqual([]);
  });

  it('rejects a workspace-file parent symlink swap during creation', async () => {
    const workspaceDirectory = path.join(root, 'workspaces');
    const originalWorkspaceDirectory = path.join(root, 'original-workspaces');
    const outside = path.join(root, 'outside-workspaces');
    const originalClone = git.clone.bind(git);
    vi.spyOn(git, 'clone').mockImplementation(async (url, destination) => {
      await originalClone(url, destination);
      await fs.rename(workspaceDirectory, originalWorkspaceDirectory);
      await fs.mkdir(outside);
      await fs.symlink(outside, workspaceDirectory);
    });

    await expect(createWorkspace()).rejects.toMatchObject({
      code: 'WORKSPACE_FILE_WRITE_FAILED',
    });
    expect(await fs.readdir(outside)).toEqual([]);
    await expect(
      fs.stat(path.join(root, 'features', 'FEAT-123-refund', 'order-api', '.git')),
    ).resolves.toBeTruthy();
  });

  it('never recursively deletes a replacement root during rollback', async () => {
    const rootPath = path.join(root, 'features', 'FEAT-123-refund');
    const displacedRoot = path.join(root, 'features', 'displaced-workspace');
    files.beforeCodeWorkspaceWrite = async (_targetPath, publishedRoot) => {
      await fs.rename(publishedRoot, displacedRoot);
      await fs.mkdir(publishedRoot);
      await fs.writeFile(
        path.join(publishedRoot, 'concurrent.txt'),
        'replacement',
        'utf8',
      );
      throw new Error('injected post-publish failure');
    };

    await expect(createWorkspace()).rejects.toMatchObject({ code: 'UNKNOWN' });
    await expect(
      fs.readFile(path.join(rootPath, 'concurrent.txt'), 'utf8'),
    ).resolves.toBe('replacement');
    await expect(
      fs.stat(path.join(displacedRoot, 'order-api', '.git')),
    ).resolves.toBeTruthy();
    expect(
      (await fs.readdir(path.join(root, 'features'))).filter((entry) =>
        entry.startsWith('.reqws-rollback-'),
      ),
    ).toEqual([]);
  });

  it('adds and logically removes a repository while preserving its local directory', async () => {
    const secondOrigin = await createLocalBareRepository(git);
    try {
      stateStore.state.repositories.push({
        id: 'repo-payment',
        name: 'payment-api',
        url: secondOrigin.originPath,
        defaultBranch: 'main',
        createdAt: '2026-08-12T00:00:00.000Z',
        updatedAt: '2026-08-12T00:00:00.000Z',
      });
      const created = await createWorkspace();

      const added = await service.addRepository({
        workspaceId: created.id,
        repositoryId: 'repo-payment',
      });
      expect(added.repositories.map((repository) => repository.name)).toEqual([
        'order-api',
        'payment-api',
      ]);

      const paymentPath = path.join(created.rootPath, 'payment-api');
      await expect(fs.stat(path.join(paymentPath, '.git'))).resolves.toBeTruthy();
      const removed = await service.removeRepository({
        workspaceId: created.id,
        catalogRepositoryId: 'repo-payment',
      });
      expect(removed.repositories.map((repository) => repository.name)).toEqual([
        'order-api',
      ]);
      await expect(fs.stat(path.join(paymentPath, '.git'))).resolves.toBeTruthy();
    } finally {
      await secondOrigin.cleanup();
    }
  });

  it('refuses to reuse a conflicting non-matching repository directory', async () => {
    const secondOrigin = await createLocalBareRepository(git);
    try {
      stateStore.state.repositories.push({
        id: 'repo-payment',
        name: 'payment-api',
        url: secondOrigin.originPath,
        defaultBranch: 'main',
        createdAt: '2026-08-12T00:00:00.000Z',
        updatedAt: '2026-08-12T00:00:00.000Z',
      });
      const created = await createWorkspace();
      await git.clone(origin.originPath, path.join(created.rootPath, 'payment-api'));

      await expect(
        service.addRepository({
          workspaceId: created.id,
          repositoryId: 'repo-payment',
        }),
      ).rejects.toMatchObject({ code: 'REPOSITORY_PATH_CONFLICT' });
      expect(stateStore.state.workspaces[0]?.status).toBe('error');
    } finally {
      await secondOrigin.cleanup();
    }
  });

  it('rejects an existing repository symlink that escapes the workspace root', async () => {
    const secondOrigin = await createLocalBareRepository(git);
    try {
      stateStore.state.repositories.push({
        id: 'repo-payment',
        name: 'payment-api',
        url: secondOrigin.originPath,
        defaultBranch: 'main',
        createdAt: '2026-08-12T00:00:00.000Z',
        updatedAt: '2026-08-12T00:00:00.000Z',
      });
      const created = await createWorkspace();
      const outside = path.join(root, 'outside-payment');
      await fs.mkdir(outside);
      await fs.writeFile(path.join(outside, 'sentinel.txt'), 'outside', 'utf8');
      await fs.symlink(outside, path.join(created.rootPath, 'payment-api'));

      await expect(
        service.addRepository({
          workspaceId: created.id,
          repositoryId: 'repo-payment',
        }),
      ).rejects.toMatchObject({ code: 'REPOSITORY_PATH_CONFLICT' });
      await expect(fs.readFile(path.join(outside, 'sentinel.txt'), 'utf8')).resolves
        .toBe('outside');
    } finally {
      await secondOrigin.cleanup();
    }
  });

  it('rejects a repository whose .git is a shared metadata symlink before Git runs', async () => {
    const secondOrigin = await createLocalBareRepository(git);
    try {
      stateStore.state.repositories.push({
        id: 'repo-payment',
        name: 'payment-api',
        url: secondOrigin.originPath,
        defaultBranch: 'main',
        createdAt: '2026-08-12T00:00:00.000Z',
        updatedAt: '2026-08-12T00:00:00.000Z',
      });
      const created = await createWorkspace();
      const target = path.join(created.rootPath, 'payment-api');
      await fs.mkdir(target);
      await fs.symlink(path.join(created.rootPath, 'order-api', '.git'), path.join(target, '.git'));
      const originCheck = vi.spyOn(git, 'originUrlMatches');
      const fetch = vi.spyOn(git, 'fetch');

      await expect(
        service.addRepository({
          workspaceId: created.id,
          repositoryId: 'repo-payment',
        }),
      ).rejects.toMatchObject({ code: 'REPOSITORY_PATH_CONFLICT' });
      expect(originCheck).not.toHaveBeenCalled();
      expect(fetch).not.toHaveBeenCalled();
    } finally {
      await secondOrigin.cleanup();
    }
  });

  it('rejects a repository gitfile pointing at shared metadata before Git runs', async () => {
    const secondOrigin = await createLocalBareRepository(git);
    try {
      stateStore.state.repositories.push({
        id: 'repo-payment',
        name: 'payment-api',
        url: secondOrigin.originPath,
        defaultBranch: 'main',
        createdAt: '2026-08-12T00:00:00.000Z',
        updatedAt: '2026-08-12T00:00:00.000Z',
      });
      const created = await createWorkspace();
      const target = path.join(created.rootPath, 'payment-api');
      await fs.mkdir(target);
      await fs.writeFile(
        path.join(target, '.git'),
        'gitdir: ../order-api/.git\n',
        'utf8',
      );
      const originCheck = vi.spyOn(git, 'originUrlMatches');
      const fetch = vi.spyOn(git, 'fetch');

      await expect(
        service.addRepository({
          workspaceId: created.id,
          repositoryId: 'repo-payment',
        }),
      ).rejects.toMatchObject({ code: 'REPOSITORY_PATH_CONFLICT' });
      expect(originCheck).not.toHaveBeenCalled();
      expect(fetch).not.toHaveBeenCalled();
    } finally {
      await secondOrigin.cleanup();
    }
  });

  it.each([
    'id',
    'rootPath',
    'workspaceFilePath',
    'name',
    'featureBranch',
    'createdAt',
  ] as const)('rejects a manifest whose %s is not bound to its summary', async (field) => {
    const created = await createWorkspace();
    const manifestFile = manifestPathFor(created.rootPath);
    const manifest = JSON.parse(
      await fs.readFile(manifestFile, 'utf8'),
    ) as WorkspaceManifest;
    Object.assign(manifest, { [field]: `${manifest[field]}-tampered` });
    await files.writeManifest(manifestFile, manifest);

    await expect(service.sync(created.id)).rejects.toMatchObject({
      code: 'MANIFEST_READ_FAILED',
    });
  });

  it('rejects unsafe manifest repository paths before workspace generation', async () => {
    const created = await createWorkspace();
    const manifestFile = manifestPathFor(created.rootPath);
    const manifest = JSON.parse(
      await fs.readFile(manifestFile, 'utf8'),
    ) as WorkspaceManifest;
    manifest.repositories[0]!.relativePath = '../outside';
    await files.writeManifest(manifestFile, manifest);

    await expect(service.sync(created.id)).rejects.toMatchObject({
      code: 'MANIFEST_READ_FAILED',
    });
  });

  it('rejects a manifest reached through a workspace-root symlink', async () => {
    const created = await createWorkspace();
    const relocatedRoot = path.join(root, 'relocated-workspace');
    await fs.rename(created.rootPath, relocatedRoot);
    await fs.symlink(relocatedRoot, created.rootPath);

    await expect(service.sync(created.id)).rejects.toMatchObject({
      code: 'MANIFEST_READ_FAILED',
    });
  });

  it('rejects a manifest reached through an escaping .reqws symlink', async () => {
    const created = await createWorkspace();
    const reqwsDirectory = path.join(created.rootPath, '.reqws');
    const relocatedReqws = path.join(root, 'outside-reqws');
    await fs.rename(reqwsDirectory, relocatedReqws);
    await fs.symlink(relocatedReqws, reqwsDirectory);

    await expect(service.sync(created.id)).rejects.toMatchObject({
      code: 'REPOSITORY_PATH_CONFLICT',
    });
  });

  it('rejects later writes after the workspace-file parent becomes a symlink', async () => {
    const created = await createWorkspace();
    const workspaceDirectory = path.dirname(created.workspaceFilePath);
    const originalWorkspaceDirectory = path.join(root, 'original-workspaces');
    const outside = path.join(root, 'outside-workspaces');
    await fs.rename(workspaceDirectory, originalWorkspaceDirectory);
    await fs.mkdir(outside);
    await fs.copyFile(
      path.join(originalWorkspaceDirectory, path.basename(created.workspaceFilePath)),
      path.join(outside, path.basename(created.workspaceFilePath)),
    );
    await fs.writeFile(path.join(outside, 'sentinel.txt'), 'unchanged', 'utf8');
    await fs.symlink(outside, workspaceDirectory);
    const writeWorkspace = vi.spyOn(files, 'writeCodeWorkspace');

    await expect(service.sync(created.id)).rejects.toMatchObject({
      code: 'WORKSPACE_FILE_WRITE_FAILED',
    });
    await expect(
      service.removeRepository({
        workspaceId: created.id,
        catalogRepositoryId: 'repo-order',
      }),
    ).rejects.toMatchObject({ code: 'WORKSPACE_FILE_WRITE_FAILED' });
    expect(writeWorkspace).not.toHaveBeenCalled();
    await expect(fs.readFile(path.join(outside, 'sentinel.txt'), 'utf8')).resolves
      .toBe('unchanged');
  });

  it('serializes concurrent mutations so repository additions do not lose updates', async () => {
    const origins = await Promise.all([
      createLocalBareRepository(git),
      createLocalBareRepository(git),
    ]);
    try {
      stateStore.state.repositories.push(
        {
          id: 'repo-payment',
          name: 'payment-api',
          url: origins[0]!.originPath,
          defaultBranch: 'main',
          createdAt: '2026-08-12T00:00:00.000Z',
          updatedAt: '2026-08-12T00:00:00.000Z',
        },
        {
          id: 'repo-customer',
          name: 'customer-api',
          url: origins[1]!.originPath,
          defaultBranch: 'main',
          createdAt: '2026-08-12T00:00:00.000Z',
          updatedAt: '2026-08-12T00:00:00.000Z',
        },
      );
      const created = await createWorkspace();

      await Promise.all([
        service.addRepository({
          workspaceId: created.id,
          repositoryId: 'repo-payment',
        }),
        service.addRepository({
          workspaceId: created.id,
          repositoryId: 'repo-customer',
        }),
      ]);

      const detail = await service.get(created.id);
      expect(detail.repositories.map((repository) => repository.name)).toEqual([
        'order-api',
        'payment-api',
        'customer-api',
      ]);
      expect(stateStore.state.workspaces[0]?.repositoryIds).toEqual([
        'repo-order',
        'repo-payment',
        'repo-customer',
      ]);
    } finally {
      await Promise.all(origins.map((fixture) => fixture.cleanup()));
    }
  });

  it('rejects a case-insensitive repository path collision before running Git', async () => {
    stateStore.state.repositories.push({
      id: 'repo-order-case-collision',
      name: 'Order-API',
      url: origin.originPath,
      defaultBranch: 'main',
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z',
    });
    const created = await createWorkspace();
    const clone = vi.spyOn(git, 'clone');

    await expect(
      service.addRepository({
        workspaceId: created.id,
        repositoryId: 'repo-order-case-collision',
      }),
    ).rejects.toMatchObject({ code: 'REPOSITORY_PATH_CONFLICT' });
    expect(clone).not.toHaveBeenCalled();
  });

  it('keeps missing indexed paths reserved until the user forgets the workspace', async () => {
    const created = await createWorkspace();
    await fs.rm(created.rootPath, { recursive: true });
    await fs.rm(created.workspaceFilePath);

    await expect(createWorkspace()).rejects.toMatchObject({
      code: 'WORKSPACE_ROOT_EXISTS',
      detail: expect.stringContaining('Forget workspace'),
    });

    await service.forget(created.id);
    const recreated = await createWorkspace();
    expect(recreated.id).not.toBe(created.id);
  });

  it('keeps a missing indexed workspace-file path reserved across root changes', async () => {
    const created = await createWorkspace();
    await fs.rm(created.workspaceFilePath);

    await expect(
      service.create({
        name: 'FEAT-123-refund',
        featureBranch: 'feature/FEAT-456',
        rootPath: path.join(root, 'features', 'FEAT-456-refund'),
        workspaceFileDirectory: path.join(root, 'workspaces'),
        repositoryIds: ['repo-order'],
      }),
    ).rejects.toMatchObject({
      code: 'WORKSPACE_FILE_EXISTS',
      detail: expect.stringContaining('Forget workspace'),
    });
  });

  it('marks missing paths on refresh and supports forgetting without disk deletion', async () => {
    const created = await createWorkspace();
    await fs.rm(created.workspaceFilePath);
    const [summary] = await service.list();
    expect(summary).toMatchObject({ status: 'missing' });
    expect(summary?.statusDetail).toContain('workspace 文件');
    const missingDetail = await service.get(created.id);
    expect(missingDetail).toMatchObject({
      status: 'missing',
      repositories: [{ name: 'order-api' }],
    });

    await service.forget(created.id);
    expect(stateStore.state.workspaces).toEqual([]);
    await expect(fs.stat(created.rootPath)).resolves.toBeTruthy();
  });
});
