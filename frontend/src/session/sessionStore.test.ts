import { beforeEach, describe, expect, it, vi } from 'vitest';
import { session, tokenExpiryMs } from './sessionStore';

function jwt(payload: object): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'HS256' })}.${b64(payload)}.signature`;
}

describe('session store', () => {
  beforeEach(() => {
    session.end('logout');
    session.clearEndReason();
    window.localStorage.clear();
  });

  it('keeps the token in localStorage and forgets it on end()', () => {
    session.start('abc');
    expect(session.getToken()).toBe('abc');
    expect(window.localStorage.getItem('cp.token')).toBe('abc');
    session.end('expired');
    expect(session.getToken()).toBeNull();
    expect(window.localStorage.getItem('cp.token')).toBeNull();
    expect(session.getEndReason()).toBe('expired');
  });

  it('a new login forgets why the previous session ended', () => {
    session.end('invalid_session');
    session.start('fresh');
    expect(session.getEndReason()).toBeNull();
  });

  it('tells subscribers, and stops after unsubscribe', () => {
    const listener = vi.fn();
    const off = session.subscribe(listener);
    session.start('a');
    expect(listener).toHaveBeenCalledTimes(1);
    off();
    session.start('b');
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('follows a logout done in another tab', () => {
    session.start('abc');
    window.dispatchEvent(new StorageEvent('storage', { key: 'cp.token', newValue: null }));
    expect(session.getToken()).toBeNull();
    expect(session.getEndReason()).toBe('logout');
  });

  it('reads the expiry of a token and tolerates garbage', () => {
    expect(tokenExpiryMs(jwt({ exp: 2000000000 }))).toBe(2000000000 * 1000);
    expect(tokenExpiryMs(jwt({}))).toBeNull();
    expect(tokenExpiryMs('not-a-token')).toBeNull();
    expect(tokenExpiryMs(null)).toBeNull();
  });
});
