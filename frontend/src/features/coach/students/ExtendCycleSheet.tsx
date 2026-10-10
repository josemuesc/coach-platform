import { useState } from 'react';
import { isApiError, messageFor } from '../../../api/errors';
import { formatDayMonth, formatDayMonthYear } from '../../../lib/format';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { Sheet } from '../../../ui/Overlays';
import { useExtendCycle, type Profile } from './queries';

/** Move the cycle's last day forward (or reopen an expired one). Mounted only while open. The dates the server accepts come with the profile. */
export function ExtendCycleSheet({ profile, onClose }: { profile: Profile; onClose: () => void }) {
  const extend = useExtendCycle();
  const cycle = profile.cycle.cycle;
  const window_ = profile.cycle.extension;
  const [date, setDate] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [dateError, setDateError] = useState<string | null>(null);
  const [newEnd, setNewEnd] = useState<string | null>(null);

  if (!cycle || !window_.canExtendCycle) return null;
  const chosen = date ?? window_.extendFrom ?? '';
  const reopening = cycle.status === 'EXPIRED';

  async function submit() {
    if (!cycle) return;
    setDateError(null);
    try {
      const result = await extend.mutateAsync({ cycleId: cycle.id, newEndDate: chosen, reason: reason.trim() });
      setNewEnd((result as { endDate: string }).endDate);
    } catch (e) {
      const code = isApiError(e) ? e.code : '';
      if (code === 'INVALID_EXTENSION' || code === 'EXTENSION_LIMIT_EXCEEDED') setDateError(messageFor(e));
    }
  }

  const generalError = extend.isError && !dateError ? extend.error : null;

  return (
    <Sheet open onOpenChange={(o) => !o && onClose()} title={reopening ? 'Reabrir ciclo' : 'Extender ciclo'} description={profile.student.fullName}>
      {newEnd ? (
        <div className="flex flex-col gap-4">
          <Banner tone="green" role="status">
            La nueva fecha límite es el <strong>{formatDayMonthYear(newEnd)}</strong>.
          </Banner>
          <Button onClick={onClose}>Listo</Button>
        </div>
      ) : (
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <p className="text-base">
            {reopening ? 'El ciclo venció el' : 'El ciclo vence el'} <strong>{formatDayMonthYear(cycle.endDate)}</strong>.
          </p>
          <div className="flex flex-col gap-1">
            <label htmlFor="extend-date" className="font-semibold">
              Nueva fecha límite
            </label>
            <input
              id="extend-date"
              type="date"
              value={chosen}
              min={window_.extendFrom ?? undefined}
              max={window_.extendUntil ?? undefined}
              aria-describedby={`extend-hint${dateError ? ' extend-date-error' : ''}`}
              aria-invalid={dateError ? true : undefined}
              onChange={(e) => setDate(e.target.value)}
              className="min-h-12 rounded-xl border border-line bg-white px-3 text-base"
            />
            <p id="extend-hint" className="text-sm text-ink-2">
              {window_.extendFrom && window_.extendUntil ? `Entre el ${formatDayMonth(window_.extendFrom)} y el ${formatDayMonth(window_.extendUntil)}.` : ''} La fecha solo se puede mover hacia adelante.
            </p>
            {dateError && (
              <p id="extend-date-error" role="alert" className="text-sm font-semibold text-red-ink">
                {dateError}
              </p>
            )}
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="extend-reason" className="font-semibold">
              Motivo
            </label>
            <textarea id="extend-reason" value={reason} maxLength={500} rows={3} onChange={(e) => setReason(e.target.value)} className="rounded-xl border border-line bg-white p-3 text-base" />
          </div>
          {generalError !== null && <Banner tone="red" role="alert">{messageFor(generalError)}</Banner>}
          <Button type="submit" loading={extend.isPending} disabled={chosen === '' || reason.trim() === ''} className="min-h-14">
            {chosen ? `${reopening ? 'Reabrir' : 'Extender'} hasta el ${formatDayMonth(chosen)}` : 'Extender ciclo'}
          </Button>
          <Button variant="ghost" onClick={onClose}>
            Cancelar
          </Button>
        </form>
      )}
    </Sheet>
  );
}
