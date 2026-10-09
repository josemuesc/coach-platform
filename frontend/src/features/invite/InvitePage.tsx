import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router';
import { api, call } from '../../api/client';
import { isApiError, messageFor } from '../../api/errors';
import type { components } from '../../api/schema';
import { applyBrand } from '../../brand/applyBrand';
import { Banner } from '../../ui/Banner';
import { BrandMark } from '../../ui/BrandMark';
import { Button } from '../../ui/Button';
import { Checkbox } from '../../ui/Checkbox';
import { Field } from '../../ui/Field';
import { Skeleton } from '../../ui/States';
import { ConsentText } from './ConsentText';

type Preview = components['schemas']['InvitationPreview'];

interface Values {
  acceptData: boolean;
  acceptWhatsapp: boolean;
  password: string;
  confirm: string;
}

/**
 * The link the coach hands over is /invite/<token>. The token is moved out of the address bar at once (the history state survives a
 * reload; it is never in a URL, a referrer or a log) and the page continues at /invite. It is only ever held in memory.
 */
function takeToken(fromPath: string | undefined): string | null {
  const stored = (window.history.state as { inviteToken?: string } | null)?.inviteToken ?? null;
  const token = fromPath ?? stored;
  if (fromPath) window.history.replaceState({ inviteToken: fromPath }, '', '/invite');
  return token;
}

function Shell({ children, brandName }: { children: React.ReactNode; brandName?: string }) {
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col gap-6 p-6">
      <div className="flex items-center gap-3">
        <BrandMark name={brandName ?? 'Coach'} />
        <span className="font-display text-xl font-bold">{brandName ?? 'Coach'}</span>
      </div>
      {children}
    </main>
  );
}

export function InvitePage() {
  const params = useParams();
  const queryClient = useQueryClient();
  const [token] = useState(() => takeToken(params.token));

  // ONE preview call: nothing refetches it when the window regains focus or the network returns (every call counts toward the rate limit)
  const preview = useQuery({
    queryKey: ['invite-preview'],
    queryFn: () => call<Preview>(api.POST('/api/invitations/preview', { body: { token: token! } })),
    enabled: token !== null,
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchOnMount: false,
  });

  useEffect(() => {
    if (preview.data) applyBrand(preview.data.primaryColor);
  }, [preview.data]);
  useEffect(
    () => () => {
      applyBrand(null);
      queryClient.removeQueries({ queryKey: ['invite-preview'] });
    },
    [queryClient],
  );

  if (token === null) {
    return (
      <Shell>
        <h1 className="font-display text-3xl font-bold">Enlace no disponible</h1>
        <Banner tone="amber" role="alert">Abre otra vez el enlace que te dio tu entrenador, o pídele uno nuevo.</Banner>
      </Shell>
    );
  }
  if (preview.isPending) {
    return (
      <Shell>
        <Skeleton label="Cargando la invitación" />
      </Shell>
    );
  }
  if (preview.isError) {
    const final = isApiError(preview.error) && preview.error.is('INVALID_INVITATION');
    return (
      <Shell>
        <h1 className="font-display text-3xl font-bold">{final ? 'Invitación no válida' : 'No pudimos cargar la invitación'}</h1>
        <Banner tone={final ? 'amber' : 'red'} role="alert">{messageFor(preview.error)}</Banner>
        {!final && <Button onClick={() => void preview.refetch()}>Reintentar</Button>}
      </Shell>
    );
  }
  // key: a reload of the texts starts the form afresh (the person must read and accept the NEW text, not carry the old acceptance over)
  return <InviteForm key={preview.dataUpdatedAt} token={token} data={preview.data} onReload={() => void preview.refetch()} reloading={preview.isFetching} />;
}

