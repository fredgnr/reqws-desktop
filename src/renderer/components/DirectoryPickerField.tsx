import { useTranslation } from 'react-i18next';

export function DirectoryPickerField({
  label,
  value,
  required = true,
  help,
  onChange,
  onPick,
}: {
  label: string;
  value: string;
  required?: boolean;
  help?: React.ReactNode;
  onChange: (value: string) => void;
  onPick: () => void;
}): React.JSX.Element {
  const { t } = useTranslation();

  return (
    <label className="field full">
      <span className="field-label">{label} {required && <span className="required">*</span>}</span>
      <span className="path-field">
        <input aria-label={label} className="field-input mono" onChange={(event) => onChange(event.target.value)} required={required} value={value} />
        <button className="button" onClick={onPick} type="button">{t('common.chooseDirectory')}</button>
      </span>
      {help && <span className="field-help">{help}</span>}
    </label>
  );
}
