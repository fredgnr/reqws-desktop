// @vitest-environment jsdom
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import i18n, { initializeI18n } from '../../src/renderer/i18n';
import type {
  OperationProgress,
  OperationRollbackReason,
  ReqwsAPI,
  SupportedLocale,
} from '../../src/shared/types';

let App: typeof import('../../src/renderer/App').App;
let progressListener: ((progress: OperationProgress) => void) | undefined;
const onProgress = vi.fn((listener: (progress: OperationProgress) => void) => {
  progressListener = listener;
  return () => undefined;
});

beforeAll(async () => {
  const api = {
    repositories: {
      list: vi.fn().mockResolvedValue([]),
      create: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
      testConnection: vi.fn(),
    },
    workspaces: {
      list: vi.fn().mockResolvedValue([]),
      get: vi.fn(),
      create: vi.fn(),
      addRepository: vi.fn(),
      removeRepository: vi.fn(),
      sync: vi.fn(),
      forget: vi.fn(),
    },
    settings: {
      get: vi.fn(),
      save: vi.fn(),
    },
    dialogs: { selectDirectory: vi.fn() },
    editors: {
      getAvailability: vi.fn().mockResolvedValue({
        git: { available: true },
        vscode: { available: true },
        cursor: { available: true },
      }),
      openVSCode: vi.fn(),
      openCursor: vi.fn(),
      openCursorRoot: vi.fn(),
      revealInFinder: vi.fn(),
    },
    operations: { onProgress },
  } as unknown as ReqwsAPI;
  Object.defineProperty(window, 'reqws', {
    configurable: true,
    value: api,
  });
  ({ App } = await import('../../src/renderer/App'));
});

afterEach(() => {
  cleanup();
  onProgress.mockClear();
  progressListener = undefined;
});

async function renderProgress(
  locale: SupportedLocale,
  rollbackReason?: OperationRollbackReason,
): Promise<void> {
  await initializeI18n(locale);
  render(
    <App
      initialSettings={{
        localePreference: locale,
        effectiveLocale: locale,
        workspaceParentDirectory: null,
        workspaceFileDirectory: null,
      }}
    />,
  );
  await waitFor(() => expect(onProgress).toHaveBeenCalled());
  act(() => progressListener?.({
    operationId: 'op_1',
    kind: 'create-workspace',
    stage: 'rolling-back',
    current: 1,
    total: 2,
    message: 'legacy main-process rollback message',
    ...(rollbackReason ? { rollbackReason } : {}),
  }));
}

describe('Operation progress localization', () => {
  it.each([
    ['zh-CN', 'CLEANING_STAGING'],
    ['zh-CN', 'RETAINING_PUBLISHED_ARTIFACTS'],
    ['en-US', 'CLEANING_STAGING'],
    ['en-US', 'RETAINING_PUBLISHED_ARTIFACTS'],
  ] as const)('localizes %s %s without rendering the legacy message', async (
    locale,
    rollbackReason,
  ) => {
    await renderProgress(locale, rollbackReason);
    const key = `operation.rollbackReasons.${rollbackReason}`;
    const expected = i18n.t(key);

    expect(expected).not.toBe(key);
    expect(screen.getAllByText(expected).length).toBeGreaterThan(0);
    expect(screen.queryByText('legacy main-process rollback message'))
      .not.toBeInTheDocument();
  });

  it('falls back to the localized stage for legacy progress payloads', async () => {
    await renderProgress('en-US');

    expect(screen.getAllByText(i18n.t('operation.stages.rolling-back')).length)
      .toBeGreaterThan(0);
  });
});
