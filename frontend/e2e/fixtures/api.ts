import { randomUUID } from 'node:crypto';
import { expect, type APIRequestContext } from '@playwright/test';

/** The tests build their world through the real API (never by writing to the database), with data nobody else shares. */
export const API = 'http://127.0.0.1:8081';
export const PASSWORD = 'Prueba-1234-x';

export interface Coach {
  email: string;
  token: string;
}

export const uniqueEmail = (prefix: string) => `${prefix}-${randomUUID().slice(0, 8)}@test.co`;

export async function registerCoach(request: APIRequestContext): Promise<Coach> {
  const email = uniqueEmail('coach');
  const res = await request.post(`${API}/api/auth/register-coach`, { data: { name: `Coach ${email}`, email, password: PASSWORD } });
  expect(res.status()).toBe(201);
  return { email, token: ((await res.json()) as { token: string }).token };
}

export async function login(request: APIRequestContext, email: string, password = PASSWORD): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, { data: { email, password } });
  expect(res.status()).toBe(200);
  return ((await res.json()) as { token: string }).token;
}

export const bearer = (token: string) => ({ Authorization: `Bearer ${token}` });

export async function setBrand(request: APIRequestContext, coach: Coach, brandName: string, primaryColor: string): Promise<void> {
  const res = await request.put(`${API}/api/coach/brand`, { headers: bearer(coach.token), data: { brandName, primaryColor } });
  expect(res.status()).toBe(200);
}

export interface Student {
  id: string;
  email: string;
  inviteToken: string;
}

export async function createStudent(request: APIRequestContext, coach: Coach, name = 'Ana Prueba'): Promise<Student> {
  const email = uniqueEmail('alumno');
  const res = await request.post(`${API}/api/coach/students`, {
    headers: bearer(coach.token),
    data: { fullName: name, email, whatsappPhone: '3001234567', birthDate: '1990-05-01' },
  });
  expect(res.status()).toBe(201);
  const body = (await res.json()) as { student: { id: string }; inviteUrl: string };
  return { id: body.student.id, email, inviteToken: body.inviteUrl.slice(body.inviteUrl.lastIndexOf('/') + 1) };
}

/** Accepts an adult's invitation the way the page will: reads the texts in force and accepts the data one. */
export async function acceptInvitation(request: APIRequestContext, student: Student, password = PASSWORD): Promise<void> {
  const preview = await request.post(`${API}/api/invitations/preview`, { data: { token: student.inviteToken } });
  expect(preview.status()).toBe(200);
  const texts = ((await preview.json()) as { consents: { type: string; version: string }[] }).consents;
  const data = texts.find((t) => t.type === 'DATA_ADULT')!;
  const res = await request.post(`${API}/api/invitations/accept`, {
    data: { token: student.inviteToken, password, acceptData: true, dataVersion: data.version, acceptWhatsapp: false, whatsappVersion: null },
  });
  expect(res.status()).toBe(200);
}

export async function studentWithAccount(request: APIRequestContext, coach: Coach): Promise<Student> {
  const student = await createStudent(request, coach);
  await acceptInvitation(request, student);
  return student;
}

/** The coach hands over a reset link: returns the token inside it. */
export async function issueResetLink(request: APIRequestContext, coach: Coach, student: Student): Promise<string> {
  const res = await request.post(`${API}/api/coach/students/${student.id}/password-reset`, { headers: bearer(coach.token) });
  expect(res.status()).toBe(201);
  const url = ((await res.json()) as { resetUrl: string }).resetUrl;
  return url.slice(url.lastIndexOf('/') + 1);
}
