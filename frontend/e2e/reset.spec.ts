import { expect, test } from './fixtures/page';
import { PASSWORD, issueResetLink, registerCoach, studentWithAccount } from './fixtures/api';

const NEW_PASSWORD = 'Otra-clave-segura-9';

test('the coach hands over a link, the student chooses a password, and is told it was reset until they change it', async ({ page, request }) => {
  const coach = await registerCoach(request);
  const student = await studentWithAccount(request, coach);
  const token = await issueResetLink(request, coach, student);

  await page.goto(`/reset/${token}`);
  // the token leaves the address bar at once
  await expect(page).toHaveURL(/\/reset$/);
  expect(page.url()).not.toContain(token);

  await page.getByLabel('Contraseña nueva').fill(NEW_PASSWORD);
  await page.getByLabel('Repite la contraseña').fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Guardar contraseña' }).click();
  await expect(page.getByText('Ya puedes iniciar sesión con tu contraseña nueva.')).toBeVisible();

  // the link worked once
  await page.goto(`/reset/${token}`);
  await page.getByLabel('Contraseña nueva').fill('Tercera-clave-123');
  await page.getByLabel('Repite la contraseña').fill('Tercera-clave-123');
  await page.getByRole('button', { name: 'Guardar contraseña' }).click();
  await expect(page.getByRole('alert')).toContainText('El enlace para cambiar la contraseña no es válido');

  // the old password is gone, the new one works
  await page.goto('/login');
  await page.getByLabel('Correo').fill(student.email);
  await page.getByLabel('Contraseña').fill(PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('alert')).toHaveText('Correo o contraseña incorrectos.');
  await page.getByLabel('Contraseña').fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).toHaveURL(/\/app$/);

  // the server says the last change came from the coach's link: the student is warned
  const notice = page.getByRole('status').filter({ hasText: 'se restableció con un enlace de tu entrenador' });
  await expect(notice).toBeVisible();

  // changing it themselves removes the notice
  await page.getByRole('link', { name: 'Cuenta' }).first().click();
  await page.getByLabel('Contraseña actual').fill(NEW_PASSWORD);
  await page.getByLabel('Contraseña nueva', { exact: true }).fill('Mi-propia-clave-77');
  await page.getByLabel('Repite la contraseña nueva').fill('Mi-propia-clave-77');
  await page.getByRole('button', { name: 'Cambiar contraseña' }).click();
  await expect(page.getByText('Contraseña cambiada.')).toBeVisible();
  await expect(notice).toHaveCount(0);
});

test('the link survives a reload (the token is kept in the history entry, never in the address)', async ({ page, request }) => {
  const coach = await registerCoach(request);
  const student = await studentWithAccount(request, coach);
  const token = await issueResetLink(request, coach, student);

  await page.goto(`/reset/${token}`);
  await expect(page).toHaveURL(/\/reset$/);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Elige tu contraseña' })).toBeVisible();
});

test('opening /reset with no link says to ask for another', async ({ page }) => {
  await page.goto('/reset');
  await expect(page.getByRole('heading', { name: 'Enlace no disponible' })).toBeVisible();
});

test('the two password fields must agree (a convenience only: the server still decides what is valid)', async ({ page, request }) => {
  const coach = await registerCoach(request);
  const student = await studentWithAccount(request, coach);
  await page.goto(`/reset/${await issueResetLink(request, coach, student)}`);
  await page.getByLabel('Contraseña nueva').fill(NEW_PASSWORD);
  await page.getByLabel('Repite la contraseña').fill('otra-distinta-123');
  await page.getByRole('button', { name: 'Guardar contraseña' }).click();
  await expect(page.getByText('Las contraseñas no coinciden.')).toBeVisible();
});
