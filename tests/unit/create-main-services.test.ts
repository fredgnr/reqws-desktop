import { describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({
  app: { getPreferredSystemLanguages: vi.fn(() => ['en-US']) },
  BrowserWindow: { fromWebContents: vi.fn() },
  dialog: {},
}));

import { resolveReadyWorkspacePaths } from '../../src/main/ipc/create-main-services';
import type { WorkspaceDetail } from '../../src/shared/types';

const readyWorkspace: WorkspaceDetail = {
  schemaVersion: 1,
  id: 'ws_1',
  name: 'feature-one',
  featureBranch: 'feature/one',
  rootPath: '/features/feature-one',
  workspaceFilePath: '/workspaces/feature-one.code-workspace',
  repositories: [],
  createdAt: '2026-08-14T00:00:00.000Z',
  updatedAt: '2026-08-14T00:00:00.000Z',
  status: 'ready',
};

describe('GoLand workspace resolution in Main', () => {
  it('gets current workspace detail on every request and returns only bound paths', async () => {
    const get = vi.fn().mockResolvedValue(readyWorkspace);
    const workspaceService = { get };

    await expect(
      resolveReadyWorkspacePaths(workspaceService, 'ws_1'),
    ).resolves.toEqual({
      rootPath: readyWorkspace.rootPath,
      workspaceFilePath: readyWorkspace.workspaceFilePath,
    });
    await resolveReadyWorkspacePaths(workspaceService, 'ws_1');

    expect(get).toHaveBeenCalledTimes(2);
    expect(get).toHaveBeenNthCalledWith(1, 'ws_1');
    expect(get).toHaveBeenNthCalledWith(2, 'ws_1');
  });

  it.each(['missing', 'error'] as const)(
    'rejects a %s workspace before GoLand launch',
    async (status) => {
      const get = vi.fn().mockResolvedValue({
        ...readyWorkspace,
        status,
        statusDetail: 'Manifest or root is not available.',
      });

      await expect(
        resolveReadyWorkspacePaths({ get }, 'ws_1'),
      ).rejects.toMatchObject({
        code: 'WORKSPACE_PATH_MISSING',
        detail: 'Manifest or root is not available.',
        stage: 'launching',
      });
    },
  );

  it('propagates a manifest re-read failure', async () => {
    const get = vi.fn().mockRejectedValue({
      code: 'MANIFEST_READ_FAILED',
      message: 'Workspace manifest could not be read.',
    });

    await expect(
      resolveReadyWorkspacePaths({ get }, 'ws_1'),
    ).rejects.toMatchObject({ code: 'MANIFEST_READ_FAILED' });
  });
});
