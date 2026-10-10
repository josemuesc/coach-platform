/** Display helpers for the coach's screens. Dates are America/Bogota; money is whole Colombian pesos. None of them decides anything. */
const ZONE = 'America/Bogota';
const MONTHS = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];
const WEEKDAYS = ['dom', 'lun', 'mar', 'mié', 'jue', 'vie', 'sáb'];

/** 180000 -> "$180.000" (a fixed thousands dot: the "es" locales skip the grouping of four-digit numbers). */
export function formatCop(amount: number): string {
  const sign = amount < 0 ? '-' : '';
  return `${sign}$${String(Math.abs(Math.trunc(amount))).replace(/\B(?=(\d{3})+(?!\d))/g, '.')}`;
}

/** What the person typed ("$ 180.000", "180000") -> 180000, or null when there are no digits. */
export function parseCop(text: string): number | null {
  const digits = text.replace(/\D/g, '');
  return digits === '' ? null : Number(digits);
}

/** "2026-09-12" -> "12 sep" (taken from the text, so no time zone can move it). */
export function formatDayMonth(day: string): string {
  const [, month, d] = day.split('-');
  return `${Number(d)} ${MONTHS[Number(month) - 1] ?? ''}`;
}

/** "2026-09-12" -> "12 sep 2026". */
export function formatDayMonthYear(day: string): string {
  return `${formatDayMonth(day)} ${day.slice(0, 4)}`;
}

/** The Bogota calendar day of an instant, as YYYY-MM-DD. */
export function bogotaDay(iso: string | Date): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(typeof iso === 'string' ? new Date(iso) : iso);
}

export function todayBogota(): string {
  return bogotaDay(new Date());
}

/** "Vie 9 oct · 7:00 a. m." for an instant. */
export function formatWeekdayDateTime(iso: string): string {
  const date = new Date(iso);
  const day = bogotaDay(date);
  const weekday = new Intl.DateTimeFormat('es-CO', { timeZone: ZONE, weekday: 'short' }).format(date).replace('.', '');
  const time = new Intl.DateTimeFormat('es-CO', { timeZone: ZONE, hour: 'numeric', minute: '2-digit', hour12: true }).format(date).replace(/\s/g, ' ');
  const label = weekday.charAt(0).toUpperCase() + weekday.slice(1);
  return `${label} ${formatDayMonth(day)} · ${time}`;
}

/** "12 sep, 3:00 p. m." for an instant (when something was created or expires). */
export function formatDayMonthTime(iso: string): string {
  const date = new Date(iso);
  const time = new Intl.DateTimeFormat('es-CO', { timeZone: ZONE, hour: 'numeric', minute: '2-digit', hour12: true }).format(date).replace(/\s/g, ' ');
  return `${formatDayMonth(bogotaDay(date))}, ${time}`;
}

export function weekdayShort(day: string): string {
  const d = new Date(`${day}T12:00:00Z`);
  return WEEKDAYS[d.getUTCDay()] ?? '';
}

/**
 * Whether a person born on `birthDate` is under 18 on `today` (both YYYY-MM-DD, Bogota). ONLY used to decide which fields the new-student
 * form shows: the server decides for real (GUARDIAN_REQUIRED). Same convention as the server: someone born on 29 February turns 18 on 1 March.
 */
export function isMinorOn(birthDate: string, today: string): boolean {
  const [y, m, d] = birthDate.split('-').map(Number);
  if (!y || !m || !d) return false;
  const leapBaby = m === 2 && d === 29;
  const month = leapBaby ? 3 : m;
  const dayOfMonth = leapBaby ? 1 : d;
  const adultOn = `${String(y + 18).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(dayOfMonth).padStart(2, '0')}`;
  return today < adultOn;
}

/**
 * The wa.me link that opens WhatsApp with the message already written (nothing is sent by itself). With the number only when it is a
 * Colombian mobile (10 digits starting with 3, optionally written with the 57 prefix); otherwise without a number, so the coach picks
 * the chat.
 */
