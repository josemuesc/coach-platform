import AxeBuilder from '@axe-core/playwright';
import jsQR from 'jsqr';
import type { Page } from '@playwright/test';
import { API, bearer, login, setBrand } from './fixtures/api';
import { expect, test, violations, watch } from './fixtures/page';
import { attendanceStatus, book, classesUsed, fitsToday, moveEvent, newWorld, pupil, type World } from './fixtures/world';

async function openToday(page: Page, world: World) {
  await page.addInitScript((token) => window.localStorage.setItem('cp.token', token), world.coach.token);
  await page.goto('/coach');
  await expect(page.getByRole('heading', { name: 'Hoy', level: 1 })).toBeVisible();
}

async function serious(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  return results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`);
}

// The classes sit in minutes around "now" and must stay inside the Bogota day; near midnight these scenarios cannot be built.
test.beforeEach(() => {
  test.skip(!fitsToday(40, 60), 'too close to midnight (Bogota) to place classes around "now"');
});

test('the day shows past, in-progress and upcoming classes, each as the server says', async ({ page, request }) => {
  const w = await newWorld(request);
  const csp = await watch(page);
  const ana = await pupil(request, w, 'Ana Gómez');
  const beto = await pupil(request, w, 'Beto Ruiz');
  const cata = await pupil(request, w, 'Cata Díaz');
  const past = await book(request, w, ana, 8);
  const now = await book(request, w, beto, 10);
  const next = await book(request, w, cata, 12);
  moveEvent(past.eventId, -35, -5);
  moveEvent(now.eventId, -3, 27);
  moveEvent(next.eventId, 40, 70);
  await request.post(`${API}/api/coach/attendances/${past.attendanceId}/mark`, { headers: bearer(w.coach.token), data: { result: 'ATTENDED' } });

  await openToday(page, w);
  const list = page.getByRole('list', { name: 'Clases de hoy' });
  await expect(list.locator('> li')).toHaveCount(3);
  await expect(page.locator('div').filter({ hasText: /^3clases$/ })).toBeVisible();   // the summary card
  await expect(list.getByText('Asistió')).toBeVisible();                         // the past one
  await expect(list.getByText('EN CURSO')).toBeVisible();
  await expect(list.getByRole('button', { name: 'Marcar que Beto Ruiz asistió' })).toBeVisible();
  await expect(list.getByText(/^en (\d+ h )?\d+ min$/)).toBeVisible();           // the upcoming one
  await expect(list.getByRole('button', { name: /Marcar que Cata/ })).toHaveCount(0);   // not started: the server gave no canMark
  await expect(list.getByRole('button', { name: /Marcar que Ana/ })).toHaveCount(0);    // already marked
  expect(await violations(page)).toEqual([]);
  expect(csp.consoleErrors.filter((m) => /Content Security Policy|Refused to/i.test(m))).toEqual([]);
});

test('marking asks first, keeps the focus, and only then uses up the class', async ({ page, request }) => {
  const w = await newWorld(request);
  const ana = await pupil(request, w, 'Ana Gómez', { semi: true });
  const beto = await pupil(request, w, 'Beto Ruiz', { semi: true });
  const a = await book(request, w, ana, 10);
  const b = await book(request, w, beto, 10);
  moveEvent(a.eventId, -3, 27);
  await openToday(page, w);

  const markAna = page.getByRole('button', { name: 'Marcar que Ana Gómez asistió' });
  await markAna.click();
  const dialog = page.getByRole('dialog', { name: 'Marcar que Ana Gómez asistió' });
  await expect(dialog.getByText('Descuenta 1 clase del plan, tanto si asistió como si no vino. Puedes cambiar entre Asistió y No vino, pero no quitar la marca.')).toBeVisible();

  // cancelling changes nothing and gives the focus back to the button that opened the dialog
  await dialog.getByRole('button', { name: 'Cancelar' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(markAna).toBeFocused();
  expect(await classesUsed(request, w, ana)).toBe(0);
  expect(await attendanceStatus(request, w, ana, a.attendanceId)).toBe('SCHEDULED');

  // the page behind cannot scroll while the dialog is open
  await markAna.click();
  expect(await page.evaluate(() => getComputedStyle(document.documentElement).overflow)).toBe('hidden');
  await page.getByRole('dialog').getByRole('button', { name: 'Sí, marcar' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('listitem').filter({ hasText: 'Ana Gómez' }).getByText('Asistió')).toBeVisible();
  await expect(page.getByText('1 de 2 marcados')).toBeVisible();
  expect(await classesUsed(request, w, ana)).toBe(1);
  // the button that opened the dialog is gone: the focus went to that student's row, not to the void
  await expect(page.locator(`[data-attendance="${a.attendanceId}"]`)).toBeFocused();

  await page.getByRole('button', { name: 'Marcar que Beto Ruiz no vino' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Sí, marcar' }).click();
  await expect(page.getByRole('listitem').filter({ hasText: 'Beto Ruiz' }).getByText('No vino')).toBeVisible();
  expect(await attendanceStatus(request, w, beto, b.attendanceId)).toBe('NO_SHOW');
  expect(await classesUsed(request, w, beto)).toBe(1);   // a no-show also uses the class
});

test('the sheet marks the pending ones at once and cancels a class with a reason', async ({ page, request }) => {
  const w = await newWorld(request);
  const ana = await pupil(request, w, 'Ana Gómez', { semi: true });
  const beto = await pupil(request, w, 'Beto Ruiz', { semi: true });
  const a = await book(request, w, ana, 10);
  await book(request, w, beto, 10);
  moveEvent(a.eventId, -3, 27);
  await openToday(page, w);

  await page.getByRole('button', { name: /Ana Gómez, Beto Ruiz|Beto Ruiz, Ana Gómez/ }).click();
  const sheet = page.getByRole('dialog', { name: 'Marcar asistencia' });
  await expect(sheet.getByText(/le quedan 8 clases/).first()).toBeVisible();
  await expect(sheet.getByText('Descuenta 1 clase del plan, tanto si asistió como si no vino. Puedes cambiar entre Asistió y No vino, pero no quitar la marca.')).toBeVisible();
  expect(await serious(page)).toEqual([]);

  await sheet.getByRole('button', { name: 'Marcar a los 2 pendientes como que asistieron' }).click();
  await page.getByRole('dialog', { name: 'Marcar a 2 alumnos como que asistieron' }).getByRole('button', { name: 'Sí, marcar a todos' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(await classesUsed(request, w, ana)).toBe(1);
  expect(await classesUsed(request, w, beto)).toBe(1);
});

test('cancelling the class lists who is affected, asks for a reason and uses up nothing', async ({ page, request }) => {
  const w = await newWorld(request);
  const ana = await pupil(request, w, 'Ana Gómez');
  const a = await book(request, w, ana, 10);
  moveEvent(a.eventId, -3, 27);
  await openToday(page, w);

  await page.getByRole('button', { name: 'Ana Gómez', exact: true }).click();
  await page.getByRole('button', { name: 'Cancelar la clase (se pide un motivo)' }).click();
  const dialog = page.getByRole('dialog', { name: 'Cancelar la clase' });
  await expect(dialog.getByText('Ana Gómez')).toBeVisible();
  const confirm = dialog.getByRole('button', { name: 'Cancelar la clase' });
  await expect(confirm).toBeDisabled();                                // no reason, no cancellation
  await dialog.getByLabel('Motivo').fill('Lluvia fuerte');
  await confirm.click();
  await expect(page.getByText('Clase cancelada. No se descontó ninguna clase.')).toBeVisible();
  expect(await attendanceStatus(request, w, ana, a.attendanceId)).toBe('CANCELLED_BY_COACH');
  expect(await classesUsed(request, w, ana)).toBe(0);
});

test('"por marcar" lists classes already given, including those of earlier days', async ({ page, request }) => {
  const w = await newWorld(request);
  const ana = await pupil(request, w, 'Ana Gómez');
  const a = await book(request, w, ana, 10);
  moveEvent(a.eventId, -30 - 24 * 60, -5 - 24 * 60);                    // yesterday: not in today's list, still pending
  await openToday(page, w);

  const tile = page.getByRole('button', { name: /por marcar/ });
  await expect(tile).toContainText('1');
  await tile.click();
  const sheet = page.getByRole('dialog', { name: 'Por marcar' });
  await sheet.getByRole('button', { name: 'Marcar que Ana Gómez asistió' }).click();
  await page.getByRole('dialog', { name: 'Marcar que Ana Gómez asistió' }).getByRole('button', { name: 'Sí, marcar' }).click();
  await expect(page.getByRole('dialog', { name: 'Por marcar' }).getByText('No hay clases por marcar.')).toBeVisible();
  expect(await classesUsed(request, w, ana)).toBe(1);
});

test('the QR screen draws a code the student can really scan, counts down and lists who confirmed', async ({ page, request }) => {
  test.slow();
  const w = await newWorld(request);
  const csp = await watch(page);
  const ana = await pupil(request, w, 'Ana Gómez', { account: true });
  const a = await book(request, w, ana, 10);
  moveEvent(a.eventId, -3, 27);
  await openToday(page, w);

  const qrRequests: number[] = [];
  page.on('request', (r) => {
    if (r.url().includes(`/events/${a.eventId}/qr`)) qrRequests.push(Date.now());
  });
  await page.getByRole('link', { name: 'Mostrar QR a los alumnos' }).click();
  await expect(page).toHaveURL(new RegExp(`/coach/qr/${a.eventId}$`));
  const canvas = page.getByRole('img', { name: /Código QR de la clase/ });
  await expect(canvas).toBeVisible();
  await expect(page.getByText(/Cambia cada 30 segundos · en \d+ s/)).toBeVisible();
  await expect(page.getByText(/Disponible desde .* hasta .*/)).toBeVisible();
  await expect(page.getByText('Confirmaron 0 de 1')).toBeVisible();
  expect(await serious(page)).toEqual([]);

  // decode what is on screen and let the student confirm with it, through the real API
  const pixels = await canvas.evaluate((c: HTMLCanvasElement) => {
    const ctx = c.getContext('2d')!;
    const d = ctx.getImageData(0, 0, c.width, c.height);
    return { width: d.width, height: d.height, data: Array.from(d.data) };
  });
  const decoded = jsQR(new Uint8ClampedArray(pixels.data), pixels.width, pixels.height);
  expect(decoded?.data).toContain('/qr#t=');
  const token = decoded!.data.slice(decoded!.data.indexOf('#t=') + 3);
  const studentToken = await login(request, ana.email);
  const scan = await request.post(`${API}/api/student/attendances/confirm-qr`, { headers: bearer(studentToken), data: { token } });
  expect(scan.status()).toBe(200);
  await expect(page.getByText('Confirmaron 1 de 1')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('Confirmó', { exact: true })).toBeVisible();

  // the code is renewed on its own (every 30 s window)
  await expect.poll(() => qrRequests.length, { timeout: 45_000 }).toBeGreaterThan(1);
  expect(await violations(page)).toEqual([]);
  expect(csp.consoleErrors.filter((m) => /Content Security Policy|Refused to/i.test(m))).toEqual([]);

  await page.getByRole('button', { name: 'Listo' }).click();
  await expect(page).toHaveURL(/\/coach$/);
});

test('empty day and failed load have their own states', async ({ page, request }) => {
  const w = await newWorld(request);
  await page.route('**/api/coach/today', (route) => route.fulfill({ status: 500, contentType: 'application/json', body: '{"code":"INTERNAL_ERROR"}' }));
  await page.addInitScript((token) => window.localStorage.setItem('cp.token', token), w.coach.token);
  await page.goto('/coach');
  await expect(page.getByRole('alert').getByText('No pudimos cargar')).toBeVisible();
  expect(await serious(page)).toEqual([]);
  await page.unroute('**/api/coach/today');
  await page.getByRole('button', { name: 'Reintentar' }).click();
  await expect(page.getByText('No tienes clases hoy')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Ver agenda' }).first()).toBeVisible();
  expect(await serious(page)).toEqual([]);
});

test('the day, the confirmation dialog and the sheet have no serious accessibility problems, also on a bright brand color', async ({ page, request }) => {
  const w = await newWorld(request);
  await setBrand(request, w.coach, 'Marca Clara', '#FFD60A');
  const ana = await pupil(request, w, 'Ana Gómez', { semi: true });
  const beto = await pupil(request, w, 'Beto Ruiz', { semi: true });
  const cata = await pupil(request, w, 'Cata Díaz');
  const a = await book(request, w, ana, 10);
  await book(request, w, beto, 10);
  const c = await book(request, w, cata, 12);
  moveEvent(a.eventId, -3, 27);
  moveEvent(c.eventId, 40, 70);
  await openToday(page, w);

  expect(await serious(page)).toEqual([]);
  await page.getByRole('button', { name: 'Marcar que Ana Gómez asistió' }).click();
  expect(await serious(page)).toEqual([]);
  await page.getByRole('dialog').getByRole('button', { name: 'Cancelar' }).click();
  await page.getByRole('button', { name: /Ana Gómez, Beto Ruiz|Beto Ruiz, Ana Gómez/ }).click();
  expect(await serious(page)).toEqual([]);
});
