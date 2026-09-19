import { useRef, useState } from 'react';
import { ChevronDown, ChevronRight, Plus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import type {
  EditorAvailability,
  Repository,
  WorkspaceArtifact,
  WorkspaceDetail,
} from '../../shared/types';
import { CloseButton, Dialog } from './Dialog';
import { GoLandLoadingSection } from './GoLandLoadingSection';
import { ErrorNotice } from './ErrorNotice';
import { formatUpdatedAt } from '../utils';

const missingArtifactMessageKeys: Record<WorkspaceArtifact, string> = {
  'workspace-root': 'workspaceDetail.missingArtifacts.workspace-root',
  manifest: 'workspaceDetail.missingArtifacts.manifest',
  'workspace-file': 'workspaceDetail.missingArtifacts.workspace-file',
};

interface WorkspaceDetailDrawerProps {
  workspace: WorkspaceDetail;
  repositories: Repository[];
  availability: EditorAvailability | null;
  busy: boolean;
  editorLaunching: boolean;
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
  editorLaunching,
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
  const cursorMenuRef = useRef<HTMLDetailsElement>(null);
  const pathParts = workspace.rootPath.split('/').filter(Boolean);
  const pathPreview = pathParts.length > 2 ? `…/${pathParts.slice(-2).join('/')}` : workspace.rootPath;
  const availableRepositories = repositories.filter((repository) => (
    !workspace.repositories.some((item) => item.catalogRepositoryId === repository.id)
  ));
  const [selectedRepositoryId, setSelectedRepositoryId] = useState('');
  const repositoryId = availableRepositories.some((repository) => repository.id === selectedRepositoryId)
    ? selectedRepositoryId
    : availableRepositories[0]?.id ?? '';
  const ready = workspace.status === 'ready';
  const vscodeAvailable = availability?.vscode.available ?? false;
  const cursorAvailable = availability?.cursor.available ?? false;
  const golandAvailable = availability?.goland.available ?? false;
  const gitAvailable = availability?.git.available ?? false;
  const vscodeUnavailable = availability !== null && !vscodeAvailable;
  const cursorUnavailable = availability !== null && !cursorAvailable;
  const golandUnavailable = availability !== null && !golandAvailable;
  const gitUnavailable = availability !== null && !gitAvailable;
  const vscodeReason = !ready
    ? t('workspaces.pathIncomplete')
    : vscodeUnavailable
      ? t('common.editorNotFound', { editor: 'Visual Studio Code' })
      : undefined;
  const cursorReason = !ready
    ? t('workspaces.pathIncomplete')
    : cursorUnavailable
      ? t('common.editorNotFound', { editor: 'Cursor' })
      : undefined;
  const unavailableEditors = [
    vscodeUnavailable ? 'Visual Studio Code' : undefined,
    cursorUnavailable ? 'Cursor' : undefined,
    golandUnavailable ? 'GoLand' : undefined,
  ].filter((editor): editor is string => Boolean(editor));
  const unavailableEditorList = new Intl.ListFormat(
    i18n.resolvedLanguage ?? i18n.language,
  ).format(unavailableEditors);
  const missingArtifactLabels = (workspace.missingArtifacts ?? []).map(
    (artifact) => {
      const messageKey = missingArtifactMessageKeys[artifact];
      return messageKey && i18n.exists(messageKey) ? t(messageKey) : artifact;
    },
  );
  const missingArtifactList = new Intl.ListFormat(
    i18n.resolvedLanguage ?? i18n.language,
  ).format(missingArtifactLabels);

  return (
    <Dialog className="workspace-detail-drawer" dismissible={!busy} drawer onClose={onClose} titleId="workspace-detail-title">
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
      <div className="workspace-launchers">
        <div className="detail-actions">
          <button aria-busy={editorLaunching} aria-describedby={!ready || vscodeUnavailable ? 'workspace-editor-status' : undefined} className="button primary" disabled={editorLaunching || !ready || !vscodeAvailable} onClick={onOpenVSCode} title={vscodeReason} type="button">VS Code</button>
          <details className="editor-menu" ref={cursorMenuRef} onBlur={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget)) event.currentTarget.open = false;
          }} onKeyDown={(event) => {
            if (event.key === 'Escape' && event.currentTarget.open) {
              event.preventDefault();
              event.stopPropagation();
              event.currentTarget.open = false;
              event.currentTarget.querySelector('summary')?.focus();
            }
          }}>
            <summary aria-busy={editorLaunching} aria-describedby={!ready || cursorUnavailable ? 'workspace-editor-status' : undefined} aria-disabled={editorLaunching || !ready || !cursorAvailable} className="button" onClick={(event) => {
              if (editorLaunching || !ready || !cursorAvailable) event.preventDefault();
            }} role="button" tabIndex={editorLaunching || !ready || !cursorAvailable ? -1 : 0} title={cursorReason}>
              Cursor <ChevronDown aria-hidden="true" size={14} />
            </summary>
            <div className="editor-menu-options">
              <button disabled={editorLaunching || !ready || !cursorAvailable} onClick={() => { if (cursorMenuRef.current) cursorMenuRef.current.open = false; onOpenCursor(); }} type="button">{t('workspaceDetail.openCursorWorkspace')}</button>
              <button disabled={editorLaunching || !ready || !cursorAvailable} onClick={() => { if (cursorMenuRef.current) cursorMenuRef.current.open = false; onOpenCursorRoot(); }} type="button">{t('workspaceDetail.openCursorRoot')}</button>
            </div>
          </details>
          <button aria-busy={editorLaunching} className="button" disabled={editorLaunching} onClick={onRevealFinder} type="button">{t('workspaceDetail.revealInFinder')}</button>
        </div>
        {(!ready || vscodeUnavailable || cursorUnavailable || golandUnavailable) && (
          <p className="muted" id="workspace-editor-status">
            {!ready
              ? t('workspaceDetail.editorDisabledPathIncomplete')
              : t('workspaceDetail.editorsUnavailable', {
                  editors: unavailableEditorList,
                })}
          </p>
        )}
        {workspace.status === 'missing' && (
          <div className="notice warning">
            {missingArtifactList && i18n.exists('workspaceDetail.pathsMissingDetail')
              ? t('workspaceDetail.pathsMissingDetail', {
                  artifacts: missingArtifactList,
                })
              : t('workspaceDetail.pathsMissing')}
          </div>
        )}
        {workspace.status === 'error' && !workspace.lastError && <div className="notice warning">{t('workspaceDetail.statusError')}</div>}
        {workspace.lastError && <ErrorNotice error={workspace.lastError} />}
        {workspace.status !== 'ready' && (
          <div className="detail-actions" style={{ marginTop: 12 }}>
            <button className="button" disabled={busy} onClick={onSync} type="button">{t('workspaceDetail.syncAndRestore')}</button>
          </div>
        )}

      </div>
      <div className="drawer-body workspace-detail-body">
        <GoLandLoadingSection available={golandAvailable} busy={busy || editorLaunching} key={`${workspace.id}:${workspace.status}`} workspace={workspace}>
          <details className="workspace-disclosure workspace-files">
            <summary>
              <ChevronRight aria-hidden="true" size={17} />
              <span>
                <strong>{t('workspaceDetail.filesTitle')}</strong>
                <span className="workspace-path-preview" title={workspace.rootPath}>{pathPreview}</span>
                <span className="workspace-disclosure-hint">{t('workspaceDetail.filesSummary')}</span>
              </span>
            </summary>
            <div className="workspace-disclosure-content">
              <div className="detail-grid">
                <div className="detail-label">{t('workspaceDetail.rootPath')}</div>
                <div className="detail-value" title={workspace.rootPath}>{workspace.rootPath}</div>
                <div className="detail-label">{t('workspaceDetail.workspaceFile')}</div>
                <div className="detail-value" title={workspace.workspaceFilePath}>{workspace.workspaceFilePath}</div>
                <div className="detail-label">{t('workspaceDetail.updatedAt')}</div>
                <div className="detail-value">{formatUpdatedAt(workspace.updatedAt, i18n.resolvedLanguage ?? i18n.language)}</div>
              </div>
              <p className="muted">{t('workspaceDetail.managedFileNotice')}</p>
            </div>
          </details>
          <details className="workspace-disclosure workspace-management">
            <summary><ChevronRight aria-hidden="true" size={17} /><strong>{t('workspaceDetail.manageTitle')}</strong></summary>
            <div className="workspace-disclosure-content">

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
                <select className="field-select" disabled={busy || availableRepositories.length === 0} id="add-workspace-repository" onChange={(event) => setSelectedRepositoryId(event.target.value)} value={repositoryId}>
                  {availableRepositories.length > 0
                    ? availableRepositories.map((repository) => <option key={repository.id} value={repository.id}>{repository.name} · {repository.defaultBranch}</option>)
                    : <option value="">{t('workspaceDetail.addRepository.empty')}</option>}
                </select>
                <button className="button" disabled={busy || !repositoryId || !gitAvailable} onClick={() => { if (repositoryId) onAddRepository(repositoryId); }} title={gitUnavailable ? t('common.gitNotFound') : undefined} type="button"><Plus aria-hidden="true" size={14} />{t('common.add')}</button>
              </div>

              <div className="danger-zone">
                <div className="section-title">{t('workspaceDetail.forget.title')}</div>
                <p className="muted">{t('workspaceDetail.forget.description')}</p>
                <button className="button danger" disabled={busy} onClick={onForget} type="button">{t('workspaceDetail.forget.action')}</button>
              </div>
            </div>
          </details>
        </GoLandLoadingSection>
      </div>
    </Dialog>
  );
}
