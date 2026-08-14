import { z } from 'zod';

import { ReqwsError } from '../../shared/errors';
import { IPC_CHANNELS } from '../../shared/ipc-channels';
import {
  globalSettingsSchema,
  localePreferenceSchema,
} from '../../shared/schemas';
import type {
  GlobalSettings,
  ResolvedGlobalSettings,
} from '../../shared/types';
import type { SettingsService } from '../services/settings-service';
import type { IpcHandlerMap } from './repository-handlers';
import { toIpcResult } from './ipc-result';

type SettingsServicePort = Pick<SettingsService, 'get' | 'save'>;

export interface SettingsHandlerDependencies {
  settingsService: SettingsServicePort;
}

const noArgumentsSchema = z.tuple([]);
const saveArgumentsSchema = z.tuple([globalSettingsSchema]);

function parseSaveArguments(args: unknown[]): GlobalSettings {
  const parsed = saveArgumentsSchema.safeParse(args);
  if (parsed.success) return parsed.data[0];

  const input = args[0];
  const locale =
    input && typeof input === 'object' && !Array.isArray(input)
      ? (input as Record<string, unknown>).localePreference
      : undefined;
  if (
    input &&
    typeof input === 'object' &&
    !Array.isArray(input) &&
    !localePreferenceSchema.safeParse(locale).success
  ) {
    throw new ReqwsError({
      code: 'SETTINGS_INVALID_LOCALE',
      message: 'The selected interface language is not supported.',
    });
  }

  throw parsed.error;
}

export function createSettingsHandlers(
  dependencies: SettingsHandlerDependencies,
): IpcHandlerMap {
  return {
    [IPC_CHANNELS.settings.get]: (_event, ...args) =>
      toIpcResult<ResolvedGlobalSettings>(() => {
        noArgumentsSchema.parse(args);
        return dependencies.settingsService.get();
      }),
    [IPC_CHANNELS.settings.save]: (_event, ...args) =>
      toIpcResult<ResolvedGlobalSettings>(() =>
        dependencies.settingsService.save(parseSaveArguments(args)),
      ),
  };
}
