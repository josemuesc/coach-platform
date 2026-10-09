import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { api, call } from '../../api/client';
import { messageFor } from '../../api/errors';
import { ME_KEY, useSession } from '../../session/SessionProvider';
import { session } from '../../session/sessionStore';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Field } from '../../ui/Field';

interface Values {
  currentPassword: string;
  newPassword: string;
  confirm: string;
}

/** Shared by both areas. The server answers with a NEW token (every older session stops working); it replaces the stored one. */
export function ChangePasswordPage() {
  const queryClient = useQueryClient();
  const { logout } = useSession();
  const [error, setError] = useState<unknown>(null);
  const [changed, setChanged] = useState(false);
  const { register, handleSubmit, getValues, reset, formState } = useForm<Values>();

  const onSubmit = handleSubmit(async ({ currentPassword, newPassword }) => {
    setError(null);
    setChanged(false);
    try {
      const { token } = await call(api.POST('/api/auth/change-password', { body: { currentPassword, newPassword } }));
      session.start(token);
      await queryClient.invalidateQueries({ queryKey: ME_KEY });
      reset();
      setChanged(true);
    } catch (e) {
      setError(e);
    }
  });

  return (
    <section className="flex flex-col gap-4 p-4">
      <h1 className="font-display text-3xl font-bold">Cuenta</h1>
      <form onSubmit={onSubmit} className="flex flex-col gap-4 rounded-2xl bg-white p-4" noValidate>
        <h2 className="font-display text-xl font-bold">Cambiar contraseña</h2>
        <Field label="Contraseña actual" type="password" autoComplete="current-password" required {...register('currentPassword', { required: true })} />
        <Field label="Contraseña nueva" type="password" autoComplete="new-password" hint="Mínimo 10 caracteres." required {...register('newPassword', { required: true })} />
        <Field
          label="Repite la contraseña nueva"
          type="password"
          autoComplete="new-password"
          error={formState.errors.confirm?.message}
          required
          {...register('confirm', { required: true, validate: (v) => v === getValues('newPassword') || 'Las contraseñas no coinciden.' })}
        />
        {error !== null && <Banner tone="red" role="alert">{messageFor(error)}</Banner>}
        {changed && <Banner tone="green" role="status">Contraseña cambiada. Tus otras sesiones se cerraron.</Banner>}
        <Button type="submit" loading={formState.isSubmitting}>
          Cambiar contraseña
        </Button>
      </form>
      <Button variant="secondary" onClick={logout}>
        Cerrar sesión
      </Button>
    </section>
  );
}
