import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

import { ReqwsError } from '../../src/shared/errors';
import {
  AtomicJsonStore,
  writeFileAtomicallyIfAbsent,
} from '../../src/main/services/atomic-json-store';

const temporaryDirectories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'reqws-json-'));
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

describe('AtomicJsonStore', () => {
  it('returns a fresh default without creating a file', async () => {
    const directory = await temporaryDirectory();
    const store = new AtomicJsonStore(path.join(directory, 'state.json'), {
      defaultValue: () => ({ values: [] as string[] }),
      parse: (value) => value as { values: string[] },
    });

    const first = await store.read();
    first.values.push('local mutation');
    expect(await store.read()).toEqual({ values: [] });
    expect(await readdir(directory)).toEqual([]);
  });

  it('writes formatted JSON atomically with a private file mode', async () => {
    const directory = await temporaryDirectory();
    const filePath = path.join(directory, 'state.json');
    const store = new AtomicJsonStore(filePath, {
      parse: (value) => value as { answer: number },
    });

    await store.write({ answer: 42 });

    expect(await readFile(filePath, 'utf8')).toBe('{\n  "answer": 42\n}\n');
    expect((await stat(filePath)).mode & 0o777).toBe(0o600);
    expect((await readdir(directory)).filter((name) => name.endsWith('.tmp'))).toEqual([]);
  });

  it('backs up corrupt input and never replaces the original', async () => {
    const directory = await temporaryDirectory();
    const filePath = path.join(directory, 'state.json');
    await writeFile(filePath, '{ definitely invalid', 'utf8');
    const store = new AtomicJsonStore(filePath, {
      parse: (value) => value,
      now: () => new Date('2026-08-12T10:11:12.345Z'),
    });

    const error = await store.read().catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(ReqwsError);
    expect((error as ReqwsError).code).toBe('STATE_CORRUPT');
    expect(await readFile(filePath, 'utf8')).toBe('{ definitely invalid');
    const backup = (await readdir(directory)).find((name) =>
      name.startsWith('state.json.corrupt-2026-08-12T10-11-12-345Z'),
    );
    expect(backup).toBeDefined();
    expect(await readFile(path.join(directory, backup as string), 'utf8')).toBe(
      '{ definitely invalid',
    );
  });

  it('publishes a new file atomically and never replaces an existing path', async () => {
    const directory = await temporaryDirectory();
    const filePath = path.join(directory, 'workspace.json');

    await writeFileAtomicallyIfAbsent(filePath, 'first');
    await expect(writeFileAtomicallyIfAbsent(filePath, 'second')).rejects.toMatchObject({
      code: 'EEXIST',
    });

    expect(await readFile(filePath, 'utf8')).toBe('first');
    expect((await readdir(directory)).filter((name) => name.endsWith('.tmp'))).toEqual([]);
  });
});
