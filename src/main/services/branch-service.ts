import {
  GIT_DEFAULT_TIMEOUT_MS,
  GitRunner,
  GitServiceError,
  type GitRunResult,
} from './git-runner';

function detailFrom(result: GitRunResult): string | undefined {
  const detail = result.stderr.trim() || result.stdout.trim();
  return detail.length > 0 ? detail : undefined;
}

export type FeatureBranchSource = 'local' | 'remote-feature' | 'remote-default';

export interface CheckoutFeatureBranchResult {
  source: FeatureBranchSource;
  branch: string;
}

export class BranchService {
  constructor(private readonly git: GitRunner) {}

  async validateBranch(branch: string): Promise<void> {
    if (!(await this.git.checkBranchName(branch))) {
      throw new GitServiceError('INVALID_BRANCH_NAME', 'Git branch name is invalid.', {
        stage: 'validating',
      });
    }
  }

  async checkoutFeatureBranch(
    repositoryPath: string,
    defaultBranch: string,
    featureBranch: string,
  ): Promise<CheckoutFeatureBranchResult> {
    await this.validateBranch(featureBranch);
    await this.validateBranch(defaultBranch);
    await this.git.fetch(repositoryPath);

    if (await this.git.refExists(repositoryPath, `refs/heads/${featureBranch}`)) {
      await this.switchBranch(repositoryPath, [featureBranch]);
      return { source: 'local', branch: featureBranch };
    }

    if (
      await this.git.refExists(
        repositoryPath,
        `refs/remotes/origin/${featureBranch}`,
      )
    ) {
      const result = await this.runSwitch(repositoryPath, [
        '--track',
        '-c',
        featureBranch,
        `origin/${featureBranch}`,
      ]);

      if (result.exitCode === 0 && !result.timedOut) {
        return { source: 'remote-feature', branch: featureBranch };
      }

      // A branch may appear between the ref check and switch. Re-check and use
      // it rather than failing a safe, idempotent retry.
      if (await this.git.refExists(repositoryPath, `refs/heads/${featureBranch}`)) {
        await this.switchBranch(repositoryPath, [featureBranch]);
        return { source: 'local', branch: featureBranch };
      }

      throw this.checkoutError(result);
    }

    const defaultRef = `refs/remotes/origin/${defaultBranch}`;
    if (!(await this.git.refExists(repositoryPath, defaultRef))) {
      throw new GitServiceError(
        'DEFAULT_BRANCH_NOT_FOUND',
        `Remote default branch "${defaultBranch}" was not found.`,
        { stage: 'switching' },
      );
    }

    await this.switchBranch(repositoryPath, [
      '-c',
      featureBranch,
      `origin/${defaultBranch}`,
    ]);
    return { source: 'remote-default', branch: featureBranch };
  }

  private runSwitch(
    repositoryPath: string,
    args: readonly string[],
  ): Promise<GitRunResult> {
    return this.git.run(['switch', ...args], {
      cwd: repositoryPath,
      timeoutMs: GIT_DEFAULT_TIMEOUT_MS,
    });
  }

  private async switchBranch(
    repositoryPath: string,
    args: readonly string[],
  ): Promise<void> {
    const result = await this.runSwitch(repositoryPath, args);
    if (result.timedOut || result.exitCode !== 0) {
      throw this.checkoutError(result);
    }
  }

  private checkoutError(result: GitRunResult): GitServiceError {
    return new GitServiceError(
      'FEATURE_BRANCH_CHECKOUT_FAILED',
      'Feature branch checkout failed.',
      { detail: detailFrom(result), stage: 'switching' },
    );
  }
}
