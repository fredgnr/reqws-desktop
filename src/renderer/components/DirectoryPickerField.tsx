import { useId } from 'react';
import { useTranslation } from 'react-i18next';

export function DirectoryPickerField({
  label,
  value,
  required = true,
  help,
  warning,
  onChange,
  onPick,
}: {
  label: string;
  value: string;
  required?: boolean;
  help?: React.ReactNode;
  warning?: React.ReactNode;
  onChange: (value: string) => void;
  onPick: () => void;
}): React.JSX.Element {
  const { t } = useTranslation();
  const fieldId = useId();
  const helpId = help ? `${fieldId}-help` : undefined;
  const warningId = warning ? `${fieldId}-warning` : undefined;
  const describedBy = [helpId, warningId].filter(Boolean).join(' ') || undefined;

  return (
    <label className="field full">
      <span className="field-label">{label} {required && <span className="required">*</span>}</span>
      <span className="path-field">
        <input
          aria-describedby={describedBy}
          aria-invalid={warning ? true : undefined}
          aria-label={label}
          className="field-input mono"
          onChange={(event) => onChange(event.target.value)}
          required={required}
          value={value}
        />
        <button className="button" onClick={onPick} type="button">{t('common.chooseDirectory')}</button>
      </span>
      {help && <span className="field-help" id={helpId}>{help}</span>}
      {warning && <span className="field-error" id={warningId} role="status">{warning}</span>}
    </label>
  );
}
