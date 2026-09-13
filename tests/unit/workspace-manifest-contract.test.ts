import { readFile } from 'node:fs/promises';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { workspaceManifestSchema } from '../../src/shared/schemas';

const fixtureDirectory = path.resolve(
  import.meta.dirname,
  '../../integrations/goland/src/test/resources/manifests',
);

async function readFixture(name: string): Promise<unknown> {
  return JSON.parse(await readFile(path.join(fixtureDirectory, name), 'utf8'));
}

describe('workspace manifest cross-IDE contract', () => {
  it.each([
    'valid-minimal-v1.json',
    'valid-full-v1.json',
    'valid-unknown-fields-v1.json',
    'valid-ecma-trim-v1.json',
  ])('accepts the shared valid fixture %s', async (name) => {
    const parsed = workspaceManifestSchema.parse(await readFixture(name));

    expect(parsed.schemaVersion).toBe(1);
  });

  it('ignores unknown fields in a supported manifest version', async () => {
    const parsed = workspaceManifestSchema.parse(
      await readFixture('valid-unknown-fields-v1.json'),
    );

    expect(parsed).not.toHaveProperty('futureTopLevelField');
    expect(parsed.repositories[0]).not.toHaveProperty(
      'futureRepositoryField',
    );
  });

  it('uses ECMAScript TrimString edge semantics', async () => {
    const parsed = workspaceManifestSchema.parse(
      await readFixture('valid-ecma-trim-v1.json'),
    );

    expect(parsed).toMatchObject({
      id: 'ws_ecma_trim',
      name: 'ECMA trim workspace',
      featureBranch: 'feature/ecma-trim',
      rootPath: '/tmp/reqws-golden-workspace',
      workspaceFilePath: '/tmp/reqws-files/ecma-trim.code-workspace',
    });
    expect(parsed.repositories[0]).toMatchObject({
      catalogRepositoryId: 'repo_ecma_trim',
      name: 'ecma-trim',
      url: 'https://example.test/team/ecma-trim.git',
      defaultBranch: 'main',
      relativePath: 'ecma-trim',
    });
  });

  it.each([
    'invalid-duplicate-name.json',
    'invalid-relative-path.json',
    'invalid-non-ecma-trim-control-v1.json',
    'unsupported-v2.json',
  ])('rejects the shared invalid fixture %s', async (name) => {
    expect(
      workspaceManifestSchema.safeParse(await readFixture(name)).success,
    ).toBe(false);
  });
});
