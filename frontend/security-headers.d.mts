export const DEFAULT_API_ORIGIN: string;
export function csp(options?: { apiOrigin?: string }): string;
export function securityHeaders(options?: { apiOrigin?: string }): Record<string, string>;
export const CACHE_RULES: { source: string; value: string }[];
export function cacheControlFor(pathname: string): string | undefined;
