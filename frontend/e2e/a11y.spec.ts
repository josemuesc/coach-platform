import AxeBuilder from '@axe-core/playwright';
import { expect, test } from './fixtures/page';
import { PASSWORD, registerCoach, setBrand, studentWithAccount } from './fixtures/api';

async function serious(page: import('@playwright/test').Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  return results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`);
}

test('the login page has no serious accessibility problems', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Inicia sesión' })).toBeVisible();
  expect(await serious(page)).toEqual([]);
});

test('the coach and student areas have none, also with a bright brand color', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await setBrand(request, coach, 'Marca Clara', '#FFD60A'); // yellow: the text on it must flip to the dark ink
  const student = await studentWithAccount(request, coach);

  await page.goto('/login');
  await page.getByLabel('Correo').fill(coach.email);
  await page.getByLabel('Contraseña').fill(PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).toHaveURL(/\/coach$/);
  expect(await serious(page)).toEqual([]);
  await page.getByRole('link', { name: 'Ajustes' }).click();
  await expect(page.getByRole('heading', { name: 'Cuenta' })).toBeVisible();
  expect(await serious(page)).toEqual([]);

  await page.getByRole('button', { name: 'Cerrar sesión' }).click();
  await page.getByLabel('Correo').fill(student.email);
  await page.getByLabel('Contraseña').fill(PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).toHaveURL(/\/app$/);
  expect(await serious(page)).toEqual([]);
});
