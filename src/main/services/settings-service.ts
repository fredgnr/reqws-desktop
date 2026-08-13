import fs from 'node:fs/promises';
import path from 'node:path';

import { ReqwsError } from '../../shared/errors';
import {
  globalSettingsSchema,
  localePreferenceSchema,
  normalizePersistedGlobalSettings,
} from '../../shared/schemas';
import type {
  AppState,
  GlobalDirectorySetting,
  GlobalSettings,
  LocalePreference,
  ResolvedGlobalSettings,
  SupportedLocale,
} from '../../shared/types';

export interface SettingsStateStore {
  read(): Promise<AppState>;
  update(
    mutator: (state: AppState) => AppState | Promise<AppState>,
  ): Promise<AppState>;
}

export interface SettingsService {
  get(): Promise<ResolvedGlobalSettings>;
  save(settings: GlobalSettings): Promise<ResolvedGlobalSettings>;
}

export type GetPreferredSystemLanguages = () => readonly string[];

function errorDetail(error: unknown): string | undefined {
  if (error instanceof Error) return error.message;
  return typeof error === 'string' ? error : undefined;
}

function isNodeError(error: unknown, code: string): boolean {
  return (
    error instanceof Error &&
    'code' in error &&
    (error as NodeJS.ErrnoException).code === code
  );
}

export function resolveEffectiveLocale(
  preference: LocalePreference,
  preferredSystemLanguages: readonly string[],
): SupportedLocale {
  if (preference !== 'system') return preference;
  return preferredSystemLanguages.some((language) =>
    /^zh(?:-|$)/iu.test(language.trim()),
  )
    ? 'zh-CN'
    : 'en-US';
}

export class DefaultSettingsService implements SettingsService {
  constructor(
    private readonly stateStore: SettingsStateStore,
    private readonly getPreferredSystemLanguages: GetPreferredSystemLanguages,
  ) {}

  async get(): Promise<ResolvedGlobalSettings> {
    let state: AppState;
    try {
      state = await this.stateStore.read();
    } catch (error) {
      throw new ReqwsError(
        {
          code: 'SETTINGS_READ_FAILED',
          message: 'Unable to read global settings.',
          detail: errorDetail(error),
        },
        { cause: error },
      );
    }

    const settings = normalizePersistedGlobalSettings(state.settings);
    const [workspaceParent, workspaceFile] = await Promise.all([
      this.readableDirectory(settings.workspaceParentDirectory),
      this.readableDirectory(settings.workspaceFileDirectory),
    ]);
    const invalidDirectoryFields: GlobalDirectorySetting[] = [];
    if (workspaceParent.invalid) invalidDirectoryFields.push('workspaceParentDirectory');
    if (workspaceFile.invalid) invalidDirectoryFields.push('workspaceFileDirectory');
    return this.resolve({
      ...settings,
      workspaceParentDirectory: workspaceParent.value,
      workspaceFileDirectory: workspaceFile.value,
    }, invalidDirectoryFields);
  }

  async save(input: GlobalSettings): Promise<ResolvedGlobalSettings> {
    const settings = this.parseSettings(input);
    await Promise.all([
      this.validateDirectory(
        settings.workspaceParentDirectory,
        'Workspace parent directory',
      ),
      this.validateDirectory(
        settings.workspaceFileDirectory,
        'Workspace file directory',
      ),
    ]);

    try {
      await this.stateStore.update((state) => ({
        ...state,
        settings,
      }));
    } catch (error) {
      throw new ReqwsError(
        {
          code: 'SETTINGS_WRITE_FAILED',
          message: 'Unable to write global settings.',
          detail: errorDetail(error),
        },
        { cause: error },
      );
    }

    return this.resolve(settings);
  }

  private parseSettings(input: GlobalSettings): GlobalSettings {
    const parsed = globalSettingsSchema.safeParse(input);
    if (parsed.success) return parsed.data;

    const locale =
      input && typeof input === 'object'
        ? (input as unknown as Record<string, unknown>).localePreference
        : undefined;
    if (!localePreferenceSchema.safeParse(locale).success) {
      throw new ReqwsError({
        code: 'SETTINGS_INVALID_LOCALE',
        message: 'The selected interface language is not supported.',
      });
    }

    throw new ReqwsError({
      code: 'INVALID_INPUT',
      message: 'Global settings input is invalid.',
      detail: parsed.error.issues
        .map((issue) => {
          const location = issue.path.length > 0 ? issue.path.join('.') : 'input';
          return `${location}: ${issue.message}`;
        })
        .join('\n'),
    });
  }

  private async validateDirectory(
    directory: string | null,
    label: string,
  ): Promise<void> {
    if (directory === null) return;
    if (!path.isAbsolute(directory)) {
      throw new ReqwsError({
        code: 'INVALID_INPUT',
        message: `${label} must be an absolute path.`,
      });
    }

    let stats;
    try {
      stats = await fs.stat(directory);
    } catch (error) {
      throw new ReqwsError(
        {
          code: 'SETTINGS_DIRECTORY_NOT_FOUND',
          message: `${label} does not exist or cannot be accessed.`,
          detail: isNodeError(error, 'ENOENT')
            ? directory
            : errorDetail(error),
        },
        { cause: error },
      );
    }

    if (!stats.isDirectory()) {
      throw new ReqwsError({
        code: 'SETTINGS_DIRECTORY_NOT_DIRECTORY',
        message: `${label} is not a directory.`,
        detail: directory,
      });
    }
  }

  private async readableDirectory(
    directory: string | null,
  ): Promise<{ value: string | null; invalid: boolean }> {
    if (directory === null) return { value: null, invalid: false };
    try {
      return (await fs.stat(directory)).isDirectory()
        ? { value: directory, invalid: false }
        : { value: null, invalid: true };
    } catch {
      // Persisted paths can become stale between launches. Treat them as an
      // unset default so settings never prevent startup or workspace creation.
      return { value: null, invalid: true };
    }
  }

  private resolve(
    settings: GlobalSettings,
    invalidDirectoryFields: GlobalDirectorySetting[] = [],
  ): ResolvedGlobalSettings {
    let preferredSystemLanguages: readonly string[] = [];
    try {
      preferredSystemLanguages = this.getPreferredSystemLanguages();
    } catch {
      // Electron locale lookup is advisory. English remains the safe fallback.
    }
    return {
      ...settings,
      effectiveLocale: resolveEffectiveLocale(
        settings.localePreference,
        preferredSystemLanguages,
      ),
      ...(invalidDirectoryFields.length > 0 ? { invalidDirectoryFields } : {}),
    };
  }
}
