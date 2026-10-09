import { test as base, expect, type Page } from '@playwright/test';

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
