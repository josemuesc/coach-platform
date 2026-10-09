// ONE definition of the security headers of the static site. Used by `vite preview` (and so by the end-to-end tests) and written to
// public/_headers (Netlify / Cloudflare Pages) and vercel.json (Vercel) by `npm run gen:headers`. `npm run check:headers` fails when
// those files drift from this module. `vite dev` does NOT enforce the CSP: its hot-reload preamble is an inline script.

export const DEFAULT_API_ORIGIN = 'http://127.0.0.1:8081';

/**
 * Strict CSP: only our own origin, no inline script or style, no third parties. The API origin is the only external connection.
 * Anything looser (blob:, wasm-unsafe-eval, unsafe-inline) must be decided explicitly, never added to make a library work.
 */
export function csp({ apiOrigin = DEFAULT_API_ORIGIN } = {}) {
  const connect = apiOrigin ? `'self' ${apiOrigin}` : "'self'";
  return [
    "default-src 'none'",
    "script-src 'self'",
    "style-src 'self'",
    "font-src 'self'",
    "img-src 'self'",
    `connect-src ${connect}`,
    "worker-src 'self'",
    "manifest-src 'self'",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "object-src 'none'",
  ].join('; ');
}

export function securityHeaders(options = {}) {
  return {
    'Content-Security-Policy': csp(options),
    'Referrer-Policy': 'no-referrer',
    'Permissions-Policy': 'camera=(self), microphone=(), geolocation=(), payment=(), usb=()',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Strict-Transport-Security': 'max-age=63072000; includeSubDomains',
  };
}

/** Cache rules: the service worker and the entry document must never be stale; hashed assets never change. */
export const CACHE_RULES = [
  { source: '/sw.js', value: 'no-cache' },
  { source: '/index.html', value: 'no-cache' },
  { source: '/manifest.webmanifest', value: 'no-cache' },
  { source: '/assets/(.*)', value: 'public, max-age=31536000, immutable' },
];

/** The Cache-Control a path gets (for the preview server). */
export function cacheControlFor(pathname) {
  if (pathname === '/' || pathname === '/index.html') return 'no-cache';
  if (pathname === '/sw.js' || pathname === '/manifest.webmanifest') return 'no-cache';
  if (pathname.startsWith('/assets/')) return 'public, max-age=31536000, immutable';
  return undefined;
}
