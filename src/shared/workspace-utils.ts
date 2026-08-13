import path from 'node:path';
import type {
  CodeWorkspaceFile,
  WorkspaceManifest,
  WorkspaceRepository,
} from './types';

export function workspaceSlug(value: string): string {
  return (
    value
      .normalize('NFC')
      .trim()
      .replace(/[^A-Za-z0-9._-]+/gu, '-')
      .replace(/^-+|-+$/gu, '') || 'feature-workspace'
  );
}

export function workspaceFileName(name: string): string {
  return `${workspaceSlug(name)}.code-workspace`;
}

export function buildCodeWorkspace(
  rootPath: string,
  repositories: readonly WorkspaceRepository[],
): CodeWorkspaceFile {
  return {
    folders: repositories.map((repository) => ({
      name: repository.name,
      path: path.resolve(rootPath, repository.relativePath),
    })),
    extensions: { recommendations: ['golang.go'] },
  };
}

export function addManifestRepository(
  manifest: WorkspaceManifest,
  repository: WorkspaceRepository,
  updatedAt: string,
): WorkspaceManifest {
  if (
    manifest.repositories.some(
      (entry) => entry.catalogRepositoryId === repository.catalogRepositoryId,
    )
  ) {
    return manifest;
  }
  return {
    ...manifest,
    repositories: [...manifest.repositories, repository],
    updatedAt,
  };
}

export function removeManifestRepository(
  manifest: WorkspaceManifest,
  catalogRepositoryId: string,
  updatedAt: string,
): WorkspaceManifest {
  return {
    ...manifest,
    repositories: manifest.repositories.filter(
      (entry) => entry.catalogRepositoryId !== catalogRepositoryId,
    ),
    updatedAt,
  };
}
