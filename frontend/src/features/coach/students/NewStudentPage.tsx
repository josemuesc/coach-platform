import { useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link } from 'react-router';
import { isApiError, messageFor } from '../../../api/errors';
import { invitationMessage, isMinorOn, todayBogota } from '../../../lib/format';
import { useSession } from '../../../session/SessionProvider';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { DatePicker } from '../../../ui/DatePicker';
import { Field } from '../../../ui/Field';
import { ShareLink } from './ShareLink';
import { useCreateStudent } from './queries';

interface Values {
  fullName: string;
  birthDate: string;
  email: string;
  whatsappPhone: string;
  guardianName: string;
  guardianRelationship: string;
  guardianPhone: string;
  guardianEmail: string;
}

interface Created {
  id: string;
  name: string;
  recipient: string;
  phone: string;
  url: string;
  expiresAt: string;
}

const REQUIRED = 'Este dato es obligatorio.';

export function NewStudentPage() {
  const { me } = useSession();
  const create = useCreateStudent();
  const [created, setCreated] = useState<Created | null>(null);
  const [formError, setFormError] = useState<unknown>(null);
  const { register, handleSubmit, control, setError, reset, formState } = useForm<Values>({
    defaultValues: { fullName: '', birthDate: '', email: '', whatsappPhone: '', guardianName: '', guardianRelationship: '', guardianPhone: '', guardianEmail: '' },
  });

  const birthDate = useWatch({ control, name: 'birthDate' });
  // ONLY decides which fields to show; the server decides for real (GUARDIAN_REQUIRED if this guess was wrong)
  const minor = birthDate !== '' && isMinorOn(birthDate, todayBogota());

  const onSubmit = handleSubmit(async (v) => {
    setFormError(null);
    try {
      const body = minor
        ? {
            fullName: v.fullName.trim(),
            birthDate: v.birthDate,
            guardian: { name: v.guardianName.trim(), relationship: v.guardianRelationship.trim(), phone: v.guardianPhone.trim(), email: v.guardianEmail.trim() },
          }
        : { fullName: v.fullName.trim(), birthDate: v.birthDate, email: v.email.trim(), ...(v.whatsappPhone.trim() ? { whatsappPhone: v.whatsappPhone.trim() } : {}) };
      const result = await create.mutateAsync(body);
      setCreated({
        id: result.student.id,
        name: result.student.fullName,
        recipient: minor ? v.guardianName.trim() : v.fullName.trim().split(/\s+/)[0] ?? v.fullName,
        phone: minor ? v.guardianPhone.trim() : v.whatsappPhone.trim(),
        url: result.inviteUrl,
        expiresAt: result.inviteExpiresAt,
      });
    } catch (e) {
      const code = isApiError(e) ? e.code : '';
      const message = messageFor(e);
      if (code === 'GUARDIAN_EMAIL_IN_USE') setError('guardianEmail', { message });
      else if (code === 'STUDENT_EMAIL_EXISTS' || code === 'EMAIL_REQUIRED') setError(minor ? 'guardianEmail' : 'email', { message });
      else if (code === 'INVALID_BIRTH_DATE' || code === 'AUDIENCE_CHANGE_BLOCKED') setError('birthDate', { message });
      else setFormError(e);
    }
  });

  if (created) {
    return (
      <section className="flex flex-col gap-4 px-4 pb-6 pt-2">
        <h1 className="font-display text-[34px] font-bold leading-tight">Alumno creado</h1>
        <Banner tone="green" role="status">
          {created.name} quedó registrado. Entrega el enlace para que {minor ? 'su acudiente' : 'la persona'} cree la cuenta.
        </Banner>
        <ShareLink
          label="Enlace de invitación"
          url={created.url}
          expiresAt={created.expiresAt}
          whatsapp={{ phone: created.phone, message: invitationMessage(created.recipient, me?.brandName ?? 'Tu entrenador', created.url) }}
        />
        <Link to={`/coach/students/${created.id}`} className="inline-flex min-h-12 items-center justify-center rounded-xl bg-brand px-4 font-semibold text-brand-contrast">
          Ver alumno
        </Link>
        <Button
          variant="secondary"
          onClick={() => {
            setCreated(null);
            reset();
            create.reset();
          }}
        >
          Crear otro alumno
        </Button>
      </section>
    );
  }

  return (
    <section className="flex flex-col gap-4 px-4 pb-6 pt-2">
      <Link to="/coach/students" className="inline-flex min-h-11 items-center gap-1 font-semibold text-brand-ink" aria-label="Volver a alumnos">
        ← Alumnos
      </Link>
      <h1 className="font-display text-[34px] font-bold leading-tight">Nuevo alumno</h1>

      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        <Field label="Nombre completo" autoComplete="off" error={formState.errors.fullName?.message} {...register('fullName', { required: REQUIRED, validate: (v) => v.trim() !== '' || REQUIRED })} />
        <Controller
          control={control}
          name="birthDate"
          rules={{ required: REQUIRED }}
          render={({ field }) => (
            <DatePicker label="Fecha de nacimiento" value={field.value} max={todayBogota()} error={formState.errors.birthDate?.message} onChange={field.onChange} />
          )}
        />

        {minor ? (
          <fieldset className="flex flex-col gap-4 rounded-2xl border-l-4 border-brand bg-white p-4">
            <legend className="sr-only">Datos del acudiente</legend>
            <h2 className="font-display text-lg font-bold">Es menor de edad</h2>
            <p className="text-ink-2">La cuenta se crea con el correo del acudiente, y es el acudiente quien acepta los consentimientos.</p>
            <Field label="Nombre del acudiente" autoComplete="off" error={formState.errors.guardianName?.message} {...register('guardianName', { required: REQUIRED })} />
            <Field
              label="Parentesco"
              hint="Madre, padre, tutor…"
              autoComplete="off"
              error={formState.errors.guardianRelationship?.message}
              {...register('guardianRelationship', { required: REQUIRED })}
            />
            <Field
              label="Celular del acudiente"
              type="tel"
              inputMode="tel"
              autoComplete="off"
              error={formState.errors.guardianPhone?.message}
              {...register('guardianPhone', { required: REQUIRED })}
            />
            <Field
              label="Correo del acudiente"
              type="email"
              autoComplete="off"
              hint="Cada alumno necesita un correo distinto."
              error={formState.errors.guardianEmail?.message}
              {...register('guardianEmail', { required: REQUIRED })}
            />
          </fieldset>
        ) : (
          birthDate !== '' && (
            <>
              <Field
                label="Correo del alumno"
                type="email"
                autoComplete="off"
                hint="Es su usuario de acceso. Cada alumno necesita un correo distinto."
                error={formState.errors.email?.message}
                {...register('email', { required: REQUIRED })}
              />
              <Field label="Celular (opcional)" type="tel" inputMode="tel" autoComplete="off" {...register('whatsappPhone')} />
            </>
          )
        )}

        {formError !== null && <Banner tone="red" role="alert">{messageFor(formError)}</Banner>}
        <p className="text-sm text-ink-2">Al crear el alumno verás un enlace de invitación para copiarlo o enviarlo por WhatsApp.</p>
        <Button type="submit" loading={formState.isSubmitting} className="min-h-14">
          Crear alumno e invitar
        </Button>
      </form>
    </section>
  );
}
