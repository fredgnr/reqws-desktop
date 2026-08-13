import { Boxes, FolderGit2, RefreshCw, Settings } from 'lucide-react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

export type PageName = 'workspaces' | 'repositories' | 'settings';

interface AppShellProps {
  page: PageName;
  workspaceCount: number;
  repositoryCount: number;
  refreshing: boolean;
  primaryDisabled?: boolean;
  onNavigate: (page: PageName) => void;
  onRefresh?: () => void;
  onPrimary?: () => void;
  children: ReactNode;
}

const pageCopy = {
  workspaces: {
    title: 'navigation.workspaces',
    subtitle: 'shell.workspaces.subtitle',
    action: 'createWorkspace.submit',
  },
  repositories: {
    title: 'navigation.repositories',
    subtitle: 'shell.repositories.subtitle',
    action: 'repositoryDialog.createTitle',
  },
  settings: {
    title: 'navigation.settings',
    subtitle: 'shell.settings.subtitle',
    action: null,
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
  const { t } = useTranslation();
  const copy = pageCopy[page];
  const showToolbar = copy.action !== null;

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div aria-hidden="true" className="brand-mark">RW</div>
          <div>
            <div className="brand-title">ReqWS</div>
            <div className="brand-subtitle">{t('shell.brandSubtitle')}</div>
          </div>
        </div>
        <div className="nav-label">ReqWS</div>
        <nav aria-label={t('navigation.main')} className="nav">
          <button
            aria-current={page === 'workspaces' ? 'page' : undefined}
            className={`nav-button ${page === 'workspaces' ? 'active' : ''}`}
            onClick={() => onNavigate('workspaces')}
            type="button"
          >
            <span className="nav-icon"><Boxes aria-hidden="true" size={16} /></span>
            <span className="nav-text">{t('navigation.workspaces')}</span>
            <span className="nav-count">{workspaceCount}</span>
          </button>
          <button
            aria-current={page === 'repositories' ? 'page' : undefined}
            className={`nav-button ${page === 'repositories' ? 'active' : ''}`}
            onClick={() => onNavigate('repositories')}
            type="button"
          >
            <span className="nav-icon"><FolderGit2 aria-hidden="true" size={16} /></span>
            <span className="nav-text">{t('navigation.repositories')}</span>
            <span className="nav-count">{repositoryCount}</span>
          </button>
          <button
            aria-current={page === 'settings' ? 'page' : undefined}
            className={`nav-button ${page === 'settings' ? 'active' : ''}`}
            onClick={() => onNavigate('settings')}
            type="button"
          >
            <span className="nav-icon"><Settings aria-hidden="true" size={16} /></span>
            <span className="nav-text">{t('navigation.settings')}</span>
          </button>
        </nav>
        <div className="sidebar-spacer" />
        <div className="sidebar-note">
          <strong>{t('shell.localOnly.title')}</strong><br />
          {t('shell.localOnly.description')}
        </div>
      </aside>
      <main className="content">
        <header className="topbar">
          <div>
            <h1 className="page-title">{t(copy.title)}</h1>
            <div className="page-subtitle">{t(copy.subtitle)}</div>
          </div>
          {showToolbar && (
            <div className="toolbar-actions">
              <button className="button" disabled={refreshing} onClick={onRefresh} type="button">
                <RefreshCw aria-hidden="true" className={refreshing ? 'spinner' : ''} size={14} />
                {t(refreshing ? 'common.refreshing' : 'common.refresh')}
              </button>
              <button className="button primary" disabled={primaryDisabled} onClick={onPrimary} type="button">
                <span aria-hidden="true">＋</span>
                {copy.action ? t(copy.action) : null}
              </button>
            </div>
          )}
        </header>
        {children}
      </main>
    </div>
  );
}
