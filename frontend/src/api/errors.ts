import { ERRORS, type ErrorCode } from './errorCatalog.generated';

/** Codes only the browser can produce (the server never sends them), so they are not in docs/error-codes.md. */
const CLIENT_MESSAGES = {
  NETWORK_ERROR: 'No hay conexión con el servidor. Revisa tu internet e inténtalo de nuevo.',
  UNKNOWN_ERROR: 'Ocurrió un error inesperado. Inténtalo de nuevo.',
} as const;
type ClientCode = keyof typeof CLIENT_MESSAGES;

export type KnownCode = ErrorCode | ClientCode;

export class ApiError extends Error {
  readonly status: number;
  /** A server code (docs/error-codes.md), a client-only code, or any string the server may add in the future. */
  readonly code: string;
  readonly details: unknown;
  /** Seconds from the Retry-After header (429). */
  readonly retryAfterSeconds: number | null;

  constructor(status: number, code: string, details?: unknown, retryAfterSeconds: number | null = null) {
    super(code);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  is(code: KnownCode): boolean {
    return this.code === code;
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

/** The text the person reads. Server codes come from the catalog generated out of docs/error-codes.md. */
export function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code in ERRORS) {
      const base = ERRORS[error.code as ErrorCode].message;
      if (error.status === 429 && error.retryAfterSeconds !== null) {
        const minutes = Math.max(1, Math.ceil(error.retryAfterSeconds / 60));
        return `${base} Puedes volver a intentarlo en ${minutes} ${minutes === 1 ? 'minuto' : 'minutos'}.`;
      }
      return base;
    }
    if (error.code in CLIENT_MESSAGES) return CLIENT_MESSAGES[error.code as ClientCode];
    return `${CLIENT_MESSAGES.UNKNOWN_ERROR} (${error.code})`;
  }
  return CLIENT_MESSAGES.UNKNOWN_ERROR;
}

export function networkError(): ApiError {
  return new ApiError(0, 'NETWORK_ERROR');
}

export function parseRetryAfter(value: string | null): number | null {
  if (!value) return null;
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? n : null;
}
