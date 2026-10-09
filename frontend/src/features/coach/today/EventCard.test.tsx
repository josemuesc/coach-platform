import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { EventCard } from './EventCard';
import type { Attendee, EventView } from './queries';

function attendee(over: Partial<Attendee> = {}): Attendee {
  return {
    attendanceId: 'a1', studentId: 's1', studentName: 'Ana Gómez', status: 'SCHEDULED', override: false, overrideReason: null,
    studentConfirmed: false, confirmationMethod: null, confirmedAt: null, onlyMarkedByCoach: false, canMark: true, minor: false, ...over,
  };
}

function event(over: Partial<EventView> = {}): EventView {
  return {
    id: 'e1', startsAt: '2026-10-09T15:00:00Z', endsAt: '2026-10-09T16:00:00Z', modality: 'PERSONALIZED', capacity: 1, occupied: 1, freeSeats: 0,
    status: 'SCHEDULED', phase: 'NOW', canShowQr: true, attendees: [attendee()], ...over,
  };
}

function show(e: EventView, onMark = vi.fn()) {
  render(
    <MemoryRouter>
      <ol>
        <EventCard event={e} nowMs={Date.parse('2026-10-09T15:10:00Z')} onMark={onMark} onOpenSheet={vi.fn()} />
      </ol>
    </MemoryRouter>,
  );
  return onMark;
}

describe('EventCard obeys the flags the server computed', () => {
  it('shows the mark buttons only when canMark is true', async () => {
    const onMark = show(event());
    await userEvent.click(screen.getByRole('button', { name: 'Marcar que Ana Gómez asistió' }));
    expect(onMark).toHaveBeenCalledWith({ kind: 'one', attendanceId: 'a1', name: 'Ana Gómez', result: 'ATTENDED', switching: false });
  });

  it('has no mark buttons when canMark is false, whatever the phase says', () => {
    show(event({ attendees: [attendee({ canMark: false })] }));
    expect(screen.queryByRole('button', { name: /Marcar que/ })).toBeNull();
  });

  it('disables the QR button with "Aún no disponible" when canShowQr is false, and links to it when true', () => {
    show(event({ canShowQr: false }));
    expect(screen.getByRole('button', { name: /Aún no disponible/ })).toBeDisabled();
    expect(screen.queryByRole('link', { name: 'Mostrar QR a los alumnos' })).toBeNull();
  });

  it('links to the QR screen of that event when canShowQr is true', () => {
    show(event());
    expect(screen.getByRole('link', { name: 'Mostrar QR a los alumnos' })).toHaveAttribute('href', '/coach/qr/e1');
  });

  it('draws the card from the server phase: upcoming has the countdown and no buttons', () => {
    show(event({ phase: 'UPCOMING', startsAt: '2026-10-09T16:46:00Z', endsAt: '2026-10-09T17:46:00Z' }));
    expect(screen.getByText('en 1 h 36 min')).toBeInTheDocument();
    expect(screen.queryByText('EN CURSO')).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('flags a minor and shows how the student confirmed', () => {
    show(event({ attendees: [attendee({ minor: true, studentConfirmed: true, confirmationMethod: 'QR', confirmedAt: '2026-10-09T15:02:00Z' })] }));
    expect(screen.getByText('Menor')).toBeInTheDocument();
    expect(screen.getByText(/^QR 10:02/)).toBeInTheDocument();
  });

  it('a past class with something left to mark offers to mark it', () => {
    show(event({ phase: 'PAST' }));
    expect(screen.getByText('Por marcar')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Marcar' })).toBeInTheDocument();
  });
});
