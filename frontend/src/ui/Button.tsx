import type { ButtonHTMLAttributes } from 'react';

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost' | 'dangerGhost';

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-brand text-brand-contrast border-transparent',
  secondary: 'bg-white text-ink border-line',
  danger: 'bg-white text-red-ink border-red-ink',
  ghost: 'bg-transparent text-ink border-transparent underline',
  dangerGhost: 'bg-transparent text-red-ink border-transparent underline',
};

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  loading?: boolean;
}

/** Every touch target is at least 44 px tall. */
export function Button({ variant = 'primary', loading = false, disabled, className = '', children, ...rest }: Props) {
  return (
    <button
      type="button"
      {...rest}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`inline-flex min-h-11 min-w-11 items-center justify-center rounded-xl border px-4 font-semibold disabled:opacity-60 ${VARIANTS[variant]} ${className}`}
    >
      {loading ? 'Un momento…' : children}
    </button>
  );
}
