import { useId, useState } from 'react';
import { messageFor } from '../../../api/errors';
import { hhmmLabel, instantAt, longDay, shortDay, todayBogota } from '../../../lib/format';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { DatePicker } from '../../../ui/DatePicker';
import { Sheet } from '../../../ui/Overlays';
import { Switch } from '../../../ui/Switch';
import { overrideNeed, REASON_CHIPS, reasonTextFor, slotNote, studentOption, studentSummary, type ReasonKey } from './present';
import { useBook, useBookableStudents, useBookingOptions } from './queries';

const REASON_MAX = 200;

export interface BookInitial {
  date: string;
  /** "HH:mm" of the free slot the coach tapped, if any. */
  time?: string;
}

interface Props {
  initial: BookInitial;
  /** Called after a class was booked, with a sentence for the agenda, or null when the coach just closed the sheet. */
  onClose: (booked: { date: string; text: string } | null) => void;
}

function TimeChips({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-2">
      {children}
    </div>
  );
}

function Pill({ selected, onClick, children }: { selected: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={`min-h-12 rounded-full border-2 px-5 font-semibold ${selected ? 'border-brand-ink bg-brand text-brand-contrast' : 'border-line bg-white text-ink'}`}
    >
      {children}
    </button>
  );
}

/** Book a class for a student. Which hours exist, which students can be booked and which need an exception are all the server's answers. Mounted only while open. */
export function BookSheet({ initial, onClose }: Props) {
  const students = useBookableStudents();
  const book = useBook();
  const [studentId, setStudentId] = useState('');
  const [date, setDate] = useState(initial.date);
  const [time, setTime] = useState<string | null>(initial.time ?? null);
  const [exception, setException] = useState(false);
  const [reasonKey, setReasonKey] = useState<ReasonKey | null>(null);
  const [reason, setReason] = useState('');
  const ids = { student: useId(), time: useId(), reason: useId() };

  const options = useBookingOptions(studentId, date);
  const student = students.data?.find((s) => s.studentId === studentId);
  const slots = options.data?.slots ?? [];
  const plainSlots = slots.filter((s) => !s.needsOverride);
  const exceptionSlots = slots.filter((s) => s.needsOverride);
  const chosenSlot = slots.find((s) => s.localTime === time);
  const timeValid = time !== null && /^\d{2}:\d{2}$/.test(time);
  // without the exception only a slot the server lists as bookable is allowed; with it, any time (the server validates the rest)
  const timeOk = exception ? timeValid : !!chosenSlot && !chosenSlot.needsOverride;
  const reasonOk = !exception || reason.trim() !== '';
  const canSubmit = !!student?.canBook && date !== '' && timeOk && reasonOk && !book.isPending;
  const when = timeValid && date ? `${shortDay(date)}, ${hhmmLabel(time)}` : '';

  function pickReason(key: ReasonKey) {
    setReasonKey(key);
    setReason(reasonTextFor(key));
  }

  async function submit() {
    if (!student || !time) return;
    try {
      await book.mutateAsync({ studentId, startsAt: instantAt(date, time), override: exception, ...(exception ? { overrideReason: reason.trim() } : {}) });
      onClose({ date, text: `Clase agendada: ${student.fullName} · ${when}.` });
    } catch {
      // the error stays in book.error and is shown below
    }
  }

  return (
    <Sheet open onOpenChange={(o) => !o && onClose(null)} title="Agendar clase">
      <form
        noValidate
        className="flex flex-col gap-5"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <div className="flex flex-col gap-1">
          <label htmlFor={ids.student} className="font-semibold">
            Alumno
          </label>
          <select
            id={ids.student}
            value={studentId}
            onChange={(e) => {
              setStudentId(e.target.value);
              book.reset();
            }}
            disabled={students.isPending}
            className="min-h-12 rounded-xl border border-line bg-white px-3 text-base"
          >
            <option value="">{students.isPending ? 'Cargando alumnos…' : 'Elige un alumno'}</option>
            {(students.data ?? []).map((s) => (
              <option key={s.studentId} value={s.studentId} disabled={!s.canBook}>
                {studentOption(s)}
              </option>
            ))}
          </select>
          {students.isError && <p role="alert" className="text-sm font-semibold text-red-ink">{messageFor(students.error)}</p>}
          {student && <p className="text-sm text-ink-2">{studentSummary(student)}</p>}
        </div>

        <DatePicker
          label="Día"
          value={date}
          min={todayBogota()}
          onChange={(d) => {
            setDate(d);
            setTime(null);
            book.reset();
          }}
        />

        {!exception && (
          <div className="flex flex-col gap-2">
            <p id={`${ids.time}-label`} className="font-semibold">
              Hora disponible
            </p>
            {!student?.canBook && <p className="text-sm text-ink-2">Elige primero a un alumno que se pueda agendar.</p>}
            {student?.canBook && options.isPending && <p role="status" className="text-sm text-ink-2">Buscando horarios…</p>}
            {student?.canBook && options.isError && <p role="alert" className="text-sm font-semibold text-red-ink">{messageFor(options.error)}</p>}
            {student?.canBook && options.data && plainSlots.length === 0 && (
              <p className="text-sm text-ink-2">
                No hay horarios libres ese día.{exceptionSlots.length > 0 ? ' Hay horarios que solo se pueden tomar como excepción.' : ''}
              </p>
            )}
            {plainSlots.length > 0 && (
              <TimeChips label="Hora disponible">
                {plainSlots.map((s) => (
                  <Pill key={s.startsAt} selected={time === s.localTime} onClick={() => setTime(s.localTime)}>
                    {hhmmLabel(s.localTime)}
                  </Pill>
                ))}
              </TimeChips>
            )}
            {chosenSlot && !chosenSlot.needsOverride && <p className="text-sm text-ink-2">{slotNote(chosenSlot)}</p>}
          </div>
        )}

        <div className="flex flex-col gap-3 rounded-2xl border border-line p-4">
          <Switch
            checked={exception}
            onChange={(on) => {
              setException(on);
              book.reset();
            }}
            label="Agendar como excepción"
            hint="Para entrar sin cupo, en otra modalidad o fuera de tu horario. Queda registrado con el motivo."
          />
        </div>

        {exception && (
          <>
            <div className="flex flex-col gap-1">
              <label htmlFor={ids.time} className="font-semibold">
                Hora (de 15 en 15 min)
              </label>
              <input
                id={ids.time}
                type="time"
                step={900}
                value={time ?? ''}
                onChange={(e) => setTime(e.target.value === '' ? null : e.target.value)}
                className="min-h-12 rounded-xl border border-line bg-white px-3 text-base"
              />
              {options.data && options.data.dayWindows.length > 0 && (
                <p className="text-sm text-ink-2">
                  Tu horario del {longDay(date).split(' ')[0]?.toLowerCase()}: {options.data.dayWindows.map((w) => `${hhmmLabel(w.start)} – ${hhmmLabel(w.end)}`).join(' · ')}. No se puede entrar a un bloqueo.
                </p>
              )}
              {options.data && options.data.dayWindows.length === 0 && <p className="text-sm text-ink-2">Ese día no tienes horario. No se puede entrar a un bloqueo.</p>}
            </div>
            {exceptionSlots.length > 0 && (
              <div className="flex flex-col gap-2">
                <p className="font-semibold">Horarios que piden excepción</p>
                <TimeChips label="Horarios que piden excepción">
                  {exceptionSlots.map((s) => (
                    <Pill key={s.startsAt} selected={time === s.localTime} onClick={() => setTime(s.localTime)}>
                      {hhmmLabel(s.localTime)} · {overrideNeed(s.blockedBy)}
                    </Pill>
                  ))}
                </TimeChips>
              </div>
            )}
            <div className="flex flex-col gap-2">
              <p className="font-semibold">Motivo</p>
              <div role="radiogroup" aria-label="Motivo rápido" className="flex flex-wrap gap-2">
                {REASON_CHIPS.map((c) => (
                  <button
                    key={c.key}
                    type="button"
                    role="radio"
                    aria-checked={reasonKey === c.key}
                    onClick={() => pickReason(c.key)}
                    className={`min-h-12 rounded-full border-2 px-4 font-semibold ${reasonKey === c.key ? 'border-brand-ink bg-brand text-brand-contrast' : 'border-line bg-white text-ink'}`}
                  >
                    {c.label}
                  </button>
                ))}
              </div>
              <label htmlFor={ids.reason} className="sr-only">
                Motivo de la excepción
              </label>
              <textarea
                id={ids.reason}
                value={reason}
                maxLength={REASON_MAX}
                rows={3}
                aria-describedby={`${ids.reason}-hint`}
                onChange={(e) => setReason(e.target.value)}
                className="rounded-xl border border-line bg-white p-3 text-base"
              />
              <p id={`${ids.reason}-hint`} className="flex justify-between text-sm text-ink-2">
                <span>No escribas datos de salud ni de contacto.</span>
                <span aria-hidden="true">
                  {reason.length}/{REASON_MAX}
                </span>
              </p>
            </div>
          </>
        )}

        {book.isError && (
          <Banner tone="red" role="alert">
            {messageFor(book.error)}
          </Banner>
        )}

        <Button type="submit" loading={book.isPending} disabled={!canSubmit} className="min-h-14 text-center">
          {exception ? `Agendar como excepción${when ? ` · ${when}` : ''}` : student && when ? `Agendar a ${student.fullName} · ${when}` : 'Agendar'}
        </Button>
        <Button variant="ghost" onClick={() => onClose(null)}>
          Cancelar
        </Button>
      </form>
    </Sheet>
  );
}
