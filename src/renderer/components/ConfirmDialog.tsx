import { AlertTriangle } from 'lucide-react';
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
          <AlertTriangle aria-hidden="true" size={15} /> 这项操作不会自动删除任何本地目录或文件。
        </div>
      </div>
      <div className="dialog-footer">
        <span />
        <div className="dialog-actions">
          <button className="button" disabled={busy} onClick={onCancel} type="button">取消</button>
          <button className={`button ${danger ? 'danger' : 'primary'}`} disabled={busy} onClick={onConfirm} type="button">
            {busy ? '处理中…' : confirmLabel}
          </button>
        </div>
      </div>
    </Dialog>
  );
}
