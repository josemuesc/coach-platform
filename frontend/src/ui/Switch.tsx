import { useId } from 'react';

interface Props {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  hint?: string;
}

/** An on/off switch with its label and hint: one button (role="switch"), at least 44 px tall. */
export function Switch({ checked, onChange, label, hint }: Props) {
  const hintId = useId();
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="min-w-0">
        <p className="font-semibold">{label}</p>
        {hint && (
          <p id={hintId} className="text-sm text-ink-2">
            {hint}
          </p>
        )}
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        aria-describedby={hint ? hintId : undefined}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-11 w-[60px] shrink-0 items-center rounded-full border-2 px-1 ${checked ? 'border-brand-ink bg-brand' : 'border-ink-2 bg-line'}`}
      >
        <span aria-hidden="true" className={`size-7 rounded-full bg-white shadow transition-transform ${checked ? 'translate-x-[24px]' : 'translate-x-0'}`} />
      </button>
    </div>
  );
}
