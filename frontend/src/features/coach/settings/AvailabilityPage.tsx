import { useState } from 'react';
import { messageFor } from '../../../api/errors';
import { dayOfWeekName, formatDayMonth, hhmmLabel } from '../../../lib/format';
import { Banner } from '../../../ui/Banner';
import { Dialog } from '../../../ui/Overlays';
import { Button } from '../../../ui/Button';
import { ChevronRightIcon } from '../../../ui/icons';
import { ErrorState, Skeleton } from '../../../ui/States';
import { BlockFlow } from './BlockFlow';
import { DaySheet } from './DaySheet';
import { BackToSettings } from './PlansPage';
import { useDeleteBlock, useUpcomingBlocks, useWeekly, type BlockSummary, type Window } from './queries';

const ORDER = [1, 2, 3, 4, 5, 6, 7];

/** "6:00 – 10:00 a. m.": the period is written once when both ends share it. */
function windowText(w: Window): string {
  const start = hhmmLabel(w.start);
  const end = hhmmLabel(w.end);
  const period = (t: string) => t.split(' ').slice(1).join(' ');
  return period(start) === period(end) && period(start) !== 'm.' ? `${start.split(' ')[0]} – ${end}` : `${start} – ${end}`;
}

function blockLabel(b: BlockSummary): string {
  const day = formatDayMonth(b.localDate);
  const wd = new Date(`${b.localDate}T12:00:00Z`).getUTCDay();
  const name = dayOfWeekName(wd === 0 ? 7 : wd).slice(0, 3);
  return `${name} ${day} · ${b.allDay ? 'Todo el día' : `${hhmmLabel(b.startTime ?? '')} – ${hhmmLabel(b.endTime ?? '')}`}`;
}

export function AvailabilityPage() {
  const weekly = useWeekly();
  const blocks = useUpcomingBlocks();
  const remove = useDeleteBlock();
  const [day, setDay] = useState<number | null>(null);
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<BlockSummary | null>(null);

  if (weekly.isPending || blocks.isPending) return <Skeleton label="Cargando disponibilidad" />;
  if (weekly.isError) return <ErrorState error={weekly.error} onRetry={() => void weekly.refetch()} />;
  if (blocks.isError) return <ErrorState error={blocks.error} onRetry={() => void blocks.refetch()} />;

  return (
    <section className="flex flex-col gap-5 px-4 pb-6 pt-2">
      <div>
        <BackToSettings />
      </div>
      <h1 className="font-display text-[34px] font-bold leading-tight">Disponibilidad</h1>

      <div className="flex flex-col gap-2">
        <h2 className="text-xs font-bold tracking-wider text-ink-2">HORARIO SEMANAL</h2>
        <ul aria-label="Horario semanal" className="overflow-hidden rounded-3xl border border-line bg-white">
          {ORDER.map((d) => {
            const windows = weekly.data.filter((w) => w.dayOfWeek === d);
            return (
              <li key={d} className="border-b border-line last:border-b-0">
                <button type="button" onClick={() => setDay(d)} aria-label={`Editar ${dayOfWeekName(d).toLowerCase()}`} className="flex min-h-14 w-full items-center gap-3 px-4 py-3 text-left">
                  <span className="w-12 shrink-0 font-bold">{dayOfWeekName(d).slice(0, 3)}</span>
                  <span className={`flex-1 ${windows.length === 0 ? 'text-ink-2' : ''}`}>{windows.length === 0 ? 'Sin clases' : windows.map(windowText).join(' · ')}</span>
                  <ChevronRightIcon />
                </button>
              </li>
            );
          })}
        </ul>
      </div>

      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between">
          <h2 className="text-xs font-bold tracking-wider text-ink-2">BLOQUEOS</h2>
          <button type="button" onClick={() => setAdding(true)} className="inline-flex min-h-11 items-center font-semibold text-brand-ink">
            + Agregar bloqueo
          </button>
        </div>
        {blocks.data.length === 0 ? (
          <p className="rounded-2xl bg-white p-4 text-ink-2">No tienes bloqueos próximos.</p>
        ) : (
          <ul aria-label="Bloqueos" className="overflow-hidden rounded-3xl border border-line bg-white">
            {blocks.data.map((b) => (
              <li key={b.id} className="flex items-center justify-between gap-3 border-b border-line px-4 py-3 last:border-b-0">
                <span>
                  <span className="block font-bold">{blockLabel(b)}</span>
                  {b.reason && <span className="block text-ink-2">{b.reason}</span>}
                </span>
                <button type="button" onClick={() => setRemoving(b)} aria-label={`Quitar el bloqueo del ${blockLabel(b)}`} className="min-h-11 px-2 font-semibold text-red-ink">
                  Quitar
                </button>
              </li>
            ))}
          </ul>
        )}
        <p className="text-sm text-ink-2">Los bloqueos impiden agendar nuevas clases en esas horas.</p>
      </div>

      {day !== null && <DaySheet dayOfWeek={day} windows={weekly.data} onClose={() => setDay(null)} />}
      {adding && <BlockFlow onClose={() => setAdding(false)} />}
      <Dialog
        open={removing !== null}
        onOpenChange={(o) => {
          if (!o) {
            setRemoving(null);
            remove.reset();
          }
        }}
        title="¿Quitar el bloqueo?"
        description="Esas horas vuelven a estar disponibles. Las clases que ya se cancelaron por este bloqueo siguen canceladas."
      >
        <div className="flex flex-col gap-3">
          {remove.isError && (
            <Banner tone="red" role="alert">
              {messageFor(remove.error)}
            </Banner>
          )}
          <Button
            variant="danger"
            loading={remove.isPending}
            onClick={() => {
              if (removing) remove.mutate(removing.id, { onSuccess: () => setRemoving(null) });
            }}
          >
            Quitar bloqueo
          </Button>
          <Button variant="ghost" onClick={() => setRemoving(null)}>
            Volver
          </Button>
        </div>
      </Dialog>
    </section>
  );
}
