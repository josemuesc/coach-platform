import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { ERRORS } from './errorCatalog.generated';
import { ApiError, messageFor, networkError, parseRetryAfter } from './errors';

describe('error messages', () => {
  it('the catalog holds exactly the codes of docs/error-codes.md', () => {
    const doc = readFileSync('../docs/error-codes.md', 'utf8');
    const inDoc = [...doc.matchAll(/^\|\s*`([A-Z][A-Z0-9_]+)`\s*\|\s*(\d{3})\s*\|/gm)].map((m) => m[1]).sort();
    expect(Object.keys(ERRORS).sort()).toEqual(inDoc);
  });

  it('shows the Spanish text of the document for a server code', () => {
    expect(messageFor(new ApiError(401, 'INVALID_CREDENTIALS'))).toBe('Correo o contraseña incorrectos.');
    expect(messageFor(new ApiError(400, 'INVALID_RESET_LINK'))).toContain('enlace');
  });

  it('adds the wait when the server says Retry-After', () => {
    expect(messageFor(new ApiError(429, 'TOO_MANY_ATTEMPTS', undefined, 840))).toContain('14 minutos');
    expect(messageFor(new ApiError(429, 'TOO_MANY_ATTEMPTS', undefined, 30))).toContain('1 minuto');
    expect(messageFor(new ApiError(429, 'TOO_MANY_ATTEMPTS'))).not.toContain('Puedes volver');
  });

  it('has a message for a lost connection and a safe one for a code the catalog does not know', () => {
    expect(messageFor(networkError())).toContain('conexión');
    const unknown = messageFor(new ApiError(500, 'SOMETHING_NEW'));
    expect(unknown).toContain('inesperado');
    expect(unknown).toContain('SOMETHING_NEW');
    expect(messageFor(new Error('boom'))).toContain('inesperado');
    expect(messageFor(null)).toContain('inesperado');
  });

  it('reads Retry-After defensively', () => {
    expect(parseRetryAfter('120')).toBe(120);
    expect(parseRetryAfter(null)).toBeNull();
    expect(parseRetryAfter('soon')).toBeNull();
    expect(parseRetryAfter('-5')).toBeNull();
  });
});
