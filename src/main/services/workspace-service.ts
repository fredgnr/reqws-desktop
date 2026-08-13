import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import {
  ReqwsError,
  toReqwsError,
  type ReqwsErrorPayload,
} from '../../shared/errors';
import {
  isValidRepositoryName,
  repositoryNameKey,
} from '../../shared/repository-utils';
import type {
  AddWorkspaceRepositoryInput,
  AppState,
  CreateWorkspaceInput,
  OperationProgress,
  RemoveWorkspaceRepositoryInput,
  Repository,
  WorkspaceDetail,
  WorkspaceManifest,
  WorkspaceRepository,
  WorkspaceStatus,
  WorkspaceSummary,
} from '../../shared/types';
import {
  addManifestRepository,
  removeManifestRepository,
  workspaceFileName,
  workspaceSlug,
} from '../../shared/workspace-utils';
import type { BranchService } from './branch-service';
import type { GitRunner } from './git-runner';
import {
  assertCanonicalParentPath,
  assertContainedPath,
  assertIndependentGitRepository,
  repositoryPath,
  resolveProspectiveRealPath,
  resolveRealParentPath,
} from './path-service';

export interface AppStatePort {
  read(): Promise<AppState>;
  update(
    mutator: (state: AppState) => AppState | Promise<AppState>,
  ): Promise<AppState>;
}

export interface WorkspaceFilePort {
  readManifest(manifestPath: string): Promise<WorkspaceManifest>;
  writeManifest(
    manifestPath: string,
    manifest: WorkspaceManifest,
  ): Promise<void>;
  writeCodeWorkspace(
    workspaceFilePath: string,
    rootPath: string,
    repositories: readonly WorkspaceRepository[],
    options?: { overwrite?: boolean },
  ): Promise<void>;
}

export interface OperationProgressPort {
  report(progress: OperationProgress): void;
}

const noProgress: OperationProgressPort = { report: () => undefined };

function manifestPathFor(rootPath: string): string {
  return path.join(rootPath, '.reqws', 'workspace.json');
}

async function pathExists(target: string): Promise<boolean> {
  try {
    await fs.access(target);
    return true;
  } catch {
    return false;
  }
}

function isNodeError(error: unknown, code: string): boolean {
  return (
    error instanceof Error &&
    'code' in error &&
    (error as NodeJS.ErrnoException).code === code
  );
}

function localPathIdentity(target: string): string {
  return path.resolve(target).normalize('NFC').toLocaleLowerCase('en-US');
}

async function pathEntryExists(target: string): Promise<boolean> {
  try {
    await fs.lstat(target);
    return true;
  } catch (error) {
    if (isNodeError(error, 'ENOENT')) return false;
    throw error;
  }
}

function assertAbsolutePath(target: string, label: string): void {
  if (!path.isAbsolute(target)) {
    throw new ReqwsError({
      code: 'INVALID_INPUT',
      message: `${label} must be an absolute path.`,
      stage: 'validating',
    });
  }
}

function snapshotRepository(repository: Repository): WorkspaceRepository {
  return {
    catalogRepositoryId: repository.id,
    name: repository.name,
    url: repository.url,
    defaultBranch: repository.defaultBranch,
    relativePath: repository.name,
  };
}

function summaryFromManifest(
  manifest: WorkspaceManifest,
  status: WorkspaceStatus = 'ready',
  lastError?: ReqwsErrorPayload,
): WorkspaceSummary {
  const summary: WorkspaceSummary = {
    id: manifest.id,
    name: manifest.name,
    featureBranch: manifest.featureBranch,
    rootPath: manifest.rootPath,
    workspaceFilePath: manifest.workspaceFilePath,
    repositoryNames: manifest.repositories.map((repository) => repository.name),
    repositoryIds: manifest.repositories.map(
      (repository) => repository.catalogRepositoryId,
    ),
    status,
    createdAt: manifest.createdAt,
    updatedAt: manifest.updatedAt,
  };
  if (lastError) summary.lastError = lastError;
  return summary;
}

/** A FIFO queue that can be shared by every WorkspaceService created by IPC. */
export class WorkspaceMutationCoordinator {
  private tail: Promise<void> = Promise.resolve();

