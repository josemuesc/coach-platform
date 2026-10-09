import createClient, { type Middleware } from 'openapi-fetch';
import { session } from '../session/sessionStore';
import { ApiError, networkError, parseRetryAfter } from './errors';
import type { paths } from './schema';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

/** Calls where a 401 means "wrong input", not "your session is over". */
const PUBLIC_PATHS = ['/api/auth/login', '/api/auth/reset-password', '/api/auth/register-coach', '/api/invitations/'];

async function codeOf(response: Response): Promise<string | null> {
  try {
    const body = (await response.clone().json()) as { code?: unknown };
    return typeof body.code === 'string' ? body.code : null;
  } catch {
    return null;
  }
}

const sessionMiddleware: Middleware = {
  onRequest({ request }) {
    const token = session.getToken();
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
    return request;
  },
  async onResponse({ request, response }) {
    if (!session.getToken()) return;
    const path = new URL(request.url, 'http://placeholder').pathname;
    if (PUBLIC_PATHS.some((p) => path.startsWith(p))) return;
    if (response.status === 401) {
      const code = await codeOf(response);
      // INVALID_CREDENTIALS is a wrong current password on change-password: an input error, the session is fine
      if (code === 'INVALID_CREDENTIALS') return;
      // INVALID_SESSION = the password changed elsewhere; anything else (an expired token has no body) is a plain expiry
      session.end(code === 'INVALID_SESSION' ? 'invalid_session' : 'expired');
    } else if (response.status === 403 && (await codeOf(response)) === 'ACCOUNT_SUSPENDED') {
      session.end('suspended');
    }
  },
};

/** The one typed door to the API. `cache: 'no-store'`: personal data never goes to the browser's HTTP cache either. */
// `fetch` is looked up at call time (not captured when the module loads), so tests can replace it
export const api = createClient<paths>({ baseUrl: BASE_URL, cache: 'no-store', fetch: (request) => globalThis.fetch(request) });
api.use(sessionMiddleware);

interface Result<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/** Awaits a call and returns its data, or throws an ApiError carrying the server's stable code. */
export async function call<T>(request: Promise<Result<T>>): Promise<T> {
  let result: Result<T>;
  try {
    result = await request;
  } catch {
    throw networkError();
  }
  if (result.response.ok) return result.data as T;
  const body = (result.error ?? {}) as { code?: unknown; details?: unknown };
  throw new ApiError(
    result.response.status,
    typeof body.code === 'string' ? body.code : 'UNKNOWN_ERROR',
    body.details,
    parseRetryAfter(result.response.headers.get('Retry-After')),
  );
}
