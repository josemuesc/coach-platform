import { useState } from 'react';
import { isApiError, messageFor } from '../../../api/errors';
import { formatCop, parseCop } from '../../../lib/format';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { Field } from '../../../ui/Field';
import { Sheet } from '../../../ui/Overlays';
import { Segmented } from '../../../ui/Segmented';
import { Switch } from '../../../ui/Switch';
import type { Plan } from '../students/queries';
import { useSavePlan } from './queries';

type Modality = Plan['modality'];

interface Props {
  /** null = a new plan. */
  plan: Plan | null;
  /** Mounted only while open. */
  onClose: () => void;
}

const MIN_CLASSES = 1;
const MAX_CLASSES = 200;

function changeText(plan: Plan | null): string {
  if (!plan) return '';
  const n = plan.activeStudents;
  const who = n === 0 ? '' : n === 1 ? 'El alumno con este plan mantiene su ciclo actual tal como se pagó. ' : `Los ${n} alumnos con este plan mantienen su ciclo actual tal como se pagó. `;
  return `${who}Los cambios aplican desde el próximo pago.`;
}

/** Create or edit a plan. A plan edited or deactivated never changes a cycle already paid (the server guarantees it; the text only says so). */
export function PlanSheet({ plan, onClose }: Props) {
  const save = useSavePlan();
  const [name, setName] = useState(plan?.name ?? '');
  const [modality, setModality] = useState<Modality>(plan?.modality ?? 'PERSONALIZED');
  const [classes, setClasses] = useState(plan?.classesIncluded ?? 8);
  const [priceText, setPriceText] = useState(plan ? String(plan.priceCop) : '');
  const [active, setActive] = useState(plan?.active ?? true);
  const [nameError, setNameError] = useState<string | undefined>();

  const price = parseCop(priceText);
  const canSave = name.trim() !== '' && price !== null;

  async function submit() {
    if (price === null) return;
    setNameError(undefined);
    try {
      await save.mutateAsync({ plan, input: { name: name.trim(), classesIncluded: classes, priceCop: price, modality }, active });
      onClose();
    } catch (e) {
      if (isApiError(e) && e.is('PLAN_NAME_EXISTS')) setNameError(messageFor(e));
    }
  }

  const generalError = save.isError && !nameError ? save.error : null;

  return (
    <Sheet open onOpenChange={(o) => !o && onClose()} title={plan ? 'Editar plan' : 'Nuevo plan'}>
      <form
        noValidate
        className="flex flex-col gap-5"
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <Field label="Nombre" value={name} maxLength={100} error={nameError} onChange={(e) => setName(e.target.value)} />

        <div className="flex flex-col gap-1">
          <p className="text-sm font-semibold">Modalidad</p>
          <Segmented
            label="Modalidad"
            value={modality}
            options={[
              { value: 'SEMI_PERSONALIZED', label: 'Semipersonalizada' },
              { value: 'PERSONALIZED', label: 'Personalizada' },
            ]}
            onChange={setModality}
          />
        </div>

        <div className="flex gap-3">
          <div className="flex flex-1 flex-col gap-1">
            <p id="plan-classes-label" className="text-sm font-semibold">
              Clases por ciclo
            </p>
            <div role="group" aria-labelledby="plan-classes-label" className="flex min-h-12 items-center justify-between rounded-xl border border-line bg-white px-1">
              <button type="button" aria-label="Una clase menos" disabled={classes <= MIN_CLASSES} onClick={() => setClasses((c) => Math.max(MIN_CLASSES, c - 1))} className="size-11 text-2xl font-bold text-brand-ink disabled:opacity-40">
                −
              </button>
              <output aria-live="polite" className="font-display text-lg font-bold">
                {classes}
              </output>
              <button type="button" aria-label="Una clase más" disabled={classes >= MAX_CLASSES} onClick={() => setClasses((c) => Math.min(MAX_CLASSES, c + 1))} className="size-11 text-2xl font-bold text-brand-ink disabled:opacity-40">
                +
              </button>
            </div>
          </div>
          <div className="flex-1">
            <Field
              label="Precio (COP)"
              inputMode="numeric"
              autoComplete="off"
              value={price === null ? '' : `$ ${formatCop(price).slice(1)}`}
              onChange={(e) => setPriceText(e.target.value)}
            />
          </div>
        </div>

        {plan && (
          <Switch checked={active} onChange={setActive} label="Plan activo" hint="Un plan desactivado no se ofrece en pagos nuevos." />
        )}
        {plan && <Banner tone="amber">{changeText(plan)}</Banner>}

        {generalError !== null && (
          <Banner tone="red" role="alert">
            {messageFor(generalError)}
          </Banner>
        )}
        <Button type="submit" loading={save.isPending} disabled={!canSave} className="min-h-14">
          {plan ? 'Guardar plan' : 'Crear plan'}
        </Button>
        <Button variant="ghost" onClick={onClose}>
          Cancelar
        </Button>
      </form>
    </Sheet>
  );
}
