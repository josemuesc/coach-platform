import type { ReactNode } from 'react';
import { messageFor } from '../api/errors';
import { Button } from './Button';

export function Skeleton({ label = 'Cargando' }: { label?: string }) {
  return (
    <div role="status" aria-label={label} className="flex animate-pulse flex-col gap-3 p-4">
      <div className="h-8 w-1/2 rounded-lg bg-line" />
      <div className="h-24 rounded-2xl bg-line" />
      <div className="h-24 rounded-2xl bg-line" />
    </div>
  );
}

/** "No pudimos cargar" with the catalog's text for the error and a retry. */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div role="alert" className="m-4 flex flex-col items-start gap-3 rounded-2xl bg-white p-4">
      <p className="font-display text-lg font-bold">No pudimos cargar</p>
      <p className="text-ink-2">{messageFor(error)}</p>
      {onRetry && <Button onClick={onRetry}>Reintentar</Button>}
    </div>
  );
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="m-4 flex flex-col items-start gap-2 rounded-2xl bg-white p-4">
      <p className="font-display text-lg font-bold">{title}</p>
      {children}
    </div>
  );
}
