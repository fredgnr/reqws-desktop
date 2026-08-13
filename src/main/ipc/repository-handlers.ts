import { randomUUID } from 'node:crypto';
import type { IpcMainInvokeEvent } from 'electron';
import { z } from 'zod';

import { ReqwsError, serializeReqwsError } from '../../shared/errors';
import { IPC_CHANNELS, type IpcResult } from '../../shared/ipc-channels';
import {
  createRepositoryInputSchema,
  idSchema,
  testRepositoryInputSchema,
  updateRepositoryInputSchema,
} from '../../shared/schemas';
import type {
  OperationProgress,
  RemoveRepositoryResult,
  Repository,
  RepositoryListItem,
  TestRepositoryResult,
} from '../../shared/types';
import type { GitRunner } from '../services/git-runner';
import type { OperationProgressPort } from '../services/workspace-service';
import type { RepositoryService } from '../services/repository-service';
import { toIpcResult } from './ipc-result';

export type IpcInvokeHandler = (
  event: IpcMainInvokeEvent,
  ...args: unknown[]
) => Promise<IpcResult<unknown>>;

export type IpcHandlerMap = Record<string, IpcInvokeHandler>;

export interface RepositoryHandlerDependencies {
  repositoryService: Pick<
    RepositoryService,
    'list' | 'create' | 'update' | 'remove'
  >;
  git: Pick<GitRunner, 'lsRemote'> | null;
  gitUnavailableError: ReqwsError;
  createOperationReporter(event: IpcMainInvokeEvent): OperationProgressPort;
}

const listArgumentsSchema = z.tuple([]);
const createArgumentsSchema = z.tuple([createRepositoryInputSchema]);
const updateArgumentsSchema = z.tuple([updateRepositoryInputSchema]);
const removeArgumentsSchema = z.tuple([idSchema, z.boolean().optional()]);
const testArgumentsSchema = z.tuple([testRepositoryInputSchema]);

function parseDefaultBranch(stdout: string): string | undefined {
  const match = /^ref:\s+refs\/heads\/(.+?)\s+HEAD\s*$/mu.exec(stdout);
  return match?.[1];
}

function report(
  reporter: OperationProgressPort,
  progress: OperationProgress,
): void {
  reporter.report(progress);
}

async function testConnection(
  event: IpcMainInvokeEvent,
  args: unknown[],
  dependencies: RepositoryHandlerDependencies,
): Promise<TestRepositoryResult> {
  const [{ url }] = testArgumentsSchema.parse(args);
  const reporter = dependencies.createOperationReporter(event);
  const operationId = randomUUID();
  report(reporter, {
    operationId,
    kind: 'test-repository',
    stage: 'validating',
    current: 0,
    total: 1,
    message: '正在测试 repository 连接',
  });

  if (!dependencies.git) {
    const error = dependencies.gitUnavailableError.toPayload();
    report(reporter, {
      operationId,
      kind: 'test-repository',
      stage: 'error',
      current: 1,
      total: 1,
      message: error.message,
      error,
    });
    return { success: false, error };
  }

  try {
    const result = await dependencies.git.lsRemote(url);
    const response: TestRepositoryResult = {
      success: true,
      ...(parseDefaultBranch(result.stdout)
        ? { defaultBranch: parseDefaultBranch(result.stdout) }
        : {}),
    };
    report(reporter, {
      operationId,
      kind: 'test-repository',
      stage: 'done',
      current: 1,
      total: 1,
      message: 'Repository 连接成功',
    });
    return response;
  } catch (error) {
    const payload = serializeReqwsError(error);
    report(reporter, {
      operationId,
      kind: 'test-repository',
      stage: 'error',
      current: 1,
      total: 1,
      message: payload.message,
      error: payload,
    });
    return { success: false, detail: payload.detail, error: payload };
  }
}

export function createRepositoryHandlers(
  dependencies: RepositoryHandlerDependencies,
): IpcHandlerMap {
  return {
    [IPC_CHANNELS.repositories.list]: (_event, ...args) =>
      toIpcResult<RepositoryListItem[]>(() => {
        listArgumentsSchema.parse(args);
        return dependencies.repositoryService.list();
      }),
    [IPC_CHANNELS.repositories.create]: (_event, ...args) =>
      toIpcResult<Repository>(() => {
        const [input] = createArgumentsSchema.parse(args);
        return dependencies.repositoryService.create(input);
      }),
    [IPC_CHANNELS.repositories.update]: (_event, ...args) =>
      toIpcResult<Repository>(() => {
        const [input] = updateArgumentsSchema.parse(args);
        return dependencies.repositoryService.update(input);
      }),
    [IPC_CHANNELS.repositories.remove]: (_event, ...args) =>
      toIpcResult<RemoveRepositoryResult>(() => {
        const [id, confirmReferenced] = removeArgumentsSchema.parse(args);
        return dependencies.repositoryService.remove(id, confirmReferenced);
      }),
    [IPC_CHANNELS.repositories.test]: (event, ...args) =>
      toIpcResult<TestRepositoryResult>(() =>
        testConnection(event, args, dependencies),
      ),
  };
}

