import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { addDays, longDay, mondayOf, monthYearHeader, todayBogota, weekRange, WEEKDAY_LETTERS } from '../../../lib/format';
import { clockTime, timeParts } from '../../../lib/time';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { Chip } from '../../../ui/Chip';
import { ChevronLeftIcon, ChevronRightIcon } from '../../../ui/icons';
import { EmptyState, ErrorState, Skeleton } from '../../../ui/States';
import { modalityName } from '../students/present';
import { BookSheet, type BookInitial } from './BookSheet';
import { blockRange, daySummary, pastChip, seatsText } from './present';
import { useWeek, type AgendaDay, type AgendaRow } from './queries';

const DAY_PARAM = /^\d{4}-\d{2}-\d{2}$/;

function TimeColumn({ iso, highlight }: { iso: string; highlight?: boolean }) {
  const { time, period } = timeParts(iso);
  return (
    <div className={`w-[52px] shrink-0 pt-3 ${highlight ? 'text-brand-ink' : ''}`}>
      <p className="font-display text-base font-bold leading-tight">{time}</p>
      <p className="text-[11px] font-semibold text-ink-2">{period}</p>
    </div>
  );
}

function EventCard({ event }: { event: NonNullable<AgendaRow['event']> }) {
  const chip = pastChip(event);
  const seats = seatsText(event);
  const past = event.phase === 'PAST';
  return (
    <div
      className={`flex-1 rounded-2xl border p-3 ${past ? 'border-transparent bg-past' : event.phase === 'NOW' ? 'border-2 border-brand-ink bg-white' : 'border-line bg-white'}`}
    >
      <div className="flex items-start justify-between gap-2">
        <p className="text-sm text-ink-2">{modalityName(event.modality)}</p>
        {chip ? <Chip tone={chip.tone}>{chip.text}</Chip> : seats && <p className="text-sm font-bold text-brand-ink">{seats}</p>}
      </div>
      <p className="mt-0.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 font-display text-lg font-bold leading-tight">
        {event.attendees.map((a, i) => (
          <span key={a.attendanceId} className="inline-flex flex-wrap items-center gap-1.5">
            {i > 0 && <span aria-hidden="true">·</span>}
            <span>{a.studentName}</span>
            {a.minor && <span className="rounded-full border border-ink-2 px-2 text-xs font-semibold text-ink-2">Menor</span>}
          </span>
        ))}
      </p>
    </div>
  );
}

function Row({ row, onBook }: { row: AgendaRow; onBook: (time: string) => void }) {
  return (
    <li className="flex gap-3">
      <TimeColumn iso={row.startsAt} highlight={row.kind === 'EVENT' && row.event?.phase === 'NOW'} />
      {row.kind === 'EVENT' && row.event && <EventCard event={row.event} />}
      {row.kind === 'FREE' && (
        <div className="flex min-h-14 flex-1 items-center justify-between gap-2 rounded-2xl border-2 border-dashed border-ink-2/40 px-3 py-2">
          <span className="font-display text-lg font-bold text-ink-2">Disponible</span>
          <button
            type="button"
            onClick={() => onBook(row.localTime)}
            aria-label={`Agendar a las ${clockTime(row.startsAt)}`}
            className="min-h-11 rounded-full px-3 font-semibold text-brand-ink"
          >
            + Agendar
          </button>
        </div>
      )}
      {row.kind === 'BLOCK' && (
        <div
          className="flex min-h-14 flex-1 items-center justify-between gap-2 rounded-2xl border border-line bg-past px-3 py-2"
          style={{ backgroundImage: 'repeating-linear-gradient(135deg, transparent 0 8px, rgba(15,26,23,0.06) 8px 16px)' }}
        >
          <span className="font-display text-lg font-bold">Bloqueado{row.blockReason ? ` · ${row.blockReason}` : ''}</span>
          <span className="shrink-0 text-sm text-ink-2">{blockRange(row, (iso) => timeParts(iso).time)}</span>
        </div>
      )}
    </li>
  );
}