  async run<T>(operation: () => Promise<T>): Promise<T> {
    let release: (() => void) | undefined;
    const previous = this.tail;
    this.tail = new Promise<void>((resolve) => {
      release = resolve;
    });
    await previous;
    try {
      return await operation();
    } finally {
      release?.();
    }
  }
}

function replaceSummary(
  state: AppState,
  summary: WorkspaceSummary,
): AppState {
  const exists = state.workspaces.some((workspace) => workspace.id === summary.id);
  return {
    ...state,
    workspaces: exists
      ? state.workspaces.map((workspace) =>
          workspace.id === summary.id ? summary : workspace,
        )
      : [...state.workspaces, summary],
  };
}

export class WorkspaceService {
  constructor(
    private readonly stateStore: AppStatePort,
    private readonly files: WorkspaceFilePort,
    private readonly git: GitRunner,
    private readonly branches: BranchService,
    private readonly progress: OperationProgressPort = noProgress,
    private readonly mutations: WorkspaceMutationCoordinator =
      new WorkspaceMutationCoordinator(),
  ) {}

  async list(): Promise<WorkspaceSummary[]> {
    const state = await this.stateStore.read();
    return Promise.all(
      state.workspaces.map((summary) => this.evaluateSummary(summary)),
    );
  }

  async get(id: string): Promise<WorkspaceDetail> {
    const state = await this.stateStore.read();
    const summary = state.workspaces.find((workspace) => workspace.id === id);
    if (!summary) throw this.notFound();
    return this.loadDetail(summary);
  }

  async create(input: CreateWorkspaceInput): Promise<WorkspaceDetail> {
    return this.mutations.run(() => this.createUnlocked(input));
  }

  private async createUnlocked(
    input: CreateWorkspaceInput,
  ): Promise<WorkspaceDetail> {
    const operationId = randomUUID();
    const total = input.repositoryIds.length + 4;
    let current = 0;
    let activeRepository: string | undefined;
    let stagingRoot: string | undefined;
    let publishedRootPath: string | undefined;
    let publishedWorkspaceFilePath: string | undefined;
    let workspaceId: string | undefined;

    const report = (
      stage: OperationProgress['stage'],
      message: string,
      error?: ReqwsErrorPayload,
    ): void => {
      this.progress.report({
        operationId,
        kind: 'create-workspace',
        stage,
        current,
        total,
        message,
        ...(activeRepository ? { repositoryName: activeRepository } : {}),
        ...(error ? { error } : {}),
      });
    };

    try {
      report('validating', '正在校验路径、分支和仓库');
      const {
        repositories,
        rootPath,
        workspaceFilePath,
      } = await this.validateCreate(input);
      await this.branches.validateBranch(input.featureBranch);
      current += 1;

      const parent = path.dirname(rootPath);
      stagingRoot = await fs.mkdtemp(
        path.join(parent, `.reqws-${workspaceSlug(input.name)}-`),
      );

      const snapshots: WorkspaceRepository[] = [];
      for (const repository of repositories) {
        activeRepository = repository.name;
        report('cloning', `正在 clone ${repository.name}`);
        const destination = await repositoryPath(stagingRoot, repository.name);
        await this.git.clone(repository.url, destination);
        await assertIndependentGitRepository(
          stagingRoot,
          destination,
          repository.name,
        );
        report('switching', `正在切换 ${repository.name} 的 feature 分支`);
        await this.branches.checkoutFeatureBranch(
          destination,
          repository.defaultBranch,
          input.featureBranch,
        );
        snapshots.push(snapshotRepository(repository));
        current += 1;
      }

      activeRepository = undefined;
      const now = new Date().toISOString();
      workspaceId = `ws_${randomUUID()}`;
      const manifest: WorkspaceManifest = {
        schemaVersion: 1,
        id: workspaceId,
        name: input.name.trim(),
        featureBranch: input.featureBranch.trim(),
        rootPath,
        workspaceFilePath,
        repositories: snapshots,
        createdAt: now,
        updatedAt: now,
      };

      report('writing', '正在写入 workspace manifest');
      await this.files.writeManifest(manifestPathFor(stagingRoot), manifest);
      current += 1;
      await this.publishStagingRoot(stagingRoot, manifest.rootPath);
      stagingRoot = undefined;
      publishedRootPath = manifest.rootPath;

      report('writing', '正在生成 VS Code workspace 文件');
      await this.assertWorkspaceFileDestination(manifest.workspaceFilePath);
      await this.files.writeCodeWorkspace(
        manifest.workspaceFilePath,
        manifest.rootPath,
        manifest.repositories,
        { overwrite: false },
      );
      publishedWorkspaceFilePath = manifest.workspaceFilePath;
      current += 1;

      report('writing', '正在更新 ReqWS 索引');
      await this.stateStore.update((state) =>
        replaceSummary(state, summaryFromManifest(manifest)),
      );
      current = total;
      report('done', 'Workspace 创建完成');
      return { ...manifest, status: 'ready' };
    } catch (error) {
      let normalized = this.withRepository(
        toReqwsError(error, {
          code: 'UNKNOWN',
          message: 'Workspace creation failed.',
        }),
        activeRepository,
      );
      if (publishedRootPath || publishedWorkspaceFilePath) {
        normalized = this.withRetainedArtifactDetail(normalized, [
          ...(publishedRootPath ? [publishedRootPath] : []),
          ...(publishedWorkspaceFilePath ? [publishedWorkspaceFilePath] : []),
        ]);
      }
      report(
        'rolling-back',
        publishedRootPath || publishedWorkspaceFilePath
          ? '创建失败，已保留发布工件供安全恢复'
          : '创建失败，正在清理未发布的 staging',
      );

      if (workspaceId) {
        await this.stateStore
          .update((state) => ({
            ...state,
            workspaces: state.workspaces.filter(
              (workspace) => workspace.id !== workspaceId,
            ),
          }))
          .catch(() => undefined);
      }
      if (stagingRoot) {
        await fs.rm(stagingRoot, { recursive: true, force: true }).catch(() => undefined);
      }
      report('error', normalized.message, normalized.toPayload());
      throw normalized;
    }
  }

