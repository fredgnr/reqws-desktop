import { useMemo, useState, type FormEvent } from 'react';
import type { CreateWorkspaceInput, Repository } from '../../shared/types';
import { defaultFeatureBranch, matchesRepository, workspaceSlug } from '../utils';
import { CloseButton, Dialog } from './Dialog';
import { DirectoryPickerField } from './DirectoryPickerField';
import { toDisplayError, type DisplayError } from '../error-utils';
import { ErrorNotice } from './ErrorNotice';

interface CreateWorkspaceDialogProps {
  repositories: Repository[];
  initialWorkspaceParentDirectory?: string;
  initialWorkspaceFileDirectory?: string;
  busy: boolean;
  onClose: () => void;
  onPickDirectory: (kind: 'root' | 'workspace-file', suggestedPath: string) => Promise<string | null>;
  onCreate: (input: CreateWorkspaceInput) => Promise<void>;
}

export function CreateWorkspaceDialog({
  repositories,
  initialWorkspaceParentDirectory = '',
  initialWorkspaceFileDirectory = '',
  busy,
  onClose,
  onPickDirectory,
  onCreate,
}: CreateWorkspaceDialogProps): React.JSX.Element {
  const [name, setName] = useState('');
  const [featureBranch, setFeatureBranch] = useState('feature/');
  const [branchEdited, setBranchEdited] = useState(false);
  const [rootPath, setRootPath] = useState('');
  const [rootParent, setRootParent] = useState(initialWorkspaceParentDirectory);
  const [rootEdited, setRootEdited] = useState(false);
  const [workspaceFileDirectory, setWorkspaceFileDirectory] = useState(initialWorkspaceFileDirectory);
  const [repoSearch, setRepoSearch] = useState('');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [selectionError, setSelectionError] = useState(false);
  const [error, setError] = useState<DisplayError | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const locked = busy || submitting;

  const visibleRepositories = useMemo(
    () => repositories.filter((repository) => matchesRepository(repository, repoSearch)),
    [repoSearch, repositories],
  );
  const slug = workspaceSlug(name);
  const filename = `${slug}.code-workspace`;

  const handleName = (value: string): void => {
    setName(value);
    if (!branchEdited) setFeatureBranch(defaultFeatureBranch(value));
    if (!rootEdited && rootParent) {
      setRootPath(`${rootParent.replace(/\/$/, '')}/${workspaceSlug(value)}`);
    }
  };

  const toggleRepository = (id: string): void => {
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    setSelectionError(false);
  };

  const pickRoot = async (): Promise<void> => {
    const selected = await onPickDirectory('root', rootParent || initialWorkspaceParentDirectory);
    if (selected) {
      const normalizedParent = selected.replace(/\/$/, '');
      setRootParent(normalizedParent);
      setRootPath(`${normalizedParent}/${slug}`);
      setRootEdited(false);
    }
  };

  const pickWorkspaceFile = async (): Promise<void> => {
    const selected = await onPickDirectory('workspace-file', workspaceFileDirectory);
    if (selected) setWorkspaceFileDirectory(selected);
  };

  const handleSubmit = async (event: FormEvent): Promise<void> => {
    event.preventDefault();
    if (locked) return;
    if (selectedIds.size === 0) {
      setSelectionError(true);
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await onCreate({
        name: name.trim(),
        featureBranch: featureBranch.trim(),
        rootPath: rootPath.trim(),
        workspaceFileDirectory: workspaceFileDirectory.trim(),
        repositoryIds: [...selectedIds],
      });
    } catch (caught) {
      setError(toDisplayError(caught));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog dismissible={!locked} onClose={onClose} titleId="create-workspace-title">
      <div className="dialog-header">
        <div>
          <h2 className="dialog-title" id="create-workspace-title">创建 Feature Workspace</h2>
          <div className="dialog-description">每个仓库都会完整 clone 到需求独立目录，并切换到同一个 feature 分支。</div>
        </div>
        {!locked && <CloseButton onClick={onClose} />}
      </div>
      <form onSubmit={(event) => void handleSubmit(event)}>
        <div className="dialog-body">
          <div className="form-grid">
            <label className="field">
              <span className="field-label">Workspace 名称 <span className="required">*</span></span>
              <input autoFocus className="field-input" onChange={(event) => handleName(event.target.value)} required value={name} />
              <span className="field-help">用于默认文件夹名和 `.code-workspace` 文件名。</span>
            </label>
            <label className="field">
              <span className="field-label">Feature 分支 <span className="required">*</span></span>
              <input
                className="field-input mono"
                onChange={(event) => { setFeatureBranch(event.target.value); setBranchEdited(true); }}
                required
                value={featureBranch}
              />
              <span className="field-help">远端存在则 tracking；否则从各 repo 默认分支创建。</span>
            </label>
            <DirectoryPickerField
              help="选择按钮用于选择父目录；最终路径会自动拼接上方名称，也可手动输入绝对路径。"
              label="代码目录（最终目录）"
              onChange={(value) => { setRootPath(value); setRootEdited(true); }}
              onPick={() => void pickRoot()}
              value={rootPath}
            />
            <DirectoryPickerField
              help={<>将生成：<strong>{filename}</strong></>}
              label="Workspace 文件目录"
              onChange={setWorkspaceFileDirectory}
              onPick={() => void pickWorkspaceFile()}
              value={workspaceFileDirectory}
            />
          </div>
          <div className="section-title-row">
            <div className="section-title">选择仓库 <span className="required">*</span></div>
            <div className="section-meta">已选择 {selectedIds.size} 个</div>
          </div>
          <div className="repo-picker">
            <div className="repo-picker-search">
              <label>
                <span className="sr-only">搜索可选仓库</span>
                <input className="field-input" onChange={(event) => setRepoSearch(event.target.value)} placeholder="搜索仓库…" value={repoSearch} />
              </label>
            </div>
            <div className="repo-picker-list">
              {visibleRepositories.length > 0 ? visibleRepositories.map((repository) => (
                <label className="repo-choice" key={repository.id}>
                  <input
                    checked={selectedIds.has(repository.id)}
                    onChange={() => toggleRepository(repository.id)}
                    type="checkbox"
                    value={repository.id}
                  />
                  <span>
                    <span className="repo-choice-name">{repository.name}</span>
                    <span className="repo-choice-url">{repository.url}</span>
                  </span>
                  <span className="branch-pill">{repository.defaultBranch}</span>
                </label>
              )) : <div className="empty-state">没有匹配的仓库</div>}
            </div>
          </div>
          {selectionError && <span className="field-error" role="alert">请至少选择一个 Repository。</span>}
          <div className="summary-box">
            <strong>创建规则：</strong>顺序 clone；任一仓库失败会清理 staging，不登记半成品；不会自动 push，也不会共享 `.git`。
          </div>
          {error && <ErrorNotice error={error} />}
        </div>
        <div className="dialog-footer">
          <span className="muted">快捷键 <span className="kbd">Esc</span> 关闭</span>
          <div className="dialog-actions">
            <button className="button" disabled={locked} onClick={onClose} type="button">取消</button>
            <button className="button primary" disabled={locked || repositories.length === 0} type="submit">
              {locked ? '创建中…' : '创建 Workspace'}
            </button>
          </div>
        </div>
      </form>
    </Dialog>
  );
}
