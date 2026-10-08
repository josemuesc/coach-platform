package com.coachplatform.scheduling;

import com.coachplatform.common.ApiException;
import org.springframework.http.HttpStatus;

/** Also used for a place that exists but belongs to another student or tenant: nothing is revealed. */
public class AttendanceNotFoundException extends ApiException {

    public AttendanceNotFoundException() {
        super(HttpStatus.NOT_FOUND, "ATTENDANCE_NOT_FOUND");
    }
}
