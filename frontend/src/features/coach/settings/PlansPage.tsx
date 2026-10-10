import { useState } from 'react';
import { Link } from 'react-router';
import { formatCop } from '../../../lib/format';
import { plural } from '../../../lib/time';
import { Banner } from '../../../ui/Banner';
import { Chip } from '../../../ui/Chip';
import { ChevronLeftIcon } from '../../../ui/icons';
import { EmptyState, ErrorState, Skeleton } from '../../../ui/States';
import { modalityName } from '../students/present';
import { usePlans, type Plan } from '../students/queries';
import { PlanSheet } from './PlanSheet';

export function BackToSettings() {
  return (
    <Link to="/coach/settings" className="inline-flex min-h-11 items-center gap-1 font-semibold text-brand-ink">
      <ChevronLeftIcon />
      Ajustes
    </Link>
  );
}

function PlanCard({ plan, onOpen }: { plan: Plan; onOpen: () => void }) {
  return (
    <li>
      <button
        type="button"
        onClick={onOpen}
        aria-label={`Editar el plan ${plan.name}`}
        className={`w-full rounded-2xl border p-4 text-left ${plan.active ? 'border-line bg-white' : 'border-line bg-past'}`}
      >
        <span className="flex items-start justify-between gap-3">
          <span className={`font-display text-xl font-bold leading-tight ${plan.active ? '' : 'text-ink-2'}`}>{plan.name}</span>
          <Chip tone={plan.active ? 'green' : 'neutral'}>{plan.active ? 'Activo' : 'Desactivado'}</Chip>
        </span>
        <span className="mt-1 block text-ink-2">
          {modalityName(plan.modality)} · {plural(plan.classesIncluded, 'clase', 'clases')} por ciclo
        </span>
        <span className="mt-2 flex items-end justify-between gap-3">
          <span className={`font-display text-[28px] font-bold leading-none ${plan.active ? '' : 'text-ink-2'}`}>{formatCop(plan.priceCop)}</span>
          <span className="text-ink-2">{plan.activeStudents === 0 ? 'Sin alumnos' : `${plural(plan.activeStudents, 'alumno', 'alumnos')} con este plan`}</span>
        </span>
      </button>
    </li>
  );
}

export function PlansPage() {
  const plans = usePlans();
  // null = closed, 'new' = creating, a plan id = editing it (the plan itself is read from the list, so the sheet always shows the saved values)
  const [open, setOpen] = useState<string | null>(null);

  if (plans.isPending) return <Skeleton label="Cargando planes" />;
  if (plans.isError) return <ErrorState error={plans.error} onRetry={() => void plans.refetch()} />;

  const editing = open && open !== 'new' ? (plans.data.find((p) => p.id === open) ?? null) : null;

  return (
    <section className="flex flex-col gap-4 px-4 pb-6 pt-2">
      <div>
        <BackToSettings />
      </div>
      <header className="flex items-end justify-between gap-3">
        <h1 className="font-display text-[34px] font-bold leading-tight">Planes</h1>
        <button type="button" onClick={() => setOpen('new')} className="inline-flex min-h-12 items-center rounded-full bg-brand px-5 font-semibold text-brand-contrast">
          + Nuevo plan
        </button>
      </header>
      <Banner tone="amber">Editar un plan no cambia los ciclos ya iniciados: los cambios aplican a los pagos nuevos.</Banner>

      {plans.data.length === 0 ? (
        <EmptyState title="Todavía no tienes planes">
          <p className="text-ink-2">Crea el primero para poder registrar pagos.</p>
        </EmptyState>
      ) : (
        <ul aria-label="Planes" className="flex flex-col gap-3">
          {plans.data.map((plan) => (
            <PlanCard key={plan.id} plan={plan} onOpen={() => setOpen(plan.id)} />
          ))}
        </ul>
      )}

      {open === 'new' && <PlanSheet plan={null} onClose={() => setOpen(null)} />}
      {editing && <PlanSheet plan={editing} onClose={() => setOpen(null)} />}
    </section>
  );
}
