import { AlertTriangle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { CloseButton, Dialog } from './Dialog';

interface ConfirmDialogProps {
  title: string;
  description: string;
  confirmLabel: string;
  danger?: boolean;
  busy?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}

export function ConfirmDialog({
  title,
  description,
  confirmLabel,
  danger = false,
  busy = false,
  onCancel,
  onConfirm,
}: ConfirmDialogProps): React.JSX.Element {
  const { t } = useTranslation();

  return (
    <Dialog className="small" dismissible={!busy} onClose={onCancel} titleId="confirm-dialog-title">
      <div className="dialog-header">
        <div>
          <h2 className="dialog-title" id="confirm-dialog-title">{title}</h2>
          <div className="dialog-description">{description}</div>
        </div>
        {!busy && <CloseButton onClick={onCancel} />}
      </div>
      <div className="dialog-body">
        <div className="notice warning">
          <AlertTriangle aria-hidden="true" size={15} /> {t('confirmDialog.localFilesPreserved')}
        </div>
      </div>
      <div className="dialog-footer">
        <span />
        <div className="dialog-actions">
          <button className="button" disabled={busy} onClick={onCancel} type="button">{t('common.cancel')}</button>
          <button className={`button ${danger ? 'danger' : 'primary'}`} disabled={busy} onClick={onConfirm} type="button">
            {busy ? t('common.processing') : confirmLabel}
          </button>
        </div>
      </div>
    </Dialog>
  );
}
