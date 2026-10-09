import { EmptyState } from '../../ui/States';

/** A screen that is on the plan but not built yet (the order of the screens is in CLAUDE.md, "Fase 4"). */
export function ComingSoon({ title }: { title: string }) {
  return (
    <EmptyState title={title}>
      <p className="text-ink-2">Esta pantalla todavía no está lista.</p>
    </EmptyState>
  );
}