export function whatsappLink(phone: string | null | undefined, message: string): string {
  const digits = (phone ?? '').replace(/\D/g, '');
  const local = digits.length === 12 && digits.startsWith('57') ? digits.slice(2) : digits;
  const text = encodeURIComponent(message);
  return /^3\d{9}$/.test(local) ? `https://wa.me/57${local}?text=${text}` : `https://wa.me/?text=${text}`;
}

/** The invitation text. It names the brand and the person only: never anything about their health or their plan. */
export function invitationMessage(recipientName: string, brandName: string, url: string): string {
  return `Hola ${recipientName}, ${brandName} te invita a crear tu cuenta: ${url}`;
}

// ---- the week and the clock of the calendar screens (display and serialization only; what is bookable is the server's) ----

const WEEKDAY_NAMES = ['domingo', 'lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado'];
const MONTH_NAMES = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];
/** The letters of the week strip, Monday first. */
export const WEEKDAY_LETTERS = ['L', 'M', 'X', 'J', 'V', 'S', 'D'] as const;

/** A YYYY-MM-DD moved by whole days (calendar arithmetic only: no zone can shift it). */
export function addDays(day: string, n: number): string {
  const d = new Date(`${day}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

/** The Monday of the week that holds `day`. */
export function mondayOf(day: string): string {
  const dow = new Date(`${day}T12:00:00Z`).getUTCDay();
  return addDays(day, -((dow + 6) % 7));
}

/** "lunes", "martes"... for a YYYY-MM-DD. */
export function weekdayName(day: string): string {
  return WEEKDAY_NAMES[new Date(`${day}T12:00:00Z`).getUTCDay()] ?? '';
}

/** Name of a weekday from the server's number (1 = Monday ... 7 = Sunday), capitalized. */
export function dayOfWeekName(dayOfWeek: number): string {
  const name = WEEKDAY_NAMES[dayOfWeek % 7] ?? '';
  return name.charAt(0).toUpperCase() + name.slice(1);
}

/** "OCTUBRE 2026" */
export function monthYearHeader(day: string): string {
  return `${MONTH_NAMES[Number(day.slice(5, 7)) - 1] ?? ''} ${day.slice(0, 4)}`.toUpperCase();
}

/** "Viernes 9 de octubre" */
export function longDay(day: string): string {
  const name = weekdayName(day);
  return `${name.charAt(0).toUpperCase()}${name.slice(1)} ${Number(day.slice(8))} de ${MONTH_NAMES[Number(day.slice(5, 7)) - 1] ?? ''}`;
}

/** "vie 9 oct" */
export function shortDay(day: string): string {
  return `${weekdayName(day).slice(0, 3)} ${formatDayMonth(day)}`;
}

/** "5 – 11 oct" for the week that starts on `monday` (the month shows once when both ends share it). */
export function weekRange(monday: string): string {
  const end = addDays(monday, 6);
  const [, m1] = monday.split('-');
  const [, m2] = end.split('-');
  return m1 === m2 ? `${Number(monday.slice(8))} – ${formatDayMonth(end)}` : `${formatDayMonth(monday)} – ${formatDayMonth(end)}`;
}

/** "06:00" -> "6:00 a. m.", "12:00" -> "12:00 m.", "16:30" -> "4:30 p. m." The coach writes noon as "12:00 m.". */
export function hhmmLabel(hhmm: string): string {
  const [h, m] = hhmm.split(':').map(Number);
  if (h === undefined || m === undefined || Number.isNaN(h) || Number.isNaN(m)) return hhmm;
  const minutes = String(m).padStart(2, '0');
  if (h === 12 && m === 0) return '12:00 m.';
  const period = h < 12 ? 'a. m.' : 'p. m.';
  return `${h % 12 === 0 ? 12 : h % 12}:${minutes} ${period}`;
}

/** The instant of a local Bogota day and time ("2026-10-10", "15:00"), as the ISO string the API takes. Bogota has no daylight saving. */
export function instantAt(day: string, hhmm: string): string {
  return new Date(`${day}T${hhmm}:00-05:00`).toISOString();
}
