import { constants as fsConstants } from 'node:fs';
import {
  copyFile,
  link,
  mkdir,
  open,
  readFile,
  rename,
  unlink,
  type FileHandle,
} from 'node:fs/promises';
import path from 'node:path';
import { randomBytes } from 'node:crypto';

import {
  ReqwsError,
  type ReqwsErrorCode,
} from '../../shared/errors';

export interface AtomicJsonStoreOptions<T> {
  defaultValue?: () => T;
  parse: (value: unknown) => T;
  readErrorCode?: ReqwsErrorCode;
  writeErrorCode?: ReqwsErrorCode;
  corruptErrorCode?: ReqwsErrorCode;
  now?: () => Date;
}

const DIRECTORY_MODE = 0o700;
const FILE_MODE = 0o600;
const TEMP_OPEN_ATTEMPTS = 4;

function errorDetail(error: unknown): string | undefined {
  if (error instanceof Error) return error.message;
  return typeof error === 'string' ? error : undefined;
}

function isNodeError(error: unknown, code: string): boolean {
  return (
    error instanceof Error &&
    'code' in error &&
    (error as NodeJS.ErrnoException).code === code
  );
}

function tempPathFor(targetPath: string): string {
  const token = randomBytes(12).toString('hex');
  return path.join(
    path.dirname(targetPath),
    `.${path.basename(targetPath)}.${process.pid}.${token}.tmp`,
  );
}

async function openUniqueTempFile(targetPath: string): Promise<{
  handle: FileHandle;
  tempPath: string;
}> {
  for (let attempt = 0; attempt < TEMP_OPEN_ATTEMPTS; attempt += 1) {
    const tempPath = tempPathFor(targetPath);
    try {
      return {
        handle: await open(tempPath, 'wx', FILE_MODE),
        tempPath,
      };
    } catch (error) {
      if (!isNodeError(error, 'EEXIST') || attempt === TEMP_OPEN_ATTEMPTS - 1) {
        throw error;
      }
    }
  }

  throw new Error('Unable to allocate an atomic-write temporary file.');
}

async function syncDirectory(directoryPath: string): Promise<void> {
  let handle: FileHandle | undefined;
  try {
    handle = await open(directoryPath, 'r');
    await handle.sync();
  } catch {
    // The file itself was already flushed. Directory fsync is best-effort on
    // platforms/filesystems that do not support opening directories.
  } finally {
    await handle?.close().catch(() => undefined);
  }
}

/**
 * Atomically replaces a UTF-8 file with a same-directory temporary file.
 * The temporary handle is flushed and closed before rename, and is removed on
 * every pre-rename failure path.
 */
export async function writeFileAtomically(
  targetPath: string,
  content: string,
): Promise<void> {
  const directoryPath = path.dirname(targetPath);
  await mkdir(directoryPath, { recursive: true, mode: DIRECTORY_MODE });

  const { handle, tempPath } = await openUniqueTempFile(targetPath);
  let handleOpen = true;
  let renamed = false;

  try {
    await handle.writeFile(content, { encoding: 'utf8' });
    await handle.sync();
    await handle.close();
    handleOpen = false;
    await rename(tempPath, targetPath);
    renamed = true;
    await syncDirectory(directoryPath);
  } finally {
    if (handleOpen) await handle.close().catch(() => undefined);
    if (!renamed) await unlink(tempPath).catch(() => undefined);
  }
}

/**
 * Atomically publishes a new file without replacing an existing path.
 * A same-directory, fully flushed temporary file is hard-linked into place;
 * `link` is atomic and fails with EEXIST if another writer won the race.
 */
