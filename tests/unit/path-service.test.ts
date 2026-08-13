import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

import { ReqwsError } from '../../src/shared/errors';
import {
  assertAbsolutePath,
  assertCanonicalParentPath,
  assertContainedPath,
  assertIndependentGitRepository,
  repositoryPath,
  resolveRealParentPath,
} from '../../src/main/services/path-service';

const temporaryDirectories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-paths-'));
  temporaryDirectories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) =>
      rm(directory, { recursive: true, force: true }),
    ),
  );
});

describe('PathService', () => {
  it('requires absolute user-selected paths', () => {
    expect(() => assertAbsolutePath('../relative')).toThrowError(ReqwsError);
    expect(assertAbsolutePath('/tmp/../tmp/workspace')).toBe('/tmp/workspace');
  });

  it('safely joins valid repository names under the root', async () => {
    const directory = await temporaryDirectory();
    const root = path.join(directory, 'root');
    await mkdir(root);

    expect(await repositoryPath(root, ' order-api ')).toBe(
      path.join(root, 'order-api'),
    );
    await expect(repositoryPath(root, '..')).rejects.toMatchObject({
      code: 'INVALID_REPOSITORY_NAME',
    });
    await expect(repositoryPath(root, '../escape')).rejects.toMatchObject({
      code: 'INVALID_REPOSITORY_NAME',
    });
  });

  it('rejects lexical escapes and symbolic-link escapes', async () => {
    const directory = await temporaryDirectory();
    const root = path.join(directory, 'root');
    const outside = path.join(directory, 'outside');
    await Promise.all([mkdir(root), mkdir(outside)]);

    await expect(
      assertContainedPath(root, path.join(directory, 'escape')),
    ).rejects.toMatchObject({ code: 'REPOSITORY_PATH_CONFLICT' });

    await symlink(outside, path.join(root, 'linked'));
    await expect(repositoryPath(root, 'linked')).rejects.toMatchObject({
      code: 'REPOSITORY_PATH_CONFLICT',
    });
    await expect(
      assertContainedPath(root, path.join(root, 'linked', 'repo')),
    ).rejects.toMatchObject({ code: 'REPOSITORY_PATH_CONFLICT' });
  });

  it('canonicalizes a target through its real parent', async () => {
    const directory = await temporaryDirectory();
    const root = path.join(directory, 'root');
    const outside = path.join(directory, 'outside');
    await Promise.all([mkdir(root), mkdir(outside)]);
    await symlink(outside, path.join(root, 'linked'));

    expect(await resolveRealParentPath(path.join(root, 'linked', 'new.json'))).toBe(
      path.join(outside, 'new.json'),
    );
  });

  it('binds destinations to canonical parents while allowing missing tails', async () => {
    const directory = await temporaryDirectory();
    const root = path.join(directory, 'root');
    const outside = path.join(directory, 'outside');
    await Promise.all([mkdir(root), mkdir(outside)]);

    await expect(
      assertCanonicalParentPath(path.join(root, 'missing', 'workspace.json')),
    ).resolves.toBe(path.join(root, 'missing', 'workspace.json'));

    await symlink(outside, path.join(root, 'linked'));
    await expect(
      assertCanonicalParentPath(path.join(root, 'linked', 'workspace.json')),
    ).rejects.toMatchObject({ code: 'INVALID_INPUT' });
  });

  it('requires real in-tree Git directories and rejects gitfiles', async () => {
    const directory = await temporaryDirectory();
    const root = path.join(directory, 'root');
    const valid = path.join(root, 'valid');
    const gitfile = path.join(root, 'gitfile');
    await mkdir(path.join(valid, '.git', 'objects'), { recursive: true });
    await mkdir(gitfile, { recursive: true });
    await writeFile(path.join(gitfile, '.git'), 'gitdir: ../valid/.git\n', 'utf8');

    await expect(assertIndependentGitRepository(root, valid)).resolves.toBe(valid);
    await expect(assertIndependentGitRepository(root, gitfile)).rejects
      .toMatchObject({ code: 'REPOSITORY_PATH_CONFLICT' });
  });
});
