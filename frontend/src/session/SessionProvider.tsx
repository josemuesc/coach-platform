import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createContext, useContext, useEffect, useMemo, useSyncExternalStore, type ReactNode } from 'react';
import { api, call } from '../api/client';
import type { components } from '../api/schema';
import { applyBrand } from '../brand/applyBrand';
import { session, tokenExpiryMs, type EndReason } from './sessionStore';

export type Me = components['schemas']['MeResponse'];

interface SessionValue {
  /** 'loading' while the token is being checked against /api/me. */
  status: 'anonymous' | 'loading' | 'authenticated' | 'error';
  me: Me | null;
  endReason: EndReason | null;
  retry: () => void;
  logout: () => void;
}

const Context = createContext<SessionValue | null>(null);

export const ME_KEY = ['me'] as const;

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const token = useSyncExternalStore(session.subscribe, session.getToken);
  const endReason = useSyncExternalStore(session.subscribe, session.getEndReason);

  const meQuery = useQuery({
    queryKey: ME_KEY,
    queryFn: () => call<Me>(api.GET('/api/me')),
    enabled: token !== null,
    retry: false,
    staleTime: 60_000,
  });

  // no token: nothing of the previous person may linger in memory, and the brand goes back to the default
  useEffect(() => {
    if (token === null) {
      queryClient.clear();
      applyBrand(null);
    }
  }, [token, queryClient]);

  useEffect(() => {
    if (meQuery.data) applyBrand(meQuery.data.primaryColor);
  }, [meQuery.data]);

  // end the session when the token's own expiry passes (the server enforces it too)
  useEffect(() => {
    const exp = tokenExpiryMs(token);
    if (exp === null) return;
    const wait = Math.min(Math.max(exp - Date.now(), 0), 2 ** 31 - 1);
    const timer = window.setTimeout(() => session.end('expired'), wait);
    return () => window.clearTimeout(timer);
  }, [token]);

  const value = useMemo<SessionValue>(() => {
    let status: SessionValue['status'] = 'anonymous';
    if (token !== null) status = meQuery.isError ? 'error' : meQuery.data ? 'authenticated' : 'loading';
    return {
      status,
      me: token !== null ? (meQuery.data ?? null) : null,
      endReason,
      retry: () => void meQuery.refetch(),
      logout: () => session.end('logout'),
    };
  }, [token, meQuery, endReason]);

  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(Context);
  if (!value) throw new Error('useSession outside SessionProvider');
  return value;
}

export function homeFor(role: string | undefined): string {
  if (role === 'COACH') return '/coach';
  if (role === 'STUDENT') return '/app';
  return '/login';
}

/** Only paths inside our own two areas are valid after login (no open redirect). */
export function safeRedirect(path: string | null | undefined): string | null {
  if (!path || !path.startsWith('/') || path.startsWith('//')) return null;
  return /^\/(coach|app)(\/|$)/.test(path) ? path : null;
}
