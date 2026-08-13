import { useState, type FormEvent } from 'react';
import type { CreateRepositoryInput, Repository, TestRepositoryResult } from '../../shared/types';
import { deriveRepositoryName } from '../utils';
import { CloseButton, Dialog } from './Dialog';
import { toDisplayError, type DisplayError } from '../error-utils';
import { ErrorNotice } from './ErrorNotice';

interface RepositoryDialogProps {
  repository?: Repository;
  gitAvailable: boolean;
  busy: boolean;
  testResult?: TestRepositoryResult | null;
  onClose: () => void;
  onSave: (input: CreateRepositoryInput) => Promise<void>;
  onTest: (input: CreateRepositoryInput) => Promise<void>;
  onDelete?: () => void;
}

export function RepositoryDialog({
  repository,
  gitAvailable,
  busy,
  testResult,
  onClose,
  onSave,
  onTest,
  onDelete,
}: RepositoryDialogProps): React.JSX.Element {
  const [url, setUrl] = useState(repository?.url ?? '');
  const [name, setName] = useState(repository?.name ?? '');
  const [defaultBranch, setDefaultBranch] = useState(repository?.defaultBranch ?? 'main');
  const [nameEdited, setNameEdited] = useState(Boolean(repository));
  const [error, setError] = useState<DisplayError | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const locked = busy || submitting;

  const input = { name: name.trim(), url: url.trim(), defaultBranch: defaultBranch.trim() };
  const valid = Boolean(input.name && input.url && input.defaultBranch);

  const handleUrl = (value: string): void => {
    setUrl(value);
    if (!nameEdited) setName(deriveRepositoryName(value));
  };

  const handleSubmit = async (event: FormEvent): Promise<void> => {
    event.preventDefault();
    if (!valid || locked) return;
    setError(null);
    setSubmitting(true);
    try {
      await onSave(input);
    } catch (caught) {
      setError(toDisplayError(caught));
    } finally {
      setSubmitting(false);
    }
  };

  const handleTest = async (): Promise<void> => {
    if (!valid || locked || !gitAvailable) return;
    setError(null);
    setSubmitting(true);
    try {
      await onTest(input);
    } catch (caught) {
      setError(toDisplayError(caught));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog className="small" dismissible={!locked} onClose={onClose} titleId="repository-dialog-title">
      <div className="dialog-header">
        <div>
          <h2 className="dialog-title" id="repository-dialog-title">{repository ? '编辑 Repository' : '录入 Repository'}</h2>
          <div className="dialog-description">只保存仓库目录信息；连接测试失败也可以保存。</div>
        </div>
        {!locked && <CloseButton onClick={onClose} />}
      </div>
      <form onSubmit={(event) => void handleSubmit(event)}>
        <div className="dialog-body">
          <div className="form-grid">
            <label className="field full">
              <span className="field-label">Git 地址 <span className="required">*</span></span>
              <input autoFocus className="field-input mono" onChange={(event) => handleUrl(event.target.value)} required value={url} />
              <span className="field-help">支持 SSH 和 HTTPS；新建时名称会从 URL 自动推导。</span>
            </label>
            <label className="field">
              <span className="field-label">名称 <span className="required">*</span></span>
              <input className="field-input" onChange={(event) => { setName(event.target.value); setNameEdited(true); }} required value={name} />
            </label>
            <label className="field">
              <span className="field-label">默认分支 <span className="required">*</span></span>
              <input className="field-input mono" onChange={(event) => setDefaultBranch(event.target.value)} required value={defaultBranch} />
            </label>
          </div>
          {!gitAvailable && <div className="notice warning">未找到 Git，连接测试不可用；仍可保存这个目录项。</div>}
          {testResult && (
            <div className={`notice ${testResult.success ? 'success' : 'error'}`} role="status">
              <strong>连接测试：</strong>{testResult.success ? '成功' : '失败'}
              {testResult.defaultBranch && ` · 远端默认分支 ${testResult.defaultBranch}`}
              {testResult.detail && <pre className="error-detail">{testResult.detail}</pre>}
              {!testResult.success && <div>连接测试不影响保存。</div>}
            </div>
          )}
          {testResult?.error && <ErrorNotice error={testResult.error} />}
          {error && <ErrorNotice error={error} />}
          {repository && (
            <div className="notice warning">
              修改 URL 或默认分支不会改写已经创建的 Workspace 快照。
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <div>
            {repository && onDelete && <button className="button danger" disabled={locked} onClick={onDelete} type="button">删除目录项</button>}
          </div>
          <div className="dialog-actions">
            <button className="button" disabled={!gitAvailable || locked || !valid} onClick={() => void handleTest()} type="button">
              {locked ? '测试中…' : '测试连接'}
            </button>
            <button className="button" disabled={locked} onClick={onClose} type="button">取消</button>
            <button className="button primary" disabled={locked || !valid} type="submit">{locked ? '保存中…' : '保存 Repository'}</button>
          </div>
        </div>
      </form>
    </Dialog>
  );
}
