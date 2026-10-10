import { messageFor } from '../../../api/errors';
import { formatDayMonthYear, formatWeekdayDateTime } from '../../../lib/format';
import { Chip } from '../../../ui/Chip';
import { Button } from '../../../ui/Button';
import { Banner } from '../../../ui/Banner';
import { Sheet } from '../../../ui/Overlays';
import type { ClassRow } from './queries';
import { useSheet } from './queries';

const STATUS: Record<ClassRow['status'], { tone: 'green' | 'amber' | 'red' | 'neutral'; text: string }> = {
  SCHEDULED: { tone: 'neutral', text: 'Agendada' },
  ATTENDED: { tone: 'green', text: 'Asistió' },
  NO_SHOW: { tone: 'red', text: 'No vino' },
  CANCELLED_ON_TIME: { tone: 'neutral', text: 'Canceló a tiempo' },
  RESCHEDULED: { tone: 'neutral', text: 'Reagendó' },
  CANCELLED_BY_COACH: { tone: 'amber', text: 'Cancelada por ti' },
};

function confirmation(row: ClassRow): string | null {
  if (row.studentConfirmed) return row.confirmationMethod === 'QR' ? 'Confirmó con QR' : 'Confirmó después';
  if (row.onlyMarkedByCoach) return 'Marcado por ti';
  return null;
}

function Rows({ rows, numbered }: { rows: ClassRow[]; numbered: boolean }) {
  return (
    <ul className="overflow-hidden rounded-2xl border border-line">
      {rows.map((r) => {
        const s = STATUS[r.status];
        const note = confirmation(r) ?? r.cancelReason;
        return (
          <li key={r.attendanceId} className="flex items-start justify-between gap-3 border-b border-line px-4 py-3 last:border-b-0">
            <span className="min-w-0">
              <span className="block font-display text-base font-bold">
                {numbered && r.number != null ? `${r.number}. ` : ''}
                {formatWeekdayDateTime(r.startsAt)}
              </span>
              {note && <span className="block text-sm text-ink-2">{note}</span>}
              {r.override && <span className="block text-sm text-ink-2">Entró como excepción</span>}
            </span>
            <Chip tone={s.tone}>{s.text}</Chip>
          </li>
        );
      })}
    </ul>
  );
}

/** The student's classes of their current (or last) cycle, numbered, with the others (cancelled / rescheduled) apart. Mounted only while open. */
export function ClassSheet({ studentId, studentName, onClose }: { studentId: string; studentName: string; onClose: () => void }) {
  const sheet = useSheet(studentId, true);
  return (
    <Sheet open onOpenChange={(o) => !o && onClose()} title="Planilla de clases" description={studentName}>
      <div className="flex flex-col gap-4">
        {sheet.isPending && <p role="status" className="text-ink-2">Cargando…</p>}
        {sheet.isError && (
          <>
            <Banner tone="red" role="alert">{messageFor(sheet.error)}</Banner>
            <Button variant="secondary" onClick={() => void sheet.refetch()}>
              Reintentar
            </Button>
          </>
        )}
        {sheet.data && (
          <>
            {sheet.data.cycle ? (
              <p className="text-base">
                Ciclo del <strong>{formatDayMonthYear(sheet.data.cycle.startDate)}</strong> al <strong>{formatDayMonthYear(sheet.data.cycle.endDate)}</strong>.
              </p>
            ) : (
              <p className="text-ink-2">Todavía no tiene ciclo.</p>
            )}
            {sheet.data.classes.length === 0 ? <p className="text-ink-2">No tiene clases en este ciclo.</p> : <Rows rows={sheet.data.classes} numbered />}
            {sheet.data.otherEntries.length > 0 && (
              <>
                <h3 className="font-display text-lg font-bold">Otras anotaciones</h3>
                <Rows rows={sheet.data.otherEntries} numbered={false} />
              </>
            )}
          </>
        )}
        <Button onClick={onClose}>Cerrar</Button>
      </div>
    </Sheet>
  );
}
