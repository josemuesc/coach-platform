import { describe, expect, it } from 'vitest';
import { daySummary, overrideNeed, pastChip, reasonTextFor, seatsText, slotNote, studentOption, studentSummary } from './present';
import type { BookableStudent } from './queries';

type Event = Parameters<typeof pastChip>[0];
const event = (over: Partial<Event> = {}): Event =>
  ({ id: 'e', startsAt: '2026-10-09T11:00:00Z', endsAt: '2026-10-09T12:00:00Z', modality: 'PERSONALIZED', capacity: 1, occupied: 1, freeSeats: 0, shared: false, status: 'SCHEDULED', phase: 'PAST', canShowQr: false, attendees: [], ...over }) as Event;
const att = (status: string) => ({ status }) as Event['attendees'][number];

describe('the day summary', () => {
  it('uses the counts of the server with the right plural', () => {
    expect(daySummary('2026-10-09', { classCount: 5, freeCount: 2 })).toBe('Viernes 9 de octubre · 5 clases · 2 espacios libres');
    expect(daySummary('2026-10-09', { classCount: 1, freeCount: 1 })).toBe('Viernes 9 de octubre · 1 clase · 1 espacio libre');
    expect(daySummary('2026-10-10', { classCount: 0, freeCount: 0 })).toBe('Sábado 10 de octubre · 0 clases · 0 espacios libres');
  });
});

describe('a class card', () => {
  it('shows seats only for semi-personalized classes', () => {
    expect(seatsText(event({ modality: 'SEMI_PERSONALIZED', capacity: 4, occupied: 3 }))).toBe('3 de 4 cupos');
    expect(seatsText(event())).toBeNull();
    expect(seatsText(event({ shared: true, capacity: 1, occupied: 2 }))).toBe('Compartida · 2 alumnos');
    expect(seatsText(event({ modality: 'SEMI_PERSONALIZED', shared: true, capacity: 4, occupied: 6 }))).toBe('Compartida · 6 alumnos');
  });

  it('says what happened to a class that ended, and nothing while it has not', () => {
    expect(pastChip(event({ attendees: [att('ATTENDED')] }))).toEqual({ tone: 'green', text: 'Asistió' });
    expect(pastChip(event({ attendees: [att('NO_SHOW')] }))).toEqual({ tone: 'neutral', text: 'No vino' });
    expect(pastChip(event({ attendees: [att('ATTENDED'), att('SCHEDULED')] }))).toEqual({ tone: 'amber', text: 'Por marcar' });
    expect(pastChip(event({ attendees: [att('ATTENDED'), att('NO_SHOW')] }))).toEqual({ tone: 'neutral', text: 'Marcada' });
    expect(pastChip(event({ phase: 'UPCOMING', attendees: [att('SCHEDULED')] }))).toBeNull();
    expect(pastChip(event({ phase: 'NOW', attendees: [att('SCHEDULED')] }))).toBeNull();
  });
});

describe('the booking sheet words', () => {
  const student = (over: Partial<BookableStudent>): BookableStudent => ({ studentId: 's', fullName: 'Laura Torres', minor: false, modality: 'SEMI_PERSONALIZED', classesAvailable: 1, canBook: true, blockedReason: null, ...over });

  it('lists a student with what they can still book, or why they cannot be chosen', () => {
    expect(studentOption(student({}))).toBe('Laura Torres · Semipersonalizada · 1 clase por agendar');
    expect(studentOption(student({ classesAvailable: 6 }))).toBe('Laura Torres · Semipersonalizada · 6 clases por agendar');
    expect(studentOption(student({ canBook: false, modality: null, classesAvailable: 0, blockedReason: 'NO_ACTIVE_CYCLE' }))).toBe('Laura Torres · sin ciclo activo');
    expect(studentOption(student({ canBook: false, classesAvailable: 0, blockedReason: 'NO_CLASSES_LEFT' }))).toBe('Laura Torres · sin clases por agendar');
  });

  it('says it neutrally under the selector', () => {
    expect(studentSummary(student({}))).toBe('Semipersonalizada · le queda 1 clase por agendar');
    expect(studentSummary(student({ classesAvailable: 5 }))).toBe('Semipersonalizada · le quedan 5 clases por agendar');
    expect(studentSummary(student({ canBook: false, blockedReason: 'NO_CLASSES_LEFT' }))).toContain('todas las clases');
  });

  it('describes a slot and the rule an exception would relax', () => {
    expect(slotNote({ modality: 'SEMI_PERSONALIZED', capacity: 4, occupied: 0, localTime: '08:00' })).toBe('Semipersonalizada · 4 cupos libres a las 8:00 a. m.');
    expect(slotNote({ modality: 'SEMI_PERSONALIZED', capacity: 4, occupied: 3, localTime: '17:00' })).toBe('Semipersonalizada · 1 cupo libre a las 5:00 p. m.');
    expect(slotNote({ modality: 'PERSONALIZED', capacity: 1, occupied: 0, localTime: '12:00' })).toBe('Personalizada · espacio libre a las 12:00 m.');
    expect(overrideNeed('EVENT_FULL')).toBe('lleno');
    expect(overrideNeed('MODALITY_MISMATCH')).toBe('otra modalidad');
    expect(overrideNeed('SLOT_TAKEN')).toBe('ocupado');
  });

  it('writes a quick reason in the field and leaves "Otro" for the person', () => {
    expect(reasonTextFor('REPOSICION')).toBe('Reposición');
    expect(reasonTextFor('COMPARTIDO')).toBe('Entreno compartido');
    expect(reasonTextFor('OTRO')).toBe('');
  });
});
