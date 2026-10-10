import { useState } from 'react';
import { Link } from 'react-router';
import { initials } from '../../../brand/applyBrand';
import { Chip } from '../../../ui/Chip';
import { EmptyState, ErrorState, Skeleton } from '../../../ui/States';
import { chipFor, matchesFilter, normalizeText, subtitleFor, type FilterKey } from './present';
import { useBoard, type BoardRow } from './queries';

const FILTERS: { key: FilterKey; label: string; count: (c: { all: number; expiring: number; active: number; inactive: number }) => number }[] = [
  { key: 'all', label: 'Todos', count: (c) => c.all },
  { key: 'expiring', label: 'Por vencer', count: (c) => c.expiring },
  { key: 'active', label: 'Activos', count: (c) => c.active },
  { key: 'inactive', label: 'Inactivos', count: (c) => c.inactive },
];

function Row({ row }: { row: BoardRow }) {
  const chip = chipFor(row);
  return (
    <li className="border-b border-line last:border-b-0">
      <Link to={`/coach/students/${row.studentId}`} className="flex min-h-[72px] items-center gap-3 px-4 py-3">
        <span aria-hidden="true" className="inline-flex size-12 shrink-0 items-center justify-center rounded-full bg-past font-display text-base font-bold">
          {initials(row.fullName)}
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-x-2">
            <span className="font-display text-lg font-bold leading-tight">{row.fullName}</span>
            {row.minor && <span className="rounded-full border border-ink-2 px-2 text-xs font-semibold text-ink-2">Menor</span>}
          </span>
          <span className="block text-sm text-ink-2">{subtitleFor(row)}</span>
        </span>
        <Chip tone={chip.tone}>{chip.text}</Chip>
      </Link>
    </li>
  );
}

export function StudentsPage() {
  const board = useBoard();
  const [filter, setFilter] = useState<FilterKey>('all');
  const [query, setQuery] = useState('');

  if (board.isPending) return <Skeleton label="Cargando alumnos" />;
  if (board.isError) return <ErrorState error={board.error} onRetry={() => void board.refetch()} />;

  const { counts, students } = board.data;
  const needle = normalizeText(query);
  const shown = students.filter((r) => matchesFilter(r, filter) && (needle === '' || normalizeText(r.fullName).includes(needle)));

  return (
    <section className="flex flex-col gap-4 px-4 pb-6 pt-2">
      <header className="flex items-end justify-between gap-3">
        <div>
          <p className="text-xs font-bold tracking-wider text-ink-2">{counts.active} {counts.active === 1 ? 'ACTIVO' : 'ACTIVOS'}</p>
          <h1 className="font-display text-[34px] font-bold leading-tight">Alumnos</h1>
        </div>
        <Link to="/coach/students/new" className="inline-flex min-h-12 items-center rounded-full bg-brand px-5 font-semibold text-brand-contrast">
          + Nuevo
        </Link>
      </header>

      <div role="search">
        <label htmlFor="student-search" className="sr-only">
          Buscar por nombre
        </label>
        <input
          id="student-search"
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Buscar por nombre"
          autoComplete="off"
          className="min-h-12 w-full rounded-2xl border border-line bg-white px-4 text-base"
        />
      </div>

      <div role="group" aria-label="Filtrar alumnos" className="flex gap-2 overflow-x-auto pb-1">
        {FILTERS.map((f) => {
          const selected = filter === f.key;
          return (
            <button
              key={f.key}
              type="button"
              aria-pressed={selected}
              onClick={() => setFilter(f.key)}
              className={`min-h-12 shrink-0 rounded-2xl border px-4 text-left text-sm font-bold leading-tight ${
                selected ? 'border-ink bg-ink text-white' : f.key === 'expiring' ? 'border-transparent bg-amber-bg text-amber-ink' : 'border-line bg-white text-ink'
              }`}
            >
              {f.label}
              <span className="block text-base">{f.count(counts)}</span>
            </button>
          );
        })}
      </div>

      {counts.all === 0 ? (
        <EmptyState title="Aún no tienes alumnos">
          <Link to="/coach/students/new" className="inline-flex min-h-11 items-center font-semibold text-brand-ink underline underline-offset-4">
            Crear el primero
          </Link>
        </EmptyState>
      ) : shown.length === 0 ? (
        <EmptyState title="Ningún alumno coincide">
          <p className="text-ink-2">Prueba con otro nombre o con otro filtro.</p>
        </EmptyState>
      ) : (
        <ul aria-label="Alumnos" className="overflow-hidden rounded-3xl border border-line bg-white">
          {shown.map((row) => (
            <Row key={row.studentId} row={row} />
          ))}
        </ul>
      )}
    </section>
  );
}
