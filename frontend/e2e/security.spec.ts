import { expect, test, violations, watch } from './fixtures/page';
import { PASSWORD, registerCoach } from './fixtures/api';

const API_ORIGIN = 'http://127.0.0.1:8081';
const APP_ORIGIN = 'http://127.0.0.1:4173';

test('every response carries the strict headers and the service worker is never cached', async ({ request }) => {
  const root = await request.get('/');
  const h = root.headers();
  const csp = h['content-security-policy']!;
  expect(csp).toContain("default-src 'none'");
  expect(csp).toContain("script-src 'self'");
  expect(csp).toContain("style-src 'self'");
  expect(csp).toContain("font-src 'self'");
  expect(csp).toContain("worker-src 'self'");
  expect(csp).toContain(`connect-src 'self' ${API_ORIGIN}`);
  expect(csp).not.toMatch(/unsafe-inline|unsafe-eval|wasm-unsafe-eval|blob:|data:|\*/);
  expect(h['referrer-policy']).toBe('no-referrer');
  expect(h['permissions-policy']).toContain('camera=(self)');
  expect(h['x-content-type-options']).toBe('nosniff');
  expect(h['x-frame-options']).toBe('DENY');
  expect(h['cache-control']).toBe('no-cache');
  expect((await request.get('/sw.js')).headers()['cache-control']).toBe('no-cache');
});

test('the entry document has no inline script or style', async ({ request }) => {
  const html = await (await request.get('/')).text();
  expect(html).not.toMatch(/<script(?![^>]*\ssrc=)[^>]*>/);
  expect(html).not.toMatch(/<style[\s>]/);
  expect(html).not.toMatch(/\sstyle=/);
  expect(html).not.toMatch(/https?:\/\/(?!127\.0\.0\.1)/);
});

test('signing in and moving around breaks no policy and talks only to the app and the API', async ({ page, request }) => {
  const w = await watch(page);
  const coach = await registerCoach(request);
  await page.goto('/login');
  await page.getByLabel('Correo').fill(coach.email);
  await page.getByLabel('Contraseña').fill(PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).toHaveURL(/\/coach$/);
  await page.getByRole('link', { name: 'Ajustes' }).click();
  await page.getByRole('link', { name: /^Cuenta/ }).click();
  await expect(page.getByRole('heading', { name: 'Cuenta' })).toBeVisible();

  expect(await violations(page)).toEqual([]);
  expect(w.consoleErrors.filter((m) => /Content Security Policy|Refused to/i.test(m))).toEqual([]);
  expect([...w.origins].filter((o) => o !== APP_ORIGIN && o !== API_ORIGIN)).toEqual([]);
});

test('the fonts are served from our own origin', async ({ page }) => {
  const fonts: string[] = [];
  page.on('response', (r) => {
    if (/\.woff2?$/.test(new URL(r.url()).pathname)) fonts.push(new URL(r.url()).origin);
  });
  await page.goto('/login');
  await page.evaluate(() => document.fonts.ready);
  await page.waitForLoadState('networkidle');
  expect(fonts.length).toBeGreaterThan(0);
  expect(new Set(fonts)).toEqual(new Set([APP_ORIGIN]));
});

test.describe('overlays and the QR decoding under the strict CSP (smoke page, end-to-end build only)', () => {
  test('the Dialog and the Sheet of the app (native <dialog>) open and close without a single policy violation', async ({ page }) => {
    await watch(page);
    await page.goto('/__smoke');
    await page.getByRole('button', { name: 'Abrir diálogo', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'Confirmar marca' })).toBeVisible();
    await page.getByRole('button', { name: 'Entendido' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.getByRole('button', { name: 'Abrir hoja' }).click();
    await expect(page.getByRole('dialog', { name: 'Marcar asistencia' })).toBeVisible();
    await page.getByRole('button', { name: 'Listo' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect(await violations(page)).toEqual([]);
  });

  test('jsQR in our own worker decodes the code with zero violations (what the strict CSP allows)', async ({ page }) => {
    await watch(page);
    await page.goto('/__smoke');
    await page.getByRole('button', { name: 'Decodificar con jsQR en worker propio' }).click();
    await expect(page.locator('#jsqr-result')).toHaveText('OK https://app.example.test/qr#t=abcDEF0123456789_-xyz');
    expect(await violations(page)).toEqual([]);
  });
});
