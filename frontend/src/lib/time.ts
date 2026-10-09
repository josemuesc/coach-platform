/** Display only: the trainer's day is America/Bogota. Which windows apply is always the server's decision. */
const ZONE = 'America/Bogota';

export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat('es-CO', { timeZone: ZONE, dateStyle: 'long', timeStyle: 'short' }).format(new Date(iso));
}

export function formatDate(iso: string): string {
  return new Intl.DateTimeFormat('es-CO', { timeZone: ZONE, dateStyle: 'long' }).format(new Date(iso));
}

/** "7:00" and "a. m." for the time column. Display only. */
export function timeParts(iso: string): { time: string; period: string } {
  const parts = new Intl.DateTimeFormat('es-CO', { timeZone: ZONE, hour: 'numeric', minute: '2-digit', hour12: true }).formatToParts(new Date(iso));
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return { time: `${get('hour')}:${get('minute')}`, period: get('dayPeriod').replace(/\s/g, ' ') };
}

/** "7:00 a. m." in one piece, for sentences and accessible names. */
export function clockTime(iso: string): string {
  const { time, period } = timeParts(iso);
  return `${time} ${period}`;
}

/** "VIE 9 OCT" for the header of "Hoy". `day` is the server's YYYY-MM-DD. */
export function headerDate(day: string): string {
  return new Intl.DateTimeFormat('es-CO', { timeZone: ZONE, weekday: 'short', day: 'numeric', month: 'short' })
    .format(new Date(`${day}T12:00:00-05:00`))
    .replace(/[.,]/g, '')
    .replace(/ de /g, ' ')
    .toUpperCase();
}

/** "en 1 h 36 min". The server decides what is upcoming; this only words the distance. */
export function untilText(iso: string, nowMs: number): string {
  const minutes = Math.max(0, Math.ceil((new Date(iso).getTime() - nowMs) / 60_000));
  if (minutes < 1) return 'en menos de 1 min';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `en ${m} min`;
  return m === 0 ? `en ${h} h` : `en ${h} h ${m} min`;
}

export function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** Whole days from one YYYY-MM-DD to another. */
export function daysBetween(fromDay: string, toDay: string): number {
  return Math.round((Date.parse(`${toDay}T00:00:00Z`) - Date.parse(`${fromDay}T00:00:00Z`)) / 86_400_000);
}
