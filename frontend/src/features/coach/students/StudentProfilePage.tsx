import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { isApiError, messageFor } from '../../../api/errors';
import { initials } from '../../../brand/applyBrand';
import { bogotaDay, formatCop, formatDayMonth, formatDayMonthTime, formatWeekdayDateTime, invitationMessage } from '../../../lib/format';
import { plural } from '../../../lib/time';
import { useSession } from '../../../session/SessionProvider';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { Chip } from '../../../ui/Chip';
import { ErrorState, Skeleton } from '../../../ui/States';
import { ClassSheet } from './ClassSheet';
import { ExtendCycleSheet } from './ExtendCycleSheet';
import { PaymentFlow } from './PaymentFlow';
import { ShareLink } from './ShareLink';
import { chipFor, methodLabel, modalityName } from './present';
import { useBoard, useIssueReset, useProfile, useReissueInvitation, useRevokeReset, type Profile } from './queries';

const CARD = 'flex flex-col gap-3 rounded-3xl border border-line bg-white p-4';
const CAPTION = 'text-xs font-bold tracking-wider text-ink-2';

function CycleCard({ profile, onPay, onExtend, onSheet }: { profile: Profile; onPay: () => void; onExtend: () => void; onSheet: () => void }) {
  const board = useBoard();
  const row = board.data?.students.find((r) => r.studentId === profile.student.id);
  const chip = row ? chipFor(row) : null;
  const { cycle, planName, lastPayment, payment, extension } = profile.cycle;

  return (
    <section aria-labelledby="cycle-title" className={CARD}>
      <div className="flex items-center justify-between gap-2">
        <h2 id="cycle-title" className={CAPTION}>
          PLAN Y CICLO
        </h2>
        {chip && <Chip tone={chip.tone}>{chip.text}</Chip>}
      </div>

      {cycle ? (
        <>
          <div>
            <p className="font-display text-xl font-bold">{planName ?? modalityName(cycle.modality)}</p>
            <p className="text-ink-2">
              {modalityName(cycle.modality)} · Ciclo del {formatDayMonth(cycle.startDate)} al {formatDayMonth(cycle.endDate)}
            </p>
          </div>
          <p className="flex items-baseline gap-2">
            <span className="font-display text-5xl font-bold leading-none">{cycle.classesRemaining}</span>
            <span className="text-ink-2">
              {cycle.classesRemaining === 1 ? 'clase por usar' : 'clases por usar'}, de {cycle.classesIncluded}
            </span>
          </p>
          <div
            role="progressbar"
            aria-label="Clases usadas"
            aria-valuemin={0}
            aria-valuemax={cycle.classesIncluded}
            aria-valuenow={cycle.classesUsed}
            className="h-2.5 overflow-hidden rounded-full bg-line"
          >
            <div className="h-full rounded-full bg-brand" style={{ width: `${(cycle.classesUsed / cycle.classesIncluded) * 100}%` }} />
          </div>
          {cycle.status === 'COMPLETED' && <Banner tone="amber">Usó todas sus clases: ya puedes registrar el pago de un ciclo nuevo.</Banner>}
          {cycle.status === 'EXPIRED' && (
            <Banner tone="red">Este ciclo venció{cycle.classesLost > 0 ? ` y perdió ${plural(cycle.classesLost, 'clase', 'clases')}` : ''}.</Banner>
          )}
          {cycle.pendingMarks > 0 && <Banner tone="amber">Tiene {plural(cycle.pendingMarks, 'clase sin marcar', 'clases sin marcar')}.</Banner>}
        </>
      ) : (
        <p className="text-lg">Todavía no tiene plan.</p>
      )}

      {lastPayment && (
        <p className="text-sm text-ink-2">
          Último pago: {formatDayMonth(lastPayment.paidOn)} · {formatCop(lastPayment.amountCop)} · {methodLabel(lastPayment.method)}
          {lastPayment.reference ? ` · Comprobante ${lastPayment.reference}` : ''}
        </p>
      )}

      <div className="flex gap-3">
        <Button className="flex-1" disabled={!payment.canRegisterPayment} onClick={onPay} aria-describedby={payment.canRegisterPayment ? undefined : 'pay-blocked'}>
          Registrar pago
        </Button>
        {extension.canExtendCycle && (
          <Button variant="secondary" className="flex-1" onClick={onExtend}>
            {cycle?.status === 'EXPIRED' ? 'Reabrir ciclo' : 'Extender ciclo'}
          </Button>
        )}
      </div>
      {!payment.canRegisterPayment && (
        <p id="pay-blocked" className="text-sm text-ink-2">
          {payment.blockedBy === 'PENDING_SESSIONS'
            ? 'Marca primero las clases que ya empezaron para poder renovar.'
            : `Podrás renovar desde el ${payment.opensOn ? formatDayMonth(payment.opensOn) : 'fin del ciclo'} o cuando use sus clases.`}
        </p>
      )}
      {cycle && (
        <button type="button" onClick={onSheet} className="min-h-11 self-start font-semibold text-brand-ink underline underline-offset-4">
          Ver planilla de clases
        </button>
      )}
    </section>
  );
}

