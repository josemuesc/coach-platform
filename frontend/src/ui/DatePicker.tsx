import { useEffect, useId, useRef, useState } from 'react';
import { dayString, initialMonth, inRange, monthCells, monthHasAllowedDay, shiftMonth, type YearMonth } from '../lib/calendar';
import { shortDay, todayBogota, WEEKDAY_LETTERS } from '../lib/format';
import { Dialog } from './Overlays';
import { CalendarIcon, ChevronLeftIcon, ChevronRightIcon } from './icons';

const MONTHS = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];
const MONTHS_SHORT = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];
type Panel = 'days' | 'years' | 'months';

interface Props {
  label: string;
  /** YYYY-MM-DD, or '' when nothing is chosen. */
  value: string;
  onChange: (value: string) => void;
  min?: string | null | undefined;
  max?: string | null | undefined;
  hint?: string;
  error?: string | undefined;
  id?: string;
  placeholder?: string;
}

function longLabel(day: string): string {
  const s = shortDay(day);
  return `${s.charAt(0).toUpperCase()}${s.slice(1)} ${day.slice(0, 4)}`;
}

/**
 * Our own calendar instead of the browser's date input (whose look cannot be styled): a field that opens a native <dialog> with the month,
 * a month/year chooser and "Hoy". The bounds only gray out days; whether a date is acceptable is still the server's decision.
 */
