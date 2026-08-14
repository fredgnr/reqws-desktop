import { z } from 'zod';
import { reqwsErrorCodes } from './errors';
import {
  isSafeRepositoryUrl,
  repositoryNameKey,
} from './repository-utils';
import {
  DEFAULT_GLOBAL_SETTINGS,
  type GlobalSettings,
} from './types';

const nonEmpty = z.string().trim().min(1);
const id = nonEmpty.max(200);
const repositoryName = nonEmpty
  .max(255)
  .refine((value) => value !== '.' && value !== '..')
  .refine((value) => !value.includes('/') && !value.includes('\\'));
const repositoryUrl = nonEmpty
  .max(8_192)
  .refine(isSafeRepositoryUrl, {
    message: 'Repository URL must use credential-free HTTPS or SSH.',
  });
const absolutePath = nonEmpty
  .max(16_384)
  .refine((value) => value.startsWith('/'), {
    message: 'Path must be absolute.',
  });

export const supportedLocaleSchema = z.enum(['zh-CN', 'en-US']);
export const localePreferenceSchema = z.enum(['system', 'zh-CN', 'en-US']);

export const globalSettingsSchema = z.strictObject({
  localePreference: localePreferenceSchema,
  workspaceParentDirectory: absolutePath.nullable(),
  workspaceFileDirectory: absolutePath.nullable(),
});

export const globalDirectorySettingSchema = z.enum([
  'workspaceParentDirectory',
  'workspaceFileDirectory',
]);

export const resolvedGlobalSettingsSchema = globalSettingsSchema.extend({
  effectiveLocale: supportedLocaleSchema,
  invalidDirectoryFields: z.array(globalDirectorySettingSchema).optional(),
});

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function normalizedPersistedPath(value: unknown): string | null {
  const parsed = absolutePath.safeParse(value);
  return parsed.success ? parsed.data : null;
}

function migratedDirectory(
  settings: Record<string, unknown>,
  currentKey: 'workspaceParentDirectory' | 'workspaceFileDirectory',
  legacyKey: 'lastWorkspaceParentDirectory' | 'lastWorkspaceFileDirectory',
): string | null {
  if (Object.prototype.hasOwnProperty.call(settings, currentKey)) {
    return settings[currentKey] === null
      ? null
      : normalizedPersistedPath(settings[currentKey]);
  }
  return normalizedPersistedPath(settings[legacyKey]);
}

/**
 * State files are user-editable and predate GlobalSettings. Normalize only
 * this subtree so bad settings never hide otherwise valid repositories and
 * workspaces or prevent the application from starting.
 */
export function normalizePersistedGlobalSettings(value: unknown): GlobalSettings {
  if (!isRecord(value)) return { ...DEFAULT_GLOBAL_SETTINGS };
  const locale = localePreferenceSchema.safeParse(value.localePreference);
  return {
    localePreference: locale.success ? locale.data : 'system',
    workspaceParentDirectory: migratedDirectory(
      value,
      'workspaceParentDirectory',
      'lastWorkspaceParentDirectory',
    ),
    workspaceFileDirectory: migratedDirectory(
      value,
      'workspaceFileDirectory',
      'lastWorkspaceFileDirectory',
    ),
  };
}

function duplicateIndexes(
  values: readonly string[],
  key: (value: string) => string = (value) => value,
): number[] {
  const seen = new Set<string>();
  const duplicates: number[] = [];
  values.forEach((value, index) => {
    const normalized = key(value);
    if (seen.has(normalized)) duplicates.push(index);
    else seen.add(normalized);
  });
  return duplicates;
}

function pathIdentity(value: string): string {
  return value.normalize('NFC').toLocaleLowerCase('en-US');
}

export const repositorySchema = z.object({
  id,
  name: repositoryName,
  url: repositoryUrl,
  defaultBranch: nonEmpty.max(1_024),
  createdAt: z.iso.datetime(),
  updatedAt: z.iso.datetime(),
});

export const createRepositoryInputSchema = repositorySchema.pick({
  name: true,
  url: true,
  defaultBranch: true,
});

export const updateRepositoryInputSchema = createRepositoryInputSchema.extend({
  id,
});

export const testRepositoryInputSchema = z.object({
  url: repositoryUrl,
});

export const workspaceRepositorySchema = z.object({
  catalogRepositoryId: id,
  name: repositoryName,
  url: repositoryUrl,
  defaultBranch: nonEmpty.max(1_024),
  relativePath: repositoryName,
});

export const workspaceArtifactSchema = z.enum([
  'workspace-root',
  'manifest',
  'workspace-file',
]);

export const reqwsErrorPayloadSchema = z.object({
  code: z.enum(reqwsErrorCodes),
  message: nonEmpty,
  detail: z.string().max(1_100_000).optional(),
  repositoryName: z.string().optional(),
  stage: z.string().optional(),
});

