import { describe, expect, it } from 'vitest';
import {
  addDays,
  bogotaDay,
  dayOfWeekName,
  hhmmLabel,
  instantAt,
  longDay,
  mondayOf,
  monthYearHeader,
  shortDay,
  weekRange,
  formatCop,
  formatDayMonth,
  formatDayMonthTime,
  formatDayMonthYear,
  formatWeekdayDateTime,
  invitationMessage,
  isMinorOn,
  parseCop,
  whatsappLink,
} from './format';

describe('money', () => {
  it('writes whole pesos with a thousands dot', () => {
    expect(formatCop(180000)).toBe('$180.000');
    expect(formatCop(1000)).toBe('$1.000');
    expect(formatCop(520000)).toBe('$520.000');
    expect(formatCop(1250000)).toBe('$1.250.000');
    expect(formatCop(999)).toBe('$999');
    expect(formatCop(0)).toBe('$0');
  });

  it('reads what the person typed', () => {
    expect(parseCop('$ 180.000')).toBe(180000);
    expect(parseCop('180000')).toBe(180000);
    expect(parseCop('')).toBeNull();
    expect(parseCop('abc')).toBeNull();
  });
});

describe('dates in America/Bogota', () => {
  it('writes a calendar day without letting a time zone move it', () => {
    expect(formatDayMonth('2026-09-12')).toBe('12 sep');
    expect(formatDayMonth('2026-01-05')).toBe('5 ene');
    expect(formatDayMonthYear('2026-10-09')).toBe('9 oct 2026');
  });

  it('takes the Bogota day of an instant, not the UTC one', () => {
    expect(bogotaDay('2026-10-10T02:00:00Z')).toBe('2026-10-09');    // 9 pm in Bogota
    expect(bogotaDay('2026-10-09T05:00:00Z')).toBe('2026-10-09');    // midnight in Bogota
    expect(bogotaDay('2026-10-09T04:59:59Z')).toBe('2026-10-08');
  });

  it('writes an instant as weekday, day, month and time', () => {
    expect(formatWeekdayDateTime('2026-10-09T12:00:00Z')).toBe('Vie 9 oct · 7:00 a. m.');
    expect(formatWeekdayDateTime('2026-10-12T22:00:00Z')).toBe('Lun 12 oct · 5:00 p. m.');
    expect(formatDayMonthTime('2026-10-10T20:00:00Z')).toBe('10 oct, 3:00 p. m.');
  });
});

describe('isMinorOn (only decides which fields the form shows)', () => {
  it('is a minor until the 18th birthday and an adult ON it', () => {
    expect(isMinorOn('2008-10-10', '2026-10-09')).toBe(true);     // turns 18 tomorrow
    expect(isMinorOn('2008-10-09', '2026-10-09')).toBe(false);    // turns 18 today
    expect(isMinorOn('2008-10-08', '2026-10-09')).toBe(false);
    expect(isMinorOn('2010-03-14', '2026-10-09')).toBe(true);
  });

  it('treats 29 February as 1 March, like the server', () => {
    expect(isMinorOn('2008-02-29', '2026-02-28')).toBe(true);
    expect(isMinorOn('2008-02-29', '2026-03-01')).toBe(false);
    expect(isMinorOn('2008-02-29', '2026-02-28')).toBe(true);
  });

  it('judges by the Bogota day: a few hours of the next UTC day do not make an adult', () => {
    const lateNightInBogota = bogotaDay('2026-10-10T03:00:00Z');   // still 9 Oct in Bogota
    expect(isMinorOn('2008-10-10', lateNightInBogota)).toBe(true);
  });

  it('is false for a value that is not a date', () => {
    expect(isMinorOn('', '2026-10-09')).toBe(false);
    expect(isMinorOn('hola', '2026-10-09')).toBe(false);
  });
});

describe('WhatsApp link', () => {
  const text = 'Hola Ana, Laura Fit te invita a crear tu cuenta: https://app.test/invite/abc';

  it('uses the number only when it is a clean Colombian mobile', () => {
    expect(whatsappLink('3001234567', text)).toBe(`https://wa.me/573001234567?text=${encodeURIComponent(text)}`);
    expect(whatsappLink('300 123 4567', text)).toContain('https://wa.me/573001234567?');
    expect(whatsappLink('+57 300 123 4567', text)).toContain('https://wa.me/573001234567?');
    expect(whatsappLink('573001234567', text)).toContain('https://wa.me/573001234567?');
  });

  it('leaves the number out otherwise, so the coach chooses the chat', () => {
    for (const phone of ['', null, undefined, '123', '6011234567', '30012345', '30012345678', 'abc']) {
      expect(whatsappLink(phone, text)).toBe(`https://wa.me/?text=${encodeURIComponent(text)}`);
    }
  });

  it('puts the message in the link, encoded, and nothing else about the person', () => {
    const url = whatsappLink('3001234567', invitationMessage('Marta', 'Laura Fit', 'https://app.test/invite/tok'));
    expect(decodeURIComponent(url.split('text=')[1] ?? '')).toBe('Hola Marta, Laura Fit te invita a crear tu cuenta: https://app.test/invite/tok');
  });
});

describe('the week of the calendar', () => {
  it('moves days by calendar arithmetic across month and year ends', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
    expect(addDays('2026-01-01', -1)).toBe('2025-12-31');
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
  });

  it('finds the Monday of any day of the week, Sunday included', () => {
    expect(mondayOf('2026-10-09')).toBe('2026-10-05');   // Friday
    expect(mondayOf('2026-10-05')).toBe('2026-10-05');   // Monday
    expect(mondayOf('2026-10-11')).toBe('2026-10-05');   // Sunday belongs to the week that started on Monday the 5th
    expect(mondayOf('2026-10-12')).toBe('2026-10-12');
  });

  it('words days and weeks in Spanish', () => {
    expect(monthYearHeader('2026-10-09')).toBe('OCTUBRE 2026');
    expect(longDay('2026-10-09')).toBe('Viernes 9 de octubre');
    expect(shortDay('2026-10-09')).toBe('vie 9 oct');
    expect(weekRange('2026-10-05')).toBe('5 – 11 oct');
    expect(weekRange('2026-10-26')).toBe('26 oct – 1 nov');
    expect(dayOfWeekName(1)).toBe('Lunes');
    expect(dayOfWeekName(7)).toBe('Domingo');
  });
});

describe('times of the day', () => {
  it('writes the coach\'s hours the way they say them', () => {
    expect(hhmmLabel('06:00')).toBe('6:00 a. m.');
    expect(hhmmLabel('12:00')).toBe('12:00 m.');
    expect(hhmmLabel('12:30')).toBe('12:30 p. m.');
    expect(hhmmLabel('16:15')).toBe('4:15 p. m.');
    expect(hhmmLabel('00:00')).toBe('12:00 a. m.');
    expect(hhmmLabel('23:45')).toBe('11:45 p. m.');
  });

  it('serializes a Bogota day and time as the instant the API takes', () => {
    expect(instantAt('2026-10-10', '15:00')).toBe('2026-10-10T20:00:00.000Z');
    expect(instantAt('2026-10-10', '00:00')).toBe('2026-10-10T05:00:00.000Z');
  });
});
