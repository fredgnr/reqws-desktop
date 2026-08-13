import { FolderX, MoreHorizontal, SearchX } from 'lucide-react';
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
}

function statusLabel(status: WorkspaceSummary['status']): string {
  if (status === 'ready') return 'Ready';
  if (status === 'missing') return 'Missing';
  return 'Error';
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
}: WorkspacesPageProps): React.JSX.Element {
  const visible = workspaces.filter((workspace) => matchesWorkspace(workspace, search));
  const ready = workspaces.filter((workspace) => workspace.status === 'ready').length;
  const vscodeAvailable = availability?.vscode.available ?? false;
  const cursorAvailable = availability?.cursor.available ?? false;
  const availabilityMessages = [
    !vscodeAvailable
      ? availability?.vscode.reason ?? '未找到 Visual Studio Code'
      : undefined,
    !cursorAvailable ? availability?.cursor.reason ?? '未找到 Cursor' : undefined,
  ].filter(Boolean);

  return (
    <section className="page">
      <div className="summary-grid">
        <div className="summary-card">
          <div className="summary-label">Workspaces</div>
          <div className="summary-value">{workspaces.length}</div>
          <div className="summary-foot">全部 feature 工作区</div>
        </div>
        <div className="summary-card">
          <div className="summary-label">Ready</div>
          <div className="summary-value">{ready}</div>
          <div className="summary-foot">路径和 workspace 文件完整</div>
        </div>
        <div className="summary-card">
          <div className="summary-label">Repositories</div>
          <div className="summary-value">{repositoryCount}</div>
          <div className="summary-foot">已录入仓库目录</div>
        </div>
      </div>
      {availabilityMessages.length > 0 && (
        <p className="muted" id="workspace-editor-availability">
          编辑器操作不可用：{availabilityMessages.join('；')}
        </p>
      )}
      <div className="panel">
        <div className="panel-toolbar">
          <SearchField
            label="搜索 Workspace"
            onChange={onSearch}
            placeholder="搜索名称、分支、仓库或路径…"
            value={search}
          />
          <div className="result-count">{visible.length} 个 workspace</div>
        </div>
        {loading ? (
          <div className="loading"><span className="spinner" />加载 Workspaces…</div>
        ) : visible.length > 0 ? (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: '23%' }}>Workspace</th>
                  <th style={{ width: '22%' }}>Repositories</th>
                  <th className="hide-compact" style={{ width: '24%' }}>代码目录</th>
                  <th style={{ width: '10%' }}>状态</th>
                  <th style={{ width: '21%', textAlign: 'right' }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((workspace) => (
                  <tr key={workspace.id}>
                    <td>
                      <div className="workspace-name">{workspace.name}</div>
                      <div className="workspace-branch">{workspace.featureBranch}</div>
                      <div className="updated">更新于 {formatUpdatedAt(workspace.updatedAt)}</div>
                    </td>
                    <td>
                      <div aria-label={`${workspace.repositoryNames.length} 个仓库`} className="tag-list">
                        {workspace.repositoryNames.slice(0, 3).map((name) => <span className="tag" key={name}>{name}</span>)}
                        {workspace.repositoryNames.length > 3 && <span className="tag more">+{workspace.repositoryNames.length - 3}</span>}
                      </div>
                      <div className="updated">{workspace.repositoryNames.length} 个仓库</div>
                    </td>
                    <td className="hide-compact"><div className="path" title={workspace.rootPath}>{workspace.rootPath}</div></td>
                    <td><span className={`status ${workspace.status}`}>{statusLabel(workspace.status)}</span></td>
                    <td>
                      <div className="row-actions">
                        <button
                          className="button small"
                          aria-describedby={!vscodeAvailable ? 'workspace-editor-availability' : undefined}
                          disabled={workspace.status !== 'ready' || !vscodeAvailable}
                          onClick={() => onOpenVSCode(workspace.id)}
                          title={!vscodeAvailable ? '未找到 Visual Studio Code' : workspace.status !== 'ready' ? 'Workspace 路径不完整' : undefined}
                          type="button"
                        >VS Code</button>
                        <button
                          className="button small"
                          aria-describedby={!cursorAvailable ? 'workspace-editor-availability' : undefined}
                          disabled={workspace.status !== 'ready' || !cursorAvailable}
                          onClick={() => onOpenCursor(workspace.id)}
                          title={!cursorAvailable ? '未找到 Cursor' : workspace.status !== 'ready' ? 'Workspace 路径不完整' : undefined}
                          type="button"
                        >Cursor</button>
                        <button aria-label={`查看 ${workspace.name} 详情`} className="button small icon-only" onClick={() => onDetails(workspace.id)} type="button">
                          <MoreHorizontal aria-hidden="true" size={16} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="empty-state">
            {search ? <SearchX aria-hidden="true" className="empty-icon" size={32} /> : <FolderX aria-hidden="true" className="empty-icon" size={32} />}
            <h2 className="empty-title">{search ? '没有匹配的 Workspace' : '还没有 Workspace'}</h2>
            <p className="empty-copy">{search ? '试试名称、分支、仓库名或路径。' : '选择至少一个仓库，创建第一个独立工作区。'}</p>
            {!search && <button className="button primary" onClick={onCreate} type="button">创建 Workspace</button>}
          </div>
        )}
      </div>
    </section>
  );
}
