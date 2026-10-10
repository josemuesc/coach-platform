import { useState } from 'react';
import { blockAffectedOf } from '../../../api/errorDetails';
import { messageFor } from '../../../api/errors';
import { formatDayMonth, hhmmLabel, shortDay, todayBogota } from '../../../lib/format';
import { clockTime, plural } from '../../../lib/time';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { Field } from '../../../ui/Field';
import { Sheet } from '../../../ui/Overlays';
import { Segmented } from '../../../ui/Segmented';
import { modalityName } from '../students/present';
import { useProfile } from '../students/queries';
import { ExtendCycleSheet } from '../students/ExtendCycleSheet';
import { useCreateBlock, usePreviewBlock, type BlockCreated, type BlockPreview } from './queries';

const REASON_MAX = 100;

type Stage = 'form' | 'review' | 'saved';
type Mode = 'all' | 'hours';

function cap(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function MinorTag() {
  return <span className="rounded-full bg-past px-2 text-xs font-semibold text-ink">Menor</span>;
}

function untouchedText(p: Pick<BlockPreview, 'markedUntouched' | 'pendingUntouched'>): string | null {
  if (p.markedUntouched + p.pendingUntouched === 0) return null;
  return `No se tocan ${plural(p.markedUntouched, 'clase ya marcada', 'clases ya marcadas')} y ${plural(p.pendingUntouched, 'pendiente de marcar', 'pendientes de marcar')} en ese rango.`;
}

/** Opens the extension sheet of a student once their profile (with the dates the server accepts) has loaded. */
function ExtendFor({ studentId, onClose }: { studentId: string; onClose: () => void }) {
  const profile = useProfile(studentId);
  if (!profile.data) return null;
  return <ExtendCycleSheet profile={profile.data} onClose={onClose} />;
}

/**
 * Add a block: the form, the review of the classes it would release (the coach confirms exactly that list), and the result. Which classes are
 * affected, what stays untouched and who is at risk are all the server's answers. Mounted only while open.
 */
export function BlockFlow({ onClose }: { onClose: () => void }) {
  const preview = usePreviewBlock();
  const create = useCreateBlock();
  const [stage, setStage] = useState<Stage>('form');
  const [date, setDate] = useState(todayBogota());
  const [mode, setMode] = useState<Mode>('hours');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [reason, setReason] = useState('');
  const [review, setReview] = useState<BlockPreview | null>(null);
  const [changed, setChanged] = useState(false);
  const [result, setResult] = useState<BlockCreated | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [extendFor, setExtendFor] = useState<string | null>(null);

  const allDay = mode === 'all';
  const range = allDay ? 'Todo el día' : from && to ? `${hhmmLabel(from)} – ${hhmmLabel(to)}` : '';
  const label = `${cap(shortDay(date))} · ${range} · ${reason.trim()}`;
  const canReview = date !== '' && reason.trim() !== '' && (allDay || (from !== '' && to !== ''));

  const body = (ids: string[]) => ({
    localDate: date,
    allDay,
    ...(allDay ? {} : { startTime: from, endTime: to }),
    reason: reason.trim(),
    affectedAttendanceIds: ids,
  });

  async function save(ids: string[]) {
    setError(null);
    try {
      setResult(await create.mutateAsync(body(ids)));
      setStage('saved');
    } catch (e) {
      const current = blockAffectedOf(e);
      if (current) {
        setReview(current);   // the list changed while the coach reviewed: show the new one and ask again
        setChanged(true);
        setStage('review');
      } else {
        setError(e);
      }
    }
  }

  async function reviewIt() {
    setError(null);
    setChanged(false);
    try {
      const p = await preview.mutateAsync(body([]));
      setReview(p);
      if (p.releasedCount === 0) await save([]);   // nothing to confirm: no class is released
      else setStage('review');
    } catch (e) {
      setError(e);
    }
  }

  const title = stage === 'form' ? 'Agregar bloqueo' : stage === 'review' ? (review && review.releasedCount > 0 ? `Se cancelarán ${plural(review.releasedCount, 'clase', 'clases')}` : 'Confirmar bloqueo') : result && result.cancelled.length > 0 ? `${plural(result.cancelled.length, 'clase cancelada', 'clases canceladas')}` : 'Bloqueo guardado';

  return (
    <>
      <Sheet open onOpenChange={(o) => !o && onClose()} title={title}>
        {stage === 'form' && (
          <form
            noValidate
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              void reviewIt();
            }}
          >
            <Field label="Fecha" type="date" value={date} min={todayBogota()} onChange={(e) => setDate(e.target.value)} />
            <Segmented
              label="Duración del bloqueo"
              value={mode}
              options={[
                { value: 'all', label: 'Todo el día' },
                { value: 'hours', label: 'Por horas' },
              ]}
              onChange={setMode}
            />
            {!allDay && (
              <div className="flex gap-3">
                <div className="flex-1">
                  <Field label="Desde" type="time" value={from} onChange={(e) => setFrom(e.target.value)} />
                </div>
                <div className="flex-1">
                  <Field label="Hasta" type="time" value={to} onChange={(e) => setTo(e.target.value)} />
                </div>
              </div>
            )}
            <div className="flex flex-col gap-1">
              <Field label="Motivo" value={reason} maxLength={REASON_MAX} onChange={(e) => setReason(e.target.value)} />
              <p className="flex justify-between text-sm text-ink-2">
                <span>Obligatorio. No escribas datos de salud ni de contacto.</span>
                <span aria-hidden="true">
                  {reason.length}/{REASON_MAX}
                </span>
              </p>
            </div>
            <Banner tone="amber">Si hay clases agendadas en ese horario, verás cuáles se cancelarán antes de guardar.</Banner>
            {error !== null && (
              <Banner tone="red" role="alert">
                {messageFor(error)}
              </Banner>
            )}
            <Button type="submit" loading={preview.isPending || create.isPending} disabled={!canReview} className="min-h-14">
              Revisar y guardar
            </Button>
            <Button variant="ghost" onClick={onClose}>
              Cancelar
            </Button>
          </form>
        )}

        {stage === 'review' && review && (
          <div className="flex flex-col gap-4">
            <p className="text-ink-2">{label}</p>
            {changed && (
              <Banner tone="amber" role="alert">
                Las clases afectadas cambiaron mientras revisabas. Revisa la lista nueva y confirma de nuevo.
              </Banner>
            )}
            {review.releasedCount > 0 ? (
              <>
                <Banner tone="red">Estas clases se cancelan sin descontar del plan. Cada alumno podrá reagendar.</Banner>
                <ul aria-label="Clases que se cancelarán" className="rounded-2xl border border-line">
                  {review.affected.map((a) => (
                    <li key={a.attendanceId} className="border-b border-line p-3 last:border-b-0">
                      <p className="flex flex-wrap items-center gap-2 font-display text-lg font-bold">
                        {a.studentName}
                        {a.minor && <MinorTag />}
                      </p>
                      <p className="text-sm text-ink-2">
                        {clockTime(a.startsAt)} · {modalityName(a.modality)}
                      </p>
                    </li>
                  ))}
                </ul>
              </>
            ) : (
              <p className="text-ink-2">Ya no hay clases agendadas en ese horario: no se cancelará ninguna.</p>
            )}
            {untouchedText(review) && <p className="text-sm text-ink-2">{untouchedText(review)}</p>}
            <ul className="list-disc pl-5 text-sm text-ink-2">
              <li>
                La app todavía no avisa a los alumnos: avísales tú.
                {review.affected.some((a) => a.minor) && ` Para menores, avisa a su acudiente: ${[...new Set(review.affected.filter((a) => a.minor).map((a) => a.studentName))].join(', ')}.`}
              </li>
              <li>Quitar el bloqueo después no restaura las clases canceladas.</li>
            </ul>
            {error !== null && (
              <Banner tone="red" role="alert">
                {messageFor(error)}
              </Banner>
            )}
            <Button variant="destructive" loading={create.isPending} onClick={() => void save(review.affected.map((a) => a.attendanceId))} className="min-h-14 text-center">
              {review.releasedCount > 0 ? `Guardar bloqueo y cancelar ${plural(review.releasedCount, 'clase', 'clases')}` : 'Guardar bloqueo'}
            </Button>
            <Button variant="ghost" onClick={() => setStage('form')}>
              Volver
            </Button>
          </div>
        )}

        {stage === 'saved' && result && (
          <div className="flex flex-col gap-4">
            <p className="text-ink-2">Bloqueo guardado: {label}</p>
            {result.students.length > 0 && (
              <ul aria-label="Alumnos afectados" className="rounded-2xl border border-line">
                {result.students.map((s) => (
                  <li key={s.studentId} className="border-b border-line p-3 last:border-b-0">
                    <p className="flex flex-wrap items-center gap-2 font-display text-lg font-bold">
                      {s.studentName}
                      {s.minor && <MinorTag />}
                    </p>
                    <p className="text-sm text-ink-2">
                      {s.releasedClasses === 1 ? 'Clase cancelada' : `${s.releasedClasses} clases canceladas`} · {s.classesLeftToSchedule} por agendar
                      {s.minor ? ' · avisar a su acudiente' : ''}
                    </p>
                  </li>
                ))}
              </ul>
            )}
            {result.students
              .filter((s) => s.atRisk)
              .map((s) => (
                <Banner key={s.studentId} tone="amber">
                  <p>
                    <strong>{s.studentName}:</strong> pocos horarios antes del vencimiento{s.cycleEndDate ? ` (${formatDayMonth(s.cycleEndDate)})` : ''} para {s.classesLeftToSchedule === 1 ? 'su clase pendiente' : 'sus clases pendientes'}.
                  </p>
                  {s.canExtend && (
                    <Button onClick={() => setExtendFor(s.studentId)} className="mt-2 bg-amber-ink text-white">
                      Extender ciclo
                    </Button>
                  )}
                </Banner>
              ))}
            {untouchedText(result) && <p className="text-sm text-ink-2">{untouchedText(result)}</p>}
            {result.students.length > 0 && <p>Recuerda avisar a {plural(result.students.length, 'alumno', 'alumnos')}; la app todavía no envía avisos.</p>}
            <Button onClick={onClose} className="min-h-14">
              Listo
            </Button>
          </div>
        )}
      </Sheet>
      {extendFor && <ExtendFor studentId={extendFor} onClose={() => setExtendFor(null)} />}
    </>
  );
}
