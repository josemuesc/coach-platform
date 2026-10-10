import { formatDayMonth } from '../../../lib/format';
import { plural } from '../../../lib/time';
import type { BoardRow } from './queries';

export type Tone = 'green' | 'amber' | 'red' | 'neutral';

export function modalityName(modality: BoardRow['planModality'] | undefined | null): string {
  return modality === 'SEMI_PERSONALIZED' ? 'Semipersonalizada' : modality === 'PERSONALIZED' ? 'Personalizada' : '';
}

/** The chip of a board row. The server chose the status and `expiringBy`; this only words them. */
export function chipFor(row: BoardRow): { tone: Tone; text: string } {
  switch (row.status) {
    case 'SUSPENDIDO':
      return { tone: 'neutral', text: 'Suspendido' };
    case 'SIN_ACTIVAR':
      return { tone: 'neutral', text: 'Sin activar' };
    case 'SIN_CLASES':
      return { tone: 'amber', text: 'Sin clases · renovar' };
    case 'VENCIDO':
      return { tone: 'red', text: 'Vencido · renovar' };
    case 'SIN_PLAN':
      return { tone: 'red', text: 'Sin plan' };
    case 'AL_DIA':
      return { tone: 'green', text: 'Al día' };
    case 'POR_VENCER': {
      const days = row.daysUntilEnd ?? 0;
      if (row.expiringBy === 'CLASSES') {
        const left = row.classesRemaining ?? 0;
        return { tone: 'amber', text: left === 1 ? 'Le queda 1 clase' : `Le quedan ${left} clases` };
      }
      return { tone: 'amber', text: days <= 0 ? 'Vence hoy' : days === 1 ? 'Vence en 1 día' : `Vence en ${days} días` };
    }
  }
}

/** The second line of a row: kind of class and what is left (or what happened). */
export function subtitleFor(row: BoardRow): string {
  if (row.planModality == null) return 'Sin plan';
  const kind = modalityName(row.planModality);
  switch (row.status) {
    case 'SIN_CLASES':
      return `${kind} · sin clases`;
    case 'VENCIDO':
      return row.endDate ? `${kind} · venció el ${formatDayMonth(row.endDate)}` : kind;
    default:
      return row.classesRemaining == null ? kind : `${kind} · ${plural(row.classesRemaining, 'clase', 'clases')}`;
  }
}

export function normalizeText(text: string): string {
  return text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase().trim();
}

export type FilterKey = 'all' | 'expiring' | 'active' | 'inactive';

/** The server decided what each flag means; a filter is just "which flag". */
export function matchesFilter(row: BoardRow, filter: FilterKey): boolean {
  switch (filter) {
    case 'all':
      return true;
    case 'expiring':
      return row.expiringSoon;
    case 'active':
      return row.activeCycle;
    case 'inactive':
      return !row.activeCycle;
  }
}

export const METHODS = [
  { value: 'NEQUI', label: 'Nequi' },
  { value: 'TRANSFER', label: 'Transferencia' },
  { value: 'CASH', label: 'Efectivo' },
  { value: 'OTHER', label: 'Otro' },
] as const;

export type Method = (typeof METHODS)[number]['value'];

export function methodLabel(value: string): string {
  return METHODS.find((m) => m.value === value)?.label ?? value;
}