function WeekStrip({ days, selected, onSelect }: { days: AgendaDay[]; selected: string; onSelect: (day: string) => void }) {
  return (
    <div role="group" aria-label="Días de la semana" className="flex justify-between gap-1">
      {days.map((d, i) => {
        const isSelected = d.localDate === selected;
        return (
          <button
            key={d.localDate}
            type="button"
            aria-pressed={isSelected}
            aria-label={`${longDay(d.localDate)}${d.hasAvailability ? '' : ' (sin horario)'}`}
            onClick={() => onSelect(d.localDate)}
            className={`flex min-h-16 w-11 flex-1 flex-col items-center justify-center rounded-2xl ${isSelected ? 'bg-brand text-brand-contrast' : ''}`}
          >
            <span className={`text-xs font-bold ${isSelected ? '' : d.hasAvailability ? 'text-brand-ink' : 'text-ink-2'}`}>{WEEKDAY_LETTERS[i]}</span>
            <span className={`font-display text-xl font-bold ${isSelected || d.hasAvailability ? '' : 'text-ink-2'}`}>{Number(d.localDate.slice(8))}</span>
          </button>
        );
      })}
    </div>
  );
}

export function AgendaPage() {
  const [params, setParams] = useSearchParams();
  const today = todayBogota();
  const raw = params.get('d');
  const selected = raw && DAY_PARAM.test(raw) ? raw : today;
  const monday = mondayOf(selected);
  const week = useWeek(monday);
  const [booking, setBooking] = useState<BookInitial | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function select(day: string) {
    setParams(day === today ? {} : { d: day }, { replace: true });
  }

  const day = week.data?.days.find((d) => d.localDate === selected);

  return (
    <section className="flex flex-col gap-4 px-4 pb-6 pt-2">
      <header className="flex items-end justify-between gap-3">
        <div>
          <p className="text-xs font-bold tracking-wider text-ink-2">{monthYearHeader(selected)}</p>
          <h1 className="font-display text-[34px] font-bold leading-tight">Agenda</h1>
        </div>
        <button
          type="button"
          onClick={() => setBooking({ date: selected })}
          className="inline-flex min-h-12 items-center rounded-full bg-brand px-5 font-semibold text-brand-contrast"
        >
          + Agendar
        </button>
      </header>

      {notice !== null && (
        <Banner tone="green" role="status">
          {notice}
        </Banner>
      )}

      <div className="flex items-center justify-between">
        <button type="button" aria-label="Semana anterior" onClick={() => select(addDays(selected, -7))} className="inline-flex size-11 items-center justify-center rounded-full">
          <ChevronLeftIcon />
        </button>
        <p className="text-sm font-semibold text-ink-2">{weekRange(monday)}</p>
        <button type="button" aria-label="Semana siguiente" onClick={() => select(addDays(selected, 7))} className="inline-flex size-11 items-center justify-center rounded-full">
          <ChevronRightIcon />
        </button>
      </div>

      {week.isPending && <Skeleton label="Cargando la agenda" />}
      {week.isError && !week.data && <ErrorState error={week.error} onRetry={() => void week.refetch()} />}

      {week.data && (
        <>
          <WeekStrip days={week.data.days} selected={selected} onSelect={select} />
          {selected !== today && (
            <Button variant="secondary" onClick={() => select(today)} className="self-start">
              Ir a hoy
            </Button>
          )}
          {day && (
            <>
              <p className="font-semibold text-ink-2">{daySummary(selected, day)}</p>
              {day.items.length === 0 ? (
                <EmptyState title={day.hasAvailability ? 'No hay horarios este día' : 'Sin horario este día'}>
                  <p className="text-ink-2">{day.hasAvailability ? 'Todo lo que quedaba libre ya pasó.' : 'No tienes franjas para este día de la semana.'}</p>
                  <Link to="/coach/availability" className="inline-flex min-h-11 items-center font-semibold text-brand-ink underline underline-offset-4">
                    Editar disponibilidad
                  </Link>
                </EmptyState>
              ) : (
                <ol aria-label="Agenda del día" className="flex flex-col gap-3">
                  {day.items.map((row) => (
                    <Row key={`${row.kind}-${row.startsAt}-${row.blockId ?? row.event?.id ?? ''}`} row={row} onBook={(time) => setBooking({ date: selected, time })} />
                  ))}
                </ol>
              )}
            </>
          )}
        </>
      )}

      {booking && (
        <BookSheet
          initial={booking}
          onClose={(booked) => {
            setBooking(null);
            if (booked) {
              setNotice(booked.text);
              select(booked.date);
            }
          }}
        />
      )}
    </section>
  );
}