function InviteForm({ token, data, onReload, reloading }: { token: string; data: Preview; onReload: () => void; reloading: boolean }) {
  const [done, setDone] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const { register, handleSubmit, getValues, formState } = useForm<Values>({ defaultValues: { acceptData: false, acceptWhatsapp: false } });

  const guardian = data.audience === 'GUARDIAN';
  const dataText = data.consents.find((c) => c.required);
  const whatsappText = data.consents.find((c) => !c.required);
  const anyDraft = data.consents.some((c) => c.draft);

  const onSubmit = handleSubmit(async (values) => {
    setError(null);
    try {
      await call(
        api.POST('/api/invitations/accept', {
          body: {
            token,
            password: values.password,
            acceptData: values.acceptData,
            dataVersion: dataText?.version,
            acceptWhatsapp: values.acceptWhatsapp,
            whatsappVersion: values.acceptWhatsapp ? whatsappText?.version : undefined,
          },
        }),
      );
      window.history.replaceState(null, '', '/invite');
      setDone(true);
    } catch (e) {
      // the invitation is NOT consumed by any failure: the form stays as it was
      setError(e);
    }
  });

  if (done) {
    return (
      <Shell brandName={data.brandName}>
        <h1 className="font-display text-3xl font-bold">Cuenta creada</h1>
        <Banner tone="green" role="status">
          Ya puedes iniciar sesión con {data.accountEmail} y la contraseña que acabas de elegir.
        </Banner>
        <Link to="/login" className="inline-flex min-h-11 items-center font-semibold underline">
          Ir a iniciar sesión
        </Link>
      </Shell>
    );
  }

  const mismatch = isApiError(error) && error.is('CONSENT_VERSION_MISMATCH');

  return (
    <Shell brandName={data.brandName}>
      {anyDraft && (
        <Banner tone="amber" role="status">
          BORRADOR · sin validez legal
        </Banner>
      )}
      {guardian ? (
        <header className="flex flex-col gap-3">
          <h1 className="font-display text-3xl font-bold">Autorización del representante legal</h1>
          <p className="rounded-xl bg-brand-soft p-3 text-base">
            <strong>{data.guardianName}</strong>, vas a aceptar como <strong>representante legal</strong> de <strong>{data.studentName}</strong>, que es
            menor de edad. La cuenta y los avisos quedan a tu nombre.
          </p>
        </header>
      ) : (
        <header className="flex flex-col gap-3">
          <h1 className="font-display text-3xl font-bold">Hola, {data.studentName}</h1>
          <p className="text-base text-ink-2">{data.brandName} te invita a crear tu cuenta.</p>
        </header>
      )}

      <form onSubmit={onSubmit} className="flex flex-col gap-5" noValidate>
        {dataText && (
          <div className="flex flex-col gap-2">
            <ConsentText text={dataText} />
            <Checkbox {...register('acceptData')}>
              {guardian ? (
                <>
                  Acepto, como representante legal de {data.studentName}, el tratamiento de sus datos personales. <strong>(Obligatorio)</strong>
                </>
              ) : (
                <>
                  Acepto el tratamiento de mis datos personales. <strong>(Obligatorio)</strong>
                </>
              )}
            </Checkbox>
          </div>
        )}

        {whatsappText && (
          <div className="flex flex-col gap-2">
            <ConsentText text={whatsappText} />
            <Checkbox {...register('acceptWhatsapp')}>Acepto recibir avisos por WhatsApp. (Opcional: puedes usar la plataforma sin aceptar.)</Checkbox>
          </div>
        )}

        <div className="flex flex-col gap-4 rounded-2xl bg-white p-4">
          <h2 className="font-display text-lg font-bold">Tu acceso</h2>
          <Field label="Correo de acceso" type="email" value={data.accountEmail} readOnly autoComplete="username" />
          <Field label="Contraseña" type="password" autoComplete="new-password" hint="Mínimo 10 caracteres." required {...register('password', { required: true })} />
          <Field
            label="Repite la contraseña"
            type="password"
            autoComplete="new-password"
            error={formState.errors.confirm?.message}
            required
            {...register('confirm', { required: true, validate: (v) => v === getValues('password') || 'Las contraseñas no coinciden.' })}
          />
        </div>

        {error !== null && <Banner tone="red" role="alert">{messageFor(error)}</Banner>}
        {mismatch && (
          <Button variant="secondary" loading={reloading} onClick={onReload}>
            Volver a cargar los textos
          </Button>
        )}
        <Button type="submit" loading={formState.isSubmitting} disabled={mismatch}>
          {guardian ? 'Aceptar y crear la cuenta' : 'Crear mi cuenta'}
        </Button>
      </form>
    </Shell>
  );
}
