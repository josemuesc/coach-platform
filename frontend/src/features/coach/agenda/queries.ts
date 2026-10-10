import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, call } from '../../../api/client';
import type { components } from '../../../api/schema';

type S = components['schemas'];
export type WeekView = S['AgendaWeekView'];
export type AgendaDay = S['AgendaDay'];
export type AgendaRow = S['AgendaRow'];
export type BookableStudent = S['BookableStudent'];
export type BookingOptions = S['BookingOptions'];
export type BookingSlot = S['BookingSlot'];
export type AttendanceView = S['AttendanceView'];

/** Every query of the coach's area starts with 'coach'. */
const COACH = ['coach'] as const;

/**
 * Anything that changes the calendar, a plan, a class or a cycle can change what "Hoy", the agenda, the booking options, the board and every
 * student's profile show, so a write refreshes ALL of the coach's data (what is on screen refetches now; the rest is marked stale).
 */
export function useRefreshCoach() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: COACH });
}

/** The week that holds `date` (the server answers Monday..Sunday with rows, counts and which days have a schedule). */
export function useWeek(date: string) {
  return useQuery({
    queryKey: ['coach', 'agenda', 'week', date],
    queryFn: () => call<WeekView>(api.GET('/api/coach/agenda/week', { params: { query: { date } } })),
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
  });
}

export function useBookableStudents() {
  return useQuery({ queryKey: ['coach', 'booking', 'students'], queryFn: () => call<BookableStudent[]>(api.GET('/api/coach/booking/students')) });
}

export function useBookingOptions(studentId: string, date: string) {
  return useQuery({
    queryKey: ['coach', 'booking', 'options', studentId, date],
    queryFn: () => call<BookingOptions>(api.GET('/api/coach/students/{studentId}/booking-options', { params: { path: { studentId }, query: { date } } })),
    enabled: studentId !== '' && date !== '',
    staleTime: 0,
  });
}

export function useBook() {
  const refresh = useRefreshCoach();
  return useMutation({
    mutationFn: ({ studentId, startsAt, override, overrideReason }: { studentId: string; startsAt: string; override: boolean; overrideReason?: string }) =>
      call<AttendanceView>(
        api.POST('/api/coach/students/{studentId}/sessions', {
          params: { path: { studentId } },
          body: { startsAt, override, ...(override && overrideReason ? { overrideReason } : {}) },
        }),
      ),
    onSuccess: refresh,
  });
}
