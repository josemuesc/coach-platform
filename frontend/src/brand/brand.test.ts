import { beforeEach, describe, expect, it } from 'vitest';
import { DEFAULT_BRAND, applyBrand, initials } from './applyBrand';
import { brandInk, contrastRatio, isHexColor, readableOn } from './contrast';

describe('contrast', () => {
  it('picks white on dark brands and the dark ink on light ones', () => {
    expect(readableOn('#0B6E5C')).toBe('#FFFFFF');
    expect(readableOn('#112233')).toBe('#FFFFFF');
    expect(readableOn('#FFE066')).toBe('#0F1A17');
    expect(readableOn('#F3F5F2')).toBe('#0F1A17');
    expect(readableOn('#777777')).toBe('#000000'); // mid-tone: only pure black reaches AA
  });

  it('always reaches WCAG AA (4.5:1) with the color it picks, for the brand colors a trainer is likely to choose', () => {
    for (const hex of ['#0B6E5C', '#FF6A00', '#FFD60A', '#2563EB', '#7C3AED', '#E11D48', '#10B981', '#777777', '#999999', '#808080', '#767676']) {
      expect(contrastRatio(hex, readableOn(hex))).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('darkens a bright brand until it can be read as text and as a focus ring on the light backgrounds', () => {
    for (const hex of ['#FFD60A', '#FF6A00', '#10B981', '#0B6E5C', '#FFFFFF', '#00FFFF']) {
      const ink = brandInk(hex);
      expect(contrastRatio(ink, '#F3F5F2')).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(ink, '#FFFFFF')).toBeGreaterThanOrEqual(4.5);
    }
    expect(brandInk('#0B6E5C')).toBe('#0B6E5C'); // already dark enough: unchanged
  });

  it('accepts only #RRGGBB', () => {
    expect(isHexColor('#0b6e5c')).toBe(true);
    for (const bad of ['#FFF', '0B6E5C', 'red', '#0B6E5C0', 'url(x)', '#0B6E5C;}', '', null, undefined, 12]) {
      expect(isHexColor(bad)).toBe(false);
    }
  });
});

describe('applyBrand', () => {
  beforeEach(() => {
    document.head.innerHTML = '<meta name="theme-color" content="#000000">';
    document.documentElement.removeAttribute('style');
  });

  it('sets the brand variables and the theme color', () => {
    applyBrand('#aa5500');
    expect(document.documentElement.style.getPropertyValue('--brand')).toBe('#AA5500');
    expect(document.documentElement.style.getPropertyValue('--brand-contrast')).toBe('#FFFFFF');
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe('#AA5500');
  });

  it('falls back to the default for null and for anything that is not #RRGGBB (nothing odd reaches the style system)', () => {
    for (const bad of [null, undefined, 'red', 'url(javascript:1)', '#FFF', '#0B6E5C;color:red']) {
      applyBrand(bad);
      expect(document.documentElement.style.getPropertyValue('--brand')).toBe(DEFAULT_BRAND);
    }
  });

  it('builds the initials of the brand name', () => {
    expect(initials('Laura Gómez Fit')).toBe('LF');
    expect(initials('  coach  ')).toBe('C');
    expect(initials('')).toBe('·');
    expect(initials(null)).toBe('·');
  });
});
