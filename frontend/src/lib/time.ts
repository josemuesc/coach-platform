/** Display only: the trainer's day is America/Bogota. Which windows apply is always the server's decision. */
const ZONE = 'America/Bogota';

export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat('es-CO', { timeZone: ZONE, dateStyle: 'long', timeStyle: 'short' }).format(new Date(iso));
}

export function formatDate(iso: string): string {
  return new Intl.DateTimeFormat('es-CO', { timeZone: ZONE, dateStyle: 'long' }).format(new Date(iso));
}
