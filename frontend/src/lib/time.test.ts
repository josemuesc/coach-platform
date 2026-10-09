import { describe, expect, it } from 'vitest';
import { daysBetween, headerDate, plural, timeParts, untilText } from './time';

describe('time formatting (display only)', () => {
  it('splits a Bogota time into the hour and the period', () => {
    expect(timeParts('2026-10-09T12:00:00Z')).toEqual({ time: '7:00', period: 'a. m.' });
    expect(timeParts('2026-10-10T00:30:00Z')).toEqual({ time: '7:30', period: 'p. m.' });
  });

  it('words the distance to a class', () => {
    const now = Date.parse('2026-10-09T15:00:00Z');
    expect(untilText('2026-10-09T16:36:00Z', now)).toBe('en 1 h 36 min');
    expect(untilText('2026-10-09T17:00:00Z', now)).toBe('en 2 h');
    expect(untilText('2026-10-09T15:12:00Z', now)).toBe('en 12 min');
    expect(untilText('2026-10-09T15:00:20Z', now)).toBe('en 1 min');
    expect(untilText('2026-10-09T14:00:00Z', now)).toBe('en menos de 1 min');
  });

  it('writes the header date and pluralises', () => {
    expect(headerDate('2026-10-09')).toBe('VIE 9 OCT');
    expect(plural(1, 'día', 'días')).toBe('1 día');
    expect(plural(3, 'día', 'días')).toBe('3 días');
    expect(daysBetween('2026-10-09', '2026-10-12')).toBe(3);
  });
});