  async addRepository(
    input: AddWorkspaceRepositoryInput,
  ): Promise<WorkspaceDetail> {
    return this.mutations.run(() => this.addRepositoryUnlocked(input));
  }

  private async addRepositoryUnlocked(
    input: AddWorkspaceRepositoryInput,
  ): Promise<WorkspaceDetail> {
    const operationId = randomUUID();
    const { summary, manifest, state } = await this.loadReadyWorkspace(
      input.workspaceId,
    );
    const repository = state.repositories.find(
      (candidate) => candidate.id === input.repositoryId,
    );
    if (!repository) {
      throw new ReqwsError({
        code: 'REPOSITORY_NOT_FOUND',
        message: 'Repository catalog item was not found.',
        stage: 'validating',
      });
    }
    if (
      manifest.repositories.some(
        (entry) => entry.catalogRepositoryId === repository.id,
      )
    ) {
      throw new ReqwsError({
        code: 'REPOSITORY_ALREADY_ADDED',
        message: `${repository.name} is already in this workspace.`,
        repositoryName: repository.name,
        stage: 'validating',
      });
    }
    const conflictingRepository = manifest.repositories.find(
      (entry) =>
        repositoryNameKey(entry.name) === repositoryNameKey(repository.name) ||
        repositoryNameKey(entry.relativePath) === repositoryNameKey(repository.name),
    );
    if (conflictingRepository) {
      throw new ReqwsError({
        code: 'REPOSITORY_PATH_CONFLICT',
        message: 'A repository with the same local path is already in this workspace.',
        detail: `Conflicts with ${conflictingRepository.name}.`,
        repositoryName: repository.name,
        stage: 'validating',
      });
    }

    const report = (
      stage: OperationProgress['stage'],
      message: string,
      current: number,
      error?: ReqwsErrorPayload,
    ): void =>
      this.progress.report({
        operationId,
        kind: 'add-repository',
        stage,
        repositoryName: repository.name,
        current,
        total: 4,
        message,
        ...(error ? { error } : {}),
      });

    let manifestWritten = false;
    let workspaceWritten = false;
    const target = await repositoryPath(manifest.rootPath, repository.name);

    try {
      report('validating', '正在检查 repository 目录', 0);
      if (await pathExists(target)) {
        await assertIndependentGitRepository(
          manifest.rootPath,
          target,
          repository.name,
        );
        if (!(await this.git.originUrlMatches(target, repository.url))) {
          throw new ReqwsError({
            code: 'REPOSITORY_PATH_CONFLICT',
            message: 'Repository path exists but its origin URL does not match.',
            repositoryName: repository.name,
            stage: 'validating',
          });
        }
      } else {
        report('cloning', `正在 clone ${repository.name}`, 1);
        await this.git.clone(repository.url, target);
        await assertIndependentGitRepository(
          manifest.rootPath,
          target,
          repository.name,
        );
      }

      report('switching', '正在切换 feature 分支', 2);
      await this.branches.checkoutFeatureBranch(
        target,
        repository.defaultBranch,
        manifest.featureBranch,
      );

      const updated = addManifestRepository(
        manifest,
        snapshotRepository(repository),
        new Date().toISOString(),
      );
      report('writing', '正在同步 manifest 和 workspace 文件', 3);
      await this.assertWorkspaceFileDestination(updated.workspaceFilePath);
      await this.files.writeManifest(manifestPathFor(manifest.rootPath), updated);
      manifestWritten = true;
      await this.assertWorkspaceFileDestination(updated.workspaceFilePath);
      await this.files.writeCodeWorkspace(
        updated.workspaceFilePath,
        updated.rootPath,
        updated.repositories,
      );
      workspaceWritten = true;
      await this.stateStore.update((currentState) =>
        replaceSummary(currentState, summaryFromManifest(updated)),
      );
      report('done', `${repository.name} 已加入 workspace`, 4);
      return { ...updated, status: 'ready' };
    } catch (error) {
      let normalized = this.withRepository(
        toReqwsError(error, {
          code: 'UNKNOWN',
          message: 'Adding repository failed.',
        }),
        repository.name,
      );
      const recoveryFailures = await this.rollbackWorkspaceFiles(
        manifest,
        manifestWritten,
        workspaceWritten,
      );
      if (!(await this.markWorkspaceError(summary, normalized.toPayload()))) {
        recoveryFailures.push('ReqWS could not persist the workspace Error status.');
      }
      normalized = this.withRecoveryFailureDetail(normalized, recoveryFailures);
      report('error', normalized.message, 4, normalized.toPayload());
      throw normalized;
    }
  }

