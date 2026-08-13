import type { ReqwsErrorPayload } from './errors';

export interface Repository {
  id: string;
  name: string;
  url: string;
  defaultBranch: string;
  createdAt: string;
  updatedAt: string;
}

export interface RepositoryListItem extends Repository {
  workspaceUsageCount: number;
  referencedBy: string[];
}

export interface CreateRepositoryInput {
  name: string;
  url: string;
  defaultBranch: string;
}

export interface UpdateRepositoryInput extends CreateRepositoryInput {
  id: string;
}

export interface RemoveRepositoryResult {
  removed: boolean;
  referencedBy: string[];
}

export interface TestRepositoryInput {
  url: string;
}

export interface TestRepositoryResult {
  success: boolean;
  defaultBranch?: string;
  detail?: string;
  error?: ReqwsErrorPayload;
}

export interface WorkspaceRepository {
  catalogRepositoryId: string;
  name: string;
  url: string;
  defaultBranch: string;
  relativePath: string;
}

export type WorkspaceStatus = 'ready' | 'missing' | 'error';

export interface WorkspaceSummary {
  id: string;
  name: string;
  featureBranch: string;
  rootPath: string;
  workspaceFilePath: string;
  repositoryNames: string[];
  /** Stable catalog references. Optional only for state.v1 files written by older builds. */
  repositoryIds?: string[];
  status: WorkspaceStatus;
  statusDetail?: string;
  lastError?: ReqwsErrorPayload;
  createdAt: string;
  updatedAt: string;
}

export interface WorkspaceManifest {
  schemaVersion: 1;
  id: string;
  name: string;
  featureBranch: string;
  rootPath: string;
  workspaceFilePath: string;
  repositories: WorkspaceRepository[];
  createdAt: string;
  updatedAt: string;
}

export interface WorkspaceDetail extends WorkspaceManifest {
  status: WorkspaceStatus;
  statusDetail?: string;
  lastError?: ReqwsErrorPayload;
}

export interface CreateWorkspaceInput {
  name: string;
  featureBranch: string;
  rootPath: string;
  workspaceFileDirectory: string;
  repositoryIds: string[];
}

export interface AddWorkspaceRepositoryInput {
  workspaceId: string;
  repositoryId: string;
}

export interface RemoveWorkspaceRepositoryInput {
  workspaceId: string;
  catalogRepositoryId: string;
}

export interface SelectDirectoryInput {
  title: string;
  defaultPath?: string;
  createDirectory?: boolean;
}

export interface AvailabilityItem {
  available: boolean;
  path?: string;
  reason?: string;
  reasonCode?: 'NOT_FOUND';
}

export interface SystemAvailability {
  git: AvailabilityItem;
  vscode: AvailabilityItem;
  cursor: AvailabilityItem;
}

export type EditorAvailability = SystemAvailability;

export type OperationKind =
  | 'create-workspace'
  | 'add-repository'
  | 'remove-repository'
  | 'test-repository'
  | 'sync-workspace';

export type OperationStage =
  | 'validating'
  | 'cloning'
  | 'fetching'
  | 'switching'
  | 'writing'
  | 'rolling-back'
  | 'done'
  | 'error';

export interface OperationProgress {
  operationId: string;
  kind: OperationKind;
  stage: OperationStage;
  repositoryName?: string;
  current: number;
  total: number;
  message: string;
  error?: ReqwsErrorPayload;
}

export type SupportedLocale = 'zh-CN' | 'en-US';

export type LocalePreference = 'system' | SupportedLocale;

export interface GlobalSettings {
  localePreference: LocalePreference;
  workspaceParentDirectory: string | null;
  workspaceFileDirectory: string | null;
}

export type GlobalDirectorySetting =
  | 'workspaceParentDirectory'
  | 'workspaceFileDirectory';

export interface ResolvedGlobalSettings extends GlobalSettings {
  effectiveLocale: SupportedLocale;
  invalidDirectoryFields?: GlobalDirectorySetting[];
}

export const DEFAULT_GLOBAL_SETTINGS: Readonly<GlobalSettings> = {
  localePreference: 'system',
  workspaceParentDirectory: null,
  workspaceFileDirectory: null,
};

/**
 * The state reader accepts this loose shape so older state.v1 files remain
 * loadable. AppStateStore normalizes it to GlobalSettings at runtime.
 */
export interface AppSettings extends Partial<GlobalSettings> {
  lastWorkspaceParentDirectory?: string;
  lastWorkspaceFileDirectory?: string;
}

export interface AppState {
  schemaVersion: 1;
  settings: AppSettings;
  repositories: Repository[];
  workspaces: WorkspaceSummary[];
}

export interface CodeWorkspaceFile {
  folders: Array<{ name: string; path: string }>;
  extensions: { recommendations: string[] };
}

export interface ReqwsAPI {
  repositories: {
    list(): Promise<RepositoryListItem[]>;
    create(input: CreateRepositoryInput): Promise<Repository>;
    update(input: UpdateRepositoryInput): Promise<Repository>;
    remove(id: string, confirmReferenced?: boolean): Promise<RemoveRepositoryResult>;
    testConnection(input: TestRepositoryInput): Promise<TestRepositoryResult>;
  };
  workspaces: {
    list(): Promise<WorkspaceSummary[]>;
    get(id: string): Promise<WorkspaceDetail>;
    create(input: CreateWorkspaceInput): Promise<WorkspaceDetail>;
    addRepository(input: AddWorkspaceRepositoryInput): Promise<WorkspaceDetail>;
    removeRepository(input: RemoveWorkspaceRepositoryInput): Promise<WorkspaceDetail>;
    sync(id: string): Promise<WorkspaceDetail>;
    forget(id: string): Promise<void>;
  };
  settings: {
    get(): Promise<ResolvedGlobalSettings>;
    save(settings: GlobalSettings): Promise<ResolvedGlobalSettings>;
  };
  dialogs: {
    selectDirectory(input: SelectDirectoryInput): Promise<string | null>;
  };
  editors: {
    getAvailability(): Promise<SystemAvailability>;
    openVSCode(workspaceId: string): Promise<void>;
    openCursor(workspaceId: string): Promise<void>;
    openCursorRoot(workspaceId: string): Promise<void>;
    revealInFinder(workspaceId: string): Promise<void>;
  };
  operations: {
    onProgress(listener: (progress: OperationProgress) => void): () => void;
  };
}
