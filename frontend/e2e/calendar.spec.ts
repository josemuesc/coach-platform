import AxeBuilder from '@axe-core/playwright';
import type { APIRequestContext, Page } from '@playwright/test';
import { API, bearer, createMinor, uniqueEmail } from './fixtures/api';
import { expect, test, violations, watch } from './fixtures/page';
import { book, newWorld, pupil, type World } from './fixtures/world';

const addDays = (day: string, n: number) => new Date(Date.parse(`${day}T12:00:00Z`) + n * 86_400_000).toISOString().slice(0, 10);
const todayBogota = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Bogota' }).format(new Date());
const tomorrow = () => addDays(todayBogota(), 1);
const isoDow = (day: string) => new Date(`${day}T12:00:00Z`).getUTCDay() || 7;

async function serious(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  return results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`);
}

async function open(page: Page, world: World, path: string) {
  await page.addInitScript((token) => window.localStorage.setItem('cp.token', token), world.coach.token);
  await page.goto(path);
}

async function weekFromApi(request: APIRequestContext, world: World, date: string) {
  const res = await request.get(`${API}/api/coach/agenda/week?date=${date}`, { headers: bearer(world.coach.token) });
  expect(res.status()).toBe(200);
  return (await res.json()) as { days: { localDate: string; classCount: number; freeCount: number }[] };
}

async function sessionsOf(request: APIRequestContext, world: World, studentId: string) {
  const res = await request.get(`${API}/api/coach/students/${studentId}/sessions`, { headers: bearer(world.coach.token) });
  expect(res.status()).toBe(200);
  return (await res.json()) as { id: string; status: string; override: boolean; overrideReason: string | null }[];
}

async function blockApi(request: APIRequestContext, world: World, date: string, start: string, end: string, reason: string, ids: string[] = []) {
  const res = await request.post(`${API}/api/coach/availability/blocks`, {
    headers: bearer(world.coach.token),
    data: { localDate: date, allDay: false, startTime: start, endTime: end, reason, affectedAttendanceIds: ids },
  });
  expect(res.status()).toBe(201);
}

const DAY = tomorrow();

// ================================================================= the agenda

test.describe('the agenda', () => {
  test('shows the day with its classes, free slots and blocks, and the counts are the server\'s', async ({ page, request }) => {
    const w = await newWorld(request);
    await watch(page);
    const ana = await pupil(request, w, 'Ana Gómez');
    const beto = await pupil(request, w, 'Beto Ruiz', { semi: true });
    const minor = await createMinor(request, w.coach, 'Mateo Soto', uniqueEmail('acudiente'));
    expect((await request.post(`${API}/api/coach/students/${minor.id}/payments`, { headers: bearer(w.coach.token), data: { planId: w.semiPlan, amountCop: 520000, method: 'CASH' } })).status()).toBe(201);
    await book(request, w, ana, 10);
    await book(request, w, beto, 11);
    expect((await request.post(`${API}/api/coach/students/${minor.id}/sessions`, { headers: bearer(w.coach.token), data: { startsAt: new Date(`${DAY}T11:00:00-05:00`).toISOString() } })).status()).toBe(201);
    await blockApi(request, w, DAY, '14:00', '16:00', 'Reunión');

    await open(page, w, `/coach/agenda?d=${DAY}`);
    await expect(page.getByRole('heading', { name: 'Agenda', level: 1 })).toBeVisible();
    const server = (await weekFromApi(request, w, DAY)).days.find((d) => d.localDate === DAY)!;
    expect(server.classCount).toBe(2);
    await expect(page.getByText(`2 clases · ${server.freeCount} espacios libres`)).toBeVisible();
    const list = page.getByRole('list', { name: 'Agenda del día' });
    await expect(list.getByText('Disponible')).toHaveCount(server.freeCount);
    await expect(list.getByText('Bloqueado · Reunión')).toBeVisible();
    await expect(list.getByText('2:00 p. m. – 4:00 p. m.').or(list.getByText('2:00 – 4:00'))).toBeVisible();
    await expect(list.getByText('Ana Gómez')).toBeVisible();                                    // full names
    await expect(list.getByText('2 de 4 cupos')).toBeVisible();
    await expect(list.getByText('Mateo Soto').locator('..').getByText('Menor')).toBeVisible();
    expect(await serious(page)).toEqual([]);

    await page.getByRole('button', { name: 'Semana siguiente' }).click();
    await expect(page).toHaveURL(new RegExp(`d=${addDays(DAY, 7)}`));
    await expect(page.getByRole('button', { name: 'Ir a hoy' })).toBeVisible();
    expect(await violations(page)).toEqual([]);
  });

  test('a day without a schedule says so and links to the availability', async ({ page, request }) => {
    const w = await newWorld(request);
    expect((await request.put(`${API}/api/coach/availability/days/${isoDow(DAY)}`, { headers: bearer(w.coach.token), data: [] })).status()).toBe(200);
    await open(page, w, `/coach/agenda?d=${DAY}`);
    await expect(page.getByText('Sin horario este día')).toBeVisible();
    await page.getByRole('link', { name: 'Editar disponibilidad' }).click();
    await expect(page).toHaveURL(/\/coach\/availability$/);
  });
});

// ================================================================= booking

test.describe('booking a class', () => {
  test('books a student in a free slot the server offers, and the selector shows who cannot be chosen and why', async ({ page, request }) => {
    const w = await newWorld(request);
    const csp = await watch(page);
    const ana = await pupil(request, w, 'Ana Gómez');
    const bruno = await pupil(request, w, 'Bruno Díaz', { pay: false });

    await open(page, w, `/coach/agenda?d=${DAY}`);
    await page.getByRole('button', { name: 'Agendar a las 9:00 a. m.' }).click();
    const sheet = page.getByRole('dialog', { name: 'Agendar clase' });
    await expect(sheet.getByRole('option', { name: /Bruno Díaz · sin ciclo activo/ })).toBeDisabled();
    await expect(sheet.getByRole('button', { name: 'Agendar', exact: true })).toBeDisabled();
    await sheet.getByLabel('Alumno').selectOption(ana.id);
    await expect(sheet.getByText('Personalizada · le quedan 8 clases por agendar')).toBeVisible();
    await expect(sheet.getByRole('button', { name: '9:00 a. m.', exact: true })).toHaveAttribute('aria-pressed', 'true');   // the slot that was tapped
    await expect(sheet.getByText('Personalizada · espacio libre a las 9:00 a. m.')).toBeVisible();
    expect(await serious(page)).toEqual([]);
    await sheet.getByRole('button', { name: /^Agendar a Ana Gómez · / }).click();

    await expect(page.getByText(/Clase agendada: Ana Gómez/)).toBeVisible();
    await expect(page.getByRole('list', { name: 'Agenda del día' }).getByText('Ana Gómez')).toBeVisible();
    const placed = await sessionsOf(request, w, ana.id);
    expect(placed).toHaveLength(1);
    expect(placed[0]!.override).toBe(false);
    expect(await sessionsOf(request, w, bruno.id)).toHaveLength(0);
    expect(await violations(page)).toEqual([]);
    expect(csp.consoleErrors.filter((m) => /Content Security Policy|Refused to/i.test(m))).toEqual([]);
  });

  test('as an exception: outside the schedule, with a reason that is required and saved', async ({ page, request }) => {
    const w = await newWorld(request);                                                         // the schedule ends at 22:00
    const ana = await pupil(request, w, 'Ana Gómez');
    await open(page, w, `/coach/agenda?d=${DAY}`);
    await page.getByRole('button', { name: '+ Agendar' }).first().click();
    const sheet = page.getByRole('dialog', { name: 'Agendar clase' });
    await sheet.getByLabel('Alumno').selectOption(ana.id);
    await sheet.getByRole('switch', { name: 'Agendar como excepción' }).click();
    await sheet.getByLabel('Hora (de 15 en 15 min)').fill('23:00');
    await expect(sheet.getByText(/Tu horario del .*5:00 a\. m\. – 10:00 p\. m\./)).toBeVisible();
    const submit = sheet.getByRole('button', { name: /^Agendar como excepción · / });
    await expect(submit).toBeDisabled();                                                       // no reason yet
    await sheet.getByRole('radio', { name: 'Reposición' }).click();
    await expect(sheet.getByLabel('Motivo de la excepción')).toHaveValue('Reposición');
    expect(await serious(page)).toEqual([]);
    await sheet.getByLabel('Motivo de la excepción').fill('Reposición de la clase del jueves');
    await submit.click();

    await expect(page.getByText(/Clase agendada: Ana Gómez/)).toBeVisible();
    const placed = await sessionsOf(request, w, ana.id);
    expect(placed[0]).toMatchObject({ override: true, overrideReason: 'Reposición de la clase del jueves' });
  });

  test('a time that is not on a quarter hour is refused by the server and the message is shown', async ({ page, request }) => {
    const w = await newWorld(request);
    const ana = await pupil(request, w, 'Ana Gómez');
    await open(page, w, `/coach/agenda?d=${DAY}`);
    await page.getByRole('button', { name: '+ Agendar' }).first().click();
    const sheet = page.getByRole('dialog', { name: 'Agendar clase' });
    await sheet.getByLabel('Alumno').selectOption(ana.id);
    await sheet.getByRole('switch', { name: 'Agendar como excepción' }).click();
    await sheet.getByLabel('Hora (de 15 en 15 min)').fill('23:10');
    await sheet.getByRole('radio', { name: 'Otro' }).click();
    await sheet.getByLabel('Motivo de la excepción').fill('Prueba');
    await sheet.getByRole('button', { name: /^Agendar como excepción · / }).click();
    await expect(sheet.getByRole('alert')).toContainText('debe empezar en una hora en punto');
    expect(await sessionsOf(request, w, ana.id)).toHaveLength(0);
  });
});

// ================================================================= settings and plans

test.describe('settings and plans', () => {
  test('the hub lists the sections, the future ones are not links, and the account stays reachable', async ({ page, request }) => {
    const w = await newWorld(request);
    await open(page, w, '/coach/settings');
    await expect(page.getByRole('heading', { name: 'Ajustes', level: 1 })).toBeVisible();
    await expect(page.getByRole('link', { name: /^Planes/ })).toContainText('2 planes · 2 activos');
    await expect(page.getByRole('link', { name: /^Disponibilidad/ })).toBeVisible();
    await expect(page.getByRole('link', { name: /^Marca/ })).toHaveCount(0);
    await expect(page.getByText('Pronto')).toHaveCount(2);
    await expect(page.getByRole('link', { name: /^Cuenta/ })).toBeVisible();
    expect(await serious(page)).toEqual([]);
    await page.getByRole('link', { name: /^Planes/ }).click();
    await expect(page.getByRole('link', { name: 'Ajustes', exact: true }).first()).toBeVisible();
  });

  test('creates, edits and deactivates a plan; a deactivated plan is not offered in a payment and the count is the server\'s', async ({ page, request }) => {
    const w = await newWorld(request);
    await pupil(request, w, 'Ana Gómez');                                                      // one current cycle with the personalized plan
    const laura = await pupil(request, w, 'Laura Torres', { pay: false });
    await open(page, w, '/coach/plans');
    const list = page.getByRole('list', { name: 'Planes' });
    await expect(list.getByRole('button', { name: /Personalizado 8/ })).toContainText('1 alumno con este plan');
    await expect(list.getByRole('button', { name: /Semi 8/ })).toContainText('Sin alumnos');

    await page.getByRole('button', { name: '+ Nuevo plan' }).click();
    const sheet = page.getByRole('dialog', { name: 'Nuevo plan' });
    await sheet.getByLabel('Nombre').fill('Plan de prueba');
    await sheet.getByRole('radio', { name: 'Semipersonalizada' }).click();
    await sheet.getByRole('button', { name: 'Una clase más' }).click();
    await sheet.getByLabel('Precio (COP)').fill('180000');
    await expect(sheet.getByLabel('Precio (COP)')).toHaveValue('$ 180.000');
    await sheet.getByLabel('Nombre').fill('Semi 8');                                          // a name that already exists
    await sheet.getByRole('button', { name: 'Crear plan' }).click();
    await expect(sheet.getByText('Ya tienes un plan con ese nombre.')).toBeVisible();
    await sheet.getByLabel('Nombre').fill('Plan de prueba');
    await sheet.getByRole('button', { name: 'Crear plan' }).click();
    await expect(list.getByRole('button', { name: /Plan de prueba/ })).toContainText('9 clases por ciclo');
    await expect(list.getByRole('button', { name: /Plan de prueba/ })).toContainText('$180.000');

    await list.getByRole('button', { name: /Plan de prueba/ }).click();
    const edit = page.getByRole('dialog', { name: 'Editar plan' });
    expect(await serious(page)).toEqual([]);
    await edit.getByRole('switch', { name: 'Plan activo' }).click();
    await edit.getByRole('button', { name: 'Guardar plan' }).click();
    await expect(list.getByRole('button', { name: /Plan de prueba/ })).toContainText('Desactivado');

    await list.getByRole('button', { name: /Personalizado 8/ }).click();
    await expect(page.getByRole('dialog', { name: 'Editar plan' }).getByText(/El alumno con este plan mantiene su ciclo actual tal como se pagó/)).toBeVisible();
    await page.getByRole('dialog', { name: 'Editar plan' }).getByRole('button', { name: 'Cancelar' }).click();

    await page.goto(`/coach/students/${laura.id}`);
    await page.getByRole('button', { name: 'Registrar pago' }).click();
    const pay = page.getByRole('dialog', { name: 'Registrar pago' });
    await expect(pay.getByRole('radio', { name: /Semi 8/ })).toBeVisible();
    await expect(pay.getByRole('radio', { name: /Plan de prueba/ })).toHaveCount(0);
  });
});

// ================================================================= availability and blocks

test.describe('availability', () => {
  test('edits one day of the schedule, rejects overlaps with the server\'s message and never moves a booked class', async ({ page, request }) => {
    const w = await newWorld(request);
    const ana = await pupil(request, w, 'Ana Gómez');
    await book(request, w, ana, 12);
    const dow = isoDow(DAY);
    const names = ['', 'lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado', 'domingo'];

    await open(page, w, '/coach/availability');
    await expect(page.getByRole('list', { name: 'Horario semanal' }).getByRole('button', { name: `Editar ${names[dow]}` })).toContainText('5:00 a. m. – 10:00 p. m.');
    expect(await serious(page)).toEqual([]);
    await page.getByRole('button', { name: `Editar ${names[dow]}` }).click();
    const sheet = page.getByRole('dialog', { name: names[dow]!.charAt(0).toUpperCase() + names[dow]!.slice(1) });
    await expect(sheet.getByText('Las clases ya agendadas no se mueven.')).toBeVisible();
    await sheet.getByLabel('Franja 1, hasta').fill('10:00');
    await sheet.getByRole('button', { name: '+ Agregar franja' }).click();
    await sheet.getByLabel('Franja 2, desde').fill('09:00');
    await sheet.getByLabel('Franja 2, hasta').fill('11:00');
    await sheet.getByRole('button', { name: `Guardar ${names[dow]}` }).click();
    await expect(sheet.getByRole('alert')).toContainText('se solapan');
    await sheet.getByLabel('Franja 2, desde').fill('16:00');
    await sheet.getByLabel('Franja 2, hasta').fill('20:00');
    expect(await serious(page)).toEqual([]);
    await sheet.getByRole('button', { name: `Guardar ${names[dow]}` }).click();

    await expect(page.getByRole('button', { name: `Editar ${names[dow]}` })).toContainText('5:00 – 10:00 a. m. · 4:00 – 8:00 p. m.');
    expect((await sessionsOf(request, w, ana.id))[0]!.status).toBe('SCHEDULED');               // 12:00 is outside the new windows and stays
  });

  test('a block with booked classes shows exactly which will be cancelled, saves them all or nothing, and nobody loses a class', async ({ page, request }) => {
    const w = await newWorld(request);
    const csp = await watch(page);
    const ana = await pupil(request, w, 'Ana Gómez');
    const beto = await pupil(request, w, 'Beto Ruiz', { semi: true });
    const carla = await pupil(request, w, 'Carla León');
    await book(request, w, ana, 10);
    await book(request, w, beto, 11);
    await book(request, w, carla, 15);                                                         // outside the block

    await open(page, w, '/coach/availability');
    await page.getByRole('button', { name: '+ Agregar bloqueo' }).click();
    const sheet = page.getByRole('dialog', { name: 'Agregar bloqueo' });
    await sheet.getByLabel('Fecha').fill(DAY);
    await sheet.getByLabel('Desde').fill('09:00');
    await sheet.getByLabel('Hasta').fill('12:00');
    await sheet.getByLabel('Motivo').fill('Reunión');
    expect(await serious(page)).toEqual([]);
    await sheet.getByRole('button', { name: 'Revisar y guardar' }).click();

    const review = page.getByRole('dialog', { name: 'Se cancelarán 2 clases' });
    await expect(review.getByRole('list', { name: 'Clases que se cancelarán' }).getByRole('listitem')).toHaveCount(2);
    await expect(review.getByText('Ana Gómez')).toBeVisible();
    await expect(review.getByText('Carla León')).toHaveCount(0);
    await expect(review.getByText('La app todavía no avisa a los alumnos')).toBeVisible();
    expect(await serious(page)).toEqual([]);
    expect((await sessionsOf(request, w, ana.id))[0]!.status).toBe('SCHEDULED');               // nothing is saved yet
    await review.getByRole('button', { name: 'Guardar bloqueo y cancelar 2 clases' }).click();

    const saved = page.getByRole('dialog', { name: '2 clases canceladas' });
    await expect(saved.getByRole('list', { name: 'Alumnos afectados' }).getByRole('listitem')).toHaveCount(2);
    await expect(saved.getByText('Recuerda avisar a 2 alumnos')).toBeVisible();
    expect(await serious(page)).toEqual([]);
    await saved.getByRole('button', { name: 'Listo' }).click();
    await expect(page.getByRole('list', { name: 'Bloqueos' }).getByText('Reunión')).toBeVisible();

    for (const s of [ana, beto]) expect((await sessionsOf(request, w, s.id))[0]!.status).toBe('CANCELLED_BY_COACH');
    expect((await sessionsOf(request, w, carla.id))[0]!.status).toBe('SCHEDULED');
    const cycle = await request.get(`${API}/api/coach/students/${ana.id}/cycles/active`, { headers: bearer(w.coach.token) });
    expect(((await cycle.json()) as { classesUsed: number }).classesUsed).toBe(0);

    await page.goto(`/coach/agenda?d=${DAY}`);                                                 // the agenda shows the block and the others' classes
    await expect(page.getByText('Bloqueado · Reunión')).toBeVisible();
    await expect(page.getByRole('list', { name: 'Agenda del día' }).getByText('Carla León')).toBeVisible();
    expect(await violations(page)).toEqual([]);
    expect(csp.consoleErrors.filter((m) => /Content Security Policy|Refused to/i.test(m))).toEqual([]);
  });

  test('if the affected classes changed while reviewing, the new list is shown and it must be confirmed again', async ({ page, request }) => {
    const w = await newWorld(request);
    const ana = await pupil(request, w, 'Ana Gómez');
    const beto = await pupil(request, w, 'Beto Ruiz');
    await book(request, w, ana, 10);

    await open(page, w, '/coach/availability');
    await page.getByRole('button', { name: '+ Agregar bloqueo' }).click();
    const sheet = page.getByRole('dialog', { name: 'Agregar bloqueo' });
    await sheet.getByLabel('Fecha').fill(DAY);
    await sheet.getByLabel('Desde').fill('09:00');
    await sheet.getByLabel('Hasta').fill('12:00');
    await sheet.getByLabel('Motivo').fill('Festivo');
    await sheet.getByRole('button', { name: 'Revisar y guardar' }).click();
    await expect(page.getByRole('dialog', { name: 'Se cancelarán 1 clase' })).toBeVisible();

    await book(request, w, beto, 11);                                                          // a class appears inside the block meanwhile
    await page.getByRole('button', { name: 'Guardar bloqueo y cancelar 1 clase' }).click();

    const again = page.getByRole('dialog', { name: 'Se cancelarán 2 clases' });
    await expect(again.getByText('Las clases afectadas cambiaron mientras revisabas')).toBeVisible();
    await expect(again.getByText('Beto Ruiz')).toBeVisible();
    expect((await sessionsOf(request, w, ana.id))[0]!.status).toBe('SCHEDULED');               // still nothing saved
    await again.getByRole('button', { name: 'Guardar bloqueo y cancelar 2 clases' }).click();
    await expect(page.getByRole('dialog', { name: '2 clases canceladas' })).toBeVisible();
    expect((await sessionsOf(request, w, beto.id))[0]!.status).toBe('CANCELLED_BY_COACH');
  });

  test('a block with no classes inside saves at once; a student left with few places is flagged and the coach may extend the cycle', async ({ page, request }) => {
    const w = await newWorld(request);
    const dow = isoDow(DAY);
    // one single slot a week: the 8 classes of the plan cannot fit before the deadline
    expect((await request.put(`${API}/api/coach/availability`, { headers: bearer(w.coach.token), data: [{ dayOfWeek: dow, start: '06:00', end: '07:00' }] })).status()).toBe(200);
    const ana = await pupil(request, w, 'Ana Gómez');
    await book(request, w, ana, 6);

    await open(page, w, '/coach/availability');
    await page.getByRole('button', { name: '+ Agregar bloqueo' }).click();
    const sheet = page.getByRole('dialog', { name: 'Agregar bloqueo' });
    await sheet.getByLabel('Fecha').fill(addDays(DAY, 1));
    await sheet.getByRole('radio', { name: 'Todo el día' }).click();
    await sheet.getByLabel('Motivo').fill('Festivo');
    await sheet.getByRole('button', { name: 'Revisar y guardar' }).click();
    const none = page.getByRole('dialog', { name: 'Bloqueo guardado' });
    await expect(none).toBeVisible();                                                          // nothing to confirm: saved directly
    await none.getByRole('button', { name: 'Listo' }).click();

    await page.getByRole('button', { name: '+ Agregar bloqueo' }).click();
    const second = page.getByRole('dialog', { name: 'Agregar bloqueo' });
    await second.getByLabel('Fecha').fill(DAY);
    await second.getByRole('radio', { name: 'Todo el día' }).click();
    await second.getByLabel('Motivo').fill('Festivo');
    await second.getByRole('button', { name: 'Revisar y guardar' }).click();
    await page.getByRole('button', { name: 'Guardar bloqueo y cancelar 1 clase' }).click();

    const saved = page.getByRole('dialog', { name: '1 clase cancelada' });
    await expect(saved.getByText(/pocos horarios antes del vencimiento/)).toBeVisible();
    expect(await serious(page)).toEqual([]);
    await saved.getByRole('button', { name: 'Extender ciclo' }).click();
    await expect(page.getByRole('dialog', { name: 'Extender ciclo' })).toBeVisible();
  });
});
