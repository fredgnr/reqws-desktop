import { Search } from 'lucide-react';

export function SearchField({
  label,
  placeholder,
  value,
  onChange,
}: {
  label: string;
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
}): React.JSX.Element {
  return (
    <label className="search-box">
      <span className="sr-only">{label}</span>
      <Search aria-hidden="true" className="search-icon" size={15} />
      <input
        className="search-input"
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        type="search"
        value={value}
      />
    </label>
  );
}
