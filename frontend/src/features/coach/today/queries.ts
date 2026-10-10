import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, call } from '../../../api/client';
import type { components } from '../../../api/schema';
import { BOARD_KEY, PROFILE_KEYS } from '../students/keys';

export type TodayView = components['schemas']['TodayView'];
export type EventView = components['schemas']['EventView'];
export type Attendee = components['schemas']['AttendeeView'];
export type PendingAttendance = components['schemas']['AttendanceView'];
export type BillingOverview = components['schemas']['StudentBillingOverview'];
export type MarkResult = 'ATTENDED' | 'NO_SHOW';

export const TODAY_KEY = ['coach', 'today'] as const;
const PENDING_KEY = ['coach', 'pending'] as const;
const OVERVIEW_KEY = ['coach', 'overview'] as const;

/** The server decides each class's phase; polling keeps it current while the screen is open. */
export function useToday(refetchMs = 30_000) {
  return useQuery({ queryKey: TODAY_KEY, queryFn: () => call<TodayView>(api.GET('/api/coach/today')), refetchInterval: refetchMs });
}

export function usePending() {
  return useQuery({ queryKey: PENDING_KEY, queryFn: () => call<PendingAttendance[]>(api.GET('/api/coach/attendances/pending')), refetchInterval: 30_000 });
}

export function useOverview() {
  return useQuery({ queryKey: OVERVIEW_KEY, queryFn: () => call<BillingOverview[]>(api.GET('/api/coach/billing/overview')) });
}

function useRefreshAfterWrite() {
  const queryClient = useQueryClient();
  // marking a class changes the board (a student may run out of classes) and every profile: refresh them too
  return () => Promise.all([TODAY_KEY, PENDING_KEY, OVERVIEW_KEY, BOARD_KEY, PROFILE_KEYS].map((queryKey) => queryClient.invalidateQueries({ queryKey })));
}

export function useMarkOne() {
  const refresh = useRefreshAfterWrite();
  return useMutation({
    mutationFn: ({ attendanceId, result }: { attendanceId: string; result: MarkResult }) =>
      call(api.POST('/api/coach/attendances/{id}/mark', { params: { path: { id: attendanceId } }, body: { result } })),
    onSuccess: refresh,
  });
}

export function useMarkEvent() {
  const refresh = useRefreshAfterWrite();
  return useMutation({
    mutationFn: ({ eventId, attendanceIds }: { eventId: string; attendanceIds: string[] }) =>
      call(
        api.POST('/api/coach/events/{id}/mark', {
          params: { path: { id: eventId } },
          body: { marks: attendanceIds.map((attendanceId) => ({ attendanceId, status: 'ATTENDED' as const })) },
        }),
      ),
    onSuccess: refresh,
  });
}

export function useCancelEvent() {
  const refresh = useRefreshAfterWrite();
  return useMutation({
    mutationFn: ({ eventId, reason }: { eventId: string; reason: string }) =>
      call(api.POST('/api/coach/events/{id}/cancel', { params: { path: { id: eventId } }, body: { reason } })),
    onSuccess: refresh,
  });
}

/** Re-renders every `ms` so "en 1 h 36 min" stays honest. */
export function useNow(ms = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), ms);
    return () => window.clearInterval(timer);
  }, [ms]);
  return now;
}
