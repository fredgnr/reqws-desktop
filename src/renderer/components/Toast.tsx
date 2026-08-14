import { Check, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';

export interface ToastMessage {
  id: number;
  errorCode?: string;
  message?: string;
  messageKey?: string;
  values?: Record<string, string | number>;
  tone?: 'success' | 'error';
}

export function ToastRegion({
  toasts,
  dismiss,
}: {
  toasts: ToastMessage[];
  dismiss: (id: number) => void;
}): React.JSX.Element {
  const { t } = useTranslation();

  return (
    <div aria-atomic="true" aria-live="polite" className="toast-region">
      {toasts.map((toast) => (
        <div className={`toast ${toast.tone === 'error' ? 'error' : ''}`} key={toast.id} role="status">
          <span className="toast-icon"><Check aria-hidden="true" size={12} /></span>
          <span className="toast-message">
            {toast.errorCode && <><strong>{toast.errorCode}</strong> · </>}
            {toast.messageKey ? t(toast.messageKey, toast.values) : toast.message}
          </span>
          <button aria-label={t('common.dismissNotification')} className="toast-dismiss" onClick={() => dismiss(toast.id)} type="button">
            <X aria-hidden="true" size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}
