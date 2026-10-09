import { Link } from 'react-router';
import { timeParts, untilText } from '../../../lib/time';
import { Button } from '../../../ui/Button';
import { Chip } from '../../../ui/Chip';
import { CheckIcon, CrossIcon } from '../../../ui/icons';
import type { MarkRequest } from './MarkConfirmDialog';
import type { Attendee, EventView } from './queries';
import { Avatar, confirmationText, eventTitle, markRequest, modalityLabel, pendingToMark, seatsText } from './shared';

interface Props {
  event: EventView;
  nowMs: number;
  onMark: (request: MarkRequest) => void;
  onOpenSheet: (eventId: string) => void;
}

function TimeColumn({ iso }: { iso: string }) {
  const { time, period } = timeParts(iso);
  return (
    <div className="w-[52px] shrink-0 pt-3 text-right">
      <p className="font-display text-base font-bold leading-tight">{time}</p>
      <p className="text-[11px] leading-tight text-ink-2">{period}</p>
    </div>
  );
}

function resultChip(a: Attendee) {
  if (a.status === 'ATTENDED') return <Chip tone="green">Asistió</Chip>;
  if (a.status === 'NO_SHOW') return <Chip tone="red">No vino</Chip>;
  return null;
}

/** The two buttons of a row. They exist only when the server says the place can be marked. */
function MarkButtons({ attendee, onMark }: { attendee: Attendee; onMark: (request: MarkRequest) => void }) {
  return (
    <div className="flex gap-2">
      <button
        type="button"
        aria-label={`Marcar que ${attendee.studentName} asistió`}
        onClick={() => onMark(markRequest(attendee, 'ATTENDED'))}
        className="inline-flex size-11 items-center justify-center rounded-xl bg-green-ink text-white"
      >
        <CheckIcon />
      </button>
      <button
        type="button"
        aria-label={`Marcar que ${attendee.studentName} no vino`}
        onClick={() => onMark(markRequest(attendee, 'NO_SHOW'))}
        className="inline-flex size-11 items-center justify-center rounded-xl bg-red-ink text-white"
      >
        <CrossIcon />
      </button>
    </div>
  );
}

function PastCard({ event, onOpenSheet }: Props) {
  const toMark = pendingToMark(event).length;
  return (
    <div className="flex-1 rounded-[18px] bg-past p-3">
      <p className="font-display text-base font-bold">{eventTitle(event)}</p>
      <p className="text-sm text-ink-2">{modalityLabel(event)}</p>
      <ul className="mt-2 flex flex-col gap-1">
        {event.attendees.map((a) => (
          <li key={a.attendanceId} className="flex items-center justify-between gap-2 text-sm">
            <span>{a.studentName}</span>
            {resultChip(a)}
          </li>
        ))}
      </ul>
      {toMark > 0 && (
        <div className="mt-2 flex items-center justify-between gap-2">
          <Chip tone="amber">{toMark === 1 ? 'Por marcar' : `${toMark} por marcar`}</Chip>
          <Button variant="secondary" onClick={() => onOpenSheet(event.id)}>
            Marcar
          </Button>
        </div>
      )}
    </div>
  );
}

function UpcomingCard({ event, nowMs }: Props) {
  const hasMinor = event.attendees.some((a) => a.minor);
  return (
    <div className="flex-1 rounded-[18px] border border-line bg-white p-3">
      <div className="flex items-start justify-between gap-2">
        <p className="font-display text-base font-bold">{eventTitle(event)}</p>
        {hasMinor && <Chip tone="amber">Menor</Chip>}
      </div>
      <p className="text-sm text-ink-2">
        {modalityLabel(event)}
        {event.modality === 'SEMI_PERSONALIZED' && ` · ${seatsText(event)}`}
      </p>
      <p className="mt-1 text-sm font-semibold text-brand-ink">{untilText(event.startsAt, nowMs)}</p>
    </div>
  );
}

function NowCard({ event, onMark, onOpenSheet }: Props) {
  const marked = event.attendees.filter((a) => a.status === 'ATTENDED' || a.status === 'NO_SHOW').length;
  const total = event.attendees.length;
  return (
    <div className="flex-1 rounded-[18px] bg-brand p-3 text-brand-contrast">
      <div className="flex items-center justify-between gap-2">
        <span className="rounded-full bg-white px-2.5 py-0.5 text-xs font-bold text-ink">EN CURSO</span>
        {event.modality === 'SEMI_PERSONALIZED' && <span className="text-sm font-semibold">{seatsText(event)}</span>}
      </div>
      <h3 className="mt-2 font-display text-[19px] font-bold leading-tight">
        <button type="button" onClick={() => onOpenSheet(event.id)} className="min-h-11 text-left underline underline-offset-4">
          {eventTitle(event)}
        </button>
      </h3>
      <div className="mt-1">
        <div
          role="progressbar"
          aria-label="Alumnos marcados"
          aria-valuemin={0}
          aria-valuemax={total}
          aria-valuenow={marked}
          className="h-1.5 overflow-hidden rounded-full bg-white/30"
        >
          <div className="h-full rounded-full bg-white" style={{ width: total === 0 ? '0%' : `${(marked / total) * 100}%` }} />
        </div>
        <p className="mt-1 text-sm">
          {marked} de {total} marcados
        </p>
      </div>
      <div className="mt-3 rounded-2xl bg-white p-2 text-ink">
        <ul className="flex flex-col">
          {event.attendees.map((a) => (
            <li key={a.attendanceId} data-attendance={a.attendanceId} tabIndex={-1} className="flex items-center gap-3 rounded-xl p-1.5 outline-offset-0">
              <Avatar name={a.studentName} />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-semibold">
                  {a.studentName}
                  {a.minor && <span className="ml-1.5 align-middle"><Chip tone="amber">Menor</Chip></span>}
                </p>
                <p className="text-xs text-ink-2">{confirmationText(a)}</p>
              </div>
              {resultChip(a) ?? (a.canMark ? <MarkButtons attendee={a} onMark={onMark} /> : null)}
            </li>
          ))}
        </ul>
        {event.canShowQr ? (
          <Link
            to={`/coach/qr/${event.id}`}
            className="mt-2 flex min-h-[46px] items-center justify-center rounded-xl border border-brand-ink px-4 font-semibold text-brand-ink"
          >
            Mostrar QR a los alumnos
          </Link>
        ) : (
          <button type="button" disabled className="mt-2 flex min-h-[46px] w-full items-center justify-center rounded-xl border border-line px-4 font-semibold text-ink-2 opacity-70">
            Mostrar QR a los alumnos · Aún no disponible
          </button>
        )}
      </div>
    </div>
  );
}

export function EventCard(props: Props) {
  const { phase } = props.event;
  return (
    <li className="flex gap-3">
      <TimeColumn iso={props.event.startsAt} />
      {phase === 'PAST' ? <PastCard {...props} /> : phase === 'NOW' ? <NowCard {...props} /> : <UpcomingCard {...props} />}
    </li>
  );
}
