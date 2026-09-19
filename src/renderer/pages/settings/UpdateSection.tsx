import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UpdateState } from '../../../shared/update-types';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { ErrorNotice } from '../../components/ErrorNotice';
import { errorMessageKey, toDisplayError, type DisplayError } from '../../error-utils';

export function UpdateSection(): React.JSX.Element {
  const { t } = useTranslation();
  const [state, setState] = useState<UpdateState | null>(null);
  const [error, setError] = useState<DisplayError | null>(null);
  const [pending, setPending] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const revision = useRef(-1);
  const mounted = useRef(false);
  const accept = (next: UpdateState) => {
    if (!mounted.current || next.revision < revision.current) return;
    revision.current = next.revision;
    setState(next);
  };
  useEffect(() => {
    mounted.current = true;
    const unsubscribe = window.reqws.updates.onStateChanged(accept);
    void window.reqws.updates.getState().then(accept).catch((caught: unknown) => {
      if (mounted.current) setError(toDisplayError(caught));
    });
    return () => { mounted.current = false; unsubscribe(); };
  }, []);

  const run = async (action: 'check' | 'download' | 'install') => {
    setPending(true);
    setError(null);
    setConfirming(false);
    try {
      if (action === 'install') await window.reqws.updates.install();
      else accept(await window.reqws.updates[action]());
    } catch (caught) {
      if (mounted.current) setError(toDisplayError(caught));
    } finally { if (mounted.current) setPending(false); }
  };

  const busy = pending || state?.phase === 'checking' || state?.phase === 'downloading' || state?.phase === 'installing';
  const canCheck = state && ['idle', 'available', 'not-available', 'error'].includes(state.phase)
    && state.reason !== 'restart-required';
  return (
    <div className="settings-section">
      <h2 className="settings-section-title">{t('updates.title')}</h2>
      <div className="field-help">{t('updates.description')}</div>
      {!state && <p role="status">{t('updates.loading')}</p>}
      {state && <>
        <p>{t('updates.currentVersion', { version: state.currentVersion })}</p>
        {state.nextVersion && <p>{t('updates.nextVersion', { version: state.nextVersion })}</p>}
        <p aria-live="polite" role="status">{t(`updates.phases.${state.phase}`)}</p>
        {state.reason && <p className="field-help">{t(`updates.reasons.${state.reason}`)}</p>}
        {state.errorCode && <p className="notice error" role="alert">{t(errorMessageKey(state.errorCode))}</p>}
        {state.phase === 'downloading' && <div>
          <progress aria-label={t('updates.progress')} max={100} value={state.percent ?? 0} />
          <span> {Math.floor(state.percent ?? 0)}%</span>
        </div>}
        {state.releaseNotes && <details>
          <summary>{t('updates.releaseNotes')}</summary>
          <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{state.releaseNotes}</pre>
        </details>}
        <div className="dialog-actions">
          <button className="button" disabled={busy || !canCheck} onClick={() => void run('check')} type="button">
            {t('updates.check')}
          </button>
          {state.phase === 'available' && <button className="button primary" disabled={busy} onClick={() => void run('download')} type="button">
            {t('updates.download')}
          </button>}
          {state.phase === 'downloaded' && <button className="button primary" disabled={busy} onClick={() => setConfirming(true)} type="button">
            {t('updates.install')}
          </button>}
        </div>
      </>}
      {error && error.code !== state?.errorCode && <ErrorNotice error={error} />}
      {confirming && state?.phase === 'downloaded' && <ConfirmDialog
        confirmLabel={t('updates.install')}
        description={t('updates.confirmDescription', { version: state.nextVersion })}
        onCancel={() => setConfirming(false)}
        onConfirm={() => void run('install')}
        title={t('updates.confirmTitle')}
      />}
    </div>
  );
}
