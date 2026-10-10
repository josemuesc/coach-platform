import { useState } from 'react';
import { messageFor } from '../../../api/errors';
import { dayOfWeekName } from '../../../lib/format';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { CrossIcon } from '../../../ui/icons';
import { Sheet } from '../../../ui/Overlays';
import { useReplaceDay, type Window } from './queries';

interface Props {
  dayOfWeek: number;
  windows: Window[];
  /** Mounted only while open. */
  onClose: () => void;
}

/** The time windows of one weekday. Overlaps and bad ranges are the server's call; booked classes are never moved by this. */
export function DaySheet({ dayOfWeek, windows, onClose }: Props) {
  const save = useReplaceDay();
  const [rows, setRows] = useState(() => windows.filter((w) => w.dayOfWeek === dayOfWeek).map((w) => ({ start: w.start, end: w.end })));
  const name = dayOfWeekName(dayOfWeek);
  const complete = rows.every((r) => r.start !== '' && r.end !== '');

  function change(i: number, patch: Partial<{ start: string; end: string }>) {
    setRows((all) => all.map((r, j) => (j === i ? { ...r, ...patch } : r)));
    save.reset();
  }

  async function submit() {
    try {
      await save.mutateAsync({ dayOfWeek, windows: rows });
      onClose();
    } catch {
      // shown below
    }
  }

  return (
    <Sheet open onOpenChange={(o) => !o && onClose()} title={name}>
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <p className="font-semibold">Franjas con clases</p>
        {rows.length === 0 && <p className="text-ink-2">Sin clases este día.</p>}
        {rows.map((r, i) => (
          <div key={i} className="flex items-center gap-2">
            <input
              type="time"
              aria-label={`Franja ${i + 1}, desde`}
              value={r.start}
              onChange={(e) => change(i, { start: e.target.value })}
              className="min-h-12 min-w-0 flex-1 rounded-xl border border-line bg-white px-3 text-base"
            />
            <span aria-hidden="true">–</span>
            <input
              type="time"
              aria-label={`Franja ${i + 1}, hasta`}
              value={r.end}
              onChange={(e) => change(i, { end: e.target.value })}
              className="min-h-12 min-w-0 flex-1 rounded-xl border border-line bg-white px-3 text-base"
            />
            <button
              type="button"
              aria-label={`Quitar la franja ${i + 1}`}
              onClick={() => {
                setRows((all) => all.filter((_, j) => j !== i));
                save.reset();
              }}
              className="inline-flex size-11 items-center justify-center text-red-ink"
            >
              <CrossIcon />
            </button>
          </div>
        ))}
        <button type="button" onClick={() => setRows((all) => [...all, { start: '', end: '' }])} className="inline-flex min-h-11 items-center self-start font-semibold text-brand-ink">
          + Agregar franja
        </button>
        <Banner tone="amber">Las clases ya agendadas no se mueven. El cambio aplica a nuevas reservas. Las franjas no pueden solaparse.</Banner>
        {save.isError && (
          <Banner tone="red" role="alert">
            {messageFor(save.error)}
          </Banner>
        )}
        <Button type="submit" loading={save.isPending} disabled={!complete} className="min-h-14">
          Guardar {name.toLowerCase()}
        </Button>
        <Button variant="ghost" onClick={onClose}>
          Cancelar
        </Button>
      </form>
    </Sheet>
  );
}
