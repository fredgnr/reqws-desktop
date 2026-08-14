import { Copy } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { errorMessageKey, type DisplayError } from '../error-utils';

export function ErrorNotice({ error }: { error: DisplayError }): React.JSX.Element {
  const { i18n, t } = useTranslation();
  const messageKey = errorMessageKey(error.code);
  const message = i18n.exists(messageKey)
    ? t(messageKey)
    : t('errors.fallback');
  const stageKey = error.stage ? `operation.stages.${error.stage}` : '';
  const stage = error.stage
    ? i18n.exists(stageKey) ? t(stageKey) : error.stage
    : undefined;
  const log = [
    `[${error.code}] ${message}`,
    error.message !== message ? `${t('errors.technicalMessage')}: ${error.message}` : '',
    error.repositoryName ? `${t('common.repository')}: ${error.repositoryName}` : '',
    stage ? `${t('errors.stage')}: ${stage}` : '',
    error.detail ?? '',
  ].filter(Boolean).join('\n');

  return (
    <div className="notice error" role="alert">
      <div className="copy-row">
        <div>
          <strong>{error.code}</strong> · {message}
          {error.repositoryName && <div>{t('common.repository')}{t('common.labelSeparator')}{error.repositoryName}</div>}
          {stage && <div>{t('errors.stage')}{t('common.labelSeparator')}{stage}</div>}
          {error.detail && (
            <details>
              <summary>{t('errors.technicalDetails')}</summary>
              <pre className="error-detail">{error.detail}</pre>
            </details>
          )}
        </div>
        <button
          aria-label={t('errors.copyLog')}
          className="button small icon-only"
          onClick={() => void navigator.clipboard?.writeText(log)}
          title={t('errors.copyLog')}
          type="button"
        >
          <Copy aria-hidden="true" size={14} />
        </button>
      </div>
    </div>
  );
}
