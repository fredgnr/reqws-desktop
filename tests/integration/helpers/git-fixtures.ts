import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { GitRunner } from '../../../src/main/services/git-runner';

export interface LocalGitFixture {
  rootPath: string;
  originPath: string;
  seedPath: string;
  cleanup(): Promise<void>;
}

async function runChecked(
  git: GitRunner,
  args: readonly string[],
  cwd?: string,
): Promise<string> {
  const result = await git.run(args, { cwd, timeoutMs: 30_000 });
  if (result.timedOut || result.exitCode !== 0) {
    throw new Error(
      `Fixture Git command failed: ${args[0] ?? 'unknown'}: ${result.stderr}`,
    );
  }
  return result.stdout.trim();
}

export async function createLocalBareRepository(
  git: GitRunner,
  defaultBranch = 'main',
): Promise<LocalGitFixture> {
  const rootPath = await mkdtemp(path.join(tmpdir(), 'reqws-git-fixture-'));
  const originPath = path.join(rootPath, 'origin.git');
  const seedPath = path.join(rootPath, 'seed');

  await mkdir(seedPath);
  await runChecked(git, ['init', '--bare', originPath]);
  await runChecked(git, ['init'], seedPath);
  await runChecked(git, ['config', 'user.name', 'ReqWS Test'], seedPath);
  await runChecked(git, ['config', 'user.email', 'reqws@example.invalid'], seedPath);
  await writeFile(path.join(seedPath, 'README.md'), '# ReqWS fixture\n', 'utf8');
  await runChecked(git, ['add', 'README.md'], seedPath);
  await runChecked(git, ['commit', '-m', 'initial fixture commit'], seedPath);
  await runChecked(git, ['branch', '-M', defaultBranch], seedPath);
  await runChecked(git, ['remote', 'add', 'origin', originPath], seedPath);
  await runChecked(git, ['push', '-u', 'origin', defaultBranch], seedPath);
  await runChecked(git, [
    '--git-dir',
    originPath,
    'symbolic-ref',
    'HEAD',
    `refs/heads/${defaultBranch}`,
  ]);

  return {
    rootPath,
    originPath,
    seedPath,
    cleanup: () => rm(rootPath, { recursive: true, force: true }),
  };
}

export async function publishFeatureBranch(
  git: GitRunner,
  fixture: LocalGitFixture,
  featureBranch: string,
): Promise<void> {
  await runChecked(git, ['switch', '-c', featureBranch], fixture.seedPath);
  await writeFile(
    path.join(fixture.seedPath, 'FEATURE.txt'),
    `${featureBranch}\n`,
    'utf8',
  );
  await runChecked(git, ['add', 'FEATURE.txt'], fixture.seedPath);
  await runChecked(git, ['commit', '-m', `publish ${featureBranch}`], fixture.seedPath);
  await runChecked(git, ['push', '-u', 'origin', featureBranch], fixture.seedPath);
}

export async function gitOutput(
  git: GitRunner,
  repositoryPath: string,
  args: readonly string[],
): Promise<string> {
  return runChecked(git, args, repositoryPath);
}
