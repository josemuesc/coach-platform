import { useEffect, useId, useRef, type ReactNode } from 'react';

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  children: ReactNode;
}

/**
 * Both overlays are the browser's own modal <dialog> (showModal): focus is trapped and returned, the page behind is inert, Esc closes,
 * and nothing injects a <style> element, which the strict CSP (style-src 'self') would refuse. Radix Dialog was tried first and does
 * inject one (react-remove-scroll): see features/dev/RadixEvidence.tsx and the FINDING test in e2e/security.spec.ts.
 */
function Modal({ open, onOpenChange, title, description, children, className }: Props & { className: string }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      // fires for Esc and for close(): the parent's state follows
      onClose={() => onOpenChange(false)}
      // a click on the backdrop lands on the <dialog> itself (it has no padding of its own)
      onClick={(e) => {
        if (e.target === e.currentTarget) onOpenChange(false);
      }}
      className={`p-0 text-ink backdrop:bg-ink/50 ${className}`}
    >
      {open && (
        <div className="p-5">
          <h2 id={titleId} className="font-display text-xl font-bold">
            {title}
          </h2>
          {description && (
            <p id={descriptionId} className="mt-2 text-base text-ink-2">
              {description}
            </p>
          )}
          <div className="mt-4">{children}</div>
        </div>
      )}
    </dialog>
  );
}

/** A centered confirmation dialog. */
export function Dialog(props: Props) {
  return <Modal {...props} className="m-auto w-[min(92vw,420px)] rounded-2xl bg-white shadow-xl" />;
}

/** A bottom sheet (mobile first). */
export function Sheet(props: Props) {
  return <Modal {...props} className="m-0 mt-auto max-h-[90dvh] w-full max-w-none overflow-y-auto rounded-t-3xl bg-white pb-6 shadow-xl" />;
}
