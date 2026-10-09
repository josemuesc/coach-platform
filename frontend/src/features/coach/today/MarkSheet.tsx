import { useState } from 'react';
import { messageFor } from '../../../api/errors';
import { daysBetween, plural } from '../../../lib/time';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { Chip } from '../../../ui/Chip';
import { Dialog, Sheet } from '../../../ui/Overlays';
import type { MarkRequest } from './MarkConfirmDialog';
import { useCancelEvent, type Attendee, type BillingOverview, type EventView, type MarkResult } from './queries';
import { Avatar, eventLabel, markRequest, pendingToMark } from './shared';

interface Props {
  event: EventView | null;
  today: string;
  overview: BillingOverview[] | undefined;
  onClose: () => void;
  onMark: (request: MarkRequest) => void;
  onCancelled: () => void;
}

function Segmented({ attendee, onMark }: { attendee: Attendee; onMark: (request: MarkRequest) => void }) {
  const options: { result: MarkResult; label: string; status: Attendee['status'] }[] = [
    { result: 'ATTENDED', label: 'Asistió', status: 'ATTENDED' },
    { result: 'NO_SHOW', label: 'No vino', status: 'NO_SHOW' },
  ];
  return (
    <div role="group" aria-label={`Resultado de ${attendee.studentName}`} className="grid grid-cols-2 gap-2">
      {options.map((o) => {
        const selected = attendee.status === o.status;
        return (
          <button
            key={o.result}
            type="button"
            aria-pressed={selected}
            disabled={!attendee.canMark}
            onClick={() => !selected && onMark(markRequest(attendee, o.result))}
            className={`min-h-12 rounded-xl border font-semibold disabled:opacity-60 ${selected ? 'border-transparent bg-brand text-brand-contrast' : 'border-line bg-white text-ink'}`}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

function PlanNote({ overview, today }: { overview: BillingOverview | undefined; today: string }) {
  if (!overview || overview.classesRemaining === null) return null;
  const left = overview.classesRemaining;
  const days = overview.endDate ? daysBetween(today, overview.endDate) : null;
  return (
    <p className="flex flex-wrap items-center gap-2 text-sm text-ink-2">
      <span>{left === 1 ? 'le queda 1 clase' : `le quedan ${left} clases`}</span>
      {overview.status === 'EXPIRING_SOON' && days !== null && (
        <Chip tone="amber">{days <= 0 ? 'Plan vence hoy' : `Plan vence en ${plural(days, 'día', 'días')}`}</Chip>
      )}
    </p>
  );
}

function CancelEventDialog({ event, onClose, onDone }: { event: EventView | null; onClose: () => void; onDone: () => void }) {
  const cancel = useCancelEvent();
  const [reason, setReason] = useState('');
  const affected = event ? event.attendees.filter((a) => a.status === 'SCHEDULED') : [];

  function close() {
    cancel.reset();
    setReason('');
    onClose();
  }

  async function submit() {
    if (!event) return;
    try {
      await cancel.mutateAsync({ eventId: event.id, reason: reason.trim() });
      cancel.reset();
      setReason('');
      onDone();
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open={event !== null} onOpenChange={(open) => !open && close()} title="Cancelar la clase" description="No se descuenta ninguna clase a los alumnos.">
      <div className="flex flex-col gap-3">
        <div>
          <p className="text-sm font-semibold">Alumnos afectados:</p>
          <ul className="list-disc pl-5 text-sm">
            {affected.map((a) => (
              <li key={a.attendanceId}>{a.studentName}</li>
            ))}
          </ul>
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="cancel-reason" className="text-sm font-semibold">
            Motivo
          </label>
          <textarea
            id="cancel-reason"
            value={reason}
            maxLength={500}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            className="rounded-xl border border-line bg-white p-3 text-base"
          />
        </div>
        {cancel.error !== null && <Banner tone="red" role="alert">{messageFor(cancel.error)}</Banner>}
        <div className="flex gap-3">
          <Button variant="secondary" className="flex-1" onClick={close} disabled={cancel.isPending}>
            Volver
          </Button>
          <Button variant="danger" className="flex-1" loading={cancel.isPending} disabled={reason.trim() === ''} onClick={() => void submit()}>
            Cancelar la clase
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

/** "Marcar asistencia" for one class: per student Asistió / No vino, the plan's state, a bulk mark and the cancellation. */
export function MarkSheet({ event, today, overview, onClose, onMark, onCancelled }: Props) {
  const [cancelling, setCancelling] = useState(false);
  const byStudent = new Map((overview ?? []).map((o) => [o.studentId, o]));
  const pending = event ? pendingToMark(event) : [];
  const anyScheduled = event ? event.attendees.some((a) => a.status === 'SCHEDULED') : false;

  return (
    <>
      <Sheet open={event !== null} onOpenChange={(open) => !open && onClose()} title="Marcar asistencia" description={event ? eventLabel(event) : undefined}>
        {event && (
          <div className="flex flex-col gap-4">
            <ul className="flex flex-col gap-4">
              {event.attendees.map((a) => (
                <li key={a.attendanceId} data-attendance={a.attendanceId} tabIndex={-1} className="flex flex-col gap-2 outline-offset-2">
                  <div className="flex items-center gap-3">
                    <Avatar name={a.studentName} />
                    <p className="font-semibold">
                      {a.studentName}
                      {a.minor && <span className="ml-2"><Chip tone="amber">Menor</Chip></span>}
                    </p>
                  </div>
                  <PlanNote overview={byStudent.get(a.studentId)} today={today} />
                  <Segmented attendee={a} onMark={onMark} />
                </li>
              ))}
            </ul>
            <p className="text-sm text-ink-2">Descuenta 1 clase del plan, tanto si asistió como si no vino. Puedes cambiar entre Asistió y No vino, pero no quitar la marca.</p>
            {pending.length > 0 && (
              <Button onClick={() => onMark({ kind: 'bulk', eventId: event.id, attendanceIds: pending.map((a) => a.attendanceId) })}>
                {pending.length === 1 ? 'Marcar al pendiente como que asistió' : `Marcar a los ${pending.length} pendientes como que asistieron`}
              </Button>
            )}
            {anyScheduled && (
              <Button variant="dangerGhost" onClick={() => setCancelling(true)}>
                Cancelar la clase (se pide un motivo)
              </Button>
            )}
          </div>
        )}
      </Sheet>
      <CancelEventDialog
        event={cancelling ? event : null}
        onClose={() => setCancelling(false)}
        onDone={() => {
          setCancelling(false);
          onCancelled();
        }}
      />
    </>
  );
}
