import * as RadixDialog from '@radix-ui/react-dialog';
import { useState } from 'react';
import { Button } from '../../ui/Button';

/** Evidence only (end-to-end build): Radix Dialog under the strict CSP. It opens, but injects a <style> element that is refused. */
export function RadixEvidence() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button onClick={() => setOpen(true)}>Abrir diálogo Radix</Button>
      <RadixDialog.Root open={open} onOpenChange={setOpen}>
        <RadixDialog.Portal>
          <RadixDialog.Overlay className="fixed inset-0 bg-ink/50" />
          <RadixDialog.Content className="fixed left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-2xl bg-white p-5">
            <RadixDialog.Title>Radix</RadixDialog.Title>
            <RadixDialog.Description>Prueba</RadixDialog.Description>
            <Button onClick={() => setOpen(false)}>Cerrar Radix</Button>
          </RadixDialog.Content>
        </RadixDialog.Portal>
      </RadixDialog.Root>
    </>
  );
}
