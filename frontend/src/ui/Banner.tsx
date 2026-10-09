import type { ReactNode } from 'react';

type Tone = 'amber' | 'green' | 'red';

const TONES: Record<Tone, string> = {
  amber: 'bg-amber-bg text-amber-ink',
  green: 'bg-green-bg text-green-ink',
  red: 'bg-red-bg text-red-ink',
};

export function Banner({ tone, children, role }: { tone: Tone; children: ReactNode; role?: 'alert' | 'status' }) {
  return (
    <div role={role} className={`rounded-xl px-4 py-3 text-sm font-semibold ${TONES[tone]}`}>
      {children}
    </div>
  );
}
