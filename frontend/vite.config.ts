import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv, type Plugin } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';
import { cacheControlFor, securityHeaders } from './security-headers.mjs';

/** `vite preview` (what the end-to-end tests load) answers with exactly the headers the static host will send. */
function staticHostHeaders(apiOrigin: string | undefined): Plugin {
  const headers = securityHeaders({ apiOrigin });
  return {
    name: 'static-host-headers',
    configurePreviewServer(server) {
      server.middlewares.use((req, res, next) => {
        for (const [name, value] of Object.entries(headers)) res.setHeader(name, value);
        const cache = cacheControlFor((req.url ?? '/').split('?')[0] ?? '/');
        if (cache) res.setHeader('Cache-Control', cache);
        next();
      });
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiBase = env.VITE_API_BASE_URL ?? '';
  const apiOrigin = apiBase ? new URL(apiBase).origin : undefined;
  return {
    plugins: [
      react(),
      tailwindcss(),
      VitePWA({
        // 'prompt': the person decides when to reload, never in the middle of marking attendance or scanning a code
        registerType: 'prompt',
        injectRegister: false,
        manifest: {
          name: 'Coach',
          short_name: 'Coach',
          description: 'Agenda, clases y progreso con tu entrenador',
          lang: 'es-CO',
          start_url: '/',
          scope: '/',
          display: 'standalone',
          orientation: 'portrait',
          background_color: '#F3F5F2',
          theme_color: '#0B6E5C',
          icons: [
            { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' },
            { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' },
            { src: '/icons/maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
          ],
        },
        workbox: {
          // The app SHELL only. There is deliberately NO runtimeCaching: nothing the API returns (personal and health data) is
          // ever written to the browser's caches.
          globPatterns: ['**/*.{js,css,html,woff2,png,svg,ico,webmanifest}'],
          navigateFallback: 'index.html',
          navigateFallbackDenylist: [/^\/api\//, /^\/v3\//, /^\/actuator\//],
          cleanupOutdatedCaches: true,
        },
      }),
      staticHostHeaders(apiOrigin),
    ],
    server: { host: '127.0.0.1', port: 5173, strictPort: true },
    preview: { host: '127.0.0.1', port: 4173, strictPort: true },
    // Never inline an asset as a data: URI (a small font would become font-src data:, which the strict CSP rightly refuses)
    build: { target: 'es2022', sourcemap: false, assetsInlineLimit: 0 },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['src/test/setup.ts'],
      // node's Request needs an absolute URL (a browser resolves '/api/..' against the page)
      env: { VITE_API_BASE_URL: 'http://api.test' },
      include: ['src/**/*.test.{ts,tsx}'],
    },
  };
});
