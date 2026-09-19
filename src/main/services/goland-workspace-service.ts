import { randomUUID } from 'node:crypto';
import { constants, type Stats } from 'node:fs';
import { lstat, mkdir, open } from 'node:fs/promises';
import path from 'node:path';
import { ReqwsError } from '../../shared/errors';
import {
  GOLAND_PROJECT_FILE,
  GOLAND_PROJECT_MAX_BYTES,
  GOLAND_SHELL_SEGMENTS,
  goLandProjectSchema,
  prepareGoLandWorkspaceSchema,
  saveGoLandSelectionSchema,
  type GoLandProject,
  type GoLandSelection,
  type GoLandWorkspaceState,
  type PrepareGoLandWorkspaceInput,
  type SaveGoLandSelectionInput,
} from '../../shared/goland-workspace';
import type { WorkspaceDetail } from '../../shared/types';
import { writeJsonAtomically, writeJsonAtomicallyIfAbsent } from './atomic-json-store';
import { assertCanonicalParentPath, resolveProspectiveRealPath } from './path-service';
import type { WorkspaceMutationCoordinator } from './workspace-service';

interface DirectoryIdentity { path: string; dev: number; ino: number }
interface EntryContext {
  workspace: WorkspaceDetail;
  shellPath: string;
  identities: DirectoryIdentity[];
}

function hasCode(error: unknown, code: string): boolean {
  return error instanceof Error && 'code' in error && error.code === code;
}

function bindingError(): ReqwsError {
  return new ReqwsError({ code: 'GOLAND_BINDING_INVALID', message: 'GoLand entry binding is invalid or its directory changed.' });
}

function entryConflict(): ReqwsError {
  return new ReqwsError({ code: 'GOLAND_ENTRY_CONFLICT', message: 'The GoLand entry already exists without a valid binding for this workspace. Preserve it and resolve the conflict manually.' });
}

/** Desktop's sole binding writer; shares the workspace FIFO and update activity gate. */
export class GoLandWorkspaceService {
  constructor(
    private readonly workspaces: { get(id: string): Promise<WorkspaceDetail> },
    private readonly mutations: WorkspaceMutationCoordinator,
    private readonly writes = { replace: writeJsonAtomically, create: writeJsonAtomicallyIfAbsent },
  ) {}

  read(workspaceId: string): Promise<GoLandWorkspaceState> {
    return this.mutations.run(async () => {
      const context = await this.context(workspaceId);
      const exists = await this.inspectShell(context);
      return { shellPath: context.shellPath, project: exists ? (await this.readProject(context)).project : null };
    });
  }

  prepare(input: PrepareGoLandWorkspaceInput): Promise<GoLandWorkspaceState> {
    const parsed = prepareGoLandWorkspaceSchema.parse(input);
    return this.mutations.run(async () => {
      const context = await this.context(parsed.workspaceId);
      const exists = await this.inspectShell(context);
      if (exists) {
        const current = await this.readProject(context);
        // First-time input must never silently replace an already published choice.
        if (parsed.selection && JSON.stringify(parsed.selection) !== JSON.stringify(current.project.selection)) {
          throw this.selectionConflict();
        }
        return { project: current.project, shellPath: context.shellPath };
      }
      const selection = this.currentSelection(context.workspace, parsed.selection ?? { mode: 'all' });
      await this.verify(context);
      const ide = path.dirname(context.shellPath);
      try { await mkdir(ide, { mode: 0o700 }); } catch (error) {
        if (!hasCode(error, 'EEXIST')) throw error;
      }
      if (!context.identities.some((identity) => identity.path === ide)) {
        context.identities.push(await this.directory(ide));
      }
      await this.verify(context);
      try { await mkdir(context.shellPath, { mode: 0o700 }); } catch (error) {
        if (hasCode(error, 'EEXIST')) throw entryConflict();
        throw error;
      }
      context.identities.push(await this.directory(context.shellPath));
      const project: GoLandProject = {
        schemaVersion: 1,
        adapterProtocol: 1,
        workspaceId: context.workspace.id,
        bindingId: randomUUID(),
        revision: 1,
        selection,
        updatedAt: new Date().toISOString(),
      };
      await this.publish(context, project, true);
      return { shellPath: context.shellPath, project };
    });
  }

  save(input: SaveGoLandSelectionInput): Promise<GoLandWorkspaceState> {
    const parsed = saveGoLandSelectionSchema.parse(input);
    return this.mutations.run(async () => {
      const context = await this.context(parsed.workspaceId);
      if (!await this.inspectShell(context)) throw bindingError();
      const current = await this.readProject(context);
      if (current.project.bindingId !== parsed.expectedBindingId || current.project.revision !== parsed.expectedRevision) {
        throw this.selectionConflict();
      }
      if (current.project.revision === Number.MAX_SAFE_INTEGER) throw this.selectionConflict();
      const project: GoLandProject = {
        ...current.project,
        selection: this.currentSelection(context.workspace, parsed.selection),
        revision: current.project.revision + 1,
        updatedAt: new Date().toISOString(),
      };
      await this.publish(context, project, false, async () => {
        const latest = await this.readProject(context);
        if (latest.raw !== current.raw || latest.stat.ino !== current.stat.ino || latest.stat.dev !== current.stat.dev) {
          throw this.selectionConflict();
        }
      });
      return { shellPath: context.shellPath, project };
    });
  }

