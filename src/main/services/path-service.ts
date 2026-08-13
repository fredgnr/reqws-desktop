import { lstat, realpath } from 'node:fs/promises';
import path from 'node:path';

import { ReqwsError } from '../../shared/errors';
import {
  isValidRepositoryName,
  normalizeRepositoryName,
} from '../../shared/repository-utils';

function errorDetail(error: unknown): string | undefined {
  return error instanceof Error ? error.message : undefined;
}

function isNodeError(error: unknown, code: string): boolean {
  return (
    error instanceof Error &&
    'code' in error &&
    (error as NodeJS.ErrnoException).code === code
  );
}

function isContained(rootPath: string, candidatePath: string): boolean {
  const relative = path.relative(rootPath, candidatePath);
  return (
    relative !== '' &&
    relative !== '..' &&
    !relative.startsWith(`..${path.sep}`) &&
    !path.isAbsolute(relative)
  );
}

function isSameOrContained(rootPath: string, candidatePath: string): boolean {
  return rootPath === candidatePath || isContained(rootPath, candidatePath);
}

/**
 * Preserve an OS-managed top-level alias after resolving the rest of a path.
 *
 * macOS exposes paths such as /var while realpath reports /private/var. The
 * top-level alias lives directly under the filesystem root and is not mutable
 * by an unprivileged user, so it is safe to treat that prefix as canonical.
 * Symlinks below the first path component are deliberately not restored: they
 * must remain visible to the caller so containment and identity checks reject
 * them.
 */
async function restoreTopLevelAlias(
  inputPath: string,
  canonicalPath: string,
): Promise<string> {
  const root = path.parse(inputPath).root;
  const firstSegment = path.relative(root, inputPath).split(path.sep)[0];
  if (!firstSegment) return canonicalPath;

  const topLevelPath = path.join(root, firstSegment);
  try {
    const stat = await lstat(topLevelPath);
    if (!stat.isSymbolicLink()) return canonicalPath;

    const canonicalTopLevelPath = await realpath(topLevelPath);
    if (!isSameOrContained(canonicalTopLevelPath, canonicalPath)) {
      return canonicalPath;
    }
    return path.resolve(
      topLevelPath,
      path.relative(canonicalTopLevelPath, canonicalPath),
    );
  } catch {
    return canonicalPath;
  }
}

export function assertAbsolutePath(
  inputPath: string,
  label = 'Path',
): string {
  const normalized = inputPath.normalize('NFC').trim();
  if (!normalized || !path.isAbsolute(normalized)) {
    throw new ReqwsError({
      code: 'INVALID_INPUT',
      message: `${label} must be an absolute path.`,
    });
  }
  return path.resolve(normalized);
}

/**
 * Resolve every existing portion of a path through realpath. Non-existent tail
 * components are appended only after the nearest existing ancestor has been
 * canonicalized, so an intermediate symlink cannot disguise an escape.
 */
export async function resolveProspectiveRealPath(inputPath: string): Promise<string> {
  let cursor = assertAbsolutePath(inputPath);
  const missingSegments: string[] = [];

  while (true) {
    try {
      await lstat(cursor);
      break;
    } catch (error) {
      if (!isNodeError(error, 'ENOENT')) {
        throw new ReqwsError({
          code: 'INVALID_INPUT',
          message: 'Unable to inspect path.',
          detail: errorDetail(error),
        }, { cause: error });
      }
      const parent = path.dirname(cursor);
      if (parent === cursor) {
        throw new ReqwsError({
          code: 'INVALID_INPUT',
          message: 'Path has no accessible existing ancestor.',
        });
      }
      missingSegments.unshift(path.basename(cursor));
      cursor = parent;
    }
  }

  try {
    const canonical = path.resolve(await realpath(cursor), ...missingSegments);
    return await restoreTopLevelAlias(assertAbsolutePath(inputPath), canonical);
  } catch (error) {
    throw new ReqwsError({
      code: 'INVALID_INPUT',
      message: 'Unable to resolve path.',
      detail: errorDetail(error),
    }, { cause: error });
  }
}

export async function resolveRealParentPath(inputPath: string): Promise<string> {
  const targetPath = assertAbsolutePath(inputPath);
  const realParent = await resolveProspectiveRealPath(path.dirname(targetPath));
  return path.join(realParent, path.basename(targetPath));
}

/**
 * Bind a destination to its canonical parent chain. Missing tail directories
 * are allowed, but any existing symlink or non-canonical ancestor is rejected.
 */
export async function assertCanonicalParentPath(
  inputPath: string,
  label = 'Path',
): Promise<string> {
  const normalized = assertAbsolutePath(inputPath, label);
  const canonical = await resolveRealParentPath(normalized);
  if (canonical !== normalized) {
    throw new ReqwsError({
      code: 'INVALID_INPUT',
      message: `${label} parent must remain on its canonical path.`,
    });
  }
  return normalized;
}

