import { useId, type InputHTMLAttributes, type Ref } from 'react';

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  hint?: string;
  error?: string | undefined;
  ref?: Ref<HTMLInputElement>;
}

export function Field({ label, hint, error, id, ref, ...rest }: Props) {
  const auto = useId();
  const inputId = id ?? auto;
  const describedBy = [hint ? `${inputId}-hint` : null, error ? `${inputId}-error` : null].filter(Boolean).join(' ') || undefined;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={inputId} className="text-sm font-semibold text-ink">
        {label}
      </label>
      <input
        id={inputId}
        ref={ref}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...rest}
        className="min-h-11 rounded-xl border border-line bg-white px-3 text-base text-ink"
      />
      {hint && (
        <p id={`${inputId}-hint`} className="text-sm text-ink-2">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${inputId}-error`} role="alert" className="text-sm font-semibold text-red-ink">
          {error}
        </p>
      )}
    </div>
  );
}