export function DatePicker({ label, value, onChange, min, max, hint, error, id, placeholder = 'Elige una fecha' }: Props) {
  const auto = useId();
  const fieldId = id ?? auto;
  const today = todayBogota();
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<YearMonth>(() => initialMonth(value, today, min, max));
  const [panel, setPanel] = useState<Panel>('days');
  const [pickedYear, setPickedYear] = useState<number | null>(null);
  const pendingFocus = useRef<string | null>(null);
  const gridRef = useRef<HTMLDivElement>(null);
  const describedBy = [hint ? `${fieldId}-hint` : null, error ? `${fieldId}-error` : null].filter(Boolean).join(' ') || undefined;

  function show() {
    setView(initialMonth(value, today, min, max));
    setPanel('days');
    setOpen(true);
  }

  function choose(day: string) {
    onChange(day);
    setOpen(false);
  }

  // after an arrow key moved the month, the day to focus exists only once the grid has been drawn
  useEffect(() => {
    if (!pendingFocus.current) return;
    gridRef.current?.querySelector<HTMLElement>(`[data-day="${pendingFocus.current}"]`)?.focus();
    pendingFocus.current = null;
  }, [view]);

  function moveFocus(from: string, delta: number) {
    const [y, m, d] = from.split('-').map(Number) as [number, number, number];
    const next = new Date(Date.UTC(y, m - 1, d + delta)).toISOString().slice(0, 10);
    if (!inRange(next, min, max)) return;
    pendingFocus.current = next;
    setView({ year: Number(next.slice(0, 4)), month: Number(next.slice(5, 7)) });
  }

  const years = (() => {
    const from = min ? Number(min.slice(0, 4)) : Number(today.slice(0, 4)) - 100;
    const to = max ? Number(max.slice(0, 4)) : Number(today.slice(0, 4)) + 5;
    return Array.from({ length: Math.max(0, to - from + 1) }, (_, i) => to - i);   // newest first
  })();

  const canPrev = !min || dayString(view.year, view.month, 1) > min;
  const next = shiftMonth(view, 1);
  const canNext = !max || dayString(next.year, next.month, 1) <= max;
  const tabStop = value && inRange(value, min, max) && value.startsWith(`${view.year}-${String(view.month).padStart(2, '0')}`) ? value : null;

  return (
    <div className="flex flex-col gap-1">
      <span id={`${fieldId}-label`} className="text-sm font-semibold text-ink">
        {label}
      </span>
      <button
        id={fieldId}
        type="button"
        aria-labelledby={`${fieldId}-label ${fieldId}`}
        aria-haspopup="dialog"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        onClick={show}
        className="flex min-h-12 items-center justify-between gap-2 rounded-xl border border-line bg-white px-3 text-left text-base"
      >
        <span className={value ? 'text-ink' : 'text-ink-2'}>{value ? longLabel(value) : placeholder}</span>
        <CalendarIcon />
      </button>
      {hint && (
        <p id={`${fieldId}-hint`} className="text-sm text-ink-2">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${fieldId}-error`} role="alert" className="text-sm font-semibold text-red-ink">
          {error}
        </p>
      )}

      <Dialog open={open} onOpenChange={setOpen} title={label}>
        <div className="flex flex-col gap-3">
          <div className="flex items-center justify-between">
            <button
              type="button"
              aria-label="Mes anterior"
              disabled={!canPrev || panel !== 'days'}
              onClick={() => setView((v) => shiftMonth(v, -1))}
              className="inline-flex size-11 items-center justify-center rounded-full disabled:opacity-30"
            >
              <ChevronLeftIcon />
            </button>
            <button
              type="button"
              aria-label={`${MONTHS[view.month - 1]} de ${view.year}: cambiar mes o año`}
              aria-expanded={panel !== 'days'}
              onClick={() => setPanel((p) => (p === 'days' ? 'years' : 'days'))}
              className="min-h-11 rounded-xl px-3 font-display text-lg font-bold capitalize"
            >
              {MONTHS[view.month - 1]} {view.year}
            </button>
            <button
              type="button"
              aria-label="Mes siguiente"
              disabled={!canNext || panel !== 'days'}
              onClick={() => setView((v) => shiftMonth(v, 1))}
              className="inline-flex size-11 items-center justify-center rounded-full disabled:opacity-30"
            >
              <ChevronRightIcon />
            </button>
          </div>

          {panel === 'years' && (
            <div role="group" aria-label="Año" className="grid max-h-64 grid-cols-4 gap-2 overflow-y-auto">
              {years.map((y) => (
                <button
                  key={y}
                  type="button"
                  aria-pressed={y === view.year}
                  onClick={() => {
                    setPickedYear(y);
                    setPanel('months');
                  }}
                  className={`min-h-11 rounded-xl border font-semibold ${y === view.year ? 'border-brand-ink bg-brand text-brand-contrast' : 'border-line bg-white'}`}
                >
                  {y}
                </button>
              ))}
            </div>
          )}

          {panel === 'months' && pickedYear !== null && (
            <div role="group" aria-label={`Mes de ${pickedYear}`} className="grid grid-cols-3 gap-2">
              {MONTHS_SHORT.map((name, i) => {
                const target = { year: pickedYear, month: i + 1 };
                const allowed = monthHasAllowedDay(target, min, max);
                return (
                  <button
                    key={name}
                    type="button"
                    disabled={!allowed}
                    aria-label={`${MONTHS[i]} de ${pickedYear}`}
                    onClick={() => {
                      setView(target);
                      setPanel('days');
                    }}
                    className="min-h-11 rounded-xl border border-line bg-white font-semibold capitalize disabled:opacity-30"
                  >
                    {name}
                  </button>
                );
              })}
            </div>
          )}

          {panel === 'days' && (
            <div ref={gridRef} role="group" aria-label={`${MONTHS[view.month - 1]} de ${view.year}`}>
              <div aria-hidden="true" className="grid grid-cols-7 text-center text-xs font-bold text-ink-2">
                {WEEKDAY_LETTERS.map((l) => (
                  <span key={l} className="py-1">
                    {l}
                  </span>
                ))}
              </div>
              <div className="grid grid-cols-7 gap-y-1">
                {monthCells(view).map((day, i) => {
                  if (!day) return <span key={`b${i}`} />;
                  const allowed = inRange(day, min, max);
                  const selected = day === value;
                  const isToday = day === today;
                  return (
                    <button
                      key={day}
                      type="button"
                      data-day={day}
                      disabled={!allowed}
                      aria-pressed={selected}
                      aria-current={isToday ? 'date' : undefined}
                      aria-label={longLabel(day)}
                      tabIndex={day === (tabStop ?? (isToday ? day : `${view.year}-${String(view.month).padStart(2, '0')}-01`)) ? 0 : -1}
                      onClick={() => choose(day)}
                      onKeyDown={(e) => {
                        const step = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }[e.key];
                        if (step !== undefined) {
                          e.preventDefault();
                          moveFocus(day, step);
                        }
                      }}
                      className={`mx-auto flex size-11 items-center justify-center rounded-full font-semibold ${
                        selected ? 'bg-brand text-brand-contrast' : isToday ? 'border-2 border-brand-ink' : ''
                      } ${allowed ? '' : 'text-ink-2/40'}`}
                    >
                      {Number(day.slice(8))}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          <div className="flex items-center justify-between border-t border-line pt-2">
            <button
              type="button"
              disabled={!inRange(today, min, max)}
              onClick={() => choose(today)}
              className="min-h-11 rounded-xl px-3 font-semibold text-brand-ink disabled:opacity-40"
            >
              Hoy
            </button>
            <button type="button" onClick={() => setOpen(false)} className="min-h-11 rounded-xl px-3 font-semibold text-ink-2">
              Cerrar
            </button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}
