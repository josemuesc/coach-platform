import { useId, type InputHTMLAttributes, type ReactNode, type Ref } from 'react';

interface Props extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  children: ReactNode;
  ref?: Ref<HTMLInputElement>;
}

/** A checkbox whose whole row is the touch target (at least 44 px tall). */
export function Checkbox({ children, id, ref, ...rest }: Props) {
  const auto = useId();
  const inputId = id ?? auto;
  return (
    <label htmlFor={inputId} className="flex min-h-11 cursor-pointer items-start gap-3 py-1.5">
      <input id={inputId} ref={ref} type="checkbox" {...rest} className="mt-0.5 size-6 shrink-0 accent-[var(--brand-ink)]" />
      <span className="text-base text-ink">{children}</span>
    </label>
  );
}
