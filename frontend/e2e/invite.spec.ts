import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { PASSWORD, createAdult, createMinor, previewStatus, registerCoach, setBrand, uniqueEmail } from './fixtures/api';
import { expect, test, violations, watch } from './fixtures/page';

const NEW_PASSWORD = 'Clave-nueva-1234';

async function serious(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  return results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`);
}

async function fillPasswords(page: Page, password = NEW_PASSWORD, repeat = password) {
  await page.getByLabel('Contraseña', { exact: true }).fill(password);
  await page.getByLabel('Repite la contraseña').fill(repeat);
}

test('an adult reads the texts, accepts the required one, skips WhatsApp, chooses a password and signs in', async ({ page, request }) => {
  const csp = await watch(page);
  const coach = await registerCoach(request);
  await setBrand(request, coach, 'Laura Fit', '#AA5500');
  const student = await createAdult(request, coach, 'Ana Prueba', uniqueEmail('ana'));

  const previews: string[] = [];
  page.on('request', (r) => {
    if (r.url().includes('/api/invitations/preview')) previews.push(r.url());
  });
  await page.goto(`/invite/${student.inviteToken}`);

  // the token leaves the address bar at once and is not part of any later URL
  await expect(page).toHaveURL(/\/invite$/);
  expect(page.url()).not.toContain(student.inviteToken);

  await expect(page.getByRole('heading', { name: 'Hola, Ana Prueba', level: 1 })).toBeVisible();
  await expect(page.getByText('Laura Fit').first()).toBeVisible();                       // the coach's brand, not a generic one
  expect(await page.evaluate(() => document.documentElement.style.getPropertyValue('--brand'))).toBe('#AA5500');
  await expect(page.getByLabel('Correo de acceso')).toHaveValue(student.email);
  await expect(page.getByText('BORRADOR · sin validez legal')).toBeVisible();            // the texts are drafts outside production
  await expect(page.getByRole('region', { name: /^Texto: / })).toHaveCount(2);           // data authorization + WhatsApp, apart
  await expect(page.getByLabel(/Acepto el tratamiento de mis datos personales/)).not.toBeChecked();
  await expect(page.getByLabel(/Acepto recibir avisos por WhatsApp/)).not.toBeChecked();
  expect(await serious(page)).toEqual([]);

  // one preview call, and coming back to the window does not make another
  await page.evaluate(() => {
    // hide the tab and show it again, the way coming back to the browser does
    const set = (state: string) => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
      document.dispatchEvent(new Event('visibilitychange', { bubbles: true }));
    };
    set('hidden');
    set('visible');
    window.dispatchEvent(new Event('online'));
  });
  await page.waitForTimeout(500);
  expect(previews).toHaveLength(1);

  // the hint is only a hint; two different passwords never leave the page
  await expect(page.getByText('Mínimo 10 caracteres.')).toBeVisible();
  await fillPasswords(page, NEW_PASSWORD, 'otra-distinta-123');
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click();
  await expect(page.getByText('Las contraseñas no coinciden.')).toBeVisible();

  // not accepting the required text: the server says so and the invitation stays usable
  await fillPasswords(page);
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click();
  await expect(page.getByRole('alert').getByText('Debes aceptar la autorización de tratamiento de datos para crear la cuenta.')).toBeVisible();
  expect(await previewStatus(request, student.inviteToken)).toBe(200);

  // a password the server refuses (under 10) does not burn it either
  await page.getByLabel(/Acepto el tratamiento de mis datos personales/).check();
  await fillPasswords(page, 'corta');
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  expect(await previewStatus(request, student.inviteToken)).toBe(200);

  await fillPasswords(page);
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click();
  await expect(page.getByRole('heading', { name: 'Cuenta creada' })).toBeVisible();
  expect(await previewStatus(request, student.inviteToken)).toBe(400);                   // single use

  await page.getByRole('link', { name: 'Ir a iniciar sesión' }).click();
  await page.getByLabel('Correo').fill(student.email);
  await page.getByLabel('Contraseña').fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).toHaveURL(/\/app$/);

  expect(await violations(page)).toEqual([]);
  expect(csp.consoleErrors.filter((m) => /Content Security Policy|Refused to/i.test(m))).toEqual([]);
});

test('a guardian accepts as legal representative, with the names of both, and also takes WhatsApp', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await setBrand(request, coach, 'Marca Clara', '#FFD60A');                              // bright: the text on it must stay readable
  const guardianEmail = uniqueEmail('madre');
  const minor = await createMinor(request, coach, 'Mateo Prueba', guardianEmail, 'Marta Pérez');

  await page.goto(`/invite/${minor.inviteToken}`);
  await expect(page.getByRole('heading', { name: 'Autorización del representante legal', level: 1 })).toBeVisible();
  const notice = page.getByText(/vas a aceptar como representante legal de/);
  await expect(notice).toContainText('Marta Pérez');
  await expect(notice).toContainText('Mateo Prueba');
  await expect(page.getByLabel('Correo de acceso')).toHaveValue(guardianEmail);          // the guardian's login, not the minor's
  await expect(page.getByLabel(/Acepto, como representante legal de Mateo Prueba/)).toBeVisible();
  await expect(page.getByText('BORRADOR · sin validez legal')).toBeVisible();
  expect(await serious(page)).toEqual([]);

  await page.getByLabel(/Acepto, como representante legal/).check();
  await page.getByLabel(/Acepto recibir avisos por WhatsApp/).check();
  await fillPasswords(page);
  await page.getByRole('button', { name: 'Aceptar y crear la cuenta' }).click();
  await expect(page.getByRole('heading', { name: 'Cuenta creada' })).toBeVisible();
  await expect(page.getByText(guardianEmail)).toBeVisible();

  await page.getByRole('link', { name: 'Ir a iniciar sesión' }).click();
  await page.getByLabel('Correo').fill(guardianEmail);
  await page.getByLabel('Contraseña').fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).toHaveURL(/\/app$/);
});

test.describe('errors that do not consume the invitation', () => {
  test('an email that already has an account says so, and the invitation is still usable', async ({ page, request }) => {
    const email = uniqueEmail('compartido');
    const coachA = await registerCoach(request);
    const first = await createAdult(request, coachA, 'Primero', email);
    const coachB = await registerCoach(request);
    const second = await createAdult(request, coachB, 'Segundo', email);                 // the coach is not told the email exists elsewhere

    await page.goto(`/invite/${first.inviteToken}`);
    await page.getByLabel(/Acepto el tratamiento de mis datos personales/).check();
    await fillPasswords(page);
    await page.getByRole('button', { name: 'Crear mi cuenta' }).click();
    await expect(page.getByRole('heading', { name: 'Cuenta creada' })).toBeVisible();

    await page.goto(`/invite/${second.inviteToken}`);
    await page.getByLabel(/Acepto el tratamiento de mis datos personales/).check();
    await fillPasswords(page);
    await page.getByRole('button', { name: 'Crear mi cuenta' }).click();
    const alert = page.getByRole('alert');
    await expect(alert).toContainText('Ese correo ya tiene una cuenta en la plataforma.');
    expect(await serious(page)).toEqual([]);
    expect(await previewStatus(request, second.inviteToken)).toBe(200);
  });

  test('a guardian email already in use asks for a different email per student', async ({ page, request }) => {
    const guardianEmail = uniqueEmail('madre');
    const coachA = await registerCoach(request);
    const first = await createMinor(request, coachA, 'Hijo Uno', guardianEmail);
    const coachB = await registerCoach(request);
    const second = await createMinor(request, coachB, 'Hijo Dos', guardianEmail);

    await page.goto(`/invite/${first.inviteToken}`);
    await page.getByLabel(/Acepto, como representante legal/).check();
    await fillPasswords(page);
    await page.getByRole('button', { name: 'Aceptar y crear la cuenta' }).click();
    await expect(page.getByRole('heading', { name: 'Cuenta creada' })).toBeVisible();

    await page.goto(`/invite/${second.inviteToken}`);
    await page.getByLabel(/Acepto, como representante legal/).check();
    await fillPasswords(page);
    await page.getByRole('button', { name: 'Aceptar y crear la cuenta' }).click();
    await expect(page.getByRole('alert')).toContainText('Este correo ya tiene una cuenta. Pide a tu entrenador que registre un correo distinto para cada alumno.');
    expect(await serious(page)).toEqual([]);
    expect(await previewStatus(request, second.inviteToken)).toBe(200);
  });

  test('an unknown, used or expired token gives one answer, and a missing token says the link is not available', async ({ page, request }) => {
    await page.goto('/invite/este-token-no-existe');
    await expect(page.getByRole('heading', { name: 'Invitación no válida' })).toBeVisible();
    await expect(page.getByRole('alert')).toContainText('La invitación no es válida o ya venció. Pide al entrenador un enlace nuevo.');
    expect(await serious(page)).toEqual([]);

    // a token that was used
    const coach = await registerCoach(request);
    const student = await createAdult(request, coach, 'Usada', uniqueEmail('usada'));
    await page.goto(`/invite/${student.inviteToken}`);
    await page.getByLabel(/Acepto el tratamiento de mis datos personales/).check();
    await fillPasswords(page);
    await page.getByRole('button', { name: 'Crear mi cuenta' }).click();
    await expect(page.getByRole('heading', { name: 'Cuenta creada' })).toBeVisible();
    await page.goto(`/invite/${student.inviteToken}`);
    await expect(page.getByRole('alert')).toContainText('La invitación no es válida o ya venció. Pide al entrenador un enlace nuevo.');

    await page.goto('/login');                                                            // (/invite from /invite would be a reload, which keeps the token)
    await page.goto('/invite');
    await expect(page.getByRole('heading', { name: 'Enlace no disponible' })).toBeVisible();
  });

  test('a network failure on the preview offers a retry (the only way to call it again)', async ({ page, request }) => {
    const coach = await registerCoach(request);
    const student = await createAdult(request, coach, 'Ana Red', uniqueEmail('red'));
    await page.route('**/api/invitations/preview', (route) => route.abort());
    await page.goto(`/invite/${student.inviteToken}`);
    await expect(page.getByRole('heading', { name: 'No pudimos cargar la invitación' })).toBeVisible();
    await page.unroute('**/api/invitations/preview');
    await page.getByRole('button', { name: 'Reintentar' }).click();
    await expect(page.getByRole('heading', { name: 'Hola, Ana Red' })).toBeVisible();
  });
});

test('the password and the token never reach the page URL or the console', async ({ page, request }) => {
  const coach = await registerCoach(request);
  const student = await createAdult(request, coach, 'Ana Log', uniqueEmail('log'));
  const logs: string[] = [];
  page.on('console', (m) => logs.push(m.text()));
  const urls: string[] = [];
  page.on('request', (r) => urls.push(r.url()));
  await page.goto(`/invite/${student.inviteToken}`);
  await page.getByLabel(/Acepto el tratamiento de mis datos personales/).check();
  await fillPasswords(page, PASSWORD);
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click();
  await expect(page.getByRole('heading', { name: 'Cuenta creada' })).toBeVisible();
  // the only request that carries the token in its URL is the first document load of the link itself
  expect(urls.filter((u) => u.includes(student.inviteToken)).length).toBeLessThanOrEqual(1);
  expect(logs.join('\n')).not.toContain(student.inviteToken);
  expect(logs.join('\n')).not.toContain(PASSWORD);
});
