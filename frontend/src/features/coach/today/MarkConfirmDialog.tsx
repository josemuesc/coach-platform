import { messageFor } from '../../../api/errors';
import { Banner } from '../../../ui/Banner';
import { Button } from '../../../ui/Button';
import { Dialog } from '../../../ui/Overlays';
import { plural } from '../../../lib/time';
import { useMarkEvent, useMarkOne, type MarkResult } from './queries';

/** What the coach is about to mark. `switching`: the place already carries a mark, so nothing more is deducted. */
export type MarkRequest =
  | { kind: 'one'; attendanceId: string; name: string; result: MarkResult; switching: boolean }
  | { kind: 'bulk'; eventId: string; attendanceIds: string[] };

interface Props {
  request: MarkRequest | null;
  /** `done` is true when the mark was applied (the caller may then move focus: the button that opened this may be gone). */
  onClose: (done: boolean) => void;
}

/** The one confirmation every mark goes through: marking a class uses it up and cannot be undone. */
export function MarkConfirmDialog({ request, onClose }: Props) {
  const one = useMarkOne();
  const bulk = useMarkEvent();
  const busy = one.isPending || bulk.isPending;
  const error = one.error ?? bulk.error;

  function close(done: boolean) {
    one.reset();
    bulk.reset();
    onClose(done);
  }

  async function confirm() {
    if (!request) return;
    try {
      if (request.kind === 'one') await one.mutateAsync({ attendanceId: request.attendanceId, result: request.result });
      else await bulk.mutateAsync({ eventId: request.eventId, attendanceIds: request.attendanceIds });
      close(true);
    } catch {
      // the error is shown inside the dialog; the coach can retry or cancel
    }
  }

  let title = '';
  let confirmLabel = 'Sí, marcar';
  let description = 'Descuenta 1 clase del plan, tanto si asistió como si no vino. Puedes cambiar entre Asistió y No vino, pero no quitar la marca.';
  if (request?.kind === 'one') {
    title = request.result === 'ATTENDED' ? `Marcar que ${request.name} asistió` : `Marcar que ${request.name} no vino`;
    if (request.switching) description = 'Cambia el resultado de esta clase. No cambia cuántas clases se descuentan.';
  } else if (request?.kind === 'bulk') {
    title = `Marcar a ${plural(request.attendanceIds.length, 'alumno', 'alumnos')} como que asistieron`;
    confirmLabel = 'Sí, marcar a todos';
  }

  return (
    <Dialog open={request !== null} onOpenChange={(open) => !open && close(false)} title={title} description={description}>
      <div className="flex flex-col gap-3">
        {error !== null && <Banner tone="red" role="alert">{messageFor(error)}</Banner>}
        <div className="flex gap-3">
          <Button variant="secondary" className="flex-1" onClick={() => close(false)} disabled={busy}>
            Cancelar
          </Button>
          <Button className="flex-1" loading={busy} onClick={() => void confirm()}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
