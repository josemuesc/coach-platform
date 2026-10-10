import { describe, expect, it } from 'vitest';
import { ApiError } from './errors';
import { blockAffectedOf, modalityConflictOf, pendingSessionsOf } from './errorDetails';

const cls = { attendanceId: 'a1', sessionId: 's1', studentId: 'st', startsAt: '2026-11-06T19:00:00Z', endsAt: '2026-11-06T20:00:00Z', eventModality: 'PERSONALIZED' };

describe('details of the renewal errors', () => {
  it('reads the pending sessions only from their own error', () => {
    expect(pendingSessionsOf(new ApiError(409, 'PENDING_SESSIONS_TO_MARK', { pendingSessions: [cls] }))).toEqual([cls]);
    expect(pendingSessionsOf(new ApiError(409, 'ACTIVE_CYCLE_EXISTS', { pendingSessions: [cls] }))).toBeNull();
    expect(pendingSessionsOf(new ApiError(409, 'PENDING_SESSIONS_TO_MARK', { pendingSessions: [{ attendanceId: 1 }] }))).toBeNull();
    expect(pendingSessionsOf(new Error('x'))).toBeNull();
  });

  it('reads the modality conflict and refuses a malformed one', () => {
    const details = { newPlanModality: 'SEMI_PERSONALIZED', conflictingAttendances: [cls], explanation: 'x', options: [{ code: 'CANCEL_WITHOUT_PENALTY', description: 'x' }, { code: 'OVERRIDE', description: 'y' }] };
    const read = modalityConflictOf(new ApiError(409, 'MODALITY_CONFLICT_ON_RENEWAL', details));
    expect(read?.newPlanModality).toBe('SEMI_PERSONALIZED');
    expect(read?.conflictingAttendances).toEqual([cls]);
    expect(read?.options.map((o) => o.code)).toEqual(['CANCEL_WITHOUT_PENALTY', 'OVERRIDE']);
    expect(modalityConflictOf(new ApiError(409, 'MODALITY_CONFLICT_ON_RENEWAL', { conflictingAttendances: 'no' }))).toBeNull();
    expect(modalityConflictOf(new ApiError(409, 'MODALITY_CONFLICT_ON_RENEWAL'))).toBeNull();
  });
});

describe('details of BLOCK_AFFECTED_CHANGED', () => {
  const affected = { attendanceId: 'a1', studentId: 'st', studentName: 'Ana Gómez', minor: false, eventId: 'e1', startsAt: '2026-10-16T15:00:00Z', endsAt: '2026-10-16T16:00:00Z', modality: 'SEMI_PERSONALIZED' };
  const preview = { affected: [affected], releasedCount: 1, markedUntouched: 0, pendingUntouched: 2 };

  it('reads the current list from its own error', () => {
    expect(blockAffectedOf(new ApiError(409, 'BLOCK_AFFECTED_CHANGED', { preview }))).toEqual(preview);
    expect(blockAffectedOf(new ApiError(409, 'CONCURRENT_CHANGE', { preview }))).toBeNull();
    expect(blockAffectedOf(new Error('x'))).toBeNull();
  });

  it('refuses a malformed answer instead of showing a wrong list', () => {
    expect(blockAffectedOf(new ApiError(409, 'BLOCK_AFFECTED_CHANGED'))).toBeNull();
    expect(blockAffectedOf(new ApiError(409, 'BLOCK_AFFECTED_CHANGED', { preview: { ...preview, releasedCount: '1' } }))).toBeNull();
    expect(blockAffectedOf(new ApiError(409, 'BLOCK_AFFECTED_CHANGED', { preview: { ...preview, affected: [{ attendanceId: 1 }] } }))).toBeNull();
  });
});
