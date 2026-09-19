const stages = {
  signing: [
    'context', 'credentials', 'public-certificate', 'temporary-files', 'keychain-search-list',
    'create-keychain', 'unlock-keychain', 'import-p12', 'key-access', 'code-signing-trust',
    'trusted-identity', 'packaging', 'cleanup', 'restore-search-list', 'revoke-code-signing-trust',
    'delete-keychain', 'delete-p12', 'delete-temporary-directory',
  ],
  'macos-package': [
    'preflight', 'forge-package', 'verify-bundle', 'create-zip', 'extract-zip',
    'verify-extracted-bundle', 'remove-extracted-bundle', 'update-metadata', 'checksums',
  ],
} as const;

export type ReleaseScope = keyof typeof stages;
export type ReleaseStage<S extends ReleaseScope> = typeof stages[S][number];

// Labels are a closed set, never paths, command arguments, environment values,
// subprocess output, or exception text. Each completion is emitted only once.
export function startReleaseStage<S extends ReleaseScope>(scope: S, stage: ReleaseStage<S>) {
  if (!(stages[scope] as readonly string[]).includes(stage)) throw new Error('Unknown release log stage.');
  const started = performance.now();
  let finished = false;
  console.log(`[release][${scope}] stage=${stage} status=started`);
  return (status: 'success' | 'failed') => {
    if (finished) return;
    finished = true;
    console.log(`[release][${scope}] stage=${stage} status=${status} duration_ms=${Math.round(performance.now() - started)}`);
  };
}

export async function runReleaseStage<S extends ReleaseScope, T>(scope: S, stage: ReleaseStage<S>, operation: () => T | Promise<T>): Promise<T> {
  const finish = startReleaseStage(scope, stage);
  try {
    const result = await operation();
    finish('success');
    return result;
  } catch (error) {
    finish('failed');
    throw error;
  }
}
