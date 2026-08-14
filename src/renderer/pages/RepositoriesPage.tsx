import { FolderGit2, MoreHorizontal, SearchX } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { Repository, RepositoryListItem } from '../../shared/types';
import { SearchField } from '../components/SearchField';
import { matchesRepository } from '../utils';

interface RepositoriesPageProps {
  repositories: RepositoryListItem[];
  gitAvailable: boolean;
  search: string;
  loading: boolean;
  testingId: string | null;
  onSearch: (value: string) => void;
  onCreate: () => void;
  onEdit: (repository: Repository) => void;
  onTest: (repository: Repository) => void;
}

export function RepositoriesPage({
  repositories,
  gitAvailable,
  search,
  loading,
  testingId,
  onSearch,
  onCreate,
  onEdit,
  onTest,
}: RepositoriesPageProps): React.JSX.Element {
  const { t } = useTranslation();
  const visible = repositories.filter((repository) => matchesRepository(repository, search));
  const inUse = repositories.filter((repository) => repository.workspaceUsageCount > 0).length;

  return (
    <section className="page">
      <div className="summary-grid">
        <div className="summary-card">
          <div className="summary-label">{t('repositories.summary.total.label')}</div>
          <div className="summary-value">{repositories.length}</div>
          <div className="summary-foot">{t('repositories.summary.total.description')}</div>
        </div>
        <div className="summary-card">
          <div className="summary-label">{t('repositories.summary.inUse.label')}</div>
          <div className="summary-value">{inUse}</div>
          <div className="summary-foot">{t('repositories.summary.inUse.description')}</div>
        </div>
        <div className="summary-card">
          <div className="summary-label">Git</div>
          <div className="summary-value compact">{gitAvailable ? t('common.available') : t('common.unavailable')}</div>
          <div className="summary-foot">
            {gitAvailable ? t('repositories.summary.git.available') : t('repositories.summary.git.unavailable')}
          </div>
        </div>
      </div>
      {!gitAvailable && <div className="notice warning">{t('repositories.gitUnavailable')}</div>}
      <div className="panel">
        <div className="panel-toolbar">
          <SearchField label={t('repositories.search.label')} onChange={onSearch} placeholder={t('repositories.search.placeholder')} value={search} />
          <div className="result-count">{t('repositories.resultCount', { count: visible.length })}</div>
        </div>
        {loading ? (
          <div className="loading"><span className="spinner" />{t('repositories.loading')}</div>
        ) : visible.length > 0 ? (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: '20%' }}>{t('repositories.table.name')}</th>
                  <th style={{ width: '42%' }}>{t('repositories.table.url')}</th>
                  <th style={{ width: '13%' }}>{t('repositories.table.defaultBranch')}</th>
                  <th style={{ width: '10%' }}>{t('repositories.table.usage')}</th>
                  <th style={{ width: '15%', textAlign: 'right' }}>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((repository) => {
                  const used = repository.workspaceUsageCount;
                  return (
                    <tr key={repository.id}>
                      <td><div className="workspace-name">{repository.name}</div></td>
                      <td><div className="path" title={repository.url}>{repository.url}</div></td>
                      <td><span className="branch-pill">{repository.defaultBranch}</span></td>
                      <td><span className="muted">{t('repositories.workspaceUsageCount', { count: used })}</span></td>
                      <td>
                        <div className="row-actions">
                          <button
                            className="button small"
                            disabled={!gitAvailable || testingId !== null}
                            onClick={() => onTest(repository)}
                            title={!gitAvailable ? t('common.gitNotFound') : undefined}
                            type="button"
                          >{testingId === repository.id ? t('repositoryDialog.test.testing') : t('repositories.test')}</button>
                          <button aria-label={t('repositories.edit', { name: repository.name })} className="button small icon-only" onClick={() => onEdit(repository)} type="button">
                            <MoreHorizontal aria-hidden="true" size={16} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="empty-state">
            {search ? <SearchX aria-hidden="true" className="empty-icon" size={32} /> : <FolderGit2 aria-hidden="true" className="empty-icon" size={32} />}
            <h2 className="empty-title">{search ? t('repositories.empty.noMatches.title') : t('repositories.empty.initial.title')}</h2>
            <p className="empty-copy">{search ? t('repositories.empty.noMatches.description') : t('repositories.empty.initial.description')}</p>
            {!search && <button className="button primary" onClick={onCreate} type="button">{t('repositoryDialog.createTitle')}</button>}
          </div>
        )}
      </div>
    </section>
  );
}
