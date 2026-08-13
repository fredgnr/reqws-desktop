import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { EditorAvailability, Repository, WorkspaceDetail } from '../../shared/types';
import { CloseButton, Dialog } from './Dialog';
import { ErrorNotice } from './ErrorNotice';
import { formatUpdatedAt } from '../utils';

interface WorkspaceDetailDrawerProps {
  workspace: WorkspaceDetail;
  repositories: Repository[];
  availability: EditorAvailability | null;
  busy: boolean;
  onClose: () => void;
  onOpenVSCode: () => void;
  onOpenCursor: () => void;
  onOpenCursorRoot: () => void;
  onRevealFinder: () => void;
  onAddRepository: (repositoryId: string) => void;
  onRemoveRepository: (repository: WorkspaceDetail['repositories'][number]) => void;
  onSync: () => void;
  onForget: () => void;
}

export function WorkspaceDetailDrawer({
  workspace,
  repositories,
  availability,
  busy,
  onClose,
  onOpenVSCode,
  onOpenCursor,
  onOpenCursorRoot,
  onRevealFinder,
  onAddRepository,
  onRemoveRepository,
  onSync,
  onForget,
}: WorkspaceDetailDrawerProps): React.JSX.Element {
  const { i18n, t } = useTranslation();
  const availableRepositories = repositories.filter((repository) => (
    !workspace.repositories.some((item) => item.catalogRepositoryId === repository.id)
  ));
  const [repositoryId, setRepositoryId] = useState(availableRepositories[0]?.id ?? '');
  const ready = workspace.status === 'ready';
  const vscodeAvailable = availability?.vscode.available ?? false;
  const cursorAvailable = availability?.cursor.available ?? false;
  const vscodeReason = !ready
    ? t('workspaces.pathIncomplete')
    : t('common.editorNotFound', { editor: 'Visual Studio Code' });
  const cursorReason = !ready
    ? t('workspaces.pathIncomplete')
    : t('common.editorNotFound', { editor: 'Cursor' });
  const unavailableEditors = [
    !vscodeAvailable ? 'Visual Studio Code' : undefined,
    !cursorAvailable ? 'Cursor' : undefined,
  ].filter((editor): editor is string => Boolean(editor));
  const unavailableEditorList = new Intl.ListFormat(
    i18n.resolvedLanguage ?? i18n.language,
  ).format(unavailableEditors);

  return (
    <Dialog dismissible={!busy} drawer onClose={onClose} titleId="workspace-detail-title">
      <div className="drawer-header">
        <div>
          <div className="drawer-title-row">
            <h2 className="drawer-title" id="workspace-detail-title">{workspace.name}</h2>
            <span className={`status ${workspace.status}`}>{t(`common.status.${workspace.status}`)}</span>
          </div>
          <div className="drawer-branch">{workspace.featureBranch}</div>
        </div>
        {!busy && <CloseButton onClick={onClose} />}
      </div>
      <div className="drawer-body">
        <div className="detail-actions">
          <button aria-describedby={!ready || !vscodeAvailable ? 'workspace-editor-status' : undefined} className="button primary" disabled={!ready || !vscodeAvailable} onClick={onOpenVSCode} title={!ready || !vscodeAvailable ? vscodeReason : undefined} type="button">VS Code</button>
          <button aria-describedby={!ready || !cursorAvailable ? 'workspace-editor-status' : undefined} className="button" disabled={!ready || !cursorAvailable} onClick={onOpenCursor} title={!ready || !cursorAvailable ? cursorReason : undefined} type="button">Cursor</button>
          <button aria-describedby={!ready || !cursorAvailable ? 'workspace-editor-status' : undefined} className="button" disabled={!ready || !cursorAvailable} onClick={onOpenCursorRoot} title={!ready || !cursorAvailable ? cursorReason : undefined} type="button">{t('workspaceDetail.openCursorRoot')}</button>
          <button className="button" onClick={onRevealFinder} type="button">{t('workspaceDetail.revealInFinder')}</button>
        </div>
        {(!ready || !vscodeAvailable || !cursorAvailable) && (
          <p className="muted" id="workspace-editor-status">
            {!ready
              ? t('workspaceDetail.editorDisabledPathIncomplete')
              : t('workspaceDetail.editorsUnavailable', {
                  editors: unavailableEditorList,
                })}
          </p>
        )}
        <div className="detail-grid">
          <div className="detail-label">{t('workspaceDetail.rootPath')}</div>
          <div className="detail-value" title={workspace.rootPath}>{workspace.rootPath}</div>
          <div className="detail-label">{t('workspaceDetail.workspaceFile')}</div>
          <div className="detail-value" title={workspace.workspaceFilePath}>{workspace.workspaceFilePath}</div>
          <div className="detail-label">{t('workspaceDetail.updatedAt')}</div>
          <div className="detail-value">{formatUpdatedAt(workspace.updatedAt, i18n.resolvedLanguage ?? i18n.language)}</div>
        </div>
        {workspace.status === 'missing' && <div className="notice warning">{t('workspaceDetail.pathsMissing')}</div>}
        {workspace.status === 'error' && !workspace.lastError && <div className="notice warning">{t('workspaceDetail.statusError')}</div>}
        {workspace.lastError && <ErrorNotice error={workspace.lastError} />}
        <div className="notice">{t('workspaceDetail.managedFileNotice')}</div>
        {workspace.status !== 'ready' && (
          <div className="detail-actions" style={{ marginTop: 12 }}>
            <button className="button" disabled={busy} onClick={onSync} type="button">{t('workspaceDetail.syncAndRestore')}</button>
          </div>
        )}

        <div className="section-title-row">
          <div className="section-title">{t('workspaceDetail.repositories.count', { count: workspace.repositories.length })}</div>
          <div className="section-meta">{t('workspaceDetail.repositories.branch', { branch: workspace.featureBranch })}</div>
        </div>
        <div className="repo-manage-list">
          {workspace.repositories.length > 0 ? workspace.repositories.map((repository) => (
            <div className="repo-manage-row" key={repository.catalogRepositoryId}>
              <div>
                <div className="repo-manage-title">{repository.name}</div>
                <div className="repo-manage-path">{workspace.rootPath}/{repository.relativePath}</div>
              </div>
              <button className="button small danger" disabled={busy} onClick={() => onRemoveRepository(repository)} type="button">{t('common.remove')}</button>
            </div>
          )) : <div className="empty-state">{t('workspaceDetail.repositories.empty')}</div>}
        </div>
        <div className="notice warning">{t('workspaceDetail.repositories.removeNotice')}</div>

        <div className="section-title-row">
          <div className="section-title">{t('workspaceDetail.addRepository.title')}</div>
          <div className="section-meta">{t('workspaceDetail.addRepository.description')}</div>
        </div>
        <div className="add-inline">
          <label className="sr-only" htmlFor="add-workspace-repository">{t('workspaceDetail.addRepository.label')}</label>
          <select className="field-select" disabled={busy || availableRepositories.length === 0} id="add-workspace-repository" onChange={(event) => setRepositoryId(event.target.value)} value={repositoryId}>
            {availableRepositories.length > 0
              ? availableRepositories.map((repository) => <option key={repository.id} value={repository.id}>{repository.name} · {repository.defaultBranch}</option>)
              : <option value="">{t('workspaceDetail.addRepository.empty')}</option>}
          </select>
          <button className="button" disabled={busy || !repositoryId || !availability?.git.available} onClick={() => onAddRepository(repositoryId)} title={!availability?.git.available ? t('common.gitNotFound') : undefined} type="button">＋ {t('common.add')}</button>
        </div>

        <div className="danger-zone">
          <div className="section-title">{t('workspaceDetail.forget.title')}</div>
          <p className="muted">{t('workspaceDetail.forget.description')}</p>
          <button className="button danger" disabled={busy} onClick={onForget} type="button">{t('workspaceDetail.forget.action')}</button>
        </div>
      </div>
    </Dialog>
  );
}