  async removeRepository(
    input: RemoveWorkspaceRepositoryInput,
  ): Promise<WorkspaceDetail> {
    return this.mutations.run(() => this.removeRepositoryUnlocked(input));
  }

  private async removeRepositoryUnlocked(
    input: RemoveWorkspaceRepositoryInput,
  ): Promise<WorkspaceDetail> {
    const { summary, manifest } = await this.loadReadyWorkspace(input.workspaceId);
    const existing = manifest.repositories.find(
      (repository) =>
        repository.catalogRepositoryId === input.catalogRepositoryId,
    );
    if (!existing) {
      throw new ReqwsError({
        code: 'REPOSITORY_NOT_FOUND',
        message: 'Repository is not part of this workspace.',
        stage: 'validating',
      });
    }

    const updated = removeManifestRepository(
      manifest,
      input.catalogRepositoryId,
      new Date().toISOString(),
    );
    let manifestWritten = false;
    let workspaceWritten = false;
    try {
      await this.assertWorkspaceFileDestination(updated.workspaceFilePath);
      await this.files.writeManifest(manifestPathFor(manifest.rootPath), updated);
      manifestWritten = true;
      await this.assertWorkspaceFileDestination(updated.workspaceFilePath);
      await this.files.writeCodeWorkspace(
        updated.workspaceFilePath,
        updated.rootPath,
        updated.repositories,
      );
      workspaceWritten = true;
      await this.stateStore.update((state) =>
        replaceSummary(state, summaryFromManifest(updated)),
      );
      return { ...updated, status: 'ready' };
    } catch (error) {
      let normalized = this.withRepository(
        toReqwsError(error, {
          code: 'UNKNOWN',
          message: 'Removing repository failed.',
        }),
        existing.name,
      );
      const recoveryFailures = await this.rollbackWorkspaceFiles(
        manifest,
        manifestWritten,
        workspaceWritten,
      );
      if (!(await this.markWorkspaceError(summary, normalized.toPayload()))) {
        recoveryFailures.push('ReqWS could not persist the workspace Error status.');
      }
      normalized = this.withRecoveryFailureDetail(normalized, recoveryFailures);
      throw normalized;
    }
  }

