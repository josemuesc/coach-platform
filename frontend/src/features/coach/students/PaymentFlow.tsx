import { useId, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { modalityConflictOf, pendingSessionsOf, type ModalityConflictDetails } from '../../../api/errorDetails';
import { isApiError, messageFor } from '../../../api/errors';
import { formatCop, formatDayMonthYear, formatWeekdayDateTime, parseCop } from '../../../lib/format';
import { plural } from '../../../lib/time';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { Chip } from '../../../ui/Chip';
import { Dialog, Sheet } from '../../../ui/Overlays';
import { RadioCards } from '../../../ui/RadioCards';
import { METHODS, methodLabel, modalityName, type Method } from './present';
import { useCancelAttendances, usePlans, useRegisterPayment, type PaymentRegistered, type Plan, type Profile } from './queries';

/** What the person changed. `null` = untouched: the value is derived (the plan of the last cycle, its price, the latest allowed date). */
interface Draft {
  planId: string | null;
  amountText: string | null;
  paidOn: string | null;
  method: Method;
  reference: string;
}

type Stage = 'closed' | 'form' | 'confirm' | 'conflict' | 'pending' | 'done';

interface Props {
  profile: Profile;
  /** Mounted only while the flow is open: closing it unmounts it and every piece of state starts over next time. */
  onClose: () => void;
}

function planLine(plan: Plan): string {
  return `${modalityName(plan.modality)} · ${plural(plan.classesIncluded, 'clase', 'clases')}`;
}

/** Register a payment: the form, the confirmation (a payment cannot be edited afterwards), and the way out of the two renewal conflicts. */
export function PaymentFlow({ profile, onClose }: Props) {
  const navigate = useNavigate();
  const student = profile.student;
  const window_ = profile.cycle.payment;
  const plans = usePlans();
  const register = useRegisterPayment(student.id);
  const cancel = useCancelAttendances(student.id);
  const [stage, setStage] = useState<Stage>('form');
  const [draft, setDraft] = useState<Draft>({ planId: null, amountText: null, paidOn: null, method: 'NEQUI', reference: '' });
  const [fieldErrors, setFieldErrors] = useState<{ reference?: string; date?: string }>({});
  const [formError, setFormError] = useState<unknown>(null);
  const [conflict, setConflict] = useState<ModalityConflictDetails | null>(null);
  const [pendingList, setPendingList] = useState<ReturnType<typeof pendingSessionsOf>>(null);
  const [result, setResult] = useState<PaymentRegistered | null>(null);
  const referenceId = useId();

  const activePlans = (plans.data ?? []).filter((p) => p.active);
  const lastPlanId = profile.cycle.cycle?.planId;

  const planId = draft.planId ?? (activePlans.some((p) => p.id === lastPlanId) ? (lastPlanId ?? '') : '');
  const plan = activePlans.find((p) => p.id === planId);
  const amountText = draft.amountText ?? (plan ? String(plan.priceCop) : '');
  const paidOn = draft.paidOn ?? window_.paidOnMax ?? '';

  function finish() {
    onClose();
  }

  const amount = parseCop(amountText);
  const canSubmit = !!plan && amount !== null && amount > 0 && paidOn !== '';

  async function send(extra: { overrideModality?: boolean; overrideReason?: string } = {}) {
    if (!plan || amount === null) return;
    setFormError(null);
    setFieldErrors({});
    try {
      const done = await register.mutateAsync({
        planId: plan.id,
        amountCop: amount,
        method: draft.method,
        paidOn,
        ...(draft.reference.trim() ? { reference: draft.reference.trim() } : {}),
        ...extra,
      });
      setResult(done);
      setStage('done');
    } catch (e) {
      const pendingSessions = pendingSessionsOf(e);
      const modality = modalityConflictOf(e);
      if (pendingSessions) {
        setPendingList(pendingSessions);
        setStage('pending');
      } else if (modality) {
        setConflict(modality);
        setStage('conflict');
      } else {
        const code = isApiError(e) ? e.code : '';
        if (code === 'PAYMENT_REFERENCE_HAS_LONG_NUMBER' || code === 'INVALID_PAYMENT_REFERENCE') setFieldErrors({ reference: messageFor(e) });
        else if (code === 'INVALID_PAYMENT_DATE') setFieldErrors({ date: messageFor(e) });
        else setFormError(e);
        setStage('form');
      }
    }
  }

  const sheetOpen = stage === 'form' || stage === 'confirm';

  return (
    <>
      <Sheet open={sheetOpen} // the sheet also closes by itself when the flow moves to another stage: only a close while the form is showing is the person's own
      onOpenChange={(o) => !o && stage === 'form' && finish()} title="Registrar pago" description={student.fullName}>
        {(
          <form
            noValidate
            className="flex flex-col gap-5"
            onSubmit={(e) => {
              e.preventDefault();
              if (canSubmit) setStage('confirm');
            }}
          >
            <div className="flex flex-col gap-2">
              <p className="font-semibold">Plan</p>
              {plans.isPending && <p className="text-ink-2">Cargando planes…</p>}
              {plans.isError && <Banner tone="red" role="alert">{messageFor(plans.error)}</Banner>}
              {plans.data && activePlans.length === 0 && <Banner tone="amber">No tienes planes activos. Crea uno antes de registrar un pago.</Banner>}
              <RadioCards
                label="Plan"
                value={planId}
                options={activePlans.map((p) => ({ value: p.id, title: p.name, sub: planLine(p), right: formatCop(p.priceCop) }))}
                onChange={(id) => {
                  const chosen = activePlans.find((p) => p.id === id);
                  setDraft({ ...draft, planId: id, amountText: chosen ? String(chosen.priceCop) : amountText });
                }}
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1">
                <label htmlFor="pay-amount" className="text-sm font-semibold">
                  Monto
                </label>
                <input
                  id="pay-amount"
                  inputMode="numeric"
                  autoComplete="off"
                  value={amountText === '' ? '' : formatCop(parseCop(amountText) ?? 0)}
                  onChange={(e) => setDraft({ ...draft, amountText: e.target.value })}
                  className="min-h-12 rounded-xl border border-line bg-white px-3 text-base"
                />
              </div>
              <div className="flex flex-col gap-1">
                <label htmlFor="pay-date" className="text-sm font-semibold">
                  Fecha del pago
                </label>
                <input
                  id="pay-date"
                  type="date"
                  value={paidOn}
                  min={window_.paidOnMin ?? undefined}
                  max={window_.paidOnMax ?? undefined}
                  aria-describedby={fieldErrors.date ? 'pay-date-error' : undefined}
                  aria-invalid={fieldErrors.date ? true : undefined}
                  onChange={(e) => setDraft({ ...draft, paidOn: e.target.value })}
                  className="min-h-12 rounded-xl border border-line bg-white px-3 text-base"
                />
              </div>
              {fieldErrors.date && (
                <p id="pay-date-error" role="alert" className="col-span-2 text-sm font-semibold text-red-ink">
                  {fieldErrors.date}
                </p>
              )}
            </div>

            <div className="flex flex-col gap-2">
              <p className="font-semibold">Cómo lo recibió</p>
              <RadioCards
                label="Cómo lo recibió"
                value={draft.method}
                columns
                options={METHODS.map((m) => ({ value: m.value, title: m.label }))}
                onChange={(method) => setDraft({ ...draft, method })}
              />
            </div>

            <div className="flex flex-col gap-1">
              <label htmlFor={referenceId} className="font-semibold">
                Número de comprobante <span className="font-normal text-ink-2">(opcional)</span>
              </label>
              <input
                id={referenceId}
                value={draft.reference}
                maxLength={100}
                autoComplete="off"
                placeholder="Ej. M1234567"
                aria-describedby={`${referenceId}-hint${fieldErrors.reference ? ` ${referenceId}-error` : ''}`}
                aria-invalid={fieldErrors.reference ? true : undefined}
                onChange={(e) => setDraft({ ...draft, reference: e.target.value })}
                className="min-h-12 rounded-xl border border-line bg-white px-3 text-base"
              />
              <p id={`${referenceId}-hint`} className="text-sm text-ink-2">
                Solo el comprobante: no escribas números de tarjeta ni de cuenta.
              </p>
              {fieldErrors.reference && (
                <p id={`${referenceId}-error`} role="alert" className="text-sm font-semibold text-red-ink">
                  {fieldErrors.reference}
                </p>
              )}
            </div>

            <p className="rounded-2xl bg-bg p-4 text-base">
              El ciclo nuevo empieza en la fecha del pago y dura un mes. Un pago registrado no se puede editar: revisa el monto, el plan y la fecha antes de guardar.
            </p>
            {formError !== null && <Banner tone="red" role="alert">{messageFor(formError)}</Banner>}
            <Button type="submit" disabled={!canSubmit} className="min-h-14">
              {amount ? `Registrar pago de ${formatCop(amount)}` : 'Registrar pago'}
            </Button>
            <Button variant="ghost" onClick={finish}>
              Cancelar
            </Button>
          </form>
        )}
      </Sheet>

      <Dialog
        open={stage === 'confirm'}
        onOpenChange={(o) => !o && stage === 'confirm' && setStage('form')}
        title="¿Registrar este pago?"
        description="Un pago registrado no se puede editar."
      >
        {plan && amount !== null && (
          <div className="flex flex-col gap-3">
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-base">
              <dt className="text-ink-2">Alumno</dt>
              <dd className="font-semibold">{student.fullName}</dd>
              <dt className="text-ink-2">Plan</dt>
              <dd className="font-semibold">
                {plan.name} · {planLine(plan)}
              </dd>
              <dt className="text-ink-2">Monto</dt>
              <dd className="font-semibold">{formatCop(amount)}</dd>
              <dt className="text-ink-2">Fecha</dt>
              <dd className="font-semibold">{formatDayMonthYear(paidOn)}</dd>
              <dt className="text-ink-2">Recibido</dt>
              <dd className="font-semibold">{methodLabel(draft.method)}</dd>
              {draft.reference.trim() && (
                <>
                  <dt className="text-ink-2">Comprobante</dt>
                  <dd className="font-semibold">{draft.reference.trim()}</dd>
                </>
              )}
            </dl>
            {amount !== plan.priceCop && <Banner tone="amber">El monto es distinto al precio del plan ({formatCop(plan.priceCop)}).</Banner>}
            {register.isError && !conflict && !pendingList && <Banner tone="red" role="alert">{messageFor(register.error)}</Banner>}
            <div className="flex gap-3">
              <Button variant="secondary" className="flex-1" disabled={register.isPending} onClick={() => setStage('form')}>
                Revisar
              </Button>
              <Button className="flex-1" loading={register.isPending} onClick={() => void send()}>
                Sí, registrar
              </Button>
            </div>
          </div>
        )}
      </Dialog>

      <Dialog open={stage === 'done'} onOpenChange={(o) => !o && finish()} title="Pago registrado" description={`${student.fullName} tiene un ciclo nuevo.`}>
        {result && (
          <div className="flex flex-col gap-3">
            <p className="text-lg">
              Del <strong>{formatDayMonthYear(result.startDate)}</strong> al <strong>{formatDayMonthYear(result.endDate)}</strong>.
            </p>
            <Button onClick={finish}>Listo</Button>
          </div>
        )}
      </Dialog>

      {stage === 'pending' && (
        <PendingSheet
          list={pendingList ?? []}
          onBack={() => setStage('form')}
          onGoMark={() => {
            finish();
            void navigate('/coach');
          }}
        />
      )}

      {stage === 'conflict' && conflict && (
        <ConflictSheet
          conflict={conflict}
          studentName={student.fullName}
          currentModality={profile.cycle.cycle?.modality}
          working={register.isPending || cancel.isPending}
          error={register.isError || cancel.isError ? (register.error ?? cancel.error) : null}
          onBack={() => {
            register.reset();
            cancel.reset();
            setStage('form');
          }}
          onCancelThenPay={async (reason) => {
            try {
              await cancel.mutateAsync({ attendanceIds: conflict.conflictingAttendances.map((c) => c.attendanceId), reason });
            } catch {
              return;   // the error is shown in the sheet; nothing was cancelled (all or nothing)
            }
            await send();
          }}
          onOverridePay={(reason) => send({ overrideModality: true, overrideReason: reason })}
        />
      )}
    </>
  );
}

function PendingSheet({ list, onBack, onGoMark }: { list: { attendanceId: string; startsAt: string; eventModality: string }[]; onBack: () => void; onGoMark: () => void }) {
  return (
    <Sheet open onOpenChange={(o) => !o && onBack()} title="Antes de renovar" description="Hay clases que ya empezaron y no se marcaron.">
      <div className="flex flex-col gap-4">
        <ul className="overflow-hidden rounded-2xl border border-line">
          {list.map((c) => (
            <li key={c.attendanceId} className="flex items-center justify-between gap-2 border-b border-line px-4 py-3 last:border-b-0">
              <span>
                <span className="block font-display text-base font-bold">{formatWeekdayDateTime(c.startsAt)}</span>
                <span className="text-sm text-ink-2">{modalityName(c.eventModality as 'PERSONALIZED' | 'SEMI_PERSONALIZED')}</span>
              </span>
              <Chip tone="amber">Sin marcar</Chip>
            </li>
          ))}
        </ul>
        <p className="text-ink-2">Márcalas (asistió o no vino) y vuelve a registrar el pago.</p>
        <Button onClick={onGoMark}>Ir a marcar las clases</Button>
        <Button variant="ghost" onClick={onBack}>
          Volver sin cambiar nada
        </Button>
      </div>
    </Sheet>
  );
}

function ConflictSheet({ conflict, studentName, currentModality, working, error, onBack, onCancelThenPay, onOverridePay }: {
  conflict: ModalityConflictDetails;
  studentName: string;
  currentModality: 'PERSONALIZED' | 'SEMI_PERSONALIZED' | undefined;
  working: boolean;
  error: unknown;
  onBack: () => void;
  onCancelThenPay: (reason: string) => Promise<void>;
  onOverridePay: (reason: string) => Promise<void>;
}) {
  const offered = conflict.options.map((o) => o.code);
  const [choice, setChoice] = useState<'CANCEL_WITHOUT_PENALTY' | 'OVERRIDE' | ''>('');
  const [reason, setReason] = useState('');
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const count = conflict.conflictingAttendances.length;

  const options = [
    { value: 'CANCEL_WITHOUT_PENALTY' as const, title: count === 1 ? 'Cancelar la clase agendada y cambiar' : `Cancelar las ${count} clases agendadas y cambiar`, sub: 'Se pide un motivo. Nadie pierde una clase.' },
    { value: 'OVERRIDE' as const, title: `Pasar ${count === 1 ? 'la clase' : `las ${count} clases`} al plan nuevo`, sub: 'Se pide un motivo. Las clases pasan al ciclo nuevo y quedan marcadas como excepción.' },
  ].filter((o) => offered.includes(o.value));

  const ready = choice !== '' && reason.trim() !== '';

  return (
    <Sheet open onOpenChange={(o) => !o && onBack()} title="Antes de cambiar de modalidad" description={`${studentName} pasaría de ${modalityName(currentModality)} a ${modalityName(conflict.newPlanModality)}.`}>
      {(
        <div className="flex flex-col gap-4">
          <Banner tone="amber">
            Tiene <strong>{plural(count, 'clase agendada', 'clases agendadas')}</strong> en la modalidad actual. El cambio las afecta.
          </Banner>
          <ul className="overflow-hidden rounded-2xl border border-line">
            {conflict.conflictingAttendances.map((c) => (
              <li key={c.attendanceId} className="flex items-center justify-between gap-2 border-b border-line px-4 py-3 last:border-b-0">
                <span>
                  <span className="block font-display text-base font-bold">{formatWeekdayDateTime(c.startsAt)}</span>
                  <span className="text-sm text-ink-2">{modalityName(c.eventModality)}</span>
                </span>
                <Chip>Agendada</Chip>
              </li>
            ))}
          </ul>
          <div className="flex flex-col gap-2">
            <p className="font-semibold">¿Qué quieres hacer?</p>
            <RadioCards label="Qué hacer con las clases agendadas" value={choice} options={options} onChange={(v) => { setChoice(v); window.requestAnimationFrame(() => reasonRef.current?.focus()); }} />
          </div>
          {choice !== '' && (
            <div className="flex flex-col gap-1">
              <label htmlFor="conflict-reason" className="font-semibold">
                Motivo
              </label>
              <textarea
                id="conflict-reason"
                ref={reasonRef}
                value={reason}
                maxLength={500}
                rows={3}
                onChange={(e) => setReason(e.target.value)}
                className="rounded-xl border border-line bg-white p-3 text-base"
              />
            </div>
          )}
          {error !== null && <Banner tone="red" role="alert">{messageFor(error)}</Banner>}
          <Button
            disabled={!ready}
            loading={working}
            onClick={() => void (choice === 'CANCEL_WITHOUT_PENALTY' ? onCancelThenPay(reason.trim()) : onOverridePay(reason.trim()))}
            className="min-h-14"
          >
            {choice === 'OVERRIDE' ? 'Registrar el pago y pasar las clases' : choice === 'CANCEL_WITHOUT_PENALTY' ? `Cancelar ${plural(count, 'clase', 'clases')} y registrar el pago` : 'Elige una opción'}
          </Button>
          <Button variant="ghost" onClick={onBack}>
            Volver sin cambiar nada
          </Button>
        </div>
      )}
    </Sheet>
  );
}
