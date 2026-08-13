import type { Repository, WorkspaceSummary } from './types';

function includesQuery(values: readonly string[], query: string): boolean {
  const needle = query.normalize('NFC').trim().toLocaleLowerCase();
  if (!needle) return true;
  return values
    .join(' ')
    .normalize('NFC')
    .toLocaleLowerCase()
    .includes(needle);
}

export function matchesRepository(repository: Repository, query: string): boolean {
  return includesQuery(
    [repository.name, repository.url, repository.defaultBranch],
    query,
  );
}

export function matchesWorkspace(
  workspace: WorkspaceSummary,
  query: string,
): boolean {
  return includesQuery(
    [
      workspace.name,
      workspace.featureBranch,
      workspace.rootPath,
      workspace.workspaceFilePath,
      ...workspace.repositoryNames,
    ],
    query,
  );
}
