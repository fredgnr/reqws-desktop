import {
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

import { ReqwsError } from '../../src/shared/errors';
import type { WorkspaceManifest } from '../../src/shared/types';
import {
  WorkspaceFileWriter,
  workspaceManifestPath,
} from '../../src/main/services/workspace-file-writer';

const temporaryDirectories: string[] = [];

async function fixture(): Promise<{
  directory: string;
  manifest: WorkspaceManifest;
}> {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-workspace-file-'));
  temporaryDirectories.push(directory);
  return {
    directory,
    manifest: {
      schemaVersion: 1,
      id: 'ws_1',
      name: 'FEAT-1',
      featureBranch: 'feature/FEAT-1',
      rootPath: path.join(directory, 'workspace'),
      workspaceFilePath: path.join(directory, 'files', 'FEAT-1.code-workspace'),
      repositories: [{
        catalogRepositoryId: 'repo_1',
        name: 'order-api',
        url: 'git@example.test:order-api.git',
        defaultBranch: 'main',
        relativePath: 'order-api',
      }],
      createdAt: '2026-08-12T00:00:00.000Z',
      updatedAt: '2026-08-12T00:00:00.000Z',
    },
  };
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe('WorkspaceFileWriter', () => {
  it('round-trips a validated manifest through an atomic write', async () => {
    const { manifest } = await fixture();
    const writer = new WorkspaceFileWriter();
    const manifestPath = workspaceManifestPath(manifest.rootPath);

    await writer.writeManifest(manifestPath, manifest);

    expect(await writer.readManifest(manifestPath)).toEqual(manifest);
    expect((await readFile(manifestPath, 'utf8')).endsWith('\n')).toBe(true);
  });

  it('writes standard JSON with ordered absolute repository paths', async () => {
    const { manifest } = await fixture();
    const writer = new WorkspaceFileWriter();

    await writer.writeCodeWorkspace(
      manifest.workspaceFilePath,
      manifest.rootPath,
      [
        ...manifest.repositories,
        {
          catalogRepositoryId: 'repo_2',
          name: 'payment-api',
          url: 'git@example.test:payment-api.git',
          defaultBranch: 'develop',
          relativePath: 'payment-api',
        },
      ],
    );

    expect(JSON.parse(await readFile(manifest.workspaceFilePath, 'utf8'))).toEqual({
      folders: [
        { name: 'order-api', path: path.join(manifest.rootPath, 'order-api') },
        { name: 'payment-api', path: path.join(manifest.rootPath, 'payment-api') },
      ],
      extensions: { recommendations: ['golang.go'] },
    });
  });

  it('can create a workspace file without overwriting a concurrently-created file', async () => {
    const { manifest } = await fixture();
    const writer = new WorkspaceFileWriter();
    await mkdir(path.dirname(manifest.workspaceFilePath), { recursive: true });
    await writeFile(manifest.workspaceFilePath, '{"owner":"user"}\n', 'utf8');

    await expect(writer.writeCodeWorkspace(
      manifest.workspaceFilePath,
      manifest.rootPath,
      manifest.repositories,
      { overwrite: false },
    )).rejects.toBeInstanceOf(ReqwsError);

    expect(await readFile(manifest.workspaceFilePath, 'utf8')).toBe('{"owner":"user"}\n');
  });

  it('refuses to write through a non-canonical parent symlink', async () => {
    const { directory, manifest } = await fixture();
    const writer = new WorkspaceFileWriter();
    const outside = path.join(directory, 'outside');
    await mkdir(outside);
    await symlink(outside, path.dirname(manifest.workspaceFilePath));

    await expect(
      writer.writeCodeWorkspace(
        manifest.workspaceFilePath,
        manifest.rootPath,
        manifest.repositories,
      ),
    ).rejects.toMatchObject({ code: 'WORKSPACE_FILE_WRITE_FAILED' });
    expect(await readdir(outside)).toEqual([]);
  });

  it('backs up and reports a corrupt manifest without overwriting it', async () => {
    const { manifest } = await fixture();
    const writer = new WorkspaceFileWriter();
    const manifestPath = workspaceManifestPath(manifest.rootPath);
    await writer.writeManifest(manifestPath, manifest);
    await writeFile(manifestPath, '{broken', 'utf8');

    const error = await writer.readManifest(manifestPath).catch(
      (reason: unknown) => reason,
    );
    expect(error).toBeInstanceOf(ReqwsError);
    expect((error as ReqwsError).code).toBe('MANIFEST_READ_FAILED');
    expect(await readFile(manifestPath, 'utf8')).toBe('{broken');
    expect((await readdir(path.dirname(manifestPath))).some(
      (name) => name.startsWith('workspace.json.corrupt-'),
    )).toBe(true);
  });
});
