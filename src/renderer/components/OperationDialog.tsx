import { Check, LoaderCircle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Dialog } from './Dialog';
import type { DisplayError } from '../error-utils';
import { ErrorNotice } from './ErrorNotice';

export interface OperationView {
  title: string;
  message: string;
  repositoryName?: string;
  current: number;
  total: number;
  done?: boolean;
  error?: DisplayError;
}

export function OperationDialog({ operation, onClose }: { operation: OperationView; onClose: () => void }): React.JSX.Element {
  const { t } = useTranslation();
  const done = !operation.error && (operation.done || (operation.total > 0 && operation.current >= operation.total));
  const percent = operation.total > 0 ? Math.min(100, Math.round((operation.current / operation.total) * 100)) : 0;
  return (
    <Dialog className="progress" dismissible={Boolean(operation.error) || done} onClose={onClose} titleId="operation-title">
      <div className="dialog-header">
        <div>
          <h2 className="dialog-title" id="operation-title">{operation.title}</h2>
          <div className="dialog-description">{t('operation.description')}</div>
        </div>
      </div>
      <div className="dialog-body">
        <div aria-live="polite" className={`progress-item ${done ? 'done' : operation.error ? '' : 'active'}`}>
          <span className="progress-symbol">
            {done ? <Check aria-hidden="true" size={12} /> : <LoaderCircle aria-hidden="true" size={12} />}
          </span>
          <span className="progress-name">{operation.repositoryName ? `${operation.message} · ${operation.repositoryName}` : operation.message}</span>
          <span className="progress-state">
            {operation.error ? t('common.failure') : done ? t('common.completed') : t('common.inProgress')}
          </span>
        </div>
        <div
          aria-label={t('operation.progressLabel')}
          aria-valuemax={100}
          aria-valuemin={0}
          aria-valuenow={percent}
          className="progress-bar"
          role="progressbar"
        ><div className="progress-fill" style={{ width: `${percent}%` }} /></div>
        <div className="progress-caption"><span>{operation.message}</span><span>{percent}%</span></div>
        {operation.error && <ErrorNotice error={operation.error} />}
      </div>
      {(operation.error || done) && (
        <div className="dialog-footer">
          <span />
          <button className="button primary" onClick={onClose} type="button">{t('common.close')}</button>
        </div>
      )}
    </Dialog>
  );
}
