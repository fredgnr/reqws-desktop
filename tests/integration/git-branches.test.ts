import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { BranchService } from '../../src/main/services/branch-service';
import {
  GitRunner,
  GitServiceError,
} from '../../src/main/services/git-runner';
import {
  createLocalBareRepository,
  gitOutput,
  publishFeatureBranch,
  type LocalGitFixture,
} from './helpers/git-fixtures';

describe('local Git branch integration', () => {
  let git: GitRunner;
  const fixtures: LocalGitFixture[] = [];

  beforeEach(async () => {
    git = await GitRunner.create(undefined, {
      allowLocalRepositoryPaths: true,
    });
  });

  afterEach(async () => {
    await Promise.all(fixtures.splice(0).map((fixture) => fixture.cleanup()));
  });

  async function fixture(defaultBranch = 'main'): Promise<LocalGitFixture> {
    const created = await createLocalBareRepository(git, defaultBranch);
    fixtures.push(created);
    return created;
  }

  it('creates a new local feature branch from origin/default', async () => {
    const source = await fixture();
    const clonePath = path.join(source.rootPath, 'workspace-repo');
    await git.clone(source.originPath, clonePath);

    const result = await new BranchService(git).checkoutFeatureBranch(
      clonePath,
      'main',
      'feature/new-work',
    );

    expect(result.source).toBe('remote-default');
    expect(await gitOutput(git, clonePath, ['branch', '--show-current'])).toBe(
      'feature/new-work',
    );
    expect(await gitOutput(git, clonePath, ['rev-parse', 'HEAD'])).toBe(
      await gitOutput(git, clonePath, ['rev-parse', 'origin/main']),
    );
  });

  it('tracks an existing remote feature branch', async () => {
    const source = await fixture();
    await publishFeatureBranch(git, source, 'feature/remote-ready');
    const clonePath = path.join(source.rootPath, 'workspace-repo');
    await git.clone(source.originPath, clonePath);

    const result = await new BranchService(git).checkoutFeatureBranch(
      clonePath,
      'main',
      'feature/remote-ready',
    );

    expect(result.source).toBe('remote-feature');
    expect(
      await gitOutput(git, clonePath, [
        'rev-parse',
        '--abbrev-ref',
        '--symbolic-full-name',
        '@{upstream}',
      ]),
    ).toBe('origin/feature/remote-ready');
  });

  it('switches an existing local feature branch without recreating it', async () => {
    const source = await fixture();
    const clonePath = path.join(source.rootPath, 'workspace-repo');
    await git.clone(source.originPath, clonePath);
    await gitOutput(git, clonePath, [
      'switch',
      '-c',
      'feature/local-existing',
      'origin/main',
    ]);
    await gitOutput(git, clonePath, ['switch', 'main']);

    const result = await new BranchService(git).checkoutFeatureBranch(
      clonePath,
      'main',
      'feature/local-existing',
    );

    expect(result.source).toBe('local');
    expect(await gitOutput(git, clonePath, ['branch', '--show-current'])).toBe(
      'feature/local-existing',
    );
  });

  it('maps a missing remote default branch to a stable error', async () => {
    const source = await fixture('legacy');
    const clonePath = path.join(source.rootPath, 'workspace-repo');
    await git.clone(source.originPath, clonePath);

    await expect(
      new BranchService(git).checkoutFeatureBranch(
        clonePath,
        'main',
        'feature/cannot-start',
      ),
    ).rejects.toMatchObject({ code: 'DEFAULT_BRANCH_NOT_FOUND' });
  });

  it('maps invalid branch, unreachable repository, and clone failures', async () => {
    const source = await fixture();
    const missingOrigin = path.join(source.rootPath, 'does-not-exist.git');

    await expect(
      new BranchService(git).validateBranch('feature/../invalid'),
    ).rejects.toMatchObject({ code: 'INVALID_BRANCH_NAME' });
    await expect(git.lsRemote(missingOrigin)).rejects.toMatchObject({
      code: 'REPOSITORY_UNREACHABLE',
    });
    await expect(
      git.clone(missingOrigin, path.join(source.rootPath, 'failed-clone')),
    ).rejects.toBeInstanceOf(GitServiceError);
    await expect(
      git.clone(missingOrigin, path.join(source.rootPath, 'failed-clone-2')),
    ).rejects.toMatchObject({ code: 'CLONE_FAILED' });
  });

  it('uses strict origin URL matching and does not rewrite equivalent URLs', async () => {
    const source = await fixture();
    const clonePath = path.join(source.rootPath, 'workspace-repo');
    await git.clone(source.originPath, clonePath);

    await expect(git.originUrlMatches(clonePath, source.originPath)).resolves.toBe(
      true,
    );
    await expect(
      git.originUrlMatches(clonePath, `${source.originPath}/`),
    ).resolves.toBe(false);
    await expect(
      git.assertMatchingOrigin(clonePath, `${source.originPath}/`),
    ).rejects.toMatchObject({ code: 'REPOSITORY_PATH_CONFLICT' });
  });
});
