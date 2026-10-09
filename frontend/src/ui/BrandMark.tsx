import { initials } from '../brand/applyBrand';

/** The circle in the trainer's color with the initials of the brand name. */
export function BrandMark({ name, size = 40 }: { name: string | null | undefined; size?: number }) {
  return (
    <span
      aria-hidden="true"
      className="inline-flex shrink-0 items-center justify-center rounded-full bg-brand font-display font-bold text-brand-contrast"
      style={{ width: size, height: size, fontSize: size * 0.4 }}
    >
      {initials(name)}
    </span>
  );
}
