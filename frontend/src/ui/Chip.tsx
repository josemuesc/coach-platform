import type { ReactNode } from 'react';

type Tone = 'green' | 'amber' | 'red' | 'neutral';

const TONES: Record<Tone, string> = {
  green: 'bg-green-bg text-green-ink',
  amber: 'bg-amber-bg text-amber-ink',
  red: 'bg-red-bg text-red-ink',
  neutral: 'bg-line text-ink',
};

export function Chip({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-bold ${TONES[tone]}`}>{children}</span>;
}
