/** Calendar arithmetic for the date picker: plain YYYY-MM-DD strings, Monday first, no time zone involved. */

export interface YearMonth {
  year: number;
  month: number; // 1-12
}

export function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

export function dayString(year: number, month: number, day: number): string {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/** The cells of one month, Monday first: `null` for the blank cells before the 1st, then every day as YYYY-MM-DD. */
export function monthCells({ year, month }: YearMonth): (string | null)[] {
  const lead = (new Date(Date.UTC(year, month - 1, 1)).getUTCDay() + 6) % 7;
  const cells: (string | null)[] = Array.from({ length: lead }, () => null);
  for (let d = 1; d <= daysInMonth(year, month); d++) cells.push(dayString(year, month, d));
  return cells;
}

export function shiftMonth({ year, month }: YearMonth, delta: number): YearMonth {
  const index = year * 12 + (month - 1) + delta;
  return { year: Math.floor(index / 12), month: (index % 12) + 1 };
}

export function monthOf(day: string): YearMonth {
  return { year: Number(day.slice(0, 4)), month: Number(day.slice(5, 7)) };
}

/** Whether a day is inside the optional bounds (inclusive). */
export function inRange(day: string, min?: string | null, max?: string | null): boolean {
  return (!min || day >= min) && (!max || day <= max);
}

/** Whether at least one day of the month is inside the bounds (to disable a whole month in the month grid). */
export function monthHasAllowedDay({ year, month }: YearMonth, min?: string | null, max?: string | null): boolean {
  return inRange(dayString(year, month, daysInMonth(year, month)), min, null) && inRange(dayString(year, month, 1), null, max);
}

/** The month to show first: the chosen day, else today, kept inside the bounds. */
export function initialMonth(value: string, today: string, min?: string | null, max?: string | null): YearMonth {
  let day = value || today;
  if (min && day < min) day = min;
  if (max && day > max) day = max;
  return monthOf(day);
}
