export function deriveRepositoryName(url: string): string {
  const normalized = url.trim().replace(/\/+$/, '');
  const segment = normalized.split(/[/:]/).pop() ?? '';
  return segment.replace(/\.git$/i, '');
}

export function workspaceSlug(value: string): string {
  return value
    .trim()
    .replace(/[^A-Za-z0-9._-]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'feature-workspace';
}

export function defaultFeatureBranch(name: string): string {
  return `feature/${workspaceSlug(name)}`;
}

export function matchesRepository(
  repository: { name: string; url: string; defaultBranch: string },
  query: string,
): boolean {
  return [repository.name, repository.url, repository.defaultBranch]
    .join(' ')
    .toLocaleLowerCase()
    .includes(query.trim().toLocaleLowerCase());
}

export function matchesWorkspace(
  workspace: {
    name: string;
    featureBranch: string;
    rootPath: string;
    workspaceFilePath: string;
    repositoryNames: string[];
  },
  query: string,
): boolean {
  return [
    workspace.name,
    workspace.featureBranch,
    workspace.rootPath,
    workspace.workspaceFilePath,
    ...workspace.repositoryNames,
  ].join(' ').toLocaleLowerCase().includes(query.trim().toLocaleLowerCase());
}

export function formatUpdatedAt(value: string, locale: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return value;
  return new Intl.DateTimeFormat(locale, {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(date);
}
