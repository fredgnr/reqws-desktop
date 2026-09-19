import { describe, expect, it, vi } from 'vitest';
import { runReleaseStage, startReleaseStage } from '../../scripts/release-log.mts';

describe('release stage logging', () => {
  it('reports completion once without changing the operation result', async () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    expect(await runReleaseStage('macos-package', 'verify-bundle', async () => 42)).toBe(42);
    expect(log.mock.calls.map(([line]) => line)).toEqual([
      '[release][macos-package] stage=verify-bundle status=started',
      expect.stringMatching(/^\[release\]\[macos-package\] stage=verify-bundle status=success duration_ms=\d+$/u),
    ]);
    log.mockClear();
    const finish = startReleaseStage('signing', 'packaging');
    finish('failed');
    finish('success');
    expect(log).toHaveBeenCalledTimes(2);
    expect(log.mock.calls[1]?.[0]).toContain('status=failed');
  });

  it('logs only the fixed stage and rethrows errors without disclosing their data', async () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => {});
    const failure = new Error('secret-password /private/keychain ::error::injected');
    await expect(runReleaseStage('signing', 'import-p12', async () => { throw failure; })).rejects.toBe(failure);
    const output = log.mock.calls.map(([line]) => String(line)).join('\n');
    expect(output).toContain('stage=import-p12 status=failed duration_ms=');
    expect(output).not.toContain(failure.message);
    expect(output).not.toContain('status=success');
  });
});
