interface Props<T extends string> {
  label: string;
  value: T;
  options: { value: T; label: string }[];
  onChange: (value: T) => void;
}

/** Two or three exclusive choices side by side (radio semantics, 48 px tall). */
export function Segmented<T extends string>({ label, value, options, onChange }: Props<T>) {
  return (
    <div role="radiogroup" aria-label={label} className="flex gap-2">
      {options.map((o) => {
        const checked = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={checked}
            onClick={() => onChange(o.value)}
            className={`min-h-12 flex-1 rounded-xl border-2 px-3 font-semibold ${checked ? 'border-brand-ink bg-brand text-brand-contrast' : 'border-line bg-white text-ink'}`}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