  async sync(id: string): Promise<WorkspaceDetail> {
    return this.mutations.run(() => this.syncUnlocked(id));
  }

  private async syncUnlocked(id: string): Promise<WorkspaceDetail> {
    const state = await this.stateStore.read();
    const summary = state.workspaces.find((workspace) => workspace.id === id);
    if (!summary) throw this.notFound();
    let manifest: WorkspaceManifest;
    try {
      if (!(await pathExists(summary.rootPath))) {
        throw new ReqwsError({
          code: 'WORKSPACE_PATH_MISSING',
          message: 'Workspace root does not exist and cannot be synchronized.',
          stage: 'validating',
        });
      }
      manifest = await this.readBoundManifest(summary);
      await this.assertWorkspaceFileDestination(manifest.workspaceFilePath);
      await this.files.writeCodeWorkspace(
        manifest.workspaceFilePath,
        manifest.rootPath,
        manifest.repositories,
      );
      await this.stateStore.update((currentState) =>
        replaceSummary(currentState, summaryFromManifest(manifest)),
      );
      return { ...manifest, status: 'ready' };
    } catch (error) {
      let normalized = toReqwsError(error, {
        code: 'WORKSPACE_FILE_WRITE_FAILED',
        message: 'Workspace file synchronization failed.',
        stage: 'writing',
      });
      if (!(await this.markWorkspaceError(summary, normalized.toPayload()))) {
        normalized = this.withRecoveryFailureDetail(normalized, [
          'ReqWS could not persist the workspace Error status.',
        ]);
      }
      throw normalized;
    }
  }

  async forget(id: string): Promise<void> {
    return this.mutations.run(() => this.forgetUnlocked(id));
  }

  private async forgetUnlocked(id: string): Promise<void> {
    await this.stateStore.update((state) => {
      if (!state.workspaces.some((workspace) => workspace.id === id)) {
        throw this.notFound();
      }
      return {
        ...state,
        workspaces: state.workspaces.filter((workspace) => workspace.id !== id),
      };
    });
  }

  private async validateCreate(input: CreateWorkspaceInput): Promise<{
    repositories: Repository[];
    rootPath: string;
    workspaceFilePath: string;
  }> {
    assertAbsolutePath(input.rootPath, 'Workspace root');
    assertAbsolutePath(input.workspaceFileDirectory, 'Workspace file directory');
    if (new Set(input.repositoryIds).size !== input.repositoryIds.length) {
      throw new ReqwsError({
        code: 'INVALID_INPUT',
        message: 'A repository can only be selected once.',
        stage: 'validating',
      });
    }
    if (await pathEntryExists(input.rootPath)) {
      throw new ReqwsError({
        code: 'WORKSPACE_ROOT_EXISTS',
        message: 'Workspace root already exists.',
        stage: 'validating',
      });
    }
    const rootPath = await resolveRealParentPath(input.rootPath);
    if (await pathEntryExists(rootPath)) {
      throw new ReqwsError({
        code: 'WORKSPACE_ROOT_EXISTS',
        message: 'Workspace root already exists.',
        stage: 'validating',
      });
    }
    const rootParent = await fs.stat(path.dirname(rootPath)).catch(() => null);
    if (!rootParent?.isDirectory()) {
      throw new ReqwsError({
        code: 'INVALID_INPUT',
        message: 'Workspace root parent directory does not exist.',
        stage: 'validating',
      });
    }
    const workspaceFileDirectory = await resolveProspectiveRealPath(
      input.workspaceFileDirectory,
    );
    const fileDirectory = await fs.stat(workspaceFileDirectory).catch(() => null);
    if (!fileDirectory?.isDirectory()) {
      throw new ReqwsError({
        code: 'INVALID_INPUT',
        message: 'Workspace file directory does not exist.',
        stage: 'validating',
      });
    }
    const workspaceFilePath = path.join(
      workspaceFileDirectory,
      workspaceFileName(input.name),
    );
    if (await pathEntryExists(workspaceFilePath)) {
      throw new ReqwsError({
        code: 'WORKSPACE_FILE_EXISTS',
        message: 'Workspace file already exists.',
        stage: 'validating',
      });
    }

    const state = await this.stateStore.read();
    const indexedRoot = state.workspaces.find(
      (workspace) =>
        localPathIdentity(workspace.rootPath) === localPathIdentity(rootPath),
    );
    if (indexedRoot) {
      throw new ReqwsError({
        code: 'WORKSPACE_ROOT_EXISTS',
        message: 'Workspace root is still reserved by the ReqWS index.',
        detail: `Forget workspace "${indexedRoot.name}" before reusing this path.`,
        stage: 'validating',
      });
    }
    const indexedWorkspaceFile = state.workspaces.find(
      (workspace) =>
        localPathIdentity(workspace.workspaceFilePath) ===
        localPathIdentity(workspaceFilePath),
    );
    if (indexedWorkspaceFile) {
      throw new ReqwsError({
        code: 'WORKSPACE_FILE_EXISTS',
        message: 'Workspace file path is still reserved by the ReqWS index.',
        detail: `Forget workspace "${indexedWorkspaceFile.name}" before reusing this path.`,
        stage: 'validating',
      });
    }
    const byId = new Map(state.repositories.map((repository) => [repository.id, repository]));
    const repositories = input.repositoryIds.map((id) => byId.get(id));
    if (repositories.some((repository) => repository === undefined)) {
      throw new ReqwsError({
        code: 'REPOSITORY_NOT_FOUND',
        message: 'One or more selected repositories no longer exist.',
        stage: 'validating',
      });
    }
    for (const repository of repositories) {
      if (!repository || !isValidRepositoryName(repository.name)) {
        throw new ReqwsError({
          code: 'INVALID_REPOSITORY_NAME',
          message: 'Repository name is unsafe for a local directory.',
          repositoryName: repository?.name,
          stage: 'validating',
        });
      }
    }
    return {
      repositories: repositories as Repository[],
      rootPath,
      workspaceFilePath,
    };
  }

