import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { z } from 'zod';

export const releaseVersionSchema = z.string().regex(/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/u);
const digestSchema = z.string().regex(/^[A-Za-z0-9+/]{86}==$/u);
const metadataSchema = z.strictObject({
  version: releaseVersionSchema,
  files: z.array(z.strictObject({
    url: z.string(), sha512: digestSchema, size: z.number().int().positive().safe(),
  })).length(1),
  path: z.string(),
  sha512: digestSchema,
  releaseDate: z.iso.datetime(),
});

export async function fileDigest(filename: string, algorithm: 'sha256' | 'sha512') {
  const hash = createHash(algorithm);
  for await (const chunk of createReadStream(filename)) hash.update(chunk as Buffer);
  return hash.digest(algorithm === 'sha512' ? 'base64' : 'hex');
}

export async function createUpdateMetadata(directory: string, version: string, now = new Date()) {
  releaseVersionSchema.parse(version);
  const filename = `ReqWS-${version}-macos-arm64.zip`;
  const archive = path.join(directory, filename);
  const status = await lstat(archive);
  if (!status.isFile() || status.isSymbolicLink() || status.size === 0) {
    throw new Error('The update ZIP must be a non-empty regular file.');
  }
  const sha512 = await fileDigest(archive, 'sha512');
  return metadataSchema.parse({
    version,
    files: [{ url: filename, sha512, size: status.size }],
    path: filename,
    sha512,
    releaseDate: now.toISOString(),
  });
}

export async function verifyUpdateMetadata(directory: string, version: string, contents: unknown) {
  const metadata = metadataSchema.parse(contents);
  const actual = await createUpdateMetadata(directory, version);
  if (metadata.version !== version || metadata.path !== actual.path
    || metadata.files[0]!.url !== actual.path
    || metadata.sha512 !== actual.sha512 || metadata.files[0]!.sha512 !== actual.sha512
    || metadata.files[0]!.size !== actual.files[0]!.size) {
    throw new Error('Update metadata does not match the final arm64 Desktop ZIP.');
  }
  return metadata;
}

export async function prepareMacosUpdate(args: string[]) {
  const [mode, directory, version] = args;
  if (args.length !== 3 || !directory || !version || !['--write', '--verify'].includes(mode ?? '')) {
    throw new Error('Usage: prepare-macos-update.mjs --write|--verify DIRECTORY VERSION');
  }
  const target = path.join(directory, 'latest-mac.yml');
  if (mode === '--write') {
    const metadata = await createUpdateMetadata(directory, version);
    // Never silently overwrite metadata for a different set of release bytes.
    await writeFile(target, `${JSON.stringify(metadata, null, 2)}\n`, { flag: 'wx' });
  }
  await verifyUpdateMetadata(directory, version, JSON.parse(await readFile(target, 'utf8')) as unknown);
}
