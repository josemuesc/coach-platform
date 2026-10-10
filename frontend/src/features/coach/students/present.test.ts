import { describe, expect, it } from 'vitest';
import { chipFor, matchesFilter, methodLabel, normalizeText, subtitleFor } from './present';
import type { BoardRow } from './queries';

function row(over: Partial<BoardRow> = {}): BoardRow {
  return {
    studentId: 's1', fullName: 'Laura Torres', minor: false, hasAccount: true, active: true, status: 'AL_DIA', activeCycle: true, expiringSoon: false,
    needsRenewal: false, expiringBy: null, planModality: 'SEMI_PERSONALIZED', classesIncluded: 8, classesRemaining: 5, endDate: '2026-11-06', daysUntilEnd: 28,
    pendingMarks: 0, ...over,
  };
}

describe('the chip of a row only words what the server decided', () => {
  it('up to date, not activated, suspended, no plan', () => {
    expect(chipFor(row())).toEqual({ tone: 'green', text: 'Al día' });
    expect(chipFor(row({ status: 'SIN_ACTIVAR' }))).toEqual({ tone: 'neutral', text: 'Sin activar' });
    expect(chipFor(row({ status: 'SUSPENDIDO' }))).toEqual({ tone: 'neutral', text: 'Suspendido' });
    expect(chipFor(row({ status: 'SIN_PLAN' }))).toEqual({ tone: 'red', text: 'Sin plan' });
  });

  it('an exhausted cycle and an expired one both say "renovar"', () => {
    expect(chipFor(row({ status: 'SIN_CLASES' }))).toEqual({ tone: 'amber', text: 'Sin clases · renovar' });
    expect(chipFor(row({ status: 'VENCIDO' }))).toEqual({ tone: 'red', text: 'Vencido · renovar' });
  });

  it('about to expire: by days, by classes, or both (the nearer one speaks)', () => {
    expect(chipFor(row({ status: 'POR_VENCER', expiringBy: 'DAYS', daysUntilEnd: 3 })).text).toBe('Vence en 3 días');
    expect(chipFor(row({ status: 'POR_VENCER', expiringBy: 'DAYS', daysUntilEnd: 1 })).text).toBe('Vence en 1 día');
    expect(chipFor(row({ status: 'POR_VENCER', expiringBy: 'DAYS', daysUntilEnd: 0 })).text).toBe('Vence hoy');
    expect(chipFor(row({ status: 'POR_VENCER', expiringBy: 'CLASSES', classesRemaining: 1 })).text).toBe('Le queda 1 clase');
    expect(chipFor(row({ status: 'POR_VENCER', expiringBy: 'CLASSES', classesRemaining: 2 })).text).toBe('Le quedan 2 clases');
    expect(chipFor(row({ status: 'POR_VENCER', expiringBy: 'BOTH', daysUntilEnd: 4, classesRemaining: 1 })).text).toBe('Vence en 4 días');
  });
});

describe('the second line of a row', () => {
  it('names the kind of class and what is left', () => {
    expect(subtitleFor(row())).toBe('Semipersonalizada · 5 clases');
    expect(subtitleFor(row({ classesRemaining: 1, planModality: 'PERSONALIZED' }))).toBe('Personalizada · 1 clase');
    expect(subtitleFor(row({ status: 'SIN_CLASES', classesRemaining: 0 }))).toBe('Semipersonalizada · sin clases');
    expect(subtitleFor(row({ status: 'VENCIDO', endDate: '2026-10-12' }))).toBe('Semipersonalizada · venció el 12 oct');
    expect(subtitleFor(row({ planModality: null, classesRemaining: null }))).toBe('Sin plan');
  });
});

describe('a filter is just "which flag the server set"', () => {
  const active = row({ activeCycle: true });
  const expiring = row({ activeCycle: true, expiringSoon: true, status: 'POR_VENCER' });
  const exhausted = row({ activeCycle: false, needsRenewal: true, status: 'SIN_CLASES' });
  const suspended = row({ activeCycle: false, active: false, status: 'SUSPENDIDO' });

  it('Todos shows everyone', () => {
    for (const r of [active, expiring, exhausted, suspended]) expect(matchesFilter(r, 'all')).toBe(true);
  });

  it('Por vencer is the expiring flag; Activos and Inactivos split on the active-cycle flag', () => {
    expect([active, expiring, exhausted, suspended].map((r) => matchesFilter(r, 'expiring'))).toEqual([false, true, false, false]);
    expect([active, expiring, exhausted, suspended].map((r) => matchesFilter(r, 'active'))).toEqual([true, true, false, false]);
    expect([active, expiring, exhausted, suspended].map((r) => matchesFilter(r, 'inactive'))).toEqual([false, false, true, true]);
  });
});

describe('search and labels', () => {
  it('ignores accents, case and edge spaces', () => {
    expect(normalizeText('  Sebastián PÉREZ ')).toBe('sebastian perez');
    expect(normalizeText('Ñandú')).toBe('nandu');
  });

  it('names the ways a payment is received', () => {
    expect(['NEQUI', 'TRANSFER', 'CASH', 'OTHER'].map(methodLabel)).toEqual(['Nequi', 'Transferencia', 'Efectivo', 'Otro']);
  });
});
