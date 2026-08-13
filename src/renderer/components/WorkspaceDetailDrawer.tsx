import { useState } from 'react';
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

function statusLabel(status: WorkspaceDetail['status']): string {
  return status === 'ready' ? 'Ready' : status === 'missing' ? 'Missing' : 'Error';
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
  const availableRepositories = repositories.filter((repository) => (
    !workspace.repositories.some((item) => item.catalogRepositoryId === repository.id)
  ));
  const [repositoryId, setRepositoryId] = useState(availableRepositories[0]?.id ?? '');
  const ready = workspace.status === 'ready';
  const vscodeAvailable = availability?.vscode.available ?? false;
  const cursorAvailable = availability?.cursor.available ?? false;
  const vscodeReason = !ready
    ? 'Workspace 路径不完整'
    : availability?.vscode.reason ?? '未找到 Visual Studio Code';
  const cursorReason = !ready
    ? 'Workspace 路径不完整'
    : availability?.cursor.reason ?? '未找到 Cursor';

  return (
    <Dialog dismissible={!busy} drawer onClose={onClose} titleId="workspace-detail-title">
      <div className="drawer-header">
        <div>
          <div className="drawer-title-row">
            <h2 className="drawer-title" id="workspace-detail-title">{workspace.name}</h2>
            <span className={`status ${workspace.status}`}>{statusLabel(workspace.status)}</span>
          </div>
          <div className="drawer-branch">{workspace.featureBranch}</div>
        </div>
        {!busy && <CloseButton onClick={onClose} />}
      </div>
      <div className="drawer-body">
        <div className="detail-actions">
          <button aria-describedby={!ready || !vscodeAvailable ? 'workspace-editor-status' : undefined} className="button primary" disabled={!ready || !vscodeAvailable} onClick={onOpenVSCode} title={!ready || !vscodeAvailable ? vscodeReason : undefined} type="button">VS Code</button>
          <button aria-describedby={!ready || !cursorAvailable ? 'workspace-editor-status' : undefined} className="button" disabled={!ready || !cursorAvailable} onClick={onOpenCursor} title={!ready || !cursorAvailable ? cursorReason : undefined} type="button">Cursor</button>
          <button aria-describedby={!ready || !cursorAvailable ? 'workspace-editor-status' : undefined} className="button" disabled={!ready || !cursorAvailable} onClick={onOpenCursorRoot} title={!ready || !cursorAvailable ? cursorReason : undefined} type="button">Cursor 打开根目录</button>
          <button className="button" onClick={onRevealFinder} type="button">Finder</button>
        </div>
        {(!ready || !vscodeAvailable || !cursorAvailable) && (
          <p className="muted" id="workspace-editor-status">
            {!ready
              ? 'Workspace 路径不完整，编辑器打开操作已禁用。'
              : [
                  !vscodeAvailable ? vscodeReason : undefined,
                  !cursorAvailable ? cursorReason : undefined,
                ].filter(Boolean).join('；')}
          </p>
        )}
        <div className="detail-grid">
          <div className="detail-label">代码目录</div>
          <div className="detail-value" title={workspace.rootPath}>{workspace.rootPath}</div>
          <div className="detail-label">Workspace 文件</div>
          <div className="detail-value" title={workspace.workspaceFilePath}>{workspace.workspaceFilePath}</div>
          <div className="detail-label">更新时间</div>
          <div className="detail-value">{formatUpdatedAt(workspace.updatedAt)}</div>
        </div>
        {workspace.statusDetail && <div className="notice warning">{workspace.statusDetail}</div>}
        {workspace.lastError && <ErrorNotice error={workspace.lastError} />}
        <div className="notice">该 `.code-workspace` 文件由 ReqWS 管理；同步、增加或移除仓库时会重写，手工编辑不会被合并。</div>
        {workspace.status !== 'ready' && (
          <div className="detail-actions" style={{ marginTop: 12 }}>
            <button className="button" disabled={busy} onClick={onSync} type="button">同步并恢复 Workspace 文件</button>
          </div>
        )}

        <div className="section-title-row">
          <div className="section-title">Repositories · {workspace.repositories.length}</div>
          <div className="section-meta">全部使用 {workspace.featureBranch}</div>
        </div>
        <div className="repo-manage-list">
          {workspace.repositories.length > 0 ? workspace.repositories.map((repository) => (
            <div className="repo-manage-row" key={repository.catalogRepositoryId}>
              <div>
                <div className="repo-manage-title">{repository.name}</div>
                <div className="repo-manage-path">{workspace.rootPath}/{repository.relativePath}</div>
              </div>
              <button className="button small danger" disabled={busy} onClick={() => onRemoveRepository(repository)} type="button">移除</button>
            </div>
          )) : <div className="empty-state">暂无 Repository</div>}
        </div>
        <div className="notice warning">“移除”只会更新 manifest 和 `.code-workspace`，本地 repo 目录会保留。</div>

        <div className="section-title-row">
          <div className="section-title">增加 Repository</div>
          <div className="section-meta">从仓库目录选择</div>
        </div>
        <div className="add-inline">
          <label className="sr-only" htmlFor="add-workspace-repository">选择要增加的 Repository</label>
          <select className="field-select" disabled={busy || availableRepositories.length === 0} id="add-workspace-repository" onChange={(event) => setRepositoryId(event.target.value)} value={repositoryId}>
            {availableRepositories.length > 0
              ? availableRepositories.map((repository) => <option key={repository.id} value={repository.id}>{repository.name} · {repository.defaultBranch}</option>)
              : <option value="">没有可增加的仓库</option>}
          </select>
          <button className="button" disabled={busy || !repositoryId || !availability?.git.available} onClick={() => onAddRepository(repositoryId)} title={!availability?.git.available ? availability?.git.reason ?? '未找到 Git' : undefined} type="button">＋ 增加</button>
        </div>

        <div className="danger-zone">
          <div className="section-title">从 ReqWS 中遗忘</div>
          <p className="muted">仅从全局索引移除。代码目录、manifest 和 Workspace 文件仍保留在磁盘。</p>
          <button className="button danger" disabled={busy} onClick={onForget} type="button">遗忘 Workspace</button>
        </div>
      </div>
    </Dialog>
  );
}
