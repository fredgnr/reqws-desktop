import { constants } from 'node:fs';
import { link, lstat, open, realpath, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { z } from 'zod';

export const linkNames = ['selection', 'trust', 'invalid-binding', 'invalid-manifest'] as const;
const envelope = {
  schemaVersion: z.literal(1),
  sessionId: z.uuid(),
  sequence: z.number().int().positive(),
};
export const linkRequestSchema = z.discriminatedUnion('operation', [
  z.object({ ...envelope, operation: z.literal('create'), name: z.enum(linkNames) }).strict(),
  z.object({ ...envelope, operation: z.literal('select'), name: z.enum(linkNames),
    selected: z.array(z.enum(['repo-a', 'repo-b'])).max(2).refine((items) => new Set(items).size === items.length),
  }).strict(),
  z.object({ ...envelope, operation: z.literal('finish') }).strict(),
]);
export type LinkRequest = z.infer<typeof linkRequestSchema>;

export async function readLinkJson(filename: string): Promise<unknown> {
  const file = await open(filename, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.size > 64 * 1024) throw new Error('Unsafe or oversized Desktop/IDE message.');
    return JSON.parse(await file.readFile('utf8')) as unknown;
  } finally { await file.close(); }
}

export async function publishLinkJson(filename: string, value: unknown): Promise<void> {
  const temporary = `${filename}.${process.pid}.tmp`;
  await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
  try { await link(temporary, filename); } finally { await unlink(temporary); }
}

export async function openDesktopLink(): Promise<{ root: string; directory: string; sessionId: string }> {
  if (process.platform !== 'darwin' || ['CI', 'GITHUB_ACTIONS', 'TEAMCITY_VERSION', 'JENKINS_URL', 'BUILD_BUILDID']
    .some((key) => !['', '0', 'false'].includes((process.env[key] ?? '').toLowerCase()))) {
    throw new Error('Desktop/IDE integration requires the local macOS launcher.');
  }
  const root = process.env.REQWS_LOCAL_IDE_RUN_ROOT;
  const sessionId = process.env.REQWS_DESKTOP_IDE_SESSION;
  if (!root || !path.isAbsolute(root) || await realpath(root) !== root || (await lstat(root)).isSymbolicLink()) {
    throw new Error('A canonical private local run root is required.');
  }
  const directory = path.join(root, 'desktop-link');
  if ((await lstat(directory)).isSymbolicLink() || await realpath(directory) !== directory) {
    throw new Error('Desktop/IDE messages must stay in their private directory.');
  }
  const session = z.object({ schemaVersion: z.literal(1), purpose: z.literal('reqws-desktop-ide-link'),
    sessionId: z.uuid(),
  }).strict().parse(await readLinkJson(path.join(directory, 'session.json')));
  if (session.sessionId !== sessionId) throw new Error('Desktop/IDE session identity mismatch.');
  return { root, directory, sessionId };
}

export async function waitLinkRequest(directory: string, sessionId: string, sequence: number, timeoutMs: number): Promise<LinkRequest> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const name of ['abort.json', `request-${sequence}.json`]) {
      let value: unknown;
      try { value = await readLinkJson(path.join(directory, name)); }
      catch (error) {
        if (error instanceof Error && 'code' in error && error.code === 'ENOENT') continue;
        throw error;
      }
      if (name === 'abort.json') throw new Error('The local IDE coordinator aborted this session.');
      const request = linkRequestSchema.parse(value);
      if (request.sessionId !== sessionId || request.sequence !== sequence) throw new Error('Stale or reordered Desktop/IDE request.');
      return request;
    }
    await delay(100);
  }
  throw new Error(`Desktop/IDE request ${sequence} timed out.`);
}