  private async publishStagingRoot(
    stagingRoot: string,
    rootPath: string,
  ): Promise<void> {
    const canonicalRoot = await resolveRealParentPath(rootPath);
    if (canonicalRoot !== rootPath || await pathEntryExists(rootPath)) {
      throw new ReqwsError({
        code: 'WORKSPACE_ROOT_EXISTS',
        message: 'Workspace root was created by another operation.',
        stage: 'writing',
      });
    }
    try {
      // A sibling rename makes the complete, frozen staging tree visible in
      // one filesystem operation. Node/macOS exposes no RENAME_NOREPLACE for
      // directories; the preflight rejects existing targets and the OS also
      // refuses non-empty destinations created before this rename executes.
      await fs.rename(stagingRoot, rootPath);
    } catch (error) {
      if (
        isNodeError(error, 'EEXIST') ||
        isNodeError(error, 'ENOTEMPTY') ||
        isNodeError(error, 'EISDIR')
      ) {
        throw new ReqwsError({
          code: 'WORKSPACE_ROOT_EXISTS',
          message: 'Workspace root was created by another operation.',
          stage: 'writing',
        }, { cause: error });
      }
      throw error;
    }
  }

  private async assertWorkspaceFileDestination(
    workspaceFilePath: string,
  ): Promise<string> {
    try {
      return await assertCanonicalParentPath(
        workspaceFilePath,
        'Workspace file path',
      );
    } catch (error) {
      if (error instanceof ReqwsError && error.code === 'WORKSPACE_FILE_WRITE_FAILED') {
        throw error;
      }
      throw new ReqwsError({
        code: 'WORKSPACE_FILE_WRITE_FAILED',
        message: 'Workspace file parent path is no longer canonical.',
        detail: error instanceof Error ? error.message : undefined,
        stage: 'validating',
      }, { cause: error });
    }
  }

  private async readBoundManifest(
    summary: WorkspaceSummary,
  ): Promise<WorkspaceManifest> {
    assertAbsolutePath(summary.rootPath, 'Workspace root');
    assertAbsolutePath(summary.workspaceFilePath, 'Workspace file path');
    await this.assertWorkspaceFileDestination(summary.workspaceFilePath);
    const rootStat = await fs.lstat(summary.rootPath);
    const realRoot = await resolveProspectiveRealPath(summary.rootPath);
    if (
      !rootStat.isDirectory() ||
      rootStat.isSymbolicLink() ||
      realRoot !== path.resolve(summary.rootPath)
    ) {
      throw new ReqwsError({
        code: 'MANIFEST_READ_FAILED',
        message: 'Workspace root identity is unsafe.',
        stage: 'validating',
      });
    }
    const manifestPath = await assertContainedPath(
      summary.rootPath,
      manifestPathFor(summary.rootPath),
    );
    const manifest = await this.files.readManifest(
      manifestPath,
    );
    await this.assertManifestMatchesSummary(summary, manifest);
    return manifest;
  }

