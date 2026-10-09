import { brandInk, isHexColor, readableOn } from './contrast';

export const DEFAULT_BRAND = '#0B6E5C';

/**
 * Sets the trainer's color as CSS variables (--brand, --brand-contrast for text ON it, --brand-ink for brand-colored text and focus rings on light
 * backgrounds; --brand-soft is derived in CSS). The server already validates
 * #RRGGBB; this guard only keeps anything else from ever reaching the style system. null = the default color.
 * Uses the CSSOM (setProperty), which a CSP without 'unsafe-inline' allows.
 */
export function applyBrand(color: string | null | undefined): void {
  const brand = isHexColor(color) ? color.toUpperCase() : DEFAULT_BRAND;
  const root = document.documentElement;
  root.style.setProperty('--brand', brand);
  root.style.setProperty('--brand-contrast', readableOn(brand));
  root.style.setProperty('--brand-ink', brandInk(brand));
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', brand);
}

export function initials(name: string | null | undefined): string {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '·';
  const first = parts[0]?.[0] ?? '';
  const second = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : '';
  return (first + second).toUpperCase();
}
