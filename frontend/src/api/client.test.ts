import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { session } from '../session/sessionStore';
import { api, call } from './client';
import { ApiError } from './errors';

function respond(status: number, body?: unknown, headers: Record<string, string> = {}) {
  const fetchMock = vi.fn<(request: Request) => Promise<Response>>(
    async () => new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } }),
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('typed client', () => {
  beforeEach(() => {
    session.end('logout');
    session.clearEndReason();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('sends the token in the Authorization header and never lets the browser cache the response', async () => {
    session.start('tok123');
    const fetchMock = respond(200, { role: 'COACH' });
    await call(api.GET('/api/me'));
    const request = fetchMock.mock.calls[0]![0];
    expect(request.headers.get('Authorization')).toBe('Bearer tok123');
    expect(request.cache).toBe('no-store');
  });

  it('sends no Authorization header without a session', async () => {
    const fetchMock = respond(401, { code: 'INVALID_CREDENTIALS' });
    await expect(call(api.POST('/api/auth/login', { body: { email: 'a@b.co', password: 'x' } }))).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' });
    expect(fetchMock.mock.calls[0]![0].headers.get('Authorization')).toBeNull();
  });

  it('an expired token (401 without a body) ends the session as expired', async () => {
    session.start('old');
    respond(401);
    await expect(call(api.GET('/api/me'))).rejects.toBeInstanceOf(ApiError);
    expect(session.getToken()).toBeNull();
    expect(session.getEndReason()).toBe('expired');
  });

  it('INVALID_SESSION (the password changed elsewhere) ends it with that reason', async () => {
    session.start('old');
    respond(401, { code: 'INVALID_SESSION' });
    await expect(call(api.GET('/api/me'))).rejects.toMatchObject({ code: 'INVALID_SESSION' });
    expect(session.getEndReason()).toBe('invalid_session');
  });

  it('ACCOUNT_SUSPENDED ends it as suspended', async () => {
    session.start('tok');
    respond(403, { code: 'ACCOUNT_SUSPENDED' });
    await expect(call(api.GET('/api/me'))).rejects.toMatchObject({ code: 'ACCOUNT_SUSPENDED' });
    expect(session.getEndReason()).toBe('suspended');
  });

  it('a wrong current password on change-password is an input error, not the end of the session', async () => {
    session.start('tok');
    respond(401, { code: 'INVALID_CREDENTIALS' });
    await expect(call(api.POST('/api/auth/change-password', { body: { currentPassword: 'x', newPassword: 'y'.repeat(10) } }))).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS',
    });
    expect(session.getToken()).toBe('tok');
  });

  it('other 4xx answers (e.g. an unknown student) leave the session alone', async () => {
    session.start('tok');
    respond(404, { code: 'STUDENT_NOT_FOUND' });
    await expect(call(api.GET('/api/coach/students/{id}', { params: { path: { id: '7b6a8d9c-0000-4000-8000-000000000000' } } }))).rejects.toMatchObject({ status: 404 });
    expect(session.getToken()).toBe('tok');
  });

  it('a login that fails (401) does not touch a leftover session state', async () => {
    session.start('tok');
    respond(401, { code: 'INVALID_CREDENTIALS' });
    await expect(call(api.POST('/api/auth/login', { body: { email: 'a@b.co', password: 'x' } }))).rejects.toBeInstanceOf(ApiError);
    expect(session.getToken()).toBe('tok');
  });

  it('turns a dead network into NETWORK_ERROR and carries Retry-After on a 429', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    await expect(call(api.GET('/api/me'))).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    respond(429, { code: 'TOO_MANY_ATTEMPTS' }, { 'Retry-After': '600' });
    await expect(call(api.POST('/api/auth/login', { body: { email: 'a@b.co', password: 'x' } }))).rejects.toMatchObject({ status: 429, retryAfterSeconds: 600 });
  });
});