  private async assertManifestMatchesSummary(
    summary: WorkspaceSummary,
    manifest: WorkspaceManifest,
  ): Promise<void> {
    const boundFields = [
      'id',
      'rootPath',
      'workspaceFilePath',
      'name',
      'featureBranch',
      'createdAt',
    ] as const;
    const mismatchedField = boundFields.find(
      (field) => manifest[field] !== summary[field],
    );
    if (mismatchedField) {
      throw new ReqwsError({
        code: 'MANIFEST_READ_FAILED',
        message: 'Workspace manifest does not match the ReqWS index.',
        detail: `Mismatched field: ${mismatchedField}`,
        stage: 'validating',
      });
    }

    const seenPaths = new Set<string>();
    const seenCatalogIds = new Set<string>();
    for (const repository of manifest.repositories) {
      if (
        !isValidRepositoryName(repository.name) ||
        repository.relativePath !== repository.name
      ) {
        throw new ReqwsError({
          code: 'MANIFEST_READ_FAILED',
          message: 'Workspace manifest contains an unsafe repository path.',
          repositoryName: repository.name,
          stage: 'validating',
        });
      }
      const resolvedPath = await repositoryPath(
        summary.rootPath,
        repository.relativePath,
      );
      const pathKey = repositoryNameKey(repository.relativePath);
      if (seenPaths.has(pathKey)) {
        throw new ReqwsError({
          code: 'MANIFEST_READ_FAILED',
          message: 'Workspace manifest contains duplicate repository paths.',
          repositoryName: repository.name,
          stage: 'validating',
        });
      }
      seenPaths.add(pathKey);
      if (seenCatalogIds.has(repository.catalogRepositoryId)) {
        throw new ReqwsError({
          code: 'MANIFEST_READ_FAILED',
          message: 'Workspace manifest contains duplicate repository IDs.',
          repositoryName: repository.name,
          stage: 'validating',
        });
      }
      seenCatalogIds.add(repository.catalogRepositoryId);
      // Keep the containment resolution above even though the case-folded name
      // is the uniqueness key on default macOS filesystems.
      void resolvedPath;
    }
  }

  private async evaluateSummary(
    summary: WorkspaceSummary,
  ): Promise<WorkspaceSummary> {
    const missing: string[] = [];
    if (!(await pathExists(summary.rootPath))) missing.push('代码目录');
    if (!(await pathExists(manifestPathFor(summary.rootPath)))) missing.push('manifest');
    if (!(await pathExists(summary.workspaceFilePath))) missing.push('workspace 文件');
    if (missing.length > 0) {
      return {
        ...summary,
        status: 'missing',
        statusDetail: `缺失：${missing.join('、')}`,
      };
    }
    if (summary.status === 'error') return summary;
    const ready: WorkspaceSummary = { ...summary, status: 'ready' };
    delete ready.statusDetail;
    delete ready.lastError;
    return ready;
  }

  private async loadDetail(summary: WorkspaceSummary): Promise<WorkspaceDetail> {
    const evaluated = await this.evaluateSummary(summary);
    if (evaluated.status === 'missing') {
      const manifestFile = manifestPathFor(summary.rootPath);
      if (await pathExists(manifestFile)) {
        const manifest = await this.readBoundManifest(summary);
        return {
          ...manifest,
          status: 'missing',
          statusDetail:
            evaluated.statusDetail ?? 'Workspace paths are missing.',
        };
      }
      return {
        schemaVersion: 1,
        id: summary.id,
        name: summary.name,
        featureBranch: summary.featureBranch,
        rootPath: summary.rootPath,
        workspaceFilePath: summary.workspaceFilePath,
        repositories: [],
        createdAt: summary.createdAt,
        updatedAt: summary.updatedAt,
        status: 'missing',
        statusDetail:
          evaluated.statusDetail ?? 'Workspace paths are missing.',
      };
    }
    try {
      const manifest = await this.readBoundManifest(summary);
      return {
        ...manifest,
        status: evaluated.status,
        ...(evaluated.statusDetail
          ? { statusDetail: evaluated.statusDetail }
          : {}),
        ...(evaluated.lastError ? { lastError: evaluated.lastError } : {}),
      };
    } catch (error) {
      throw toReqwsError(error, {
        code: 'MANIFEST_READ_FAILED',
        message: 'Workspace manifest could not be read.',
      });
    }
  }

