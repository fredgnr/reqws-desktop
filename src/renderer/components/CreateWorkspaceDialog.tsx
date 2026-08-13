import { useMemo, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation();
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
          <h2 className="dialog-title" id="create-workspace-title">{t('createWorkspace.title')}</h2>
          <div className="dialog-description">{t('createWorkspace.description')}</div>
        </div>
        {!locked && <CloseButton onClick={onClose} />}
      </div>
      <form onSubmit={(event) => void handleSubmit(event)}>
        <div className="dialog-body">
          <div className="form-grid">
            <label className="field">
              <span className="field-label">{t('createWorkspace.name.label')} <span className="required">*</span></span>
              <input aria-label={t('createWorkspace.name.label')} autoFocus className="field-input" onChange={(event) => handleName(event.target.value)} required value={name} />
              <span className="field-help">{t('createWorkspace.name.help')}</span>
            </label>
            <label className="field">
              <span className="field-label">{t('createWorkspace.branch.label')} <span className="required">*</span></span>
              <input
                aria-label={t('createWorkspace.branch.label')}
                className="field-input mono"
                onChange={(event) => { setFeatureBranch(event.target.value); setBranchEdited(true); }}
                required
                value={featureBranch}
              />
              <span className="field-help">{t('createWorkspace.branch.help')}</span>
            </label>
            <DirectoryPickerField
              help={t('createWorkspace.rootPath.help')}
              label={t('createWorkspace.rootPath.label')}
              onChange={(value) => { setRootPath(value); setRootEdited(true); }}
              onPick={() => void pickRoot()}
              value={rootPath}
            />
            <DirectoryPickerField
              help={t('createWorkspace.workspaceFileDirectory.help', { filename })}
              label={t('createWorkspace.workspaceFileDirectory.label')}
              onChange={setWorkspaceFileDirectory}
              onPick={() => void pickWorkspaceFile()}
              value={workspaceFileDirectory}
            />
          </div>
          <div className="section-title-row">
            <div className="section-title">{t('createWorkspace.repositories.label')} <span className="required">*</span></div>
            <div className="section-meta">{t('createWorkspace.repositories.selectedCount', { count: selectedIds.size })}</div>
          </div>
          <div className="repo-picker">
            <div className="repo-picker-search">
              <label>
                <span className="sr-only">{t('createWorkspace.repositories.searchLabel')}</span>
                <input className="field-input" onChange={(event) => setRepoSearch(event.target.value)} placeholder={t('createWorkspace.repositories.searchPlaceholder')} value={repoSearch} />
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
              )) : <div className="empty-state">{t('createWorkspace.repositories.noMatches')}</div>}
            </div>
          </div>
          {selectionError && <span className="field-error" role="alert">{t('createWorkspace.repositories.required')}</span>}
          <div className="summary-box">
            <strong>{t('createWorkspace.rules.title')}</strong>{t('createWorkspace.rules.description')}
          </div>
          {error && <ErrorNotice error={error} />}
        </div>
        <div className="dialog-footer">
          <span className="muted">{t('common.pressKeyToClose', { key: 'Esc' })}</span>
          <div className="dialog-actions">
            <button className="button" disabled={locked} onClick={onClose} type="button">{t('common.cancel')}</button>
            <button className="button primary" disabled={locked || repositories.length === 0} type="submit">
              {locked ? t('createWorkspace.creating') : t('createWorkspace.submit')}
            </button>
          </div>
        </div>
      </form>
    </Dialog>
  );
}
