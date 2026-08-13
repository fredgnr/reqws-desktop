import { Check, X } from 'lucide-react';

export interface ToastMessage {
  id: number;
  message: string;
  tone?: 'success' | 'error';
}

export function ToastRegion({
  toasts,
  dismiss,
}: {
  toasts: ToastMessage[];
  dismiss: (id: number) => void;
}): React.JSX.Element {
  return (
    <div aria-atomic="true" aria-live="polite" className="toast-region">
      {toasts.map((toast) => (
        <div className={`toast ${toast.tone === 'error' ? 'error' : ''}`} key={toast.id} role="status">
          <span className="toast-icon"><Check aria-hidden="true" size={12} /></span>
          <span className="toast-message">{toast.message}</span>
          <button aria-label="关闭通知" className="toast-dismiss" onClick={() => dismiss(toast.id)} type="button">
            <X aria-hidden="true" size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}
