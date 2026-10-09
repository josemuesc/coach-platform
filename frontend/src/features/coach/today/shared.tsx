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

export function eventTitle(event: EventView): string {
  const names = event.attendees.map((a) => a.studentName);
  return names.length === 0 ? modalityLabel(event) : names.join(', ');
}

export function eventLabel(event: EventView): string {
  return `${modalityLabel(event)} de las ${clockTime(event.startsAt)}`;
}

/** What the student did about the class, in words, for the row under their name. */
export function confirmationText(a: Attendee): string {
  if (!a.studentConfirmed) return 'Sin confirmar';
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
  window.requestAnimationFrame(() => {
    document.querySelector<HTMLElement>(`[data-attendance="${CSS.escape(attendanceId)}"]`)?.focus();
  });
}