  private async loadReadyWorkspace(id: string): Promise<{
    state: AppState;
    summary: WorkspaceSummary;
    manifest: WorkspaceManifest;
  }> {
    const state = await this.stateStore.read();
    const summary = state.workspaces.find((workspace) => workspace.id === id);
    if (!summary) throw this.notFound();
    const detail = await this.loadDetail(summary);
    if (detail.status !== 'ready') {
      throw new ReqwsError({
        code: 'WORKSPACE_PATH_MISSING',
        message: 'Workspace must be Ready before repositories can be changed.',
        stage: 'validating',
      });
    }
    const manifest: WorkspaceManifest = {
      schemaVersion: detail.schemaVersion,
      id: detail.id,
      name: detail.name,
      featureBranch: detail.featureBranch,
      rootPath: detail.rootPath,
      workspaceFilePath: detail.workspaceFilePath,
      repositories: detail.repositories,
      createdAt: detail.createdAt,
      updatedAt: detail.updatedAt,
    };
    return { state, summary, manifest };
  }

  private async rollbackWorkspaceFiles(
    original: WorkspaceManifest,
    manifestWritten: boolean,
    workspaceWritten: boolean,
  ): Promise<string[]> {
    const failures: string[] = [];
    if (manifestWritten) {
      try {
        await this.files.writeManifest(manifestPathFor(original.rootPath), original);
      } catch {
        failures.push('Manifest rollback failed; run workspace Sync before continuing.');
      }
    }
    if (manifestWritten || workspaceWritten) {
      try {
        await this.assertWorkspaceFileDestination(original.workspaceFilePath);
        await this.files.writeCodeWorkspace(
          original.workspaceFilePath,
          original.rootPath,
          original.repositories,
        );
      } catch {
        failures.push('Workspace-file rollback failed; run workspace Sync before continuing.');
      }
    }
    return failures;
  }

  private async markWorkspaceError(
    summary: WorkspaceSummary,
    error: ReqwsErrorPayload,
  ): Promise<boolean> {
    try {
      await this.stateStore.update((state) =>
        replaceSummary(state, {
          ...summary,
          status: 'error',
          statusDetail: error.message,
          lastError: error,
          updatedAt: new Date().toISOString(),
        }),
      );
      return true;
    } catch {
      return false;
    }
  }

  private withRepository(error: ReqwsError, name?: string): ReqwsError {
    if (!name || error.repositoryName) return error;
    return new ReqwsError(
      { ...error.toPayload(), repositoryName: name },
      { cause: error },
    );
  }

  private withRetainedArtifactDetail(
    error: ReqwsError,
    artifactPaths: readonly string[],
  ): ReqwsError {
    const recoveryDetail =
      `Published artifacts were retained for safe recovery: ${artifactPaths.join(', ')}`;
    return new ReqwsError(
      {
        ...error.toPayload(),
        detail: error.detail
          ? `${error.detail}\n${recoveryDetail}`
          : recoveryDetail,
      },
      { cause: error },
    );
  }

  private withRecoveryFailureDetail(
    error: ReqwsError,
    failures: readonly string[],
  ): ReqwsError {
    if (failures.length === 0) return error;
    const recoveryDetail = `Recovery warnings:\n${failures.join('\n')}`;
    return new ReqwsError(
      {
        ...error.toPayload(),
        detail: error.detail
          ? `${error.detail}\n${recoveryDetail}`
          : recoveryDetail,
      },
      { cause: error },
    );
  }

  private notFound(): ReqwsError {
    return new ReqwsError({
      code: 'WORKSPACE_NOT_FOUND',
      message: 'Workspace was not found in the ReqWS index.',
    });
  }
}

export { manifestPathFor };
