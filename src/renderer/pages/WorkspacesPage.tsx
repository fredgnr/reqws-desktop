import { FolderX, MoreHorizontal, SearchX } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type { EditorAvailability, WorkspaceSummary } from '../../shared/types';
import { SearchField } from '../components/SearchField';
import { formatUpdatedAt, matchesWorkspace } from '../utils';

interface WorkspacesPageProps {
  workspaces: WorkspaceSummary[];
  repositoryCount: number;
  availability: EditorAvailability | null;
  search: string;
  loading: boolean;
  onSearch: (value: string) => void;
  onCreate: () => void;
  onDetails: (id: string) => void;
  onOpenVSCode: (id: string) => void;
  onOpenCursor: (id: string) => void;
  onOpenGoLand: (id: string) => void;
  launchingWorkspaceIds: ReadonlySet<string>;
}

export function WorkspacesPage({
  workspaces,
  repositoryCount,
  availability,
  search,
  loading,
  onSearch,
  onCreate,
  onDetails,
  onOpenVSCode,
  onOpenCursor,
  onOpenGoLand,
  launchingWorkspaceIds,
}: WorkspacesPageProps): React.JSX.Element {
  const { i18n, t } = useTranslation();
  const visible = workspaces.filter((workspace) => matchesWorkspace(workspace, search));
  const ready = workspaces.filter((workspace) => workspace.status === 'ready').length;
  const vscodeAvailable = availability?.vscode.available ?? false;
  const cursorAvailable = availability?.cursor.available ?? false;
  const golandAvailable = availability?.goland.available ?? false;
  const vscodeUnavailable = availability !== null && !vscodeAvailable;
  const cursorUnavailable = availability !== null && !cursorAvailable;
  const golandUnavailable = availability !== null && !golandAvailable;
  const unavailableEditors = [
    vscodeUnavailable ? 'Visual Studio Code' : undefined,
    cursorUnavailable ? 'Cursor' : undefined,
    golandUnavailable ? 'GoLand' : undefined,
  ].filter((editor): editor is string => Boolean(editor));
  const vscodeReason = t('common.editorNotFound', { editor: 'Visual Studio Code' });
  const cursorReason = t('common.editorNotFound', { editor: 'Cursor' });
  const golandReason = t('common.editorNotFound', { editor: 'GoLand' });
  const unavailableEditorList = new Intl.ListFormat(
    i18n.resolvedLanguage ?? i18n.language,
  ).format(unavailableEditors);

  return (
    <section className="page">
      <div className="summary-grid">
        <div className="summary-card">
          <div className="summary-label">{t('workspaces.summary.total.label')}</div>
          <div className="summary-value">{workspaces.length}</div>
          <div className="summary-foot">{t('workspaces.summary.total.description')}</div>
        </div>
        <div className="summary-card">
          <div className="summary-label">{t('workspaces.summary.ready.label')}</div>
          <div className="summary-value">{ready}</div>
          <div className="summary-foot">{t('workspaces.summary.ready.description')}</div>
        </div>
        <div className="summary-card">
          <div className="summary-label">{t('workspaces.summary.repositories.label')}</div>
          <div className="summary-value">{repositoryCount}</div>
          <div className="summary-foot">{t('workspaces.summary.repositories.description')}</div>
        </div>
      </div>
      {unavailableEditors.length > 0 && (
        <p className="muted" id="workspace-editor-availability">
          {t('workspaces.editorsUnavailable', {
            editors: unavailableEditorList,
          })}
        </p>
      )}
      <div className="panel">
        <div className="panel-toolbar">
          <SearchField
            label={t('workspaces.search.label')}
            onChange={onSearch}
            placeholder={t('workspaces.search.placeholder')}
            value={search}
          />
          <div className="result-count">{t('workspaces.resultCount', { count: visible.length })}</div>
        </div>
        {loading ? (
          <div className="loading"><span className="spinner" />{t('workspaces.loading')}</div>
        ) : visible.length > 0 ? (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: '21%' }}>{t('workspaces.table.workspace')}</th>
                  <th style={{ width: '20%' }}>{t('workspaces.table.repositories')}</th>
                  <th className="hide-compact" style={{ width: '22%' }}>{t('workspaces.table.rootPath')}</th>
                  <th style={{ width: '9%' }}>{t('workspaces.table.status')}</th>
                  <th style={{ width: '28%', textAlign: 'right' }}>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((workspace) => {
                  const editorLaunching = launchingWorkspaceIds.has(workspace.id);
                  return (
                    <tr key={workspace.id}>
                    <td>
                      <div className="workspace-name">{workspace.name}</div>
                      <div className="workspace-branch">{workspace.featureBranch}</div>
                      <div className="updated">{t('common.updatedAt', {
                        date: formatUpdatedAt(workspace.updatedAt, i18n.resolvedLanguage ?? i18n.language),
                      })}</div>
                    </td>
                    <td>
                      <div aria-label={t('common.repositoryCount', { count: workspace.repositoryNames.length })} className="tag-list">
                        {workspace.repositoryNames.slice(0, 3).map((name) => <span className="tag" key={name}>{name}</span>)}
                        {workspace.repositoryNames.length > 3 && <span className="tag more">+{workspace.repositoryNames.length - 3}</span>}
                      </div>
                      <div className="updated">{t('common.repositoryCount', { count: workspace.repositoryNames.length })}</div>
                    </td>
                    <td className="hide-compact"><div className="path" title={workspace.rootPath}>{workspace.rootPath}</div></td>
                    <td><span className={`status ${workspace.status}`}>{t(`common.status.${workspace.status}`)}</span></td>
                    <td>
                      <div className="row-actions">
                        <button
                          className="button small"
                          aria-describedby={vscodeUnavailable ? 'workspace-editor-availability' : undefined}
                          aria-busy={editorLaunching}
                          disabled={editorLaunching || workspace.status !== 'ready' || !vscodeAvailable}
                          onClick={() => onOpenVSCode(workspace.id)}
                          title={workspace.status !== 'ready' ? t('workspaces.pathIncomplete') : vscodeUnavailable ? vscodeReason : undefined}
                          type="button"
                        >VS Code</button>
                        <button
                          className="button small"
                          aria-describedby={cursorUnavailable ? 'workspace-editor-availability' : undefined}
                          aria-busy={editorLaunching}
                          disabled={editorLaunching || workspace.status !== 'ready' || !cursorAvailable}
                          onClick={() => onOpenCursor(workspace.id)}
                          title={workspace.status !== 'ready' ? t('workspaces.pathIncomplete') : cursorUnavailable ? cursorReason : undefined}
                          type="button"
                        >Cursor</button>
                        <button
                          className="button small"
                          aria-describedby={golandUnavailable ? 'workspace-editor-availability' : undefined}
                          aria-busy={editorLaunching}
                          disabled={editorLaunching || workspace.status !== 'ready' || !golandAvailable}
                          onClick={() => onOpenGoLand(workspace.id)}
                          title={workspace.status !== 'ready' ? t('workspaces.pathIncomplete') : golandUnavailable ? golandReason : undefined}
                          type="button"
                        >GoLand</button>
                        <button aria-label={t('workspaces.viewDetails', { name: workspace.name })} className="button small icon-only" onClick={() => onDetails(workspace.id)} type="button">
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
            {search ? <SearchX aria-hidden="true" className="empty-icon" size={32} /> : <FolderX aria-hidden="true" className="empty-icon" size={32} />}
            <h2 className="empty-title">{search ? t('workspaces.empty.noMatches.title') : t('workspaces.empty.initial.title')}</h2>
            <p className="empty-copy">{search ? t('workspaces.empty.noMatches.description') : t('workspaces.empty.initial.description')}</p>
            {!search && <button className="button primary" onClick={onCreate} type="button">{t('createWorkspace.submit')}</button>}
          </div>
        )}
      </div>
    </section>
  );
}