export const workspaceSummarySchema = z.object({
  id,
  name: nonEmpty.max(255),
  featureBranch: nonEmpty.max(1_024),
  rootPath: absolutePath,
  workspaceFilePath: absolutePath,
  repositoryNames: z.array(repositoryName),
  repositoryIds: z.array(id).optional(),
  status: z.enum(['ready', 'missing', 'error']),
  statusDetail: z.string().optional(),
  missingArtifacts: z.array(workspaceArtifactSchema).optional(),
  lastError: reqwsErrorPayloadSchema.optional(),
  createdAt: z.iso.datetime(),
  updatedAt: z.iso.datetime(),
}).superRefine((summary, context) => {
  for (const index of duplicateIndexes(summary.repositoryNames, repositoryNameKey)) {
    context.addIssue({
      code: 'custom',
      path: ['repositoryNames', index],
      message: 'Workspace repository names must be unique.',
    });
  }
  if (summary.repositoryIds) {
    for (const index of duplicateIndexes(summary.repositoryIds)) {
      context.addIssue({
        code: 'custom',
        path: ['repositoryIds', index],
        message: 'Workspace repository IDs must be unique.',
      });
    }
    if (summary.repositoryIds.length !== summary.repositoryNames.length) {
      context.addIssue({
        code: 'custom',
        path: ['repositoryIds'],
        message: 'Workspace repository IDs and names must have equal lengths.',
      });
    }
  }
});

export const workspaceManifestSchema = z.object({
  schemaVersion: z.literal(1),
  id,
  name: nonEmpty.max(255),
  featureBranch: nonEmpty.max(1_024),
  rootPath: absolutePath,
  workspaceFilePath: absolutePath,
  repositories: z.array(workspaceRepositorySchema),
  createdAt: z.iso.datetime(),
  updatedAt: z.iso.datetime(),
}).superRefine((manifest, context) => {
  for (const index of duplicateIndexes(
    manifest.repositories.map((repository) => repository.catalogRepositoryId),
  )) {
    context.addIssue({
      code: 'custom',
      path: ['repositories', index, 'catalogRepositoryId'],
      message: 'Manifest repository IDs must be unique.',
    });
  }
  for (const index of duplicateIndexes(
    manifest.repositories.map((repository) => repository.name),
    repositoryNameKey,
  )) {
    context.addIssue({
      code: 'custom',
      path: ['repositories', index, 'name'],
      message: 'Manifest repository names must be unique.',
    });
  }
  for (const [index, repository] of manifest.repositories.entries()) {
    if (repository.relativePath !== repository.name) {
      context.addIssue({
        code: 'custom',
        path: ['repositories', index, 'relativePath'],
        message: 'Manifest repository path must match its safe name.',
      });
    }
  }
});

export const appStateSchema = z.object({
  schemaVersion: z.literal(1),
  settings: z.unknown().optional().transform(normalizePersistedGlobalSettings),
  repositories: z.array(repositorySchema),
  workspaces: z.array(workspaceSummarySchema),
}).superRefine((state, context) => {
  const uniquenessChecks: Array<{
    values: string[];
    path: 'repositories' | 'workspaces';
    field: string;
    message: string;
    key?: (value: string) => string;
  }> = [
    {
      values: state.repositories.map((repository) => repository.id),
      path: 'repositories',
      field: 'id',
      message: 'Repository IDs must be unique.',
    },
    {
      values: state.repositories.map((repository) => repository.name),
      path: 'repositories',
      field: 'name',
      message: 'Repository names must be unique.',
      key: repositoryNameKey,
    },
    {
      values: state.workspaces.map((workspace) => workspace.id),
      path: 'workspaces',
      field: 'id',
      message: 'Workspace IDs must be unique.',
    },
    {
      values: state.workspaces.map((workspace) => workspace.rootPath),
      path: 'workspaces',
      field: 'rootPath',
      message: 'Workspace root paths must be unique.',
      key: pathIdentity,
    },
    {
      values: state.workspaces.map((workspace) => workspace.workspaceFilePath),
      path: 'workspaces',
      field: 'workspaceFilePath',
      message: 'Workspace file paths must be unique.',
      key: pathIdentity,
    },
  ];
  for (const check of uniquenessChecks) {
    for (const index of duplicateIndexes(check.values, check.key)) {
      context.addIssue({
        code: 'custom',
        path: [check.path, index, check.field],
        message: check.message,
      });
    }
  }
});

export const createWorkspaceInputSchema = z.object({
  name: nonEmpty.max(255),
  featureBranch: nonEmpty.max(1_024),
  rootPath: absolutePath,
  workspaceFileDirectory: absolutePath,
  repositoryIds: z.array(id).min(1),
});

export const addWorkspaceRepositoryInputSchema = z.object({
  workspaceId: id,
  repositoryId: id,
});

export const removeWorkspaceRepositoryInputSchema = z.object({
  workspaceId: id,
  catalogRepositoryId: id,
});

export const selectDirectoryInputSchema = z.object({
  title: nonEmpty.max(255),
  defaultPath: z.string().max(16_384).optional(),
  createDirectory: z.boolean().optional(),
});

export const idSchema = id;
