import { describe, expect, it } from 'vitest';
import { daysInMonth, initialMonth, inRange, monthCells, monthHasAllowedDay, shiftMonth } from './calendar';

describe('month grid', () => {
  it('starts on Monday: October 2026 begins on a Thursday, so three blanks come first', () => {
    const cells = monthCells({ year: 2026, month: 10 });
    expect(cells.slice(0, 4)).toEqual([null, null, null, '2026-10-01']);
    expect(cells).toHaveLength(3 + 31);
    expect(cells.at(-1)).toBe('2026-10-31');
  });

  it('has no blanks when the month starts on a Monday', () => {
    expect(monthCells({ year: 2026, month: 6 })[0]).toBe('2026-06-01');   // 1 June 2026 is a Monday
  });

  it('knows leap years and month lengths', () => {
    expect(daysInMonth(2028, 2)).toBe(29);
    expect(daysInMonth(2026, 2)).toBe(28);
    expect(daysInMonth(2026, 4)).toBe(30);
    expect(monthCells({ year: 2028, month: 2 }).at(-1)).toBe('2028-02-29');
  });

  it('moves between months across year ends', () => {
    expect(shiftMonth({ year: 2026, month: 12 }, 1)).toEqual({ year: 2027, month: 1 });
    expect(shiftMonth({ year: 2026, month: 1 }, -1)).toEqual({ year: 2025, month: 12 });
    expect(shiftMonth({ year: 2026, month: 3 }, -14)).toEqual({ year: 2025, month: 1 });
  });
});

describe('bounds', () => {
  it('includes both ends', () => {
    expect(inRange('2026-10-10', '2026-10-10', '2026-10-20')).toBe(true);
    expect(inRange('2026-10-20', '2026-10-10', '2026-10-20')).toBe(true);
    expect(inRange('2026-10-09', '2026-10-10', '2026-10-20')).toBe(false);
    expect(inRange('2026-10-21', '2026-10-10', '2026-10-20')).toBe(false);
    expect(inRange('1999-01-01')).toBe(true);
  });

  it('disables whole months outside the bounds', () => {
    expect(monthHasAllowedDay({ year: 2026, month: 9 }, '2026-10-10', '2026-12-31')).toBe(false);
    expect(monthHasAllowedDay({ year: 2026, month: 10 }, '2026-10-31', null)).toBe(true);
    expect(monthHasAllowedDay({ year: 2027, month: 1 }, null, '2026-12-31')).toBe(false);
  });

  it('opens on the chosen day, else today, always inside the bounds', () => {
    expect(initialMonth('2026-05-14', '2026-10-10')).toEqual({ year: 2026, month: 5 });
    expect(initialMonth('', '2026-10-10')).toEqual({ year: 2026, month: 10 });
    expect(initialMonth('', '2026-10-10', '2026-12-01', null)).toEqual({ year: 2026, month: 12 });
    expect(initialMonth('', '2026-10-10', null, '2026-08-15')).toEqual({ year: 2026, month: 8 });
  });
});
