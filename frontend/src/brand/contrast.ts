const HEX = /^#[0-9A-Fa-f]{6}$/;

export function isHexColor(value: unknown): value is string {
  return typeof value === 'string' && HEX.test(value);
}

function channel(v: number): number {
  const s = v / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}

/** WCAG relative luminance of #RRGGBB. */
export function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255);
}

export function contrastRatio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

function toHex(r: number, g: number, b: number): string {
  return '#' + [r, g, b].map((v) => Math.round(v).toString(16).padStart(2, '0')).join('').toUpperCase();
}

/**
 * The brand color made dark enough to be read as TEXT (and as a focus ring) on the light backgrounds of the app: the same hue,
 * mixed with black in small steps until it reaches 4.5:1. A bright yellow brand cannot be text as it is. Presentation only.
 */
export function brandInk(hex: string, background = '#F3F5F2'): string {
  const n = parseInt(hex.slice(1), 16);
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255] as const;
  for (let step = 0; step <= 20; step++) {
    const k = 1 - step * 0.05;
    const candidate = toHex(r * k, g * k, b * k);
    if (contrastRatio(candidate, background) >= 4.5 && contrastRatio(candidate, '#FFFFFF') >= 4.5) return candidate;
  }
  return '#000000';
}

const WHITE = '#FFFFFF';
const INK = '#0F1A17';
const BLACK = '#000000';
const AA = 4.5;

/**
 * The text color for a brand-colored surface. White, or the dark ink of the design; in the narrow band of mid-tone colors where
 * neither reaches WCAG AA (4.5:1), pure black or white, whichever reads better (one of them always does). Presentation only.
 */
export function readableOn(hex: string): string {
  const onWhite = contrastRatio(hex, WHITE);
  const onInk = contrastRatio(hex, INK);
  if (onWhite >= AA || onInk >= AA) return onWhite >= onInk ? WHITE : INK;
  return contrastRatio(hex, BLACK) >= onWhite ? BLACK : WHITE;
}
