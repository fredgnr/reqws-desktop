import { FolderGit2, MoreHorizontal, SearchX } from 'lucide-react';
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
  const visible = repositories.filter((repository) => matchesRepository(repository, search));
  const inUse = repositories.filter((repository) => repository.workspaceUsageCount > 0).length;

  return (
    <section className="page">
      <div className="summary-grid">
        <div className="summary-card">
          <div className="summary-label">Repositories</div>
          <div className="summary-value">{repositories.length}</div>
          <div className="summary-foot">可用于创建 workspace 的仓库</div>
        </div>
        <div className="summary-card">
          <div className="summary-label">In use</div>
          <div className="summary-value">{inUse}</div>
          <div className="summary-foot">至少被一个 workspace 使用</div>
        </div>
        <div className="summary-card">
          <div className="summary-label">Git</div>
          <div className="summary-value compact">{gitAvailable ? 'Available' : 'Unavailable'}</div>
          <div className="summary-foot">{gitAvailable ? '可测试连接和创建 Workspace' : '请安装 Git 或配置 PATH'}</div>
        </div>
      </div>
      {!gitAvailable && <div className="notice warning">未找到 Git。你仍可维护仓库目录，但连接测试、创建和增加仓库已禁用。</div>}
      <div className="panel">
        <div className="panel-toolbar">
          <SearchField label="搜索 Repository" onChange={onSearch} placeholder="搜索仓库名称、Git 地址或默认分支…" value={search} />
          <div className="result-count">{visible.length} 个 repository</div>
        </div>
        {loading ? (
          <div className="loading"><span className="spinner" />加载 Repositories…</div>
        ) : visible.length > 0 ? (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: '20%' }}>名称</th>
                  <th style={{ width: '42%' }}>Git 地址</th>
                  <th style={{ width: '13%' }}>默认分支</th>
                  <th style={{ width: '10%' }}>使用中</th>
                  <th style={{ width: '15%', textAlign: 'right' }}>操作</th>
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
                      <td><span className="muted">{used} 个</span></td>
                      <td>
                        <div className="row-actions">
                          <button
                            className="button small"
                            disabled={!gitAvailable || testingId !== null}
                            onClick={() => onTest(repository)}
                            title={!gitAvailable ? '未找到 Git' : undefined}
                            type="button"
                          >{testingId === repository.id ? '测试中…' : '测试'}</button>
                          <button aria-label={`编辑 ${repository.name}`} className="button small icon-only" onClick={() => onEdit(repository)} type="button">
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
            <h2 className="empty-title">{search ? '没有匹配的 Repository' : '还没有 Repository'}</h2>
            <p className="empty-copy">{search ? '试试仓库名称、Git 地址或默认分支。' : '录入 Git 地址，建立可选择的仓库目录。'}</p>
            {!search && <button className="button primary" onClick={onCreate} type="button">录入 Repository</button>}
          </div>
        )}
      </div>
    </section>
  );
}
