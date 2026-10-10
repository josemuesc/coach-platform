import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { API, bearer, createMinor, acceptInvitation, previewStatus, setBrand, uniqueEmail } from './fixtures/api';
import { dateTrigger, expect, goToMonth, pickDate, test, violations, watch } from './fixtures/page';
import { book, endCycleIn, expireCycle, fitsToday, moveEvent, newWorld, plan, pupil, suspend, useAClass, type World } from './fixtures/world';

const MONTHS = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];
const dm = (day: string) => `${Number(day.slice(8))} ${MONTHS[Number(day.slice(5, 7)) - 1]}`;
const dmy = (day: string) => `${dm(day)} ${day.slice(0, 4)}`;
const todayBogota = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Bogota' }).format(new Date());
const addDays = (day: string, n: number) => new Date(Date.parse(`${day}T12:00:00Z`) + n * 86_400_000).toISOString().slice(0, 10);

async function serious(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  return results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`);
}

async function open(page: Page, world: World, path: string) {
  await page.addInitScript((token) => window.localStorage.setItem('cp.token', token), world.coach.token);
  await page.goto(path);
}

const nav = (page: Page) => page.getByRole('navigation', { name: 'Secciones del entrenador' });
const rows = (page: Page) => page.getByRole('list', { name: 'Alumnos' }).getByRole('listitem');
const filter = (page: Page, name: RegExp) => page.getByRole('group', { name: 'Filtrar alumnos' }).getByRole('button', { name });

async function activeCycle(request: import('@playwright/test').APIRequestContext, world: World, studentId: string) {
  const res = await request.get(`${API}/api/coach/students/${studentId}/cycles/active`, { headers: bearer(world.coach.token) });
  expect(res.status()).toBe(200);
  return (await res.json()) as { startDate: string; endDate: string; status: string; modality: string };
}

async function profileOf(request: import('@playwright/test').APIRequestContext, world: World, studentId: string) {
  const res = await request.get(`${API}/api/coach/students/${studentId}/profile`, { headers: bearer(world.coach.token) });
  expect(res.status()).toBe(200);
  return (await res.json()) as { cycle: { extension: { extendFrom: string; extendUntil: string } } };
}

// ================================================================= the list

test.describe('the list of students', () => {
  test.beforeEach(() => {
    test.skip(!fitsToday(120, 30), 'too close to midnight (Bogota) to place a class in the past');
  });

  test('every student gets the chip and the place the server decided, and the four filters use its counts', async ({ page, request }) => {
    const w = await newWorld(request);
    const csp = await watch(page);
    const oneClass = await plan(request, w, 'Una clase', 1, 'PERSONALIZED');
    await pupil(request, w, 'Ana Gómez', { account: true });
    const beto = await pupil(request, w, 'Beto Ruiz', { account: true });
    endCycleIn(beto.id, 3);
    const cata = await pupil(request, w, 'Cata Díaz', { account: true, planId: oneClass });
    await useAClass(request, w, cata, 10, 0);                                                   // all its classes used: COMPLETED
    const dani = await pupil(request, w, 'Dani Pérez', { account: true });
    expireCycle(dani.id);
    await pupil(request, w, 'Enzo Mora', { account: true, pay: false });
    await pupil(request, w, 'Fabi León');                                                        // invitation never accepted, active cycle
    const gus = await pupil(request, w, 'Gus Peña', { account: true });
    await suspend(request, w, gus, 'Gus Peña');

    await open(page, w, '/coach/students');
    await expect(page.getByRole('heading', { name: 'Alumnos', level: 1 })).toBeVisible();
    await expect(page.getByText('3 ACTIVOS')).toBeVisible();

    // order: active cycles by name, then the inactive (to renew first, then the rest), the suspended last
    await expect(rows(page).locator('span.text-lg')).toHaveText(['Ana Gómez', 'Beto Ruiz', 'Fabi León', 'Cata Díaz', 'Dani Pérez', 'Enzo Mora', 'Gus Peña']);
    const row = (name: string) => rows(page).filter({ hasText: name });
    await expect(row('Ana Gómez')).toContainText('Al día');
    await expect(row('Ana Gómez')).toContainText('Personalizada · 8 clases');
    await expect(row('Beto Ruiz')).toContainText('Vence en 3 días');
    await expect(row('Fabi León')).toContainText('Sin activar');
    await expect(row('Cata Díaz')).toContainText('Sin clases · renovar');
    await expect(row('Dani Pérez')).toContainText('Vencido · renovar');
    await expect(row('Enzo Mora')).toContainText('Sin plan');
    await expect(row('Gus Peña')).toContainText('Suspendido');

    // the chips carry the server's counts
    await expect(filter(page, /^Todos/)).toContainText('7');
    await expect(filter(page, /^Por vencer/)).toContainText('1');
    await expect(filter(page, /^Activos/)).toContainText('3');
    await expect(filter(page, /^Inactivos/)).toContainText('4');
    expect(await serious(page)).toEqual([]);

    await filter(page, /^Por vencer/).click();
    await expect(filter(page, /^Por vencer/)).toHaveAttribute('aria-pressed', 'true');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Beto Ruiz');
    await filter(page, /^Activos/).click();
    await expect(rows(page).locator('span.text-lg')).toHaveText(['Ana Gómez', 'Beto Ruiz', 'Fabi León']);
    await filter(page, /^Inactivos/).click();
    await expect(rows(page).locator('span.text-lg')).toHaveText(['Cata Díaz', 'Dani Pérez', 'Enzo Mora', 'Gus Peña']);

    // the search is local, ignores accents, and combines with the filter
    await filter(page, /^Todos/).click();
    await page.getByLabel('Buscar por nombre').fill('perez');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Dani Pérez');
    await page.getByLabel('Buscar por nombre').fill('zzz');
    await expect(page.getByText('Ningún alumno coincide')).toBeVisible();

    expect(await violations(page)).toEqual([]);
    expect(csp.consoleErrors.filter((m) => /Content Security Policy|Refused to/i.test(m))).toEqual([]);
  });

  test('marking the last class in "Hoy" changes the list and the profile without reloading the page', async ({ page, request }) => {
    const w = await newWorld(request);
    const oneClass = await plan(request, w, 'Una clase', 1, 'PERSONALIZED');
    const cata = await pupil(request, w, 'Cata Díaz', { account: true, planId: oneClass });
    const booked = await book(request, w, cata, 10);
    moveEvent(booked.eventId, -3, 27);                                                           // in progress, not marked

    await open(page, w, '/coach/students');
    await page.evaluate(() => ((window as unknown as { __marker: number }).__marker = 1));      // proves nothing reloads
    await expect(rows(page).first()).toContainText('Le queda 1 clase');
    await rows(page).first().getByRole('link').click();
    await expect(page.getByRole('heading', { name: 'Cata Díaz', level: 1 })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Registrar pago' })).toBeDisabled();
    await expect(page.getByText(/Podrás renovar desde el .* o cuando use sus clases\./)).toBeVisible();

    await nav(page).getByRole('link', { name: 'Hoy' }).click();
    await page.getByRole('button', { name: 'Marcar que Cata Díaz asistió' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Sí, marcar' }).click();
    await expect(page.getByRole('listitem').filter({ hasText: 'Cata Díaz' }).getByText('Asistió')).toBeVisible();

    await nav(page).getByRole('link', { name: 'Alumnos' }).click();
    await expect(rows(page).first()).toContainText('Sin clases · renovar');
    await filter(page, /^Inactivos/).click();
    await expect(rows(page)).toHaveCount(1);
    await rows(page).first().getByRole('link').click();
    await expect(page.getByText('Usó todas sus clases: ya puedes registrar el pago de un ciclo nuevo.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Registrar pago' })).toBeEnabled();
    expect(await page.evaluate(() => (window as unknown as { __marker?: number }).__marker)).toBe(1);
  });

  test('an empty list and a failed load have their own states', async ({ page, request }) => {
    const w = await newWorld(request);
    await page.route('**/api/coach/students/board', (route) => route.abort());
    await open(page, w, '/coach/students');
    await expect(page.getByRole('alert').getByText('No pudimos cargar')).toBeVisible();
    expect(await serious(page)).toEqual([]);
    await page.unroute('**/api/coach/students/board');
    await page.getByRole('button', { name: 'Reintentar' }).click();
    await expect(page.getByText('Aún no tienes alumnos')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Crear el primero' })).toBeVisible();
    expect(await serious(page)).toEqual([]);
  });
});

// ================================================================= the new student

test.describe('creating a student', () => {
  test('an adult: email appears, the invitation link can be copied and sent by WhatsApp with a short message', async ({ page, request, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    const w = await newWorld(request);
    await setBrand(request, w.coach, 'Laura Fit', '#0B6E5C');
    await open(page, w, '/coach/students/new');
    expect(await serious(page)).toEqual([]);
    await page.getByLabel('Nombre completo').fill('Elena Invitada');
    await expect(page.getByLabel('Correo del alumno')).toHaveCount(0);                            // no birth date yet: nothing to ask
    await pickDate(page, page, 'Fecha de nacimiento', '1990-05-01');
    await page.getByLabel('Correo del alumno').fill(uniqueEmail('elena'));
    await page.getByLabel('Celular (opcional)').fill('300 123 4567');
    expect(await serious(page)).toEqual([]);
    await page.getByRole('button', { name: 'Crear alumno e invitar' }).click();

    await expect(page.getByRole('heading', { name: 'Alumno creado' })).toBeVisible();
    const url = await page.getByLabel('Enlace de invitación').inputValue();
    expect(url).toMatch(/\/invite\/[\w-]+$/);
    await page.getByRole('button', { name: 'Copiar' }).click();
    await expect(page.getByText('Enlace copiado.')).toBeVisible();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(url);

    const wa = await page.getByRole('link', { name: 'Enviar por WhatsApp' }).getAttribute('href');
    expect(wa).toMatch(/^https:\/\/wa\.me\/573001234567\?text=/);
    expect(decodeURIComponent(wa!.split('text=')[1]!)).toBe(`Hola Elena, Laura Fit te invita a crear tu cuenta: ${url}`);
    expect(await page.getByRole('link', { name: 'Enviar por WhatsApp' }).getAttribute('target')).toBe('_blank');
    expect(await previewStatus(request, url.slice(url.lastIndexOf('/') + 1))).toBe(200);
    expect(await serious(page)).toEqual([]);
    await page.getByRole('link', { name: 'Ver alumno' }).click();
    await expect(page.getByRole('heading', { name: 'Elena Invitada', level: 1 })).toBeVisible();
  });

  test('a minor: the guardian block appears, the 18th birthday is the border, a repeated guardian email is told at the field', async ({ page, request }) => {
    const w = await newWorld(request);
    const taken = uniqueEmail('madre');
    const first = await createMinor(request, w.coach, 'Hijo Uno', taken);
    await acceptInvitation(request, first);                                                       // that email now has an account

    await open(page, w, '/coach/students/new');
    const today = todayBogota();
    const eighteen = `${Number(today.slice(0, 4)) - 18}${today.slice(4)}`;
    await page.getByLabel('Nombre completo').fill('Mateo Sánchez');
    await pickDate(page, page, 'Fecha de nacimiento', eighteen);                                  // turns 18 TODAY: an adult
    await expect(page.getByLabel('Correo del alumno')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Es menor de edad' })).toHaveCount(0);
    await pickDate(page, page, 'Fecha de nacimiento', addDays(eighteen, 1));                      // turns 18 tomorrow: still a minor
    await expect(page.getByRole('heading', { name: 'Es menor de edad' })).toBeVisible();
    await expect(page.getByLabel('Correo del alumno')).toHaveCount(0);
    await pickDate(page, page, 'Fecha de nacimiento', '2010-03-14');

    await page.getByLabel('Nombre del acudiente').fill('Marta Sánchez');
    await page.getByLabel('Parentesco').fill('madre');
    await page.getByLabel('Celular del acudiente').fill('+57 300 123 4567');
    await page.getByLabel('Correo del acudiente').fill(taken);
    await expect(page.getByText('Cada alumno necesita un correo distinto.').first()).toBeVisible();
    expect(await serious(page)).toEqual([]);
    await page.getByRole('button', { name: 'Crear alumno e invitar' }).click();
    const message = 'Este correo ya tiene una cuenta. Cada alumno necesita un correo distinto; si eres el acudiente, avisa a tu entrenador para que registre otro.';
    await expect(page.getByRole('alert').filter({ hasText: message })).toBeVisible();              // at the field, not a generic banner
    expect(await serious(page)).toEqual([]);

    await page.getByLabel('Correo del acudiente').fill(uniqueEmail('madre2'));
    await page.getByRole('button', { name: 'Crear alumno e invitar' }).click();
    await expect(page.getByRole('heading', { name: 'Alumno creado' })).toBeVisible();
    const wa = await page.getByRole('link', { name: 'Enviar por WhatsApp' }).getAttribute('href');
    expect(wa).toMatch(/^https:\/\/wa\.me\/573001234567\?text=/);                                 // the guardian's number, prefix normalized
    expect(decodeURIComponent(wa!.split('text=')[1]!)).toMatch(/^Hola Marta Sánchez, .* te invita a crear tu cuenta: http/);
    expect(decodeURIComponent(wa!)).not.toContain('Mateo');                                       // nothing about the minor in the message
  });

  test('a phone that is not a clean Colombian mobile leaves the number out of the WhatsApp link', async ({ page, request }) => {
    const w = await newWorld(request);
    await open(page, w, '/coach/students/new');
    await page.getByLabel('Nombre completo').fill('Sin Número');
    await pickDate(page, page, 'Fecha de nacimiento', '1990-05-01');
    await page.getByLabel('Correo del alumno').fill(uniqueEmail('sn'));
    await page.getByLabel('Celular (opcional)').fill('6011234567');
    await page.getByRole('button', { name: 'Crear alumno e invitar' }).click();
    await expect(page.getByRole('heading', { name: 'Alumno creado' })).toBeVisible();
    expect(await page.getByRole('link', { name: 'Enviar por WhatsApp' }).getAttribute('href')).toMatch(/^https:\/\/wa\.me\/\?text=/);
  });

  test('missing data is asked for at the field', async ({ page, request }) => {
    const w = await newWorld(request);
    await open(page, w, '/coach/students/new');
    await page.getByRole('button', { name: 'Crear alumno e invitar' }).click();
    await expect(page.getByText('Este dato es obligatorio.').first()).toBeVisible();
    expect(await serious(page)).toEqual([]);
  });
});

// ================================================================= the profile

test.describe('the profile', () => {
  test('shows the plan, the classes, the consents, the emergency row and the account, and has no serious accessibility problem', async ({ page, request }) => {
    test.skip(!fitsToday(120, 30), 'too close to midnight (Bogota) to place a class in the past');
    const w = await newWorld(request);
    await setBrand(request, w.coach, 'Marca Clara', '#FFD60A');
    const ana = await pupil(request, w, 'Ana Gómez', { account: true });
    await useAClass(request, w, ana, 10, 0);
    await book(request, w, ana, 12);

    await open(page, w, `/coach/students/${ana.id}`);
    await expect(page.getByRole('heading', { name: 'Ana Gómez', level: 1 })).toBeVisible();
    await expect(page.getByText('Personalizado 8', { exact: true })).toBeVisible();
    await expect(page.getByText('7', { exact: true })).toBeVisible();                              // classes left
    await expect(page.getByText(/clases por usar, de 8/)).toBeVisible();
    await expect(page.getByRole('progressbar', { name: 'Clases usadas' })).toHaveAttribute('aria-valuenow', '1');
    await expect(page.getByText(/Último pago: .* · \$520\.000 · Efectivo/)).toBeVisible();
    await expect(page.getByText('Tratamiento de datos', { exact: true })).toBeVisible();
    await expect(page.getByText(/Aceptado el .* · versión /)).toBeVisible();
    await expect(page.getByText('Mensajes por WhatsApp')).toBeVisible();
    await expect(page.getByText('No aceptado')).toBeVisible();
    await expect(page.getByText('Contacto de emergencia:')).toBeVisible();
    await expect(page.getByText('aún no registrado')).toBeVisible();
    await expect(page.getByText('Cuenta activa.')).toBeVisible();
    await expect(page.getByRole('list').filter({ hasText: 'Personalizada' }).getByRole('listitem').first()).toBeVisible();   // next classes
    expect(await serious(page)).toEqual([]);

    // the roll of classes of the cycle
    await page.getByRole('button', { name: 'Ver planilla de clases' }).click();
    const sheet = page.getByRole('dialog', { name: 'Planilla de clases' });
    await expect(sheet.getByText(/^1\. /)).toBeVisible();
    await expect(sheet.getByText('Asistió')).toBeVisible();
    await expect(sheet.getByText('Marcado por ti')).toBeVisible();
    await expect(sheet.getByText(/^2\. /)).toBeVisible();
    await expect(sheet.getByText('Agendada')).toBeVisible();
    expect(await serious(page)).toEqual([]);
    await sheet.getByRole('button', { name: 'Cerrar' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('a student with no plan can be charged; the reference is checked by the server at the field; the confirmation precedes the save; the new cycle dates come from the server', async ({ page, request }) => {
    const w = await newWorld(request);
    const csp = await watch(page);
    const laura = await pupil(request, w, 'Laura Torres', { account: true, pay: false });
    await open(page, w, `/coach/students/${laura.id}`);
    await expect(page.getByText('Todavía no tiene plan.')).toBeVisible();
    await page.getByRole('button', { name: 'Registrar pago' }).click();
    const sheet = page.getByRole('dialog', { name: 'Registrar pago' });
    await expect(sheet.getByRole('radio', { name: /Personalizado 8/ })).toBeVisible();
    await expect(sheet.getByRole('button', { name: 'Registrar pago', exact: true })).toBeDisabled();   // no plan chosen yet
    await sheet.getByRole('radio', { name: /Personalizado 8/ }).click();
    await expect(sheet.getByRole('radio', { name: /Personalizado 8/ })).toHaveAttribute('aria-checked', 'true');
    await expect(sheet.getByLabel('Monto')).toHaveValue('$520.000');
    await expect(sheet.getByRole('radio', { name: 'Nequi' })).toHaveAttribute('aria-checked', 'true');
    await expect(sheet.getByText('Solo el comprobante: no escribas números de tarjeta ni de cuenta.')).toBeVisible();
    expect(await serious(page)).toEqual([]);

    // a different amount is said out loud in the confirmation
    await sheet.getByLabel('Monto').fill('400000');
    await sheet.getByLabel(/Número de comprobante/).fill('4111111111111111');
    await sheet.getByRole('button', { name: 'Registrar pago de $400.000' }).click();
    const confirm = page.getByRole('dialog', { name: '¿Registrar este pago?' });
    await expect(confirm.getByText('Un pago registrado no se puede editar.')).toBeVisible();
    await expect(confirm.getByText('$400.000')).toBeVisible();
    await expect(confirm.getByText('El monto es distinto al precio del plan ($520.000).')).toBeVisible();
    expect(await serious(page)).toEqual([]);
    await confirm.getByRole('button', { name: 'Sí, registrar' }).click();

    // the server refuses the card-like number: the message sits next to the field and nothing was recorded
    const error = sheet.getByRole('alert').filter({ hasText: 'Escribe solo el número de comprobante, sin números de tarjeta ni de cuenta.' });
    await expect(error).toBeVisible();
    expect(await serious(page)).toEqual([]);
    const none = await request.get(`${API}/api/coach/payments?studentId=${laura.id}`, { headers: bearer(w.coach.token) });
    expect(await none.json()).toEqual([]);

    await sheet.getByLabel('Monto').fill('520000');
    await sheet.getByLabel(/Número de comprobante/).fill('M1234567');
    await sheet.getByRole('radio', { name: 'Transferencia' }).click();
    await sheet.getByRole('button', { name: 'Registrar pago de $520.000' }).click();
    await expect(confirm.getByText('Transferencia')).toBeVisible();
    await expect(confirm.getByText('M1234567')).toBeVisible();
    await confirm.getByRole('button', { name: 'Sí, registrar' }).click();

    const done = page.getByRole('dialog', { name: 'Pago registrado' });
    await expect(done).toBeVisible();
    const cycle = await activeCycle(request, w, laura.id);
    await expect(done.getByText(new RegExp(`Del ${dmy(cycle.startDate)} al ${dmy(cycle.endDate)}`))).toBeVisible();
    expect(await serious(page)).toEqual([]);
    await done.getByRole('button', { name: 'Listo' }).click();

    await expect(page.getByText(/Último pago: .* · \$520\.000 · Transferencia · Comprobante M1234567/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Registrar pago' })).toBeDisabled();
    await expect(page.getByText(`Podrás renovar desde el ${dm(cycle.endDate)} o cuando use sus clases.`)).toBeVisible();
    expect(await violations(page)).toEqual([]);
    expect(csp.consoleErrors.filter((m) => /Content Security Policy|Refused to/i.test(m))).toEqual([]);
  });

  test('a student who used every class may renew at once, and the finished cycle is left as it was', async ({ page, request }) => {
    test.skip(!fitsToday(120, 30), 'too close to midnight (Bogota) to place a class in the past');
    const w = await newWorld(request);
    const oneClass = await plan(request, w, 'Una clase', 1, 'PERSONALIZED');
    const cata = await pupil(request, w, 'Cata Díaz', { account: true, planId: oneClass });
    await useAClass(request, w, cata, 10, 0);
    const before = await request.get(`${API}/api/coach/students/${cata.id}/cycles`, { headers: bearer(w.coach.token) });
    const [finished] = (await before.json()) as { id: string; startDate: string; endDate: string; status: string }[];

    await open(page, w, `/coach/students/${cata.id}`);
    await expect(page.getByText('Sin clases · renovar')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Extender ciclo' })).toHaveCount(0);            // a finished cycle is not extended
    await page.getByRole('button', { name: 'Registrar pago' }).click();
    const sheet = page.getByRole('dialog', { name: 'Registrar pago' });
    await expect(sheet.getByRole('radio', { name: /Una clase/ })).toHaveAttribute('aria-checked', 'true');    // the last plan, preselected
    await sheet.getByRole('radio', { name: /Personalizado 8/ }).click();
    await sheet.getByRole('button', { name: /^Registrar pago de/ }).click();
    await page.getByRole('dialog', { name: '¿Registrar este pago?' }).getByRole('button', { name: 'Sí, registrar' }).click();
    await expect(page.getByRole('dialog', { name: 'Pago registrado' })).toBeVisible();
    await page.getByRole('button', { name: 'Listo' }).click();

    const after = await request.get(`${API}/api/coach/students/${cata.id}/cycles`, { headers: bearer(w.coach.token) });
    const cycles = (await after.json()) as { id: string; startDate: string; endDate: string; status: string }[];
    expect(cycles.map((c) => c.status).sort()).toEqual(['ACTIVE', 'COMPLETED']);
    const old = cycles.find((c) => c.id === finished!.id)!;
    expect(old.status).toBe('COMPLETED');
    expect(old.startDate).toBe(finished!.startDate);
    expect(old.endDate).toBe(finished!.endDate);
  });

  test('extending the cycle: the dates the server accepts, a reason, the new date; beyond the cap is refused at the field', async ({ page, request }) => {
    const w = await newWorld(request);
    const ana = await pupil(request, w, 'Ana Gómez', { account: true });
    const { cycle } = await profileOf(request, w, ana.id);
    await open(page, w, `/coach/students/${ana.id}`);
    await page.getByRole('button', { name: 'Extender ciclo' }).click();
    const sheet = page.getByRole('dialog', { name: 'Extender ciclo' });
    await expect(sheet.getByText('La fecha solo se puede mover hacia adelante.')).toBeVisible();
    await expect(sheet.getByRole('button', { name: /^Extender hasta/ })).toBeDisabled();           // a reason is required
    expect(await serious(page)).toEqual([]);

    // the calendar grays out what the server would refuse: before the allowed range and after the extension limit
    await dateTrigger(sheet, 'Nueva fecha límite').click();
    const calendar = page.getByRole('dialog', { name: 'Nueva fecha límite' });
    await expect(calendar.locator(`[data-day="${cycle.extension.extendFrom}"]`)).toBeEnabled();
    const before = addDays(cycle.extension.extendFrom, -1);
    if (before.slice(0, 7) === cycle.extension.extendFrom.slice(0, 7)) await expect(calendar.locator(`[data-day="${before}"]`)).toBeDisabled();
    else await expect(calendar.getByRole('button', { name: 'Mes anterior' })).toBeDisabled();
    await goToMonth(calendar, cycle.extension.extendUntil);
    await expect(calendar.locator(`[data-day="${cycle.extension.extendUntil}"]`)).toBeEnabled();
    const after = addDays(cycle.extension.extendUntil, 1);
    if (after.slice(0, 7) === cycle.extension.extendUntil.slice(0, 7)) await expect(calendar.locator(`[data-day="${after}"]`)).toBeDisabled();
    else await expect(calendar.getByRole('button', { name: 'Mes siguiente' })).toBeDisabled();
    await calendar.getByRole('button', { name: 'Cerrar' }).click();
    await sheet.getByLabel('Motivo').fill('Viaje');

    const target = addDays(cycle.extension.extendFrom, 2);
    await pickDate(page, sheet, 'Nueva fecha límite', target);
    await sheet.getByRole('button', { name: /^Extender hasta/ }).click();
    await expect(sheet.getByText(new RegExp(`La nueva fecha límite es el ${dmy(target)}`))).toBeVisible();
    await sheet.getByRole('button', { name: 'Listo' }).click();
    expect((await activeCycle(request, w, ana.id)).endDate).toBe(target);
    await expect(page.getByText(new RegExp(`al ${dm(target)}`))).toBeVisible();
  });

  test('the password link: shown once with its warning, listed as open, revocable; for a minor it is the guardian\'s account', async ({ page, request }) => {
    const w = await newWorld(request);
    const ana = await pupil(request, w, 'Ana Gómez', { account: true });
    await open(page, w, `/coach/students/${ana.id}`);
    await expect(page.getByText('Cuenta activa.')).toBeVisible();
    await page.getByRole('button', { name: 'Generar enlace para restablecer la clave' }).click();
    const url = await page.getByLabel('Enlace para restablecer la clave').inputValue();
    expect(url).toMatch(/\/reset\/[\w-]+$/);
    await expect(page.getByText('Quien tenga el enlace puede entrar como el alumno. Vale 24 horas y un solo uso. Queda registrado.')).toBeVisible();
    await expect(page.getByText(/Hay un enlace abierto hasta el /)).toBeVisible();
    expect(await serious(page)).toEqual([]);

    await page.getByRole('button', { name: 'Revocar enlace' }).click();
    await expect(page.getByText(/Hay un enlace abierto hasta el /)).toHaveCount(0);
    await expect(page.getByLabel('Enlace para restablecer la clave')).toHaveCount(0);
    const token = url.slice(url.lastIndexOf('/') + 1);
    const used = await request.post(`${API}/api/auth/reset-password`, { data: { token, newPassword: 'Clave-nueva-1234' } });
    expect(used.status()).toBe(400);                                                               // the revoked link no longer works

    // a link used by the student shows who last changed the password
    await page.getByRole('button', { name: 'Generar enlace para restablecer la clave' }).click();
    const second = await page.getByLabel('Enlace para restablecer la clave').inputValue();
    expect((await request.post(`${API}/api/auth/reset-password`, { data: { token: second.slice(second.lastIndexOf('/') + 1), newPassword: 'Clave-nueva-1234' } })).status()).toBe(204);
    await page.reload();
    await expect(page.getByText(/La clave se restableció con un enlace tuyo el /)).toBeVisible();

    // a minor: the account is the guardian's, and the text says so
    const minor = await createMinor(request, w.coach, 'Mateo Menor', uniqueEmail('madre'), 'Marta Pérez');
    await acceptInvitation(request, minor);
    await page.goto(`/coach/students/${minor.id}`);
    await expect(page.getByText('Menor', { exact: true })).toBeVisible();
    await expect(page.getByText(/La cuenta es del acudiente: Marta Pérez/)).toBeVisible();
    await page.getByRole('button', { name: 'Generar enlace para restablecer la clave' }).click();
    await expect(page.getByText(/Es el enlace de la cuenta del acudiente: entrégaselo a Marta Pérez\./)).toBeVisible();
    await expect(page.getByText('Tratamiento de datos (representante legal)')).toBeVisible();
    expect(await serious(page)).toEqual([]);
  });

  test('a student who never accepted gets a new invitation link (the old one stops working) to send by WhatsApp', async ({ page, request }) => {
    const w = await newWorld(request);
    await setBrand(request, w.coach, 'Laura Fit', '#0B6E5C');
    const fabi = await pupil(request, w, 'Fabi León');
    await open(page, w, `/coach/students/${fabi.id}`);
    await expect(page.getByText('Todavía no activó la cuenta: falta que acepte la invitación.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Generar enlace para restablecer la clave' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Generar enlace de invitación nuevo' }).click();
    const url = await page.getByLabel('Enlace de invitación').inputValue();
    expect(await previewStatus(request, url.slice(url.lastIndexOf('/') + 1))).toBe(200);
    const wa = await page.getByRole('link', { name: 'Enviar por WhatsApp' }).getAttribute('href');
    expect(wa).toMatch(/^https:\/\/wa\.me\/573001234567\?text=/);
    expect(decodeURIComponent(wa!.split('text=')[1]!)).toBe(`Hola Fabi, Laura Fit te invita a crear tu cuenta: ${url}`);
    expect(await serious(page)).toEqual([]);
  });

  test('a profile that does not exist, or belongs to another coach, says so', async ({ page, request }) => {
    const w = await newWorld(request);
    const other = await newWorld(request);
    const theirs = await pupil(request, other, 'Ajeno');
    await open(page, w, `/coach/students/${theirs.id}`);
    await expect(page.getByRole('alert').getByText('No pudimos cargar')).toBeVisible();
    await expect(page.getByRole('link', { name: '← Alumnos' })).toBeVisible();
    expect(await serious(page)).toEqual([]);
  });
});

// ================================================================= renewing across modalities

test.describe('renewing into another modality', () => {
  async function conflict(request: import('@playwright/test').APIRequestContext) {
    const w = await newWorld(request);
    const ana = await pupil(request, w, 'Ana Gómez', { account: true });
    const booked = await book(request, w, ana, 10);                                                // a PERSONALIZED class tomorrow
    endCycleIn(ana.id, 0);                                                                          // today is its last day: renewing is allowed
    return { w, ana, booked };
  }

  async function chooseSemiAndSave(page: Page) {
    await page.getByRole('button', { name: 'Registrar pago' }).click();
    const sheet = page.getByRole('dialog', { name: 'Registrar pago' });
    await sheet.getByRole('radio', { name: /Semi 8/ }).click();
    await sheet.getByRole('button', { name: /^Registrar pago de/ }).click();
    await page.getByRole('dialog', { name: '¿Registrar este pago?' }).getByRole('button', { name: 'Sí, registrar' }).click();
    return page.getByRole('dialog', { name: 'Antes de cambiar de modalidad' });
  }

  test('option 1: cancel the booked classes without penalty, then the payment goes through', async ({ page, request }) => {
    const { w, ana, booked } = await conflict(request);
    await open(page, w, `/coach/students/${ana.id}`);
    await expect(page.getByRole('button', { name: 'Registrar pago' })).toBeEnabled();              // its last day: the server says so
    const sheet = await chooseSemiAndSave(page);
    await expect(sheet.getByText('Ana Gómez pasaría de Personalizada a Semipersonalizada.')).toBeVisible();
    await expect(sheet.getByText(/Tiene 1 clase agendada en la modalidad actual/)).toBeVisible();
    await expect(sheet.getByText('Agendada', { exact: true })).toBeVisible();
    await expect(sheet.getByText(/avisa/i)).toHaveCount(0);                                         // nothing is notified in this phase
    const save = sheet.getByRole('button', { name: 'Elige una opción' });
    await expect(save).toBeDisabled();
    expect(await serious(page)).toEqual([]);

    await sheet.getByRole('radio', { name: /Cancelar la clase agendada y cambiar/ }).click();
    await expect(sheet.getByRole('button', { name: 'Cancelar 1 clase y registrar el pago' })).toBeDisabled();   // a reason is required
    await sheet.getByLabel('Motivo').fill('Pasa al plan grupal');
    expect(await serious(page)).toEqual([]);
    await sheet.getByRole('button', { name: 'Cancelar 1 clase y registrar el pago' }).click();
    await expect(page.getByRole('dialog', { name: 'Pago registrado' })).toBeVisible();

    const sessions = await request.get(`${API}/api/coach/students/${ana.id}/sessions`, { headers: bearer(w.coach.token) });
    const list = (await sessions.json()) as { id: string; status: string }[];
    expect(list.find((s) => s.id === booked.attendanceId)!.status).toBe('CANCELLED_BY_COACH');
    expect((await activeCycle(request, w, ana.id)).modality).toBe('SEMI_PERSONALIZED');
  });

  test('option 2: move the classes to the new plan with a reason, flagged as an exception', async ({ page, request }) => {
    const { w, ana, booked } = await conflict(request);
    await open(page, w, `/coach/students/${ana.id}`);
    const sheet = await chooseSemiAndSave(page);
    await sheet.getByRole('radio', { name: /Pasar la clase al plan nuevo/ }).click();
    await expect(sheet.getByText(/quedan marcadas como excepción/)).toBeVisible();
    await sheet.getByLabel('Motivo').fill('Se queda con su horario');
    await sheet.getByRole('button', { name: 'Registrar el pago y pasar las clases' }).click();
    await expect(page.getByRole('dialog', { name: 'Pago registrado' })).toBeVisible();

    const sessions = await request.get(`${API}/api/coach/students/${ana.id}/sessions`, { headers: bearer(w.coach.token) });
    const place = ((await sessions.json()) as { id: string; status: string; override: boolean }[]).find((s) => s.id === booked.attendanceId)!;
    expect(place.status).toBe('SCHEDULED');
    expect(place.override).toBe(true);
    expect((await activeCycle(request, w, ana.id)).modality).toBe('SEMI_PERSONALIZED');
  });

  test('going back changes nothing', async ({ page, request }) => {
    const { w, ana } = await conflict(request);
    await open(page, w, `/coach/students/${ana.id}`);
    const sheet = await chooseSemiAndSave(page);
    await sheet.getByRole('button', { name: 'Volver sin cambiar nada' }).click();
    await expect(page.getByRole('dialog', { name: 'Registrar pago' })).toBeVisible();
    await page.getByRole('button', { name: 'Cancelar', exact: true }).click();
    expect((await activeCycle(request, w, ana.id)).modality).toBe('PERSONALIZED');
  });

  test('unmarked classes are listed before renewing, with a way to go and mark them', async ({ page, request }) => {
    const w = await newWorld(request);
    const ana = await pupil(request, w, 'Ana Gómez', { account: true });
    await page.route('**/api/coach/students/*/payments', (route) =>
      route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'PENDING_SESSIONS_TO_MARK',
          details: { pendingSessions: [{ attendanceId: 'a1', sessionId: 's1', studentId: ana.id, startsAt: '2026-10-07T15:00:00Z', endsAt: '2026-10-07T16:00:00Z', eventModality: 'SEMI_PERSONALIZED' }] },
        }),
      }),
    );
    await open(page, w, `/coach/students/${ana.id}`);
    endCycleIn(ana.id, 0);
    await page.reload();
    await page.getByRole('button', { name: 'Registrar pago' }).click();
    const sheet = page.getByRole('dialog', { name: 'Registrar pago' });
    await sheet.getByRole('radio', { name: /Personalizado 8/ }).click();
    await sheet.getByRole('button', { name: /^Registrar pago de/ }).click();
    await page.getByRole('dialog', { name: '¿Registrar este pago?' }).getByRole('button', { name: 'Sí, registrar' }).click();
    const pending = page.getByRole('dialog', { name: 'Antes de renovar' });
    await expect(pending.getByText('Hay clases que ya empezaron y no se marcaron.')).toBeVisible();
    await expect(pending.getByText('Semipersonalizada')).toBeVisible();
    await expect(pending.getByText('Sin marcar')).toBeVisible();
    expect(await serious(page)).toEqual([]);
    await pending.getByRole('button', { name: 'Ir a marcar las clases' }).click();
    await expect(page).toHaveURL(/\/coach$/);
  });
});
