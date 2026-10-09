/**
 * The login token and why a session ended. Framework-free so the API client can use it and tests can drive it.
 * The token lives in localStorage (decided: it must survive reloads and the installed PWA's restarts; the strict CSP and the absence
 * of third-party scripts are what protect it). It is never logged and never put in a URL.
 */
export type EndReason = 'logout' | 'expired' | 'invalid_session' | 'suspended';

const KEY = 'cp.token';
const hasWindow = typeof window !== 'undefined';

function readStored(): string | null {
  if (!hasWindow) return null;
  try {
    return window.localStorage.getItem(KEY);
  } catch {
    return null; // storage blocked: the session simply does not persist
  }
}

function writeStored(value: string | null): void {
  if (!hasWindow) return;
  try {
    if (value === null) window.localStorage.removeItem(KEY);
    else window.localStorage.setItem(KEY, value);
  } catch {
    /* ignore */
  }
}

let token: string | null = readStored();
let endReason: EndReason | null = null;
const listeners = new Set<() => void>();

function emit(): void {
  listeners.forEach((l) => l());
}

export const session = {
  getToken: (): string | null => token,
  getEndReason: (): EndReason | null => endReason,

  subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },

  /** A new token (login, or the one change-password returns). */
  start(newToken: string): void {
    token = newToken;
    endReason = null;
    writeStored(newToken);
    emit();
  },

  /** Ends the session and remembers why, so the login page can say it. */
  end(reason: EndReason): void {
    token = null;
    endReason = reason;
    writeStored(null);
    emit();
  },

  clearEndReason(): void {
    endReason = null;
    emit();
  },
};

if (hasWindow) {
  // another tab logged in or out
  window.addEventListener('storage', (e) => {
    if (e.key !== KEY) return;
    token = e.newValue;
    if (token === null) endReason = 'logout';
    emit();
  });
}

/** When the token stops being accepted (the `exp` claim). Only used to end the session politely; the server decides validity. */
export function tokenExpiryMs(jwt: string | null): number | null {
  if (!jwt) return null;
  try {
    const payload = jwt.split('.')[1];
    if (!payload) return null;
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    const exp = (JSON.parse(json) as { exp?: unknown }).exp;
    return typeof exp === 'number' ? exp * 1000 : null;
  } catch {
    return null;
  }
}
