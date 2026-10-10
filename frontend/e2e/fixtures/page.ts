import { test as base, expect, type Locator, type Page } from '@playwright/test';

/** Everything the browser reports about the CSP, and every origin the page talks to. */
export interface Watch {
  violations: string[];
  origins: Set<string>;
  consoleErrors: string[];
}

export async function watch(page: Page): Promise<Watch> {
  const w: Watch = { violations: [], origins: new Set(), consoleErrors: [] };
  await page.addInitScript(() => {
    (window as unknown as { __csp: string[] }).__csp = [];
    document.addEventListener('securitypolicyviolation', (e) => {
      (window as unknown as { __csp: string[] }).__csp.push(`${e.violatedDirective} ${e.blockedURI}`);
    });
  });
  page.on('request', (r) => w.origins.add(new URL(r.url()).origin));
  page.on('console', (m) => {
    if (m.type() === 'error') w.consoleErrors.push(m.text());
  });
  return w;
}

export async function violations(page: Page): Promise<string[]> {
  return page.evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? []);
}

export const test = base;
export { expect };

const MONTH_NAMES = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];

/** The trigger of a date field: its name is the label followed by the chosen date or the placeholder. */
export function dateTrigger(scope: Page | Locator, label: string) {
  return scope.getByRole('button', { name: new RegExp(`^${label} (Elige una fecha|\\S+ \\d+ \\S+ \\d{4})$`) });
}

/** Inside an open calendar: goes to the month of `day` (year, then month) so its days can be seen. */
export async function goToMonth(calendar: Locator, day: string): Promise<void> {
  const [year, month] = day.split('-').map(Number) as [number, number];
  await calendar.getByRole('button', { name: /cambiar mes o año/ }).click();
  await calendar.getByRole('group', { name: 'Año' }).getByRole('button', { name: String(year), exact: true }).click();
  await calendar.getByRole('button', { name: `${MONTH_NAMES[month - 1]} de ${year}`, exact: true }).click();
}

/** Opens the calendar of a date field and picks a day the way a person does: year, month, day. */
export async function pickDate(page: Page, scope: Page | Locator, label: string, day: string): Promise<void> {
  await dateTrigger(scope, label).click();
  const calendar = page.getByRole('dialog', { name: label });
  await goToMonth(calendar, day);
  await calendar.locator(`[data-day="${day}"]`).click();
  await expect(calendar).toBeHidden();
}