  private async context(workspaceId: string): Promise<EntryContext> {
    const workspace = await this.workspaces.get(workspaceId);
    if (workspace.status !== 'ready' || workspace.id !== workspaceId) {
      throw new ReqwsError({ code: 'WORKSPACE_PATH_MISSING', message: 'Workspace must be Ready before preparing GoLand.' });
    }
    const root = await this.directory(workspace.rootPath);
    const metadata = await this.directory(path.join(workspace.rootPath, '.reqws'));
    return { workspace, shellPath: path.join(workspace.rootPath, ...GOLAND_SHELL_SEGMENTS), identities: [root, metadata] };
  }

  private async inspectShell(context: EntryContext): Promise<boolean> {
    for (const directory of [path.dirname(context.shellPath), context.shellPath]) {
      try { context.identities.push(await this.directory(directory)); } catch (error) {
        if (hasCode(error, 'ENOENT')) return false;
        throw error;
      }
    }
    return true;
  }

  private async directory(directoryPath: string): Promise<DirectoryIdentity> {
    const stat = await lstat(directoryPath);
    if (!stat.isDirectory() || stat.isSymbolicLink()) throw bindingError();
    await assertCanonicalParentPath(directoryPath);
    if (await resolveProspectiveRealPath(directoryPath) !== path.resolve(directoryPath)) throw bindingError();
    return { path: directoryPath, dev: stat.dev, ino: stat.ino };
  }

  private async verify(context: EntryContext): Promise<void> {
    for (const previous of context.identities) {
      const current = await this.directory(previous.path);
      if (current.dev !== previous.dev || current.ino !== previous.ino) throw bindingError();
    }
  }

  private async readProject(context: EntryContext): Promise<{ project: GoLandProject; raw: string; stat: Stats }> {
    await this.verify(context);
    const filename = path.join(context.shellPath, GOLAND_PROJECT_FILE);
    try {
      const handle = await open(filename, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
      try {
        const before = await handle.stat();
        if (!before.isFile() || before.size > GOLAND_PROJECT_MAX_BYTES || before.nlink !== 1) throw bindingError();
        const bytes = Buffer.alloc(GOLAND_PROJECT_MAX_BYTES + 1);
        let length = 0;
        while (length < bytes.length) {
          const result = await handle.read(bytes, length, bytes.length - length, null);
          if (result.bytesRead === 0) break;
          length += result.bytesRead;
        }
        const stat = await handle.stat();
        const current = await lstat(filename);
        if (length > GOLAND_PROJECT_MAX_BYTES || current.isSymbolicLink() || current.dev !== stat.dev || current.ino !== stat.ino ||
          before.size !== stat.size || before.mtimeMs !== stat.mtimeMs || length !== stat.size) throw bindingError();
        const raw = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes.subarray(0, length));
        const project = goLandProjectSchema.parse(JSON.parse(raw) as unknown);
        if (project.workspaceId !== context.workspace.id) throw entryConflict();
        await this.verify(context);
        return { project, raw, stat };
      } finally { await handle.close(); }
    } catch (error) {
      if (error instanceof ReqwsError) throw error;
      if (hasCode(error, 'ENOENT')) throw entryConflict();
      throw bindingError();
    }
  }

  private currentSelection(workspace: WorkspaceDetail, selection: GoLandSelection): GoLandSelection {
    if (selection.mode === 'all') return selection;
    const members = new Set(workspace.repositories.map((repository) => repository.catalogRepositoryId));
    if (selection.repositoryIds.some((id) => !members.has(id))) {
      throw new ReqwsError({ code: 'INVALID_INPUT', message: 'Only current workspace members can be selected.' });
    }
    return selection;
  }

  private async publish(context: EntryContext, project: GoLandProject, create: boolean, assertCurrent?: () => Promise<void>): Promise<void> {
    const parsed = goLandProjectSchema.parse(project);
    if (Buffer.byteLength(JSON.stringify(parsed, null, 2) + '\n') > GOLAND_PROJECT_MAX_BYTES) throw bindingError();
    try {
      await (create ? this.writes.create : this.writes.replace)(path.join(context.shellPath, GOLAND_PROJECT_FILE), parsed, {
        assertDestination: async () => {
          await this.verify(context);
          await assertCurrent?.();
        },
      });
      await this.verify(context);
    } catch (error) {
      if (error instanceof ReqwsError) throw error;
      throw new ReqwsError({ code: 'GOLAND_WRITE_FAILED', message: 'Unable to save the GoLand loading selection.' }, { cause: error });
    }
  }

  private selectionConflict(): ReqwsError {
    return new ReqwsError({ code: 'GOLAND_SELECTION_CONFLICT', message: 'The GoLand selection changed. Reload it before saving your changes.' });
  }
}
