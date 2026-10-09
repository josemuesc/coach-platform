import { expect, test } from './fixtures/page';
import { API, PASSWORD, bearer, login, registerCoach, setBrand, studentWithAccount, uniqueEmail } from './fixtures/api';
import type { Page } from '@playwright/test';

async function signIn(page: Page, email: string, password = PASSWORD) {
  await page.goto('/login');
  await page.getByLabel('Correo').fill(email);
  await page.getByLabel('Contraseña').fill(password);
  await page.getByRole('button', { name: 'Entrar' }).click();
}

test('a coach signs in, lands in the coach area with the brand, and is kept out of the student area', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await setBrand(request, coach, 'Laura Fit', '#AA5500');

  await signIn(page, coach.email);
  await expect(page).toHaveURL(/\/coach$/);
  await expect(page.getByText('Laura Fit')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Secciones del entrenador' })).toBeVisible();

  // the brand reaches the CSS variables, and the text on it stays readable
  const brand = await page.evaluate(() => ({
    color: document.documentElement.style.getPropertyValue('--brand'),
    contrast: document.documentElement.style.getPropertyValue('--brand-contrast'),
  }));
  expect(brand).toEqual({ color: '#AA5500', contrast: '#FFFFFF' });

  await page.goto('/app');
  await expect(page).toHaveURL(/\/coach$/); // wrong area: sent back to its own
});

test('a student signs in to the student area and sees the brand of THEIR coach', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await setBrand(request, coach, 'Marca Alumno', '#112233');
  const student = await studentWithAccount(request, coach);

  await signIn(page, student.email);
  await expect(page).toHaveURL(/\/app$/);
  await expect(page.getByText('Marca Alumno')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.style.getPropertyValue('--brand'))).toBe('#112233');

  await page.goto('/coach');
  await expect(page).toHaveURL(/\/app$/);
});

test('without a session the areas send you to the login and back afterwards', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await page.goto('/coach/students');
  await expect(page).toHaveURL(/\/login\?redirect=%2Fcoach%2Fstudents/);
  await page.getByLabel('Correo').fill(coach.email);
  await page.getByLabel('Contraseña').fill(PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).toHaveURL(/\/coach\/students$/);
});

test('an outside address cannot be used as the redirect after login', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await page.goto('/login?redirect=https%3A%2F%2Fevil.example.com%2Fx');
  await page.getByLabel('Correo').fill(coach.email);
  await page.getByLabel('Contraseña').fill(PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).toHaveURL(/\/coach$/);
});

test('a wrong password shows the catalog message, unknown emails look the same, and the form stays usable', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await signIn(page, coach.email, 'clave-equivocada-1');
  const wrong = await page.getByRole('alert').innerText();
  expect(wrong).toBe('Correo o contraseña incorrectos.');

  await signIn(page, uniqueEmail('fantasma'), 'clave-equivocada-1');
  expect(await page.getByRole('alert').innerText()).toBe(wrong); // an unknown email says exactly the same
  await expect(page).toHaveURL(/\/login/);
});

test('repeated failures are throttled and the person is told how long to wait', async ({ page, request }) => {
  const coach = await registerCoach(request);
  for (let i = 0; i < 5; i++) {
    await request.post(`${API}/api/auth/login`, { data: { email: coach.email, password: `mala-${i}-clave` } });
  }
  await signIn(page, coach.email);
  await expect(page.getByRole('alert')).toContainText('Demasiados intentos');
  await expect(page.getByRole('alert')).toContainText(/minutos?/);
});

test('logging out clears the session and the areas close again', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await signIn(page, coach.email);
  await page.getByRole('link', { name: 'Ajustes' }).click();
  await page.getByRole('button', { name: 'Cerrar sesión' }).click();
  await expect(page).toHaveURL(/\/login/);
  expect(await page.evaluate(() => window.localStorage.getItem('cp.token'))).toBeNull();
  await page.goto('/coach');
  await expect(page).toHaveURL(/\/login/);
});

test('a session ended by a password change elsewhere says so on the login page', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await signIn(page, coach.email);
  await expect(page).toHaveURL(/\/coach$/);

  // somebody changes the password through the API (another device)
  const change = await request.post(`${API}/api/auth/change-password`, {
    headers: bearer(coach.token),
    data: { currentPassword: PASSWORD, newPassword: 'Nueva-clave-segura-9' },
  });
  expect(change.status()).toBe(200);

  await page.reload();
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByRole('status')).toContainText('Tu sesión terminó porque la contraseña cambió');
});

test('changing the password in the app keeps this session on a NEW token and ends the old ones', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await signIn(page, coach.email);
  const otherDevice = await login(request, coach.email);
  const before = await page.evaluate(() => window.localStorage.getItem('cp.token'));

  await page.getByRole('link', { name: 'Ajustes' }).click();
  await page.getByLabel('Contraseña actual').fill(PASSWORD);
  await page.getByLabel('Contraseña nueva', { exact: true }).fill('Nueva-clave-segura-9');
  await page.getByLabel('Repite la contraseña nueva').fill('Nueva-clave-segura-9');
  await page.getByRole('button', { name: 'Cambiar contraseña' }).click();
  await expect(page.getByText('Contraseña cambiada. Tus otras sesiones se cerraron.')).toBeVisible();

  const after = await page.evaluate(() => window.localStorage.getItem('cp.token'));
  expect(after).not.toBe(before);
  await page.reload();
  await expect(page).toHaveURL(/\/coach\/settings$/); // still signed in
  expect((await request.get(`${API}/api/me`, { headers: bearer(otherDevice) })).status()).toBe(401);
  await login(request, coach.email, 'Nueva-clave-segura-9');
});

test('a wrong current password on the change form is an input error and does NOT sign you out', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await signIn(page, coach.email);
  await page.getByRole('link', { name: 'Ajustes' }).click();
  await page.getByLabel('Contraseña actual').fill('no-es-la-clave');
  await page.getByLabel('Contraseña nueva', { exact: true }).fill('Nueva-clave-segura-9');
  await page.getByLabel('Repite la contraseña nueva').fill('Nueva-clave-segura-9');
  await page.getByRole('button', { name: 'Cambiar contraseña' }).click();
  await expect(page.getByRole('alert')).toHaveText('Correo o contraseña incorrectos.');
  await expect(page).toHaveURL(/\/coach\/settings$/);
});

test('a too-short new password is refused by the server and the message comes from the catalog', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await signIn(page, coach.email);
  await page.getByRole('link', { name: 'Ajustes' }).click();
  await page.getByLabel('Contraseña actual').fill(PASSWORD);
  await page.getByLabel('Contraseña nueva', { exact: true }).fill('corta');
  await page.getByLabel('Repite la contraseña nueva').fill('corta');
  await page.getByRole('button', { name: 'Cambiar contraseña' }).click();
  await expect(page.getByRole('alert')).toContainText('Revisa los datos enviados');
});
