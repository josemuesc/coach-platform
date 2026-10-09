import { Chip } from '../../../ui/Chip';
import { Sheet } from '../../../ui/Overlays';
import { formatDateTime } from '../../../lib/time';
import { CheckIcon, CrossIcon } from '../../../ui/icons';
import type { MarkRequest } from './MarkConfirmDialog';
import type { PendingAttendance } from './queries';

interface Props {
  open: boolean;
  items: PendingAttendance[];
  onClose: () => void;
  onMark: (request: MarkRequest) => void;
}

/** Every class already given but not marked yet, including earlier days. The server lists them and says whether each can be marked. */
export function PendingSheet({ open, items, onClose, onMark }: Props) {
  return (
    <Sheet open={open} onOpenChange={(o) => !o && onClose()} title="Por marcar" description="Clases que ya empezaron y siguen sin marca.">
      {items.length === 0 ? (
        <p className="text-ink-2">No hay clases por marcar.</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {items.map((p) => {
            const name = p.studentName ?? 'Alumno';
            return (
              <li key={p.id} data-attendance={p.id} tabIndex={-1} className="flex items-center gap-3 outline-offset-2">
                <div className="min-w-0 flex-1">
                  <p className="truncate font-semibold">{name}</p>
                  <p className="text-sm text-ink-2">{formatDateTime(p.startsAt)}</p>
                </div>
                {p.canMark ? (
                  <div className="flex gap-2">
                    <button
                      type="button"
                      aria-label={`Marcar que ${name} asistió`}
                      onClick={() => onMark({ kind: 'one', attendanceId: p.id, name, result: 'ATTENDED', switching: false })}
                      className="inline-flex size-11 items-center justify-center rounded-xl bg-green-ink text-white"
                    >
                      <CheckIcon />
                    </button>
                    <button
                      type="button"
                      aria-label={`Marcar que ${name} no vino`}
                      onClick={() => onMark({ kind: 'one', attendanceId: p.id, name, result: 'NO_SHOW', switching: false })}
                      className="inline-flex size-11 items-center justify-center rounded-xl bg-red-ink text-white"
                    >
                      <CrossIcon />
                    </button>
                  </div>
                ) : (
                  <Chip tone="amber">No se puede marcar</Chip>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </Sheet>
  );
}
