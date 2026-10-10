package com.coachplatform.scheduling.api;

import java.util.List;

/** The places that were cancelled (CANCELLED_BY_COACH, nobody loses a class), in the order they were asked for. */
public record CancelAttendancesResult(List<AttendanceView> cancelled) {
}
