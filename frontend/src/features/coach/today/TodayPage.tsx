import { useState } from 'react';
import { Link } from 'react-router';
import { headerDate, plural } from '../../../lib/time';
import { Banner } from '../../../ui/Banner';
import { EmptyState, ErrorState, Skeleton } from '../../../ui/States';
import { EventCard } from './EventCard';
import { MarkConfirmDialog, type MarkRequest } from './MarkConfirmDialog';
import { MarkSheet } from './MarkSheet';
import { PendingSheet } from './PendingSheet';
import { useNow, useOverview, usePending, useToday } from './queries';
import { focusRow } from './shared';

function Stat({ value, label, tone = 'plain', onClick }: { value: string; label: string; tone?: 'plain' | 'amber'; onClick?: () => void }) {
  const className = `flex min-h-[72px] flex-1 flex-col items-center justify-center rounded-2xl p-3 text-center ${tone === 'amber' ? 'bg-amber-bg text-amber-ink' : 'bg-white'}`;
  const content = (
    <>
      <span className="font-display text-2xl font-bold leading-none">{value}</span>
      <span className="mt-1 text-xs font-semibold">{label}</span>
    </>
  );
  return onClick ? (
    <button type="button" onClick={onClick} className={className}>
      {content}
    </button>
  ) : (
    <div className={className}>{content}</div>
  );
}

export function TodayPage() {
  const today = useToday();
  const pending = usePending();
  const overview = useOverview();
  const nowMs = useNow();
  const [sheetEventId, setSheetEventId] = useState<string | null>(null);
  const [pendingOpen, setPendingOpen] = useState(false);
  const [request, setRequest] = useState<MarkRequest | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (today.isPending) return <Skeleton label="Cargando tu día" />;
  if (today.isError) return <ErrorState error={today.error} onRetry={() => void today.refetch()} />;

  const view = today.data;
  const students = new Set(view.events.flatMap((e) => e.attendees.map((a) => a.studentId))).size;
  const pendingCount = pending.data?.length;
  const expiring = (overview.data ?? []).filter((o) => o.status === 'EXPIRING_SOON').length;
  const sheetEvent = view.events.find((e) => e.id === sheetEventId) ?? null;

  function finishMark(done: boolean) {
    const marked = request;
    setRequest(null);
    if (done && marked?.kind === 'one') focusRow(marked.attendanceId);
    if (done && marked?.kind === 'bulk') setSheetEventId(null);
  }

  return (
    <section className="flex flex-col gap-4 px-4 pb-6 pt-2">
      <header>
        <p className="text-xs font-bold tracking-wider text-ink-2">{headerDate(view.date)}</p>
        <h1 className="font-display text-[34px] font-bold leading-tight">Hoy</h1>
      </header>

      {notice !== null && (
        <Banner tone="green" role="status">
          {notice}
        </Banner>
      )}

      <div className="flex gap-2">
        <Stat value={String(view.events.length)} label="clases" />
        <Stat value={String(students)} label="alumnos" />
        <Stat value={pendingCount === undefined ? '–' : String(pendingCount)} label="por marcar" tone={pendingCount ? 'amber' : 'plain'} onClick={() => setPendingOpen(true)} />
      </div>

      {expiring > 0 && (
        <Link to="/coach/students" className="flex min-h-11 items-center text-sm font-semibold text-brand-ink underline underline-offset-4">
          {plural(expiring, 'plan por vencer', 'planes por vencer')}
        </Link>
      )}

      {view.events.length === 0 ? (
        <EmptyState title="No tienes clases hoy">
          <Link to="/coach/agenda" className="inline-flex min-h-11 items-center font-semibold text-brand-ink underline underline-offset-4">
            Ver agenda
          </Link>
        </EmptyState>
      ) : (
        <ol className="flex flex-col gap-3" aria-label="Clases de hoy">
          {view.events.map((event) => (
            <EventCard key={event.id} event={event} nowMs={nowMs} onMark={setRequest} onOpenSheet={setSheetEventId} />
          ))}
        </ol>
      )}

      <MarkSheet
        event={sheetEvent}
        today={view.date}
        overview={overview.data}
        onClose={() => setSheetEventId(null)}
        onMark={setRequest}
        onCancelled={() => {
          setSheetEventId(null);
          setNotice('Clase cancelada. No se descontó ninguna clase.');
        }}
      />
      <PendingSheet open={pendingOpen} items={pending.data ?? []} onClose={() => setPendingOpen(false)} onMark={setRequest} />
      <MarkConfirmDialog request={request} onClose={finishMark} />
    </section>
  );
}
