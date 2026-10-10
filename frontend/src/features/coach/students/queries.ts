import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, call } from '../../../api/client';
import type { components } from '../../../api/schema';
import { BOARD_KEY, PLANS_KEY, profileKey, sheetKey } from './keys';

type S = components['schemas'];
export type Board = S['StudentBoard'];
export type BoardRow = S['BoardRow'];
export type Profile = S['StudentProfile'];
export type Plan = S['PlanSummary'];
export type RegisterPayment = S['RegisterPaymentCommand'];
export type PaymentRegistered = S['PaymentRegistered'];
export type Sheet = S['SheetView'];
export type ClassRow = S['ClassRow'];

export function useBoard() {
  return useQuery({ queryKey: BOARD_KEY, queryFn: () => call<Board>(api.GET('/api/coach/students/board')) });
}

export function useProfile(studentId: string) {
  return useQuery({
    queryKey: profileKey(studentId),
    queryFn: () => call<Profile>(api.GET('/api/coach/students/{id}/profile', { params: { path: { id: studentId } } })),
  });
}

export function usePlans(enabled = true) {
  return useQuery({ queryKey: PLANS_KEY, queryFn: () => call<Plan[]>(api.GET('/api/coach/plans')), enabled });
}

export function useSheet(studentId: string, enabled: boolean) {
  return useQuery({
    queryKey: sheetKey(studentId),
    queryFn: () => call<Sheet>(api.GET('/api/coach/students/{id}/sheet', { params: { path: { id: studentId } } })),
    enabled,
    gcTime: 0,
  });
}

/** A write about a student, a cycle or a class can change "Hoy", the agenda, the booking options, the board and every profile: refresh all of the coach's data. */
function useRefreshStudents() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: ['coach'] });
}

export function useRegisterPayment(studentId: string) {
  const refresh = useRefreshStudents();
  return useMutation({
    mutationFn: (body: RegisterPayment) => call<PaymentRegistered>(api.POST('/api/coach/students/{studentId}/payments', { params: { path: { studentId } }, body })),
    onSuccess: refresh,
  });
}

export function useCancelAttendances(studentId: string) {
  const refresh = useRefreshStudents();
  return useMutation({
    mutationFn: (body: { attendanceIds: string[]; reason: string }) =>
      call(api.POST('/api/coach/students/{studentId}/attendances/cancel', { params: { path: { studentId } }, body })),
    onSuccess: refresh,
  });
}

export function useExtendCycle() {
  const refresh = useRefreshStudents();
  return useMutation({
    mutationFn: ({ cycleId, newEndDate, reason }: { cycleId: string; newEndDate: string; reason: string }) =>
      call(api.POST('/api/coach/cycles/{cycleId}/extend', { params: { path: { cycleId } }, body: { newEndDate, reason } })),
    onSuccess: refresh,
  });
}

export function useIssueReset(studentId: string) {
  const refresh = useRefreshStudents();
  return useMutation({
    mutationFn: () => call(api.POST('/api/coach/students/{id}/password-reset', { params: { path: { id: studentId } } })),
    onSuccess: refresh,
  });
}

export function useRevokeReset(studentId: string) {
  const refresh = useRefreshStudents();
  return useMutation({
    mutationFn: () => call(api.DELETE('/api/coach/students/{id}/password-reset', { params: { path: { id: studentId } } })),
    onSuccess: refresh,
  });
}

export function useReissueInvitation(studentId: string) {
  const refresh = useRefreshStudents();
  return useMutation({
    mutationFn: () => call(api.POST('/api/coach/students/{id}/invitations', { params: { path: { id: studentId } } })),
    onSuccess: refresh,
  });
}

export type NewStudent = S['StudentInput'];

export function useCreateStudent() {
  const refresh = useRefreshStudents();
  return useMutation({
    mutationFn: (body: NewStudent) => call(api.POST('/api/coach/students', { body })),
    onSuccess: refresh,
  });
}
