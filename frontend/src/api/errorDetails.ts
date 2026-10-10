import type { components } from './schema';
import { isApiError } from './errors';

/**
 * The `details` of the two 409 answers of a renewal (documented in docs/error-codes.md, shape fixed by BulkCancelTest on the back end).
 * OpenAPI cannot describe them, so they are typed here and checked at run time before use. The server's English `explanation` /
 * `description` texts are never shown: the screens word everything themselves.
 */
export type Modality = components['schemas']['PlanSummary']['modality'];

export interface BookedClass {
  attendanceId: string;
  sessionId: string;
  studentId: string;
  startsAt: string;
  endsAt: string;
  eventModality: Modality;
}

export interface PendingSessionsDetails {
  pendingSessions: BookedClass[];
}

export interface ModalityConflictDetails {
  newPlanModality: Modality;
  conflictingAttendances: BookedClass[];
  options: { code: 'CANCEL_WITHOUT_PENALTY' | 'OVERRIDE' }[];
}

function isBookedClass(value: unknown): value is BookedClass {
  const v = value as Partial<BookedClass> | null;
  return !!v && typeof v.attendanceId === 'string' && typeof v.startsAt === 'string' && typeof v.eventModality === 'string';
}

/** The classes that block a renewal because they started and were never marked; null when the error is something else. */
export function pendingSessionsOf(error: unknown): BookedClass[] | null {
  if (!isApiError(error) || !error.is('PENDING_SESSIONS_TO_MARK')) return null;
  const list = (error.details as Partial<PendingSessionsDetails> | undefined)?.pendingSessions;
  return Array.isArray(list) && list.every(isBookedClass) ? list : null;
}

/** The booked classes of another modality than the new plan, and the way out the server offers; null when the error is something else. */
export function modalityConflictOf(error: unknown): ModalityConflictDetails | null {
  if (!isApiError(error) || !error.is('MODALITY_CONFLICT_ON_RENEWAL')) return null;
  const d = error.details as Partial<ModalityConflictDetails> | undefined;
  if (!d || typeof d.newPlanModality !== 'string' || !Array.isArray(d.conflictingAttendances) || !d.conflictingAttendances.every(isBookedClass)) return null;
  return { newPlanModality: d.newPlanModality, conflictingAttendances: d.conflictingAttendances, options: Array.isArray(d.options) ? d.options : [] };
}
