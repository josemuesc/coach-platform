import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router';
import { api, call } from '../../api/client';
import { messageFor } from '../../api/errors';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Field } from '../../ui/Field';

interface Values {
  newPassword: string;
  confirm: string;
}

/**
 * The link the coach hands over is /reset/<token>. The token is moved out of the address bar at once (history state survives a reload;
 * it is never in a URL, a referrer or a log) and the page continues at /reset.
 */
function takeToken(fromPath: string | undefined): string | null {
  const stored = (window.history.state as { resetToken?: string } | null)?.resetToken ?? null;
  const token = fromPath ?? stored;
  if (fromPath) window.history.replaceState({ resetToken: fromPath }, '', '/reset');
  return token;
}

export function ResetPasswordPage() {
  const params = useParams();
  const [token] = useState(() => takeToken(params.token));
  const [done, setDone] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const { register, handleSubmit, getValues, formState } = useForm<Values>();

  if (done) {
    return (
      <>
        <h1 className="font-display text-3xl font-bold">Contraseña lista</h1>
        <Banner tone="green" role="status">Ya puedes iniciar sesión con tu contraseña nueva.</Banner>
        <Link to="/login" className="inline-flex min-h-11 items-center font-semibold underline">
          Ir a iniciar sesión
        </Link>
      </>
    );
  }
  if (!token) {
    return (
      <>
        <h1 className="font-display text-3xl font-bold">Enlace no disponible</h1>
        <Banner tone="amber" role="alert">Abre otra vez el enlace que te dio tu entrenador, o pídele uno nuevo.</Banner>
      </>
    );
  }

  const onSubmit = handleSubmit(async ({ newPassword }) => {
    setError(null);
    try {
      await call(api.POST('/api/auth/reset-password', { body: { token, newPassword } }));
      window.history.replaceState(null, '', '/reset');
      setDone(true);
    } catch (e) {
      setError(e);
    }
  });

  return (
    <>
      <h1 className="font-display text-3xl font-bold">Elige tu contraseña</h1>
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        <Field
          label="Contraseña nueva"
          type="password"
          autoComplete="new-password"
          hint="Mínimo 10 caracteres."
          required
          {...register('newPassword', { required: true })}
        />
        <Field
          label="Repite la contraseña"
          type="password"
          autoComplete="new-password"
          error={formState.errors.confirm?.message}
          required
          {...register('confirm', { required: true, validate: (v) => v === getValues('newPassword') || 'Las contraseñas no coinciden.' })}
        />
        {error !== null && <Banner tone="red" role="alert">{messageFor(error)}</Banner>}
        <Button type="submit" loading={formState.isSubmitting}>
          Guardar contraseña
        </Button>
      </form>
    </>
  );
}
