import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { dateTrigger, expect, pickDate, test } from './fixtures/page';
import { newWorld } from './fixtures/world';

const addDays = (day: string, n: number) => new Date(Date.parse(`${day}T12:00:00Z`) + n * 86_400_000).toISOString().slice(0, 10);
const todayBogota = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Bogota' }).format(new Date());

async function openNewStudent(page: Page, request: Parameters<typeof newWorld>[0]) {
  const w = await newWorld(request);
  await page.addInitScript((token) => window.localStorage.setItem('cp.token', token), w.coach.token);
  await page.goto('/coach/students/new');
}

test('our calendar replaces the browser date input: it picks a date far in the past, shows it in words and has no serious accessibility problems', async ({ page, request }) => {
  await openNewStudent(page, request);
  expect(await page.locator('input[type="date"]').count()).toBe(0);
  await expect(dateTrigger(page, 'Fecha de nacimiento')).toContainText('Elige una fecha');
  await pickDate(page, page, 'Fecha de nacimiento', '1990-05-01');
  await expect(dateTrigger(page, 'Fecha de nacimiento')).toContainText('Mar 1 may 1990');

  await dateTrigger(page, 'Fecha de nacimiento').click();
  const calendar = page.getByRole('dialog', { name: 'Fecha de nacimiento' });
  await expect(calendar.getByRole('button', { name: /mayo de 1990/ })).toBeVisible();          // it opens on the chosen month
  await expect(calendar.locator('[data-day="1990-05-01"]')).toHaveAttribute('aria-pressed', 'true');
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => v.id)).toEqual([]);
  await page.keyboard.press('Escape');
  await expect(calendar).toBeHidden();
  await expect(dateTrigger(page, 'Fecha de nacimiento')).toBeFocused();                       // the focus goes back to the field
});

test('the bounds gray days out, "Hoy" picks today and the arrow keys move between days', async ({ page, request }) => {
  await openNewStudent(page, request);                                                         // a birth date cannot be in the future
  const today = todayBogota();
  await dateTrigger(page, 'Fecha de nacimiento').click();
  const calendar = page.getByRole('dialog', { name: 'Fecha de nacimiento' });
  await expect(calendar.getByRole('button', { name: 'Mes siguiente' })).toBeDisabled();       // nothing after the allowed maximum
  await expect(calendar.locator(`[data-day="${today}"]`)).toBeEnabled();

  await calendar.locator(`[data-day="${today}"]`).focus();
  await page.keyboard.press('ArrowLeft');
  await expect(calendar.locator(`[data-day="${addDays(today, -1)}"]`)).toBeFocused();
  await page.keyboard.press('ArrowUp');
  await expect(calendar.locator(`[data-day="${addDays(today, -8)}"]`)).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(calendar).toBeHidden();
  await expect(dateTrigger(page, 'Fecha de nacimiento')).not.toContainText('Elige una fecha');

  await dateTrigger(page, 'Fecha de nacimiento').click();
  await calendar.getByRole('button', { name: 'Hoy' }).click();
  await expect(calendar).toBeHidden();
  await dateTrigger(page, 'Fecha de nacimiento').click();
  await expect(calendar.locator(`[data-day="${today}"]`)).toHaveAttribute('aria-pressed', 'true');
});