export async function assertContainedPath(
  rootPath: string,
  candidatePath: string,
): Promise<string> {
  const normalizedRoot = assertAbsolutePath(rootPath, 'Root path');
  const normalizedCandidate = assertAbsolutePath(candidatePath, 'Candidate path');
  if (!isContained(normalizedRoot, normalizedCandidate)) {
    throw new ReqwsError({
      code: 'REPOSITORY_PATH_CONFLICT',
      message: 'Repository path must be contained by the workspace root.',
    });
  }

  const [realRoot, realCandidate] = await Promise.all([
    resolveProspectiveRealPath(normalizedRoot),
    resolveProspectiveRealPath(normalizedCandidate),
  ]);
  if (!isContained(realRoot, realCandidate)) {
    throw new ReqwsError({
      code: 'REPOSITORY_PATH_CONFLICT',
      message: 'Repository path escapes the workspace root through a symbolic link.',
    });
  }
  return normalizedCandidate;
}

export async function repositoryPath(
  rootPath: string,
  repositoryName: string,
): Promise<string> {
  const name = normalizeRepositoryName(repositoryName);
  if (!isValidRepositoryName(name)) {
    throw new ReqwsError({
      code: 'INVALID_REPOSITORY_NAME',
      message: 'Repository name is invalid.',
      repositoryName: name || repositoryName,
    });
  }

  const root = assertAbsolutePath(rootPath, 'Workspace root');
  return assertContainedPath(root, path.join(root, name));
}

async function assertRealDirectory(
  directoryPath: string,
  repositoryName?: string,
): Promise<void> {
  let stat: Awaited<ReturnType<typeof lstat>>;
  try {
    stat = await lstat(directoryPath);
  } catch (error) {
    throw new ReqwsError({
      code: 'REPOSITORY_PATH_CONFLICT',
      message: 'Repository is not a complete independent clone.',
      repositoryName,
      detail: errorDetail(error),
      stage: 'validating',
    }, { cause: error });
  }
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new ReqwsError({
      code: 'REPOSITORY_PATH_CONFLICT',
      message: 'Repository metadata must be a real directory.',
      repositoryName,
      stage: 'validating',
    });
  }
  if (
    await resolveProspectiveRealPath(directoryPath) !== path.resolve(directoryPath)
  ) {
    throw new ReqwsError({
      code: 'REPOSITORY_PATH_CONFLICT',
      message: 'Repository metadata escapes through a symbolic link.',
      repositoryName,
      stage: 'validating',
    });
  }
}

/** Reject worktrees, submodules, linked metadata and shared object stores. */
export async function assertIndependentGitRepository(
  rootPath: string,
  candidatePath: string,
  repositoryName?: string,
): Promise<string> {
  const target = await assertContainedPath(rootPath, candidatePath);
  await assertRealDirectory(target, repositoryName);

  const gitDirectory = path.join(target, '.git');
  await assertContainedPath(target, gitDirectory);
  await assertRealDirectory(gitDirectory, repositoryName);

  const objectsDirectory = path.join(gitDirectory, 'objects');
  await assertContainedPath(gitDirectory, objectsDirectory);
  await assertRealDirectory(objectsDirectory, repositoryName);

  for (const sharedMetadataPath of [
    path.join(gitDirectory, 'commondir'),
    path.join(objectsDirectory, 'info', 'alternates'),
  ]) {
    try {
      await lstat(sharedMetadataPath);
    } catch (error) {
      if (isNodeError(error, 'ENOENT')) continue;
      throw error;
    }
    throw new ReqwsError({
      code: 'REPOSITORY_PATH_CONFLICT',
      message: 'Repository uses shared Git metadata and is not independent.',
      repositoryName,
      stage: 'validating',
    });
  }
  return target;
}

export class PathService {
  assertAbsolutePath(inputPath: string, label?: string): string {
    return assertAbsolutePath(inputPath, label);
  }

  resolveRealParentPath(inputPath: string): Promise<string> {
    return resolveRealParentPath(inputPath);
  }

  assertCanonicalParentPath(inputPath: string, label?: string): Promise<string> {
    return assertCanonicalParentPath(inputPath, label);
  }

  assertContainedPath(rootPath: string, candidatePath: string): Promise<string> {
    return assertContainedPath(rootPath, candidatePath);
  }

  repositoryPath(rootPath: string, repositoryName: string): Promise<string> {
    return repositoryPath(rootPath, repositoryName);
  }


  assertIndependentGitRepository(
    rootPath: string,
    candidatePath: string,
    repositoryName?: string,
  ): Promise<string> {
    return assertIndependentGitRepository(rootPath, candidatePath, repositoryName);
  }
}