export async function writeFileAtomicallyIfAbsent(
  targetPath: string,
  content: string,
): Promise<void> {
  const directoryPath = path.dirname(targetPath);
  await mkdir(directoryPath, { recursive: true, mode: DIRECTORY_MODE });

  const { handle, tempPath } = await openUniqueTempFile(targetPath);
  let handleOpen = true;
  try {
    await handle.writeFile(content, { encoding: 'utf8' });
    await handle.sync();
    await handle.close();
    handleOpen = false;
    await link(tempPath, targetPath);
    await syncDirectory(directoryPath);
  } finally {
    if (handleOpen) await handle.close().catch(() => undefined);
    await unlink(tempPath).catch(() => undefined);
  }
}

export async function writeJsonAtomically(
  targetPath: string,
  value: unknown,
): Promise<void> {
  await writeFileAtomically(targetPath, `${JSON.stringify(value, null, 2)}\n`);
}

export async function writeJsonAtomicallyIfAbsent(
  targetPath: string,
  value: unknown,
): Promise<void> {
  await writeFileAtomicallyIfAbsent(
    targetPath,
    `${JSON.stringify(value, null, 2)}\n`,
  );
}

function corruptSuffix(now: Date): string {
  return now.toISOString().replace(/[:.]/gu, '-');
}

/** Preserve invalid user data before surfacing the read error. */
export async function backupCorruptJsonFile(
  filePath: string,
  now = new Date(),
): Promise<string> {
  const baseBackupPath = `${filePath}.corrupt-${corruptSuffix(now)}`;
  let backupPath = baseBackupPath;

  for (let attempt = 0; attempt < TEMP_OPEN_ATTEMPTS; attempt += 1) {
    try {
      await copyFile(filePath, backupPath, fsConstants.COPYFILE_EXCL);
      return backupPath;
    } catch (error) {
      if (!isNodeError(error, 'EEXIST')) throw error;
      backupPath = `${baseBackupPath}-${randomBytes(4).toString('hex')}`;
    }
  }

  throw new Error('Unable to allocate a corrupt-file backup name.');
}

export class AtomicJsonStore<T> {
  constructor(
    readonly filePath: string,
    private readonly options: AtomicJsonStoreOptions<T>,
  ) {}

  async read(): Promise<T> {
    let source: string;
    try {
      source = await readFile(this.filePath, 'utf8');
    } catch (error) {
      if (isNodeError(error, 'ENOENT') && this.options.defaultValue) {
        return this.options.defaultValue();
      }
      throw new ReqwsError({
        code: this.options.readErrorCode ?? 'STATE_READ_FAILED',
        message: 'Unable to read JSON data.',
        detail: errorDetail(error),
      }, { cause: error });
    }

    try {
      return this.options.parse(JSON.parse(source) as unknown);
    } catch (error) {
      let backupPath: string | undefined;
      let backupError: unknown;
      try {
        backupPath = await backupCorruptJsonFile(
          this.filePath,
          this.options.now?.() ?? new Date(),
        );
      } catch (backupFailure) {
        backupError = backupFailure;
      }

      const details = [
        errorDetail(error),
        backupPath ? `Backup: ${backupPath}` : undefined,
        backupError
          ? `Backup failed: ${errorDetail(backupError) ?? 'unknown error'}`
          : undefined,
      ].filter((value): value is string => Boolean(value));

      throw new ReqwsError({
        code: this.options.corruptErrorCode ?? 'STATE_CORRUPT',
        message: 'JSON data is corrupt or has an unsupported format.',
        detail: details.join('\n') || undefined,
      }, { cause: error });
    }
  }

  async write(value: T): Promise<void> {
    let parsed: T;
    try {
      parsed = this.options.parse(value);
    } catch (error) {
      throw new ReqwsError({
        code: this.options.writeErrorCode ?? 'STATE_WRITE_FAILED',
        message: 'Refusing to write invalid JSON data.',
        detail: errorDetail(error),
      }, { cause: error });
    }

    try {
      await writeJsonAtomically(this.filePath, parsed);
    } catch (error) {
      throw new ReqwsError({
        code: this.options.writeErrorCode ?? 'STATE_WRITE_FAILED',
        message: 'Unable to write JSON data.',
        detail: errorDetail(error),
      }, { cause: error });
    }
  }
}
