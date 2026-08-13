import { randomUUID } from 'node:crypto';

import { ReqwsError } from '../../shared/errors';
import {
  isValidRepositoryName,
  isSafeRepositoryUrl,
  normalizeRepositoryName,
  repositoryNameKey,
} from '../../shared/repository-utils';
import {
  createRepositoryInputSchema,
  updateRepositoryInputSchema,
} from '../../shared/schemas';
import type {
  CreateRepositoryInput,
  RemoveRepositoryResult,
  Repository,
  RepositoryListItem,
  UpdateRepositoryInput,
  WorkspaceSummary,
} from '../../shared/types';
import { AppStateStore } from './app-state-store';

export interface RepositoryServiceOptions {
  now?: () => Date;
  createId?: () => string;
  findReferences?: (
    repository: Repository,
    workspaces: readonly WorkspaceSummary[],
  ) => readonly string[] | Promise<readonly string[]>;
}

function normalizedCreateInput(input: CreateRepositoryInput): CreateRepositoryInput {
  return {
    name: normalizeRepositoryName(input.name),
    url: input.url.normalize('NFC').trim(),
    defaultBranch: input.defaultBranch.normalize('NFC').trim(),
  };
}

function invalidInput(error: unknown): ReqwsError {
  return new ReqwsError({
    code: 'INVALID_INPUT',
    message: 'Repository input is invalid.',
    detail: error instanceof Error ? error.message : undefined,
  }, { cause: error });
}

function defaultReferences(
  repository: Repository,
  workspaces: readonly WorkspaceSummary[],
): string[] {
  const key = repositoryNameKey(repository.name);
  return workspaces
    .filter((workspace) => {
      const repositoryIds = (
        workspace as WorkspaceSummary & { repositoryIds?: string[] }
      ).repositoryIds;
      return repositoryIds
        ? repositoryIds.includes(repository.id)
        : workspace.repositoryNames.some(
            (name) => repositoryNameKey(name) === key,
          );
    })
    .map((workspace) => workspace.name);
}

export class RepositoryService {
  private readonly now: () => Date;
  private readonly createId: () => string;
  private readonly findReferences: NonNullable<
    RepositoryServiceOptions['findReferences']
  >;

  constructor(
    private readonly stateStore: AppStateStore,
    options: RepositoryServiceOptions = {},
  ) {
    this.now = options.now ?? (() => new Date());
    this.createId = options.createId ?? (() => `repo_${randomUUID()}`);
    this.findReferences = options.findReferences ?? defaultReferences;
  }

  async list(): Promise<RepositoryListItem[]> {
    const state = await this.stateStore.read();
    return Promise.all(
      state.repositories.map(async (repository) => {
        const referencedBy = [
          ...new Set(await this.findReferences(repository, state.workspaces)),
        ];
        return {
          ...structuredClone(repository),
          workspaceUsageCount: referencedBy.length,
          referencedBy,
        };
      }),
    );
  }

  async getById(id: string): Promise<Repository> {
    const normalizedId = id.trim();
    const repository = (await this.stateStore.read()).repositories.find(
      (entry) => entry.id === normalizedId,
    );
    if (!repository) {
      throw new ReqwsError({
        code: 'REPOSITORY_NOT_FOUND',
        message: 'Repository was not found.',
      });
    }
    return structuredClone(repository);
  }

  async create(input: CreateRepositoryInput): Promise<Repository> {
    const normalized = normalizedCreateInput(input);
    this.validateName(normalized.name);
    this.validateUrl(normalized.url);
    try {
      createRepositoryInputSchema.parse(normalized);
    } catch (error) {
      throw invalidInput(error);
    }

    const timestamp = this.now().toISOString();
    const repository: Repository = {
      id: this.createId(),
      ...normalized,
      createdAt: timestamp,
      updatedAt: timestamp,
    };

    await this.stateStore.update((state) => {
      this.assertUniqueName(state.repositories, repository.name);
      return {
        ...state,
        repositories: [...state.repositories, repository],
      };
    });
    return structuredClone(repository);
  }

  async update(input: UpdateRepositoryInput): Promise<Repository> {
    const normalized = {
      id: input.id.trim(),
      ...normalizedCreateInput(input),
    };
    this.validateName(normalized.name);
    this.validateUrl(normalized.url);
    try {
      updateRepositoryInputSchema.parse(normalized);
    } catch (error) {
      throw invalidInput(error);
    }

    let updated: Repository | undefined;
    await this.stateStore.update((state) => {
      const existing = state.repositories.find(
        (repository) => repository.id === normalized.id,
      );
      if (!existing) {
        throw new ReqwsError({
          code: 'REPOSITORY_NOT_FOUND',
          message: 'Repository was not found.',
        });
      }
      this.assertUniqueName(state.repositories, normalized.name, existing.id);
      updated = {
        ...existing,
        ...normalized,
        updatedAt: this.now().toISOString(),
      };
      return {
        ...state,
        repositories: state.repositories.map((repository) =>
          repository.id === existing.id ? updated as Repository : repository,
        ),
      };
    });
    return structuredClone(updated as Repository);
  }

  async remove(
    id: string,
    confirmReferenced = false,
  ): Promise<RemoveRepositoryResult> {
    const normalizedId = id.trim();
    let referencedBy: string[] = [];
    await this.stateStore.update(async (state) => {
      const repository = state.repositories.find(
        (entry) => entry.id === normalizedId,
      );
      if (!repository) {
        throw new ReqwsError({
          code: 'REPOSITORY_NOT_FOUND',
          message: 'Repository was not found.',
        });
      }

      referencedBy = [
        ...new Set(await this.findReferences(repository, state.workspaces)),
      ];
      if (referencedBy.length > 0 && !confirmReferenced) {
        throw new ReqwsError({
          code: 'REPOSITORY_IN_USE',
          message: 'Repository is referenced by one or more workspaces.',
          detail: referencedBy.join('\n'),
          repositoryName: repository.name,
        });
      }

      return {
        ...state,
        repositories: state.repositories.filter(
          (entry) => entry.id !== repository.id,
        ),
      };
    });

    return { removed: true, referencedBy };
  }

  private validateName(name: string): void {
    if (!isValidRepositoryName(name)) {
      throw new ReqwsError({
        code: 'INVALID_REPOSITORY_NAME',
        message: 'Repository name is invalid.',
      });
    }
  }

  private validateUrl(url: string): void {
    if (!isSafeRepositoryUrl(url)) {
      throw new ReqwsError({
        code: 'INVALID_INPUT',
        message: 'Repository URL must use credential-free HTTPS or SSH.',
      });
    }
  }

  private assertUniqueName(
    repositories: readonly Repository[],
    name: string,
    excludedId?: string,
  ): void {
    const key = repositoryNameKey(name);
    if (
      repositories.some(
        (repository) =>
          repository.id !== excludedId && repositoryNameKey(repository.name) === key,
      )
    ) {
      throw new ReqwsError({
        code: 'DUPLICATE_REPOSITORY_NAME',
        message: `A repository named "${name}" already exists.`,
        repositoryName: name,
      });
    }
  }
}
