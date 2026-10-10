import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { plural } from '../../../lib/time';
import { ChevronRightIcon } from '../../../ui/icons';
import { usePlans } from '../students/queries';

function LinkRow({ to, title, sub }: { to: string; title: string; sub: ReactNode }) {
  return (
    <li className="border-b border-line last:border-b-0">
      <Link to={to} className="flex min-h-[72px] items-center justify-between gap-3 px-4 py-3">
        <span>
          <span className="block font-display text-lg font-bold">{title}</span>
          <span className="block text-sm text-ink-2">{sub}</span>
        </span>
        <ChevronRightIcon />
      </Link>
    </li>
  );
}

/** A section that is on the plan but not built: visible, not a link, and said so ("Pronto"). */
function SoonRow({ title, sub }: { title: string; sub: string }) {
  return (
    <li aria-disabled="true" className="flex min-h-[72px] items-center justify-between gap-3 border-b border-line px-4 py-3 last:border-b-0">
      <span>
        <span className="block font-display text-lg font-bold text-ink-2">{title}</span>
        <span className="block text-sm text-ink-2">{sub}</span>
      </span>
      <span className="rounded-full bg-past px-3 py-1 text-xs font-bold text-ink-2">Pronto</span>
    </li>
  );
}

/** The list of settings. How people use this area is to be reviewed after the pilot (CLAUDE.md). */
export function SettingsPage() {
  const plans = usePlans();
  const planSub = plans.data ? `${plural(plans.data.length, 'plan', 'planes')} · ${plural(plans.data.filter((p) => p.active).length, 'activo', 'activos')}` : 'Precios y clases de cada plan';
  return (
    <section className="flex flex-col gap-4 px-4 pb-6 pt-2">
      <h1 className="font-display text-[34px] font-bold leading-tight">Ajustes</h1>
      <ul className="overflow-hidden rounded-3xl border border-line bg-white">
        <LinkRow to="/coach/plans" title="Planes" sub={planSub} />
        <LinkRow to="/coach/availability" title="Disponibilidad" sub="Horario semanal y bloqueos" />
        <SoonRow title="Marca" sub="Color y nombre de tu espacio" />
        <SoonRow title="Reglas" sub="Plazos y cupos" />
        <LinkRow to="/coach/account" title="Cuenta" sub="Contraseña y cierre de sesión" />
      </ul>
    </section>
  );
}
