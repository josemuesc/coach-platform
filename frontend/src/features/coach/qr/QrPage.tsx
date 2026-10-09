import { useQuery } from '@tanstack/react-query';
import QRCode from 'qrcode';
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { api, call } from '../../../api/client';
import type { components } from '../../../api/schema';
import { ApiError, messageFor } from '../../../api/errors';
import { clockTime } from '../../../lib/time';
import { useToday, type EventView } from '../today/queries';
import { eventLabel } from '../today/shared';

type Qr = components['schemas']['QrView'];
/** `refreshAtMs` is on the DEVICE clock but derived from the server's: the countdown follows the server even if the phone's clock is wrong. */
interface Issued {
  qr: Qr;
  refreshAtMs: number;
}

/** The code changes with every 30-second window; the server accepts the current and the previous one. */
const WINDOW_MS = 30_000;

/** Wake Lock where the browser has it, so the phone does not dim while the students scan. Best effort. */
function useWakeLock() {
  useEffect(() => {
    let lock: WakeLockSentinel | null = null;
    let released = false;
    async function acquire() {
      try {
        lock = (await navigator.wakeLock?.request('screen')) ?? null;
      } catch {
        lock = null;
      }
    }
    void acquire();
    const onVisible = () => {
      if (document.visibilityState === 'visible' && !released) void acquire();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      released = true;
      document.removeEventListener('visibilitychange', onVisible);
      void lock?.release().catch(() => undefined);
    };
  }, []);
}

function QrCanvas({ url, label }: { url: string; label: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (ref.current) void QRCode.toCanvas(ref.current, url, { width: 280, margin: 2 });
  }, [url]);
  return <canvas ref={ref} aria-label={label} role="img" className="rounded-2xl bg-white" />;
}

function confirmedLine(event: EventView | undefined): string {
  if (!event) return '';
  const confirmed = event.attendees.filter((a) => a.studentConfirmed).length;
  return `Confirmaron ${confirmed} de ${event.attendees.length}`;
}

export function QrPage() {
  const { eventId = '' } = useParams();
  const navigate = useNavigate();
  const today = useToday(5000);   // the list of who confirmed follows the scans
  const [now, setNow] = useState(() => Date.now());
  useWakeLock();

  const qr = useQuery({
    queryKey: ['coach', 'qr', eventId],
    queryFn: async (): Promise<Issued> => {
      const qr = await call<Qr>(api.GET('/api/coach/events/{id}/qr', { params: { path: { id: eventId } } }));
      const expiresAt = new Date(qr.expiresAt).getTime();
      const skewMs = expiresAt - qr.validForSeconds * 1000 - Date.now();   // server now minus device now
      return { qr, refreshAtMs: expiresAt - WINDOW_MS - skewMs } satisfies Issued;
    },
    // the code is never kept around once the screen is closed
    gcTime: 0,
    staleTime: 0,
    retry: false,
  });

  // One timer per issued code, set when it arrives (a time-dependent refetchInterval would be re-armed by the countdown's own
  // re-render every second, and lose the race against it). 500 ms past the boundary so the new window is surely the server's.
  const refreshAtMs = qr.isError ? undefined : qr.data?.refreshAtMs;
  const { refetch } = qr;
  useEffect(() => {
    if (refreshAtMs === undefined) return;
    const timer = window.setTimeout(() => void refetch(), Math.max(1000, refreshAtMs + 500 - Date.now()));
    return () => window.clearTimeout(timer);
  }, [refreshAtMs, refetch]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  // a hiccup (network, 5xx) must not leave a blank screen in front of the class; a 4xx (window closed, cancelled) is final
  const transient = qr.error instanceof ApiError && (qr.error.status === 0 || qr.error.status >= 500);
  useEffect(() => {
    if (!transient) return;
    const timer = window.setTimeout(() => void refetch(), 5000);
    return () => window.clearTimeout(timer);
  }, [transient, refetch, qr.errorUpdatedAt]);

  const event = today.data?.events.find((e) => e.id === eventId);
  const issued = qr.isError ? undefined : qr.data;
  const data = issued?.qr;
  const leftMs = issued ? Math.max(0, issued.refreshAtMs - now) : 0;
  const secondsLeft = Math.ceil(leftMs / 1000);
  const progress = Math.min(1, leftMs / WINDOW_MS);

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center gap-5 bg-[#0f1a17] px-5 py-8 text-white">
      <h1 className="text-center font-display text-2xl font-bold">{event ? eventLabel(event) : 'Código de la clase'}</h1>

      {qr.isError && (
        <p role="alert" className="rounded-xl bg-white/10 p-4 text-center">
          {messageFor(qr.error)}
        </p>
      )}
      {qr.isPending && <p role="status">Preparando el código…</p>}
      {data && (
        <>
          <QrCanvas url={data.url} label="Código QR de la clase. Los alumnos lo escanean con la cámara de la app." />
          <div className="w-full max-w-[280px] text-center">
            <p className="text-sm">Cambia cada 30 segundos · en {secondsLeft} s</p>
            <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-white/20" aria-hidden="true">
              <div className="h-full rounded-full bg-white" style={{ width: `${progress * 100}%` }} />
            </div>
            <p className="mt-3 text-sm text-white/80">
              Disponible desde {clockTime(data.availableFrom)} hasta {clockTime(data.availableUntil)}
            </p>
          </div>
        </>
      )}

      {event && event.attendees.length > 0 && (
        <section aria-label="Confirmaciones" className="w-full rounded-2xl bg-white/10 p-4">
          <h2 className="font-display text-lg font-bold">{confirmedLine(event)}</h2>
          <ul className="mt-2 flex flex-col gap-1">
            {event.attendees.map((a) => (
              <li key={a.attendanceId} className="flex items-center justify-between gap-2 text-sm">
                <span>{a.studentName}</span>
                <span className={a.studentConfirmed ? 'font-bold text-white' : 'text-white/80'}>{a.studentConfirmed ? 'Confirmó' : 'Falta'}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <button type="button" onClick={() => void navigate('/coach')} className="mt-auto min-h-12 w-full max-w-[280px] rounded-xl bg-white px-4 font-semibold text-[#0f1a17]">
        Listo
      </button>
    </main>
  );
}
