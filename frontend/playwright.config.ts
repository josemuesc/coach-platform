import { defineConfig, devices } from '@playwright/test';

const FRONT = 'http://127.0.0.1:4173';
const API = 'http://127.0.0.1:8081';

/**
 * End-to-end against the REAL back end (a throwaway Postgres in Docker, see scripts/stack.mjs). The front end under test is the
 * production-like build served by `vite preview` with the exact security headers of the static host, on another origin than the API,
 * so CORS and the CSP are exercised for real. Mobile first: a phone on Chromium (and, opt-in, an iPhone on WebKit).
 */
export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  workers: 3,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: FRONT, trace: 'retain-on-failure', locale: 'es-CO', timezoneId: 'America/Bogota' },
  projects: [
    { name: 'chromium-mobile', use: { ...devices['Pixel 5'] } },
    // WebKit (iPhone) is opt-in: E2E_WEBKIT=1. On macOS 14 Playwright only ships a frozen WebKit that cannot open a page, so it needs
    // macOS 15+ or a Linux machine / CI. Without it, iOS-only behavior is covered by the manual checklist in CLAUDE.md.
    ...(process.env.E2E_WEBKIT ? [{ name: 'webkit-mobile', use: { ...devices['iPhone 12'] } }] : []),
  ],
  webServer: [
    {
      command: `node scripts/stack.mjs --frontend-origin ${FRONT}`,
      url: `${API}/v3/api-docs`,
      timeout: 300_000,
      reuseExistingServer: true,
      stdout: 'pipe',
    },
    {
      command: 'npm run build:e2e && npm run preview',
      url: FRONT,
      timeout: 240_000,
      reuseExistingServer: true,
      env: { VITE_API_BASE_URL: API },
    },
  ],
});
