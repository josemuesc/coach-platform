import { expect, test } from './fixtures/page';
import { PASSWORD, registerCoach } from './fixtures/api';

test('the manifest and the shell are installable, with the icons', async ({ page, request }) => {
  await page.goto('/login');
  const href = await page.locator('link[rel="manifest"]').getAttribute('href');
  const manifest = (await (await request.get(href!)).json()) as { display: string; icons: { src: string; purpose?: string }[]; start_url: string };
  expect(manifest.display).toBe('standalone');
  expect(manifest.start_url).toBe('/');
  expect(manifest.icons.map((i) => i.purpose ?? 'any').sort()).toEqual(['any', 'any', 'maskable']);
  for (const icon of manifest.icons) expect((await request.get(icon.src)).status()).toBe(200);
});

test('the service worker caches the shell and NEVER anything the API returned', async ({ page, request }) => {
  const coach = await registerCoach(request);
  await page.goto('/login');
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.getByLabel('Correo').fill(coach.email);
  await page.getByLabel('Contraseña').fill(PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page).toHaveURL(/\/coach$/);
  await page.reload(); // controlled by the worker now
  await expect(page.getByRole('navigation', { name: 'Secciones del entrenador' })).toBeVisible();

  const cached = await page.evaluate(async () => {
    const urls: string[] = [];
    for (const name of await caches.keys()) {
      const cache = await caches.open(name);
      for (const req of await cache.keys()) urls.push(req.url);
    }
    return urls;
  });
  expect(cached.length).toBeGreaterThan(0); // the shell is there...
  expect(cached.some((u) => u.endsWith('/index.html') || u.endsWith('.js'))).toBe(true);
  expect(cached.filter((u) => /\/api\/|:8081|\/v3\//.test(u))).toEqual([]); // ...and no API response is
});

test('offline the shell still opens, says there is no connection, and shows no stale personal data', async ({ page, context, request }) => {
  const coach = await registerCoach(request);
  await page.goto('/login');
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.reload();
  await context.setOffline(true);
  await expect(page.getByText('Sin conexión: necesitas internet para ver y cambiar tus datos.')).toBeVisible();

  // a login attempt fails with the network message instead of anything cached
  await page.getByLabel('Correo').fill(coach.email);
  await page.getByLabel('Contraseña').fill(PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('alert')).toContainText('No hay conexión con el servidor');
  await context.setOffline(false);
});
