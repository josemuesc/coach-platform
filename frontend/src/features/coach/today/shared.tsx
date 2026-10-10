import type { Attendee, EventView, MarkResult } from './queries';
import type { MarkRequest } from './MarkConfirmDialog';
import { initials } from '../../../brand/applyBrand';
import { clockTime, timeParts } from '../../../lib/time';

export function modalityLabel(event: EventView): string {
  return event.modality === 'PERSONALIZED' ? 'Personalizada' : 'Semipersonalizada';
}

export function seatsText(event: EventView): string {
  return `${event.occupied} de ${event.capacity} cupos`;
}

/** Who is in the class, for cards that have no row per student. */
export function attendeeNames(event: EventView): string {
  return event.attendees.map((a) => a.studentName).join(', ');
}

export function eventLabel(event: EventView): string {
  return `${modalityLabel(event)} de las ${clockTime(event.startsAt)}`;
}

/** What the student did about the class, in words, for the row under their name. */
export function confirmationText(a: Attendee): string {
  if (!a.studentConfirmed) return a.onlyMarkedByCoach ? 'Marcado por ti' : 'Sin confirmar';
  const at = a.confirmedAt ? ` ${timeParts(a.confirmedAt).time}` : '';
  return a.confirmationMethod === 'QR' ? `QR${at}` : 'Confirmó después';
}

/** Places that can be marked now and have not been marked yet. The server decides ("canMark"); this only filters. */
export function pendingToMark(event: EventView): Attendee[] {
  return event.attendees.filter((a) => a.status === 'SCHEDULED' && a.canMark);
}

export function markRequest(a: Attendee, result: MarkResult): MarkRequest {
  return { kind: 'one', attendanceId: a.attendanceId, name: a.studentName, result, switching: a.status !== 'SCHEDULED' };
}

export function Avatar({ name }: { name: string }) {
  return (
    <span aria-hidden="true" className="inline-flex size-9 shrink-0 items-center justify-center rounded-full bg-brand-soft text-sm font-bold text-brand-ink">
      {initials(name)}
    </span>
  );
}

/** After a mark the button the coach pressed is gone (a chip replaces it): put the focus on that student's row instead of losing it. */
export function focusRow(attendanceId: string) {
  // The closing dialog also gives the focus back to the element that opened it (which no longer exists) and, under load, can do it AFTER
  // the first try: keep trying for a moment until the row really holds the focus.
  let tries = 0;
  const attempt = () => {
    const row = document.querySelector<HTMLElement>(`[data-attendance="${CSS.escape(attendanceId)}"]`);
    row?.focus();
    if (row && document.activeElement === row) return;
    if (++tries < 8) window.setTimeout(attempt, 60);
  };
  window.requestAnimationFrame(attempt);
}
