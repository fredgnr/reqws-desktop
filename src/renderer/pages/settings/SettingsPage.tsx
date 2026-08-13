import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import type {
  GlobalSettings,
  LocalePreference,
  ResolvedGlobalSettings,
} from '../../../shared/types';
import { errorMessageKey, toDisplayError } from '../../error-utils';

interface SettingsPageProps {
  settings: ResolvedGlobalSettings | null;
  loading: boolean;
  onSaved: (settings: ResolvedGlobalSettings) => void;
  onToast: (message: string, tone?: 'success' | 'error') => void;
}

function editableSettings(settings: ResolvedGlobalSettings): GlobalSettings {
  return {
    localePreference: settings.localePreference,
    workspaceParentDirectory: settings.workspaceParentDirectory,
    workspaceFileDirectory: settings.workspaceFileDirectory,
  };
}

function equalSettings(left: GlobalSettings, right: GlobalSettings): boolean {
  return left.localePreference === right.localePreference
    && left.workspaceParentDirectory === right.workspaceParentDirectory
    && left.workspaceFileDirectory === right.workspaceFileDirectory;
}

export function SettingsPage({
  settings,
  loading,
  onSaved,
  onToast,
}: SettingsPageProps): React.JSX.Element {
  const { i18n, t } = useTranslation();
  const [form, setForm] = useState<GlobalSettings | null>(
    settings ? editableSettings(settings) : null,
  );
  const [saved, setSaved] = useState<GlobalSettings | null>(
    settings ? editableSettings(settings) : null,
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!settings) return;
    let active = true;
    queueMicrotask(() => {
      if (!active) return;
      const next = editableSettings(settings);
      setForm(next);
      setSaved(next);
      setError(null);
    });
    return () => { active = false; };
  }, [settings]);

  const dirty = useMemo(
    () => Boolean(form && saved && !equalSettings(form, saved)),
    [form, saved],
  );

  const chooseDirectory = async (
    field: 'workspaceParentDirectory' | 'workspaceFileDirectory',
  ): Promise<void> => {
    if (!form || saving) return;
    setError(null);
    try {
      const selected = await window.reqws.dialogs.selectDirectory({
        title: t(field === 'workspaceParentDirectory'
          ? 'app.directoryDialogs.workspaceParent'
          : 'app.directoryDialogs.workspaceFile'),
        ...(form[field] ? { defaultPath: form[field] } : {}),
        createDirectory: true,
      });
      if (selected) setForm((current) => current ? { ...current, [field]: selected } : current);
    } catch (caught) {
      const normalized = toDisplayError(caught);
      const key = errorMessageKey(normalized.code);
      setError(i18n.exists(key) ? t(key) : t('errors.settingsDirectoryInvalid'));
    }
  };

  const save = async (): Promise<void> => {
    if (!form || !dirty || saving) return;
    setSaving(true);
    setError(null);
    try {
      const result = await window.reqws.settings.save(form);
      await i18n.changeLanguage(result.effectiveLocale);
      const persisted = editableSettings(result);
      setForm(persisted);
      setSaved(persisted);
      onSaved(result);
      onToast(i18n.t('settings.saved'));
    } catch (caught) {
      const normalized = toDisplayError(caught);
      const key = errorMessageKey(normalized.code);
      const message = i18n.exists(key)
        ? t(key)
        : t('errors.settingsSaveFailed');
      setError(message);
      onToast(message, 'error');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="loading settings-loading"><span className="spinner" />{t('settings.loading')}</div>;
  }

  if (!form) {
    return <div className="page"><div className="notice error" role="alert">{t('errors.settingsLoadFailed')}</div></div>;
  }

  const directoryField = (
    field: 'workspaceParentDirectory' | 'workspaceFileDirectory',
    labelKey: string,
    descriptionKey: string,
  ): React.JSX.Element => (
    <div className="settings-field">
      <div>
        <label className="settings-label" htmlFor={`settings-${field}`}>{t(labelKey)}</label>
        <div className="field-help">{t(descriptionKey)}</div>
      </div>
      <div>
        <div className="settings-directory-row">
          <input
            aria-describedby={settings?.invalidDirectoryFields?.includes(field)
              && !form[field] ? `settings-${field}-error` : undefined}
            aria-invalid={settings?.invalidDirectoryFields?.includes(field)
              && !form[field] ? true : undefined}
            className="field-input mono"
            id={`settings-${field}`}
            placeholder={t('settings.emptyDirectory')}
            readOnly
            value={form[field] ?? ''}
          />
          {form[field] && (
            <button
              aria-label={t('settings.clearDirectoryFor', { name: t(labelKey) })}
              className="button"
              disabled={saving}
              onClick={() => setForm({ ...form, [field]: null })}
              type="button"
            >
              {t('settings.clearDirectory')}
            </button>
          )}
          <button
            aria-label={t('settings.selectDirectoryFor', { name: t(labelKey) })}
            className="button"
            disabled={saving}
            onClick={() => void chooseDirectory(field)}
            type="button"
          >
            {t('settings.selectDirectory')}
          </button>
        </div>
        {settings?.invalidDirectoryFields?.includes(field) && !form[field] && (
          <div className="field-error" id={`settings-${field}-error`} role="status">
            {t('settings.directoryUnavailable')}
          </div>
        )}
      </div>
    </div>
  );

  return (
    <section className="page settings-page">
      <div className="panel settings-panel">
        <div className="settings-section">
          <h2 className="settings-section-title">{t('settings.generalSection')}</h2>
          <div className="settings-field">
            <div>
              <label className="settings-label" htmlFor="settings-locale">{t('settings.language.label')}</label>
              <div className="field-help">{t('settings.language.description')}</div>
            </div>
            <select
              className="field-select settings-select"
              disabled={saving}
              id="settings-locale"
              onChange={(event) => setForm({
                ...form,
                localePreference: event.target.value as LocalePreference,
              })}
              value={form.localePreference}
            >
              <option value="system">{t('settings.language.system')}</option>
              <option value="zh-CN">{t('settings.language.zhCN')}</option>
              <option value="en-US">{t('settings.language.enUS')}</option>
            </select>
          </div>
        </div>
        <div className="settings-section">
          <h2 className="settings-section-title">{t('settings.workspaceSection')}</h2>
          {directoryField(
            'workspaceParentDirectory',
            'settings.workspaceParentDirectory.label',
            'settings.workspaceParentDirectory.description',
          )}
          {directoryField(
            'workspaceFileDirectory',
            'settings.workspaceFileDirectory.label',
            'settings.workspaceFileDirectory.description',
          )}
        </div>
        {error && <div className="notice error settings-error" role="alert">{error}</div>}
        <div className="settings-actions">
          <button
            className="button primary"
            disabled={!dirty || saving}
            onClick={() => void save()}
            type="button"
          >
            {t(saving ? 'settings.saving' : 'settings.save')}
          </button>
        </div>
      </div>
    </section>
  );
}