function UpcomingCard({ profile }: { profile: Profile }) {
  return (
    <section aria-labelledby="upcoming-title" className={CARD}>
      <h2 id="upcoming-title" className={CAPTION}>
        PRÓXIMAS CLASES
      </h2>
      {profile.upcoming.length === 0 ? (
        <p className="text-ink-2">No tiene clases agendadas.</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {profile.upcoming.map((c) => (
            <li key={c.attendanceId} className="flex items-center justify-between gap-2">
              <span>
                <span className="block font-display text-base font-bold">{formatWeekdayDateTime(c.startsAt)}</span>
                <span className="text-sm text-ink-2">{modalityName(c.modality)}</span>
              </span>
              {c.today && <Chip tone="green">Hoy</Chip>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

const CONSENT_LABEL: Record<string, string> = {
  DATA_ADULT: 'Tratamiento de datos',
  DATA_GUARDIAN: 'Tratamiento de datos (representante legal)',
  WHATSAPP: 'Mensajes por WhatsApp',
};

function ConsentsCard({ profile }: { profile: Profile }) {
  const items = profile.consents.consents.filter((c) => c.applies);
  const emergency = profile.emergencyContact;
  return (
    <section aria-labelledby="consents-title" className={CARD}>
      <h2 id="consents-title" className={CAPTION}>
        CONSENTIMIENTOS
      </h2>
      <ul className="flex flex-col gap-3">
        {items.map((c) => (
          <li key={c.type} className="flex items-start gap-3">
            <span aria-hidden="true" className={`mt-0.5 w-5 text-center font-bold ${c.active ? 'text-green-ink' : 'text-ink-2'}`}>
              {c.active ? '✓' : '–'}
            </span>
            <span>
              <span className="block font-display text-base font-bold">{CONSENT_LABEL[c.type] ?? c.type}</span>
              <span className="block text-sm text-ink-2">
                {c.active && c.acceptedAt
                  ? `Aceptado el ${formatDayMonth(bogotaDay(c.acceptedAt))} · versión ${c.acceptedVersion ?? ''}${c.upToDate ? '' : ' · hay una versión nueva'}`
                  : 'No aceptado'}
              </span>
            </span>
          </li>
        ))}
      </ul>
      <p className="border-t border-line pt-3 text-sm">
        <span className="font-semibold">Contacto de emergencia: </span>
        {emergency ? (
          <span>
            {emergency.name} ({emergency.relationship}) · {emergency.phone}
          </span>
        ) : (
          <span className="text-ink-2">aún no registrado</span>
        )}
      </p>
    </section>
  );
}

interface Shown {
  kind: 'reset' | 'invite';
  url: string;
  expiresAt: string;
}

function AccountCard({ profile }: { profile: Profile }) {
  const { me } = useSession();
  const student = profile.student;
  const account = profile.account;
  const issue = useIssueReset(student.id);
  const revoke = useRevokeReset(student.id);
  const reissue = useReissueInvitation(student.id);
  const [shown, setShown] = useState<Shown | null>(null);
  const [error, setError] = useState<unknown>(null);
  const guardian = student.minor ? student.guardian : null;

  async function generateReset() {
    setError(null);
    try {
      const r = (await issue.mutateAsync()) as { resetUrl: string; expiresAt: string };
      setShown({ kind: 'reset', url: r.resetUrl, expiresAt: r.expiresAt });
    } catch (e) {
      setError(e);
    }
  }

  async function generateInvite() {
    setError(null);
    try {
      const r = (await reissue.mutateAsync()) as { inviteUrl: string; expiresAt: string };
      setShown({ kind: 'invite', url: r.inviteUrl, expiresAt: r.expiresAt });
    } catch (e) {
      setError(e);
    }
  }

  async function revokeLink() {
    setError(null);
    try {
      await revoke.mutateAsync();
      setShown(null);
    } catch (e) {
      setError(e);
    }
  }

  const owner = guardian ? `la cuenta del acudiente (${guardian.name})` : 'el alumno';
  let status: string;
  if (!account.active) status = 'La cuenta está suspendida.';
  else if (!account.hasAccount) status = 'Todavía no activó la cuenta: falta que acepte la invitación.';
  else if (account.passwordChangedBy === 'SELF' && account.passwordChangedAt) status = `Cuenta activa. ${guardian ? 'El acudiente' : 'El alumno'} cambió su clave el ${formatDayMonth(bogotaDay(account.passwordChangedAt))}.`;
  else if (account.passwordChangedBy === 'COACH_LINK' && account.passwordChangedAt) status = `Cuenta activa. La clave se restableció con un enlace tuyo el ${formatDayMonth(bogotaDay(account.passwordChangedAt))}.`;
  else status = 'Cuenta activa.';

  return (
    <section aria-labelledby="account-title" className={CARD}>
      <h2 id="account-title" className={CAPTION}>
        ACCESO A LA CUENTA
      </h2>
      <p>{status}</p>
      {guardian && <p className="text-sm text-ink-2">La cuenta es del acudiente: {guardian.name} ({guardian.email}).</p>}

      {account.active && account.hasAccount && (
        <>
          <Button variant="secondary" loading={issue.isPending} onClick={() => void generateReset()}>
            Generar enlace para restablecer la clave
          </Button>
          <Banner tone="amber">
            {guardian
              ? `Es el enlace de la cuenta del acudiente: entrégaselo a ${guardian.name}. Quien lo tenga puede entrar a esa cuenta.`
              : 'Quien tenga el enlace puede entrar como el alumno.'}{' '}
            Vale 24 horas y un solo uso. Queda registrado.
          </Banner>
          {account.openResetLinkUntil && (
            <div className="flex flex-col gap-2">
              <p className="text-sm">Hay un enlace abierto hasta el {formatDayMonthTime(account.openResetLinkUntil)}.</p>
              <Button variant="secondary" loading={revoke.isPending} onClick={() => void revokeLink()}>
                Revocar enlace
              </Button>
            </div>
          )}
        </>
      )}

      {account.active && !account.hasAccount && (
        <>
          <Button variant="secondary" loading={reissue.isPending} onClick={() => void generateInvite()}>
            Generar enlace de invitación nuevo
          </Button>
          <p className="text-sm text-ink-2">El enlace anterior deja de servir. Se lo entregas a {guardian ? guardian.name : 'el alumno'} para que cree {owner === 'el alumno' ? 'su cuenta' : 'la cuenta'}.</p>
        </>
      )}

      {error !== null && <Banner tone="red" role="alert">{messageFor(error)}</Banner>}
      {shown && (
        <ShareLink
          label={shown.kind === 'reset' ? 'Enlace para restablecer la clave' : 'Enlace de invitación'}
          url={shown.url}
          expiresAt={shown.expiresAt}
          whatsapp={
            shown.kind === 'invite'
              ? { phone: guardian ? guardian.phone : student.whatsappPhone, message: invitationMessage(guardian ? guardian.name : student.fullName.split(/\s+/)[0] ?? student.fullName, me?.brandName ?? 'Tu entrenador', shown.url) }
              : undefined
          }
        />
      )}
    </section>
  );
}

export function StudentProfilePage() {
  const { id = '' } = useParams();
  const profile = useProfile(id);
  const [flow, setFlow] = useState<'none' | 'pay' | 'extend' | 'sheet'>('none');

  if (profile.isPending) return <Skeleton label="Cargando al alumno" />;
  if (profile.isError) {
    const gone = isApiError(profile.error) && profile.error.status === 404;
    return (
      <div className="flex flex-col gap-2 px-4">
        <Link to="/coach/students" className="inline-flex min-h-11 items-center font-semibold text-brand-ink">
          ← Alumnos
        </Link>
        <ErrorState error={profile.error} onRetry={gone ? undefined : () => void profile.refetch()} />
      </div>
    );
  }

  const p = profile.data;
  const student = p.student;
  return (
    <section className="flex flex-col gap-4 px-4 pb-6 pt-2">
      <Link to="/coach/students" className="inline-flex min-h-11 items-center font-semibold text-brand-ink">
        ← Alumnos
      </Link>
      <header className="flex items-center gap-3">
        <span aria-hidden="true" className="inline-flex size-16 shrink-0 items-center justify-center rounded-full bg-past font-display text-xl font-bold">
          {initials(student.fullName)}
        </span>
        <div className="min-w-0">
          <h1 className="font-display text-3xl font-bold leading-tight">{student.fullName}</h1>
          <p className="truncate text-ink-2">{student.email}</p>
          {student.minor && <span className="mt-1 inline-block rounded-full border border-ink-2 px-2 text-xs font-semibold text-ink-2">Menor</span>}
        </div>
      </header>

      <CycleCard profile={p} onPay={() => setFlow('pay')} onExtend={() => setFlow('extend')} onSheet={() => setFlow('sheet')} />
      <UpcomingCard profile={p} />
      <ConsentsCard profile={p} />
      <AccountCard profile={p} />

      {flow === 'pay' && <PaymentFlow profile={p} onClose={() => setFlow('none')} />}
      {flow === 'extend' && <ExtendCycleSheet profile={p} onClose={() => setFlow('none')} />}
      {flow === 'sheet' && <ClassSheet studentId={student.id} studentName={student.fullName} onClose={() => setFlow('none')} />}
    </section>
  );
}
