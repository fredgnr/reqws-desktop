import path from 'node:path';

import { ReqwsError } from '../../shared/errors';
import {
  workspaceManifestSchema,
  workspaceRepositorySchema,
} from '../../shared/schemas';
import type {
  WorkspaceManifest,
  WorkspaceRepository,
} from '../../shared/types';
import { buildCodeWorkspace } from '../../shared/workspace-utils';
import {
  AtomicJsonStore,
  writeJsonAtomically,
  writeJsonAtomicallyIfAbsent,
} from './atomic-json-store';
import { assertCanonicalParentPath } from './path-service';

export interface CodeWorkspaceWriteOptions {
  /** Creation uses false so a concurrent user-created file is never replaced. */
  overwrite?: boolean;
}

export const WORKSPACE_MANIFEST_RELATIVE_PATH = path.join(
  '.reqws',
  'workspace.json',
);

function detailFrom(error: unknown): string | undefined {
  return error instanceof Error ? error.message : undefined;
}

function hasCode(error: unknown, code: string): boolean {
  return error instanceof Error && 'code' in error &&
    (error as NodeJS.ErrnoException).code === code;
}

function assertAbsolute(inputPath: string, description: string): string {
  if (!path.isAbsolute(inputPath)) {
    throw new ReqwsError({
      code: 'INVALID_INPUT',
      message: `${description} must be an absolute path.`,
    });
  }
  return path.resolve(inputPath);
}

function parseManifest(value: unknown): WorkspaceManifest {
  const manifest = workspaceManifestSchema.parse(value);
  assertAbsolute(manifest.rootPath, 'Workspace root');
  assertAbsolute(manifest.workspaceFilePath, 'Workspace file path');
  return manifest;
}

export function workspaceManifestPath(rootPath: string): string {
  return path.join(assertAbsolute(rootPath, 'Workspace root'), WORKSPACE_MANIFEST_RELATIVE_PATH);
}

export class WorkspaceFileWriter {
  async readManifest(manifestPath: string): Promise<WorkspaceManifest> {
    const targetPath = assertAbsolute(manifestPath, 'Manifest path');
    const store = new AtomicJsonStore<WorkspaceManifest>(targetPath, {
      parse: parseManifest,
      readErrorCode: 'MANIFEST_READ_FAILED',
      corruptErrorCode: 'MANIFEST_READ_FAILED',
      writeErrorCode: 'MANIFEST_WRITE_FAILED',
    });
    try {
      return await store.read();
    } catch (error) {
      if (error instanceof ReqwsError) throw error;
      throw new ReqwsError({
        code: 'MANIFEST_READ_FAILED',
        message: 'Unable to read workspace manifest.',
        detail: detailFrom(error),
      }, { cause: error });
    }
  }

  async writeManifest(
    manifestPath: string,
    manifest: WorkspaceManifest,
  ): Promise<void> {
    let targetPath: string;
    let parsed: WorkspaceManifest;
    try {
      targetPath = assertAbsolute(manifestPath, 'Manifest path');
      await assertCanonicalParentPath(targetPath, 'Manifest path');
      parsed = parseManifest(manifest);
    } catch (error) {
      if (error instanceof ReqwsError && error.code === 'INVALID_INPUT') {
        throw new ReqwsError({
          code: 'MANIFEST_WRITE_FAILED',
          message: 'Workspace manifest is invalid.',
          detail: error.message,
        }, { cause: error });
      }
      throw new ReqwsError({
        code: 'MANIFEST_WRITE_FAILED',
        message: 'Workspace manifest is invalid.',
        detail: detailFrom(error),
      }, { cause: error });
    }

    try {
      await assertCanonicalParentPath(targetPath, 'Manifest path');
      await writeJsonAtomically(targetPath, parsed);
    } catch (error) {
      throw new ReqwsError({
        code: 'MANIFEST_WRITE_FAILED',
        message: 'Unable to write workspace manifest.',
        detail: detailFrom(error),
      }, { cause: error });
    }
  }

  async writeCodeWorkspace(
    workspaceFilePath: string,
    rootPath: string,
    repositories: readonly WorkspaceRepository[],
    options: CodeWorkspaceWriteOptions = {},
  ): Promise<void> {
    let targetPath: string;
    let workspaceRoot: string;
    let parsedRepositories: WorkspaceRepository[];
    try {
      targetPath = assertAbsolute(workspaceFilePath, 'Workspace file path');
      await assertCanonicalParentPath(targetPath, 'Workspace file path');
      workspaceRoot = assertAbsolute(rootPath, 'Workspace root');
      parsedRepositories = repositories.map((repository) =>
        workspaceRepositorySchema.parse(repository),
      );
    } catch (error) {
      throw new ReqwsError({
        code: 'WORKSPACE_FILE_WRITE_FAILED',
        message: 'Workspace file input is invalid.',
        detail: detailFrom(error),
      }, { cause: error });
    }

    const workspace = buildCodeWorkspace(workspaceRoot, parsedRepositories);
    if (workspace.folders.some((folder) => !path.isAbsolute(folder.path))) {
      throw new ReqwsError({
        code: 'WORKSPACE_FILE_WRITE_FAILED',
        message: 'Workspace folder paths must be absolute.',
      });
    }

    try {
      await assertCanonicalParentPath(targetPath, 'Workspace file path');
      if (options.overwrite === false) {
        await writeJsonAtomicallyIfAbsent(targetPath, workspace);
      } else {
        await writeJsonAtomically(targetPath, workspace);
      }
    } catch (error) {
      if (options.overwrite === false && hasCode(error, 'EEXIST')) {
        throw new ReqwsError({
          code: 'WORKSPACE_FILE_EXISTS',
          message: 'Workspace file already exists and will not be overwritten.',
          stage: 'writing',
        }, { cause: error });
      }
      throw new ReqwsError({
        code: 'WORKSPACE_FILE_WRITE_FAILED',
        message: 'Unable to write workspace file.',
        detail: detailFrom(error),
      }, { cause: error });
    }
  }
}
