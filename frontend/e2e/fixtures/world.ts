import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, type APIRequestContext } from '@playwright/test';
import { API, bearer, login, registerCoach, uniqueEmail, acceptInvitation, type Coach } from './api';

/**
 * Scenarios that depend on the clock. The classes are booked through the real API on a free slot TOMORROW and then moved, by SQL, to
 * the minutes that give the phase under test (the back end's clock is the real one). This only ever talks to the throwaway Postgres
 * that scripts/stack.mjs started.
 */
export function sql(statement: string): void {
  const state = JSON.parse(readFileSync(resolve(import.meta.dirname, '..', '..', '.stack', 'state.json'), 'utf8')) as { container: string };
  execFileSync('docker', ['exec', state.container, 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1', '-q', '-c', statement], { stdio: 'pipe' });
}

/** Minutes since midnight in Bogota. The scenarios need the whole span of minutes around "now" to fall inside today. */
export function bogotaMinutes(): number {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'America/Bogota', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date());
  return Number(parts.find((p) => p.type === 'hour')!.value) * 60 + Number(parts.find((p) => p.type === 'minute')!.value);
}

export const fitsToday = (before: number, after: number) => bogotaMinutes() - before >= 1 && bogotaMinutes() + after <= 24 * 60 - 2;

export interface World {
  coach: Coach;
  personalizedPlan: string;
  semiPlan: string;
}

export async function newWorld(request: APIRequestContext): Promise<World> {
  const coach = await registerCoach(request);
  const windows = [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({ dayOfWeek, start: '05:00', end: '22:00' }));
  expect((await request.put(`${API}/api/coach/availability`, { headers: bearer(coach.token), data: windows })).status()).toBe(200);
  const plan = async (name: string, modality: string) => {
    const res = await request.post(`${API}/api/coach/plans`, { headers: bearer(coach.token), data: { name, classesIncluded: 8, priceCop: 520000, modality } });
    expect(res.status()).toBe(201);
    return ((await res.json()) as { id: string }).id;
  };
  return { coach, personalizedPlan: await plan('Personalizado 8', 'PERSONALIZED'), semiPlan: await plan('Semi 8', 'SEMI_PERSONALIZED') };
}

export interface Pupil {
  id: string;
  name: string;
  email: string;
}

/** A student with a paid, active cycle. `account`: also accepts the invitation so they can log in. */
export async function pupil(
  request: APIRequestContext,
  world: World,
  name: string,
  opts: { semi?: boolean; account?: boolean; planId?: string; pay?: boolean } = {},
): Promise<Pupil> {
  const email = uniqueEmail('alumno');
  const created = await request.post(`${API}/api/coach/students`, {
    headers: bearer(world.coach.token),
    data: { fullName: name, email, whatsappPhone: '3001234567', birthDate: '1990-05-01' },
  });
  expect(created.status()).toBe(201);
  const body = (await created.json()) as { student: { id: string }; inviteUrl: string };
  const student = { id: body.student.id, email, inviteToken: body.inviteUrl.slice(body.inviteUrl.lastIndexOf('/') + 1) };
  if (opts.account) await acceptInvitation(request, student);
  if (opts.pay !== false) {
    const paid = await request.post(`${API}/api/coach/students/${student.id}/payments`, {
      headers: bearer(world.coach.token),
      data: { planId: opts.planId ?? (opts.semi ? world.semiPlan : world.personalizedPlan), amountCop: 520000, method: 'CASH' },
    });
    expect(paid.status()).toBe(201);
  }
  return { id: student.id, name, email };
}

/** Tomorrow at HH:00 Bogota as an ISO instant. */
function tomorrowAt(hour: number): string {
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Bogota' }).format(new Date(Date.now() + 24 * 3600 * 1000));
  return new Date(`${day}T${String(hour).padStart(2, '0')}:00:00-05:00`).toISOString();
}

export interface Booked {
  attendanceId: string;
  eventId: string;
}

/** Books a place on a slot tomorrow (the same hour for several students shares the event). */
export async function book(request: APIRequestContext, world: World, student: Pupil, hour: number): Promise<Booked> {
  const res = await request.post(`${API}/api/coach/students/${student.id}/sessions`, {
    headers: bearer(world.coach.token),
    data: { startsAt: tomorrowAt(hour) },
  });
  expect(res.status()).toBe(201);
  const body = (await res.json()) as { id: string; eventId: string };
  return { attendanceId: body.id, eventId: body.eventId };
}

/** Moves an event to [now + from, now + to] minutes (negative = in the past). */
export function moveEvent(eventId: string, fromMinutes: number, toMinutes: number): void {
  const at = (m: number) => `date_trunc('minute', now()) + interval '${m} minutes'`;
  sql(`UPDATE class_session SET starts_at = ${at(fromMinutes)}, ends_at = ${at(toMinutes)} WHERE id = '${eventId}'`);
}

export async function studentToken(request: APIRequestContext, student: Pupil): Promise<string> {
  return login(request, student.email);
}

export async function attendanceStatus(request: APIRequestContext, world: World, student: Pupil, attendanceId: string): Promise<string> {
  const res = await request.get(`${API}/api/coach/students/${student.id}/sessions`, { headers: bearer(world.coach.token) });
  expect(res.status()).toBe(200);
  const list = (await res.json()) as { id: string; status: string }[];
  return list.find((a) => a.id === attendanceId)!.status;
}

export async function classesUsed(request: APIRequestContext, world: World, student: Pupil): Promise<number> {
  const res = await request.get(`${API}/api/coach/students/${student.id}/cycles/active`, { headers: bearer(world.coach.token) });
  expect(res.status()).toBe(200);
  return ((await res.json()) as { classesUsed: number }).classesUsed;
}

/** A plan of the coach (the world already has an 8-class personalized and an 8-class semi one). */
export async function plan(request: APIRequestContext, world: World, name: string, classes: number, modality: 'PERSONALIZED' | 'SEMI_PERSONALIZED', price = 520000): Promise<string> {
  const res = await request.post(`${API}/api/coach/plans`, { headers: bearer(world.coach.token), data: { name, classesIncluded: classes, priceCop: price, modality } });
  expect(res.status()).toBe(201);
  return ((await res.json()) as { id: string }).id;
}

const TODAY_SQL = "(now() AT TIME ZONE 'America/Bogota')::date";

/** The student's active cycle now ends `days` days from today (Bogota): the cycle is moved by SQL, the rules are the server's own. */
export function endCycleIn(studentId: string, days: number): void {
  sql(`UPDATE cycle SET start_date = ${TODAY_SQL} - 10, end_date = ${TODAY_SQL} + ${days}, original_end_date = ${TODAY_SQL} + ${days} WHERE student_id = '${studentId}' AND status = 'ACTIVE'`);
}

/** The student's active cycle ran out of time two days ago (the server reads it as EXPIRED). */
export function expireCycle(studentId: string): void {
  sql(`UPDATE cycle SET start_date = ${TODAY_SQL} - 40, end_date = ${TODAY_SQL} - 2, original_end_date = ${TODAY_SQL} - 2 WHERE student_id = '${studentId}' AND status = 'ACTIVE'`);
}

/** Books one class, moves it into the past and marks it, so the student has used one class. `slot` keeps the coach's events from overlapping. */
export async function useAClass(request: APIRequestContext, world: World, student: Pupil, hour: number, slot: number, result: 'ATTENDED' | 'NO_SHOW' = 'ATTENDED'): Promise<Booked> {
  const booked = await book(request, world, student, hour);
  moveEvent(booked.eventId, -(40 + 50 * slot), -(5 + 50 * slot));
  const res = await request.post(`${API}/api/coach/attendances/${booked.attendanceId}/mark`, { headers: bearer(world.coach.token), data: { result } });
  expect(res.status()).toBe(200);
  return booked;
}

export async function suspend(request: APIRequestContext, world: World, student: Pupil, name: string): Promise<void> {
  const res = await request.put(`${API}/api/coach/students/${student.id}`, {
    headers: bearer(world.coach.token),
    data: { data: { fullName: name, email: student.email, whatsappPhone: '3001234567', birthDate: '1990-05-01' }, active: false },
  });
  expect(res.status()).toBe(200);
}
