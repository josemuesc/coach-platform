import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InvitePage } from './InvitePage';

function preview(version: string) {
  return {
    brandName: 'Laura Fit', primaryColor: null, studentName: 'Ana Prueba', accountEmail: 'ana@example.test', audience: 'ADULT', guardianName: null,
    consents: [
      { type: 'DATA_ADULT', version, title: 'Autorización de datos', bodyMarkdown: 'Texto', required: true, draft: true },
      { type: 'WHATSAPP', version: 'w1', title: 'Avisos por WhatsApp', bodyMarkdown: 'Texto', required: false, draft: true },
    ],
  };
}

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('InvitePage when the consent text changed under the person', () => {
  let accepts: { dataVersion?: string }[];
  let previews: number;

  beforeEach(() => {
    accepts = [];
    previews = 0;
    // the first preview offers v1; by the time the person accepts, the server is on v2 (and offers v2 on the second preview)
    vi.stubGlobal(
      'fetch',
      vi.fn(async (request: Request) => {
        const path = new URL(request.url).pathname;
        if (path === '/api/invitations/preview') {
          previews += 1;
          return json(200, preview(previews === 1 ? 'v1' : 'v2'));
        }
        const body = (await request.clone().json()) as { dataVersion?: string };
        accepts.push(body);
        return body.dataVersion === 'v2' ? json(200, { email: 'ana@example.test' }) : json(409, { code: 'CONSENT_VERSION_MISMATCH' });
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  function open() {
    const router = createMemoryRouter([{ path: '/invite/:token', element: <InvitePage /> }], { initialEntries: ['/invite/tok-abc'] });
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );
  }

  async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByLabelText(/Acepto el tratamiento de mis datos personales/));
    await user.type(screen.getByLabelText('Contraseña', { exact: true }), 'Clave-nueva-1234');
    await user.type(screen.getByLabelText('Repite la contraseña'), 'Clave-nueva-1234');
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));
  }

  it('explains it, offers to reload the texts, and never resends the old version by itself', async () => {
    const user = userEvent.setup();
    open();
    await fillAndSubmit(user);

    expect(await screen.findByText(/El texto de la autorización cambió/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Volver a cargar los textos' })).toBeInTheDocument();
    expect(accepts).toHaveLength(1);
    expect(accepts[0]?.dataVersion).toBe('v1');
    // the submit is blocked until the texts are reloaded: the old version cannot be sent again
    expect(screen.getByRole('button', { name: 'Crear mi cuenta' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));
    expect(accepts).toHaveLength(1);
  });

  it('after reloading, the person accepts the NEW text again and only then the new version is sent', async () => {
    const user = userEvent.setup();
    open();
    await fillAndSubmit(user);
    await user.click(await screen.findByRole('button', { name: 'Volver a cargar los textos' }));

    // a fresh form: the acceptance of the old text does not carry over
    await waitFor(() => expect(previews).toBe(2));
    const consent = await screen.findByLabelText(/Acepto el tratamiento de mis datos personales/);
    await waitFor(() => expect(consent).not.toBeChecked());
    expect(screen.queryByText(/El texto de la autorización cambió/)).toBeNull();
    expect(accepts).toHaveLength(1);

    await fillAndSubmit(user);
    expect(await screen.findByRole('heading', { name: 'Cuenta creada' })).toBeInTheDocument();
    expect(accepts.map((a) => a.dataVersion)).toEqual(['v1', 'v2']);
  });
});
