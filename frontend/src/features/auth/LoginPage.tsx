import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Navigate, useNavigate, useSearchParams } from 'react-router';
import { api, call } from '../../api/client';
import { ERRORS } from '../../api/errorCatalog.generated';
import { messageFor } from '../../api/errors';
import { ME_KEY, homeFor, safeRedirect, useSession, type Me } from '../../session/SessionProvider';
import { session, type EndReason } from '../../session/sessionStore';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Field } from '../../ui/Field';

interface Values {
  email: string;
  password: string;
}

function endMessage(reason: EndReason | null): string | null {
  switch (reason) {
    case 'expired':
      return 'Tu sesión expiró. Inicia sesión de nuevo.';
    case 'invalid_session':
      return ERRORS.INVALID_SESSION.message;
    case 'suspended':
      return ERRORS.ACCOUNT_SUSPENDED.message;
    default:
      return null;
  }
}

export function LoginPage() {
  const { status, me, endReason } = useSession();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [error, setError] = useState<unknown>(null);
  const { register, handleSubmit, formState } = useForm<Values>();

  if (status === 'authenticated') return <Navigate to={safeRedirect(params.get('redirect')) ?? homeFor(me?.role)} replace />;

  const onSubmit = handleSubmit(async (values) => {
    setError(null);
    try {
      const { token } = await call(api.POST('/api/auth/login', { body: { email: values.email.trim(), password: values.password } }));
      session.start(token);
      const fresh = await queryClient.fetchQuery({ queryKey: ME_KEY, queryFn: () => call<Me>(api.GET('/api/me')) });
      navigate(safeRedirect(params.get('redirect')) ?? homeFor(fresh.role), { replace: true });
    } catch (e) {
      setError(e);
    }
  });

  const ended = endMessage(endReason);
  return (
    <>
      <h1 className="font-display text-3xl font-bold">Inicia sesión</h1>
      {ended && <Banner tone="amber" role="status">{ended}</Banner>}
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        <Field label="Correo" type="email" autoComplete="username" inputMode="email" required {...register('email', { required: true })} />
        <Field label="Contraseña" type="password" autoComplete="current-password" required {...register('password', { required: true })} />
        {error !== null && <Banner tone="red" role="alert">{messageFor(error)}</Banner>}
        <Button type="submit" loading={formState.isSubmitting}>
          Entrar
        </Button>
      </form>
    </>
  );
}
