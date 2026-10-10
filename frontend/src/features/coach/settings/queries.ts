import { useMutation, useQuery } from '@tanstack/react-query';
import { api, call } from '../../../api/client';
import type { components } from '../../../api/schema';
import { useRefreshCoach } from '../agenda/queries';
import type { Plan } from '../students/queries';

type S = components['schemas'];
export type Window = S['WindowView'];
export type BlockSummary = S['BlockSummary'];
export type BlockInput = S['BlockInput'];
export type BlockPreview = S['BlockPreview'];
export type BlockCreated = S['BlockCreated'];
export type StudentImpact = S['StudentImpact'];
export type PlanInput = S['PlanInput'];

export function useWeekly() {
  return useQuery({ queryKey: ['coach', 'availability', 'weekly'], queryFn: () => call<Window[]>(api.GET('/api/coach/availability')) });
}

export function useUpcomingBlocks() {
  return useQuery({ queryKey: ['coach', 'availability', 'blocks'], queryFn: () => call<BlockSummary[]>(api.GET('/api/coach/availability/blocks/upcoming')) });
}

/** Replaces the windows of one weekday; classes already booked are never moved by it (the server guarantees it). */
export function useReplaceDay() {
  const refresh = useRefreshCoach();
  return useMutation({
    mutationFn: ({ dayOfWeek, windows }: { dayOfWeek: number; windows: { start: string; end: string }[] }) =>
      call<Window[]>(api.PUT('/api/coach/availability/days/{dayOfWeek}', { params: { path: { dayOfWeek } }, body: windows })),
    onSuccess: refresh,
  });
}

/** What saving the block would do. Writes nothing, so it does not refresh anything. */
export function usePreviewBlock() {
  return useMutation({ mutationFn: (body: BlockInput) => call<BlockPreview>(api.POST('/api/coach/availability/blocks/preview', { body })) });
}

export function useCreateBlock() {
  const refresh = useRefreshCoach();
  return useMutation({
    mutationFn: (body: BlockInput) => call<BlockCreated>(api.POST('/api/coach/availability/blocks', { body })),
    onSuccess: refresh,
  });
}

export function useDeleteBlock() {
  const refresh = useRefreshCoach();
  return useMutation({
    mutationFn: (id: string) => call(api.DELETE('/api/coach/availability/blocks/{id}', { params: { path: { id } } })),
    onSuccess: refresh,
  });
}

/** Creates a plan, or edits one and, when the switch changed, activates / deactivates it. The two calls are separate on the server: the data is saved first. */
export function useSavePlan() {
  const refresh = useRefreshCoach();
  return useMutation({
    mutationFn: async ({ plan, input, active }: { plan: Plan | null; input: PlanInput; active: boolean }) => {
      if (!plan) return call<Plan>(api.POST('/api/coach/plans', { body: input }));
      const saved = await call<Plan>(api.PUT('/api/coach/plans/{id}', { params: { path: { id: plan.id } }, body: input }));
      return plan.active === active ? saved : call<Plan>(api.PATCH('/api/coach/plans/{id}/active', { params: { path: { id: plan.id } }, body: { active } }));
    },
    onSuccess: refresh,
  });
}
