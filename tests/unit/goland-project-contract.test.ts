import { readFile } from 'node:fs/promises';
import { describe, expect, it } from 'vitest';
import { goLandProjectSchema, resolveGoLandSelection } from '../../src/shared/goland-workspace';

const corpus = JSON.parse(await readFile(
  new URL('../../integrations/goland/src/test/resources/contracts/goland-project.json', import.meta.url),
  'utf8',
)) as { members: string[]; cases: Array<{ name: string; valid: boolean; input: unknown }> };

describe('shared GoLand binding contract', () => {
  it.each(corpus.cases)('$name', ({ valid, input }) => {
    expect(goLandProjectSchema.safeParse(input).success).toBe(valid);
  });

  it('keeps manifest order, ignores stale IDs and preserves explicit empty selection', () => {
    const members = corpus.members.map((catalogRepositoryId) => ({ catalogRepositoryId }));
    expect(resolveGoLandSelection(members, { mode: 'all' })).toEqual(members);
    expect(resolveGoLandSelection(members, { mode: 'selected', repositoryIds: [] })).toEqual([]);
    expect(resolveGoLandSelection(members, {
      mode: 'selected', repositoryIds: ['repo_2', 'stale', 'repo_1'],
    })).toEqual(members.slice(0, 2));
  });
});
