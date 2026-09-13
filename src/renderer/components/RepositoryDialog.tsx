import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { CreateRepositoryInput, Repository, TestRepositoryResult } from '../../shared/types';
import { deriveRepositoryName } from '../utils';
import { CloseButton, Dialog } from './Dialog';
import { toDisplayError, type DisplayError } from '../error-utils';
import { ErrorNotice } from './ErrorNotice';

interface RepositoryDialogProps {
  repository?: Repository;
  gitAvailable: boolean | null;
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
  const { t } = useTranslation();
  const [url, setUrl] = useState(repository?.url ?? '');
  const [name, setName] = useState(repository?.name ?? '');
  const [defaultBranch, setDefaultBranch] = useState(repository?.defaultBranch ?? 'main');
  const [nameEdited, setNameEdited] = useState(Boolean(repository));
  const [error, setError] = useState<DisplayError | null>(null);
  const [pendingAction, setPendingAction] = useState<'save' | 'test' | null>(null);
  const locked = busy || pendingAction !== null;

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
    setPendingAction('save');
    try {
      await onSave(input);
    } catch (caught) {
      setError(toDisplayError(caught));
    } finally {
      setPendingAction(null);
    }
  };

  const handleTest = async (): Promise<void> => {
    if (!valid || locked || gitAvailable !== true) return;
    setError(null);
    setPendingAction('test');
    try {
      await onTest(input);
    } catch (caught) {
      setError(toDisplayError(caught));
    } finally {
      setPendingAction(null);
    }
  };

  return (
    <Dialog className="small" dismissible={!locked} onClose={onClose} titleId="repository-dialog-title">
      <div className="dialog-header">
        <div>
          <h2 className="dialog-title" id="repository-dialog-title">
            {repository ? t('repositoryDialog.editTitle') : t('repositoryDialog.createTitle')}
          </h2>
          <div className="dialog-description">{t('repositoryDialog.description')}</div>
        </div>
        {!locked && <CloseButton onClick={onClose} />}
      </div>
      <form onSubmit={(event) => void handleSubmit(event)}>
        <div className="dialog-body">
          <div className="form-grid">
            <label className="field full">
              <span className="field-label">{t('repositoryDialog.url.label')} <span className="required">*</span></span>
              <input aria-label={t('repositoryDialog.url.label')} autoFocus className="field-input mono" onChange={(event) => handleUrl(event.target.value)} required value={url} />
              <span className="field-help">{t('repositoryDialog.url.help')}</span>
            </label>
            <label className="field">
              <span className="field-label">{t('repositoryDialog.name')} <span className="required">*</span></span>
              <input aria-label={t('repositoryDialog.name')} className="field-input" onChange={(event) => { setName(event.target.value); setNameEdited(true); }} required value={name} />
            </label>
            <label className="field">
              <span className="field-label">{t('repositoryDialog.defaultBranch')} <span className="required">*</span></span>
              <input aria-label={t('repositoryDialog.defaultBranch')} className="field-input mono" onChange={(event) => setDefaultBranch(event.target.value)} required value={defaultBranch} />
            </label>
          </div>
          {gitAvailable === false && <div className="notice warning">{t('repositoryDialog.gitUnavailable')}</div>}
          {testResult && (
            <div className={`notice ${testResult.success ? 'success' : 'error'}`} role="status">
              <strong>{t('repositoryDialog.test.resultLabel')}</strong>
              {testResult.success ? t('common.success') : t('common.failure')}
              {testResult.defaultBranch && ` · ${t('repositoryDialog.test.remoteDefaultBranch', { branch: testResult.defaultBranch })}`}
              {!testResult.success && <div>{t('repositoryDialog.test.failureDoesNotBlockSave')}</div>}
            </div>
          )}
          {testResult?.error && <ErrorNotice error={testResult.error} />}
          {error && <ErrorNotice error={error} />}
          {repository && (
            <div className="notice warning">
              {t('repositoryDialog.editWarning')}
            </div>
          )}
        </div>
        <div className="dialog-footer">
          <div>
            {repository && onDelete && <button className="button danger" disabled={locked} onClick={onDelete} type="button">{t('repositoryDialog.remove')}</button>}
          </div>
          <div className="dialog-actions">
            <button className="button" disabled={gitAvailable !== true || locked || !valid} onClick={() => void handleTest()} type="button">
              {pendingAction === 'test'
                ? t('repositoryDialog.test.testing')
                : t('repositoryDialog.test.action')}
            </button>
            <button className="button" disabled={locked} onClick={onClose} type="button">{t('common.cancel')}</button>
            <button className="button primary" disabled={locked || !valid} type="submit">
              {pendingAction === 'save'
                ? t('common.saving')
                : t(repository ? 'repositoryDialog.save' : 'repositoryDialog.create')}
            </button>
          </div>
        </div>
      </form>
    </Dialog>
  );
}
