import { Boxes, FolderGit2, RefreshCw } from 'lucide-react';
import type { ReactNode } from 'react';

export type PageName = 'workspaces' | 'repositories';

interface AppShellProps {
  page: PageName;
  workspaceCount: number;
  repositoryCount: number;
  refreshing: boolean;
  primaryDisabled?: boolean;
  onNavigate: (page: PageName) => void;
  onRefresh: () => void;
  onPrimary: () => void;
  children: ReactNode;
}

const pageCopy = {
  workspaces: {
    title: 'Workspaces',
    subtitle: '按需求搜索、管理并打开隔离的多仓库工作区',
    action: '＋ 创建 Workspace',
  },
  repositories: {
    title: 'Repositories',
    subtitle: '维护可被 feature workspace 选择的 Git 仓库目录',
    action: '＋ 录入 Repository',
  },
} as const;

export function AppShell({
  page,
  workspaceCount,
  repositoryCount,
  refreshing,
  primaryDisabled,
  onNavigate,
  onRefresh,
  onPrimary,
  children,
}: AppShellProps): React.JSX.Element {
  const copy = pageCopy[page];
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div aria-hidden="true" className="brand-mark">RW</div>
          <div>
            <div className="brand-title">ReqWS</div>
            <div className="brand-subtitle">Feature workspace manager</div>
          </div>
        </div>
        <div className="nav-label">Workspace</div>
        <nav aria-label="主导航" className="nav">
          <button
            aria-current={page === 'workspaces' ? 'page' : undefined}
            className={`nav-button ${page === 'workspaces' ? 'active' : ''}`}
            onClick={() => onNavigate('workspaces')}
            type="button"
          >
            <span className="nav-icon"><Boxes aria-hidden="true" size={16} /></span>
            <span className="nav-text">Workspaces</span>
            <span className="nav-count">{workspaceCount}</span>
          </button>
          <button
            aria-current={page === 'repositories' ? 'page' : undefined}
            className={`nav-button ${page === 'repositories' ? 'active' : ''}`}
            onClick={() => onNavigate('repositories')}
            type="button"
          >
            <span className="nav-icon"><FolderGit2 aria-hidden="true" size={16} /></span>
            <span className="nav-text">Repositories</span>
            <span className="nav-count">{repositoryCount}</span>
          </button>
        </nav>
        <div className="sidebar-spacer" />
        <div className="sidebar-note">
          <strong>本机私有</strong><br />
          仓库、路径和操作日志只保存在这台 Mac 上。
        </div>
      </aside>
      <main className="content">
        <header className="topbar">
          <div>
            <h1 className="page-title">{copy.title}</h1>
            <div className="page-subtitle">{copy.subtitle}</div>
          </div>
          <div className="toolbar-actions">
            <button className="button" disabled={refreshing} onClick={onRefresh} type="button">
              <RefreshCw aria-hidden="true" className={refreshing ? 'spinner' : ''} size={14} />
              {refreshing ? '刷新中…' : '刷新'}
            </button>
            <button className="button primary" disabled={primaryDisabled} onClick={onPrimary} type="button">
              {copy.action}
            </button>
          </div>
        </header>
        {children}
      </main>
    </div>
  );
}
