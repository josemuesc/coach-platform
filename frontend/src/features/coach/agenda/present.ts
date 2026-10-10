import { hhmmLabel, longDay } from '../../../lib/format';
import { plural } from '../../../lib/time';
import { modalityName, type Tone } from '../students/present';
import type { AgendaDay, AgendaRow, BookableStudent, BookingSlot } from './queries';

type EventRow = NonNullable<AgendaRow['event']>;

/** "Viernes 9 de octubre · 5 clases · 2 espacios libres". The counts are the server's. */
export function daySummary(date: string, day: Pick<AgendaDay, 'classCount' | 'freeCount'>): string {
  return `${longDay(date)} · ${plural(day.classCount, 'clase', 'clases')} · ${plural(day.freeCount, 'espacio libre', 'espacios libres')}`;
}

/** "Compartida · 2 alumnos" when an exception put more people than the capacity (the server's `shared`); "3 de 4 cupos" for a semi-personalized class; null for a personalized one. */
export function seatsText(event: EventRow): string | null {
  if (event.shared) return `Compartida · ${event.occupied} alumnos`;
  return event.modality === 'SEMI_PERSONALIZED' ? `${event.occupied} de ${event.capacity} cupos` : null;
}

/** What happened to a class that already ended, from the statuses the server reports; null while it has not ended. */
export function pastChip(event: EventRow): { tone: Tone; text: string } | null {
  if (event.phase !== 'PAST') return null;
  const statuses = event.attendees.map((a) => a.status);
  if (statuses.length > 0 && statuses.every((s) => s === 'ATTENDED')) return { tone: 'green', text: 'Asistió' };
  if (statuses.length > 0 && statuses.every((s) => s === 'NO_SHOW')) return { tone: 'neutral', text: 'No vino' };
  if (statuses.includes('SCHEDULED')) return { tone: 'amber', text: 'Por marcar' };
  return { tone: 'neutral', text: 'Marcada' };
}

/** "10:00 – 12:00" of a block, or "Todo el día". `localTime` is the row's start; the end is read from the instant. */
export function blockRange(row: Pick<AgendaRow, 'allDay' | 'startsAt' | 'endsAt'>, clock: (iso: string) => string): string {
  return row.allDay ? 'Todo el día' : `${clock(row.startsAt)} – ${clock(row.endsAt)}`;
}

/** "1 cupo libre", "4 cupos libres", or "espacio libre" for a personalized one, with the hour. */
export function slotNote(slot: Pick<BookingSlot, 'modality' | 'capacity' | 'occupied' | 'localTime'>): string {
  const free = Math.max(0, slot.capacity - slot.occupied);
  const space = slot.modality === 'PERSONALIZED' ? 'espacio libre' : plural(free, 'cupo libre', 'cupos libres');
  return `${modalityName(slot.modality)} · ${space} a las ${hhmmLabel(slot.localTime)}`;
}

/** The short reason an exception is needed for a slot (the server named the rule). */
export function overrideNeed(blockedBy: BookingSlot['blockedBy']): string {
  switch (blockedBy) {
    case 'EVENT_FULL':
      return 'lleno';
    case 'MODALITY_MISMATCH':
      return 'otra modalidad';
    case 'SLOT_TAKEN':
      return 'ocupado';
    default:
      return 'excepción';
  }
}

/** The line of one student in the selector, and why they cannot be chosen when they cannot. */
export function studentOption(s: BookableStudent): string {
  if (s.canBook) return `${s.fullName} · ${modalityName(s.modality)} · ${plural(s.classesAvailable, 'clase por agendar', 'clases por agendar')}`;
  return `${s.fullName} · ${s.blockedReason === 'NO_CLASSES_LEFT' ? 'sin clases por agendar' : 'sin ciclo activo'}`;
}

/** The line under the selector once a student is chosen. */
export function studentSummary(s: BookableStudent): string {
  if (!s.canBook) return s.blockedReason === 'NO_CLASSES_LEFT' ? 'Ya tiene agendadas todas las clases de su plan.' : 'No tiene un plan activo.';
  return `${modalityName(s.modality)} · le ${s.classesAvailable === 1 ? 'queda 1 clase' : `quedan ${s.classesAvailable} clases`} por agendar`;
}

export const REASON_CHIPS = [
  { key: 'REPOSICION', label: 'Reposición' },
  { key: 'COMPARTIDO', label: 'Entreno compartido' },
  { key: 'OTRO', label: 'Otro' },
] as const;
export type ReasonKey = (typeof REASON_CHIPS)[number]['key'];

/** The text a quick reason writes in the field ('Otro' leaves it for the person to write). */
export function reasonTextFor(key: ReasonKey): string {
  return key === 'OTRO' ? '' : (REASON_CHIPS.find((c) => c.key === key)?.label ?? '');
}
