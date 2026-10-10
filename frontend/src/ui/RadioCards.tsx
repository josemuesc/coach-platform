/** Radio cards: one button per option, the chosen one is `aria-checked`. */
export function RadioCards<T extends string>({ label, value, options, onChange, columns }: {
  label: string;
  value: T | '';
  options: { value: T; title: string; sub?: string; right?: string }[];
  onChange: (value: T) => void;
  columns?: boolean;
}) {
  return (
    <div role="radiogroup" aria-label={label} className={columns ? 'flex flex-wrap gap-2' : 'flex flex-col gap-2'}>
      {options.map((o) => {
        const checked = value === o.value;
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={checked}
            onClick={() => onChange(o.value)}
            className={`flex min-h-12 items-center justify-between gap-3 rounded-2xl border-2 px-4 py-2 text-left ${
              checked ? 'border-brand-ink bg-brand-soft' : 'border-line bg-white'
            } ${columns ? 'justify-center' : ''}`}
          >
            <span>
              <span className="block font-display text-base font-bold">{o.title}</span>
              {o.sub && <span className="block text-sm text-ink-2">{o.sub}</span>}
            </span>
            {o.right && <span className="font-display text-base font-bold">{o.right}</span>}
          </button>
        );
      })}
    </div>
  );
}
