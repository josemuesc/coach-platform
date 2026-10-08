package com.coachplatform.scheduling;

import com.coachplatform.scheduling.domain.SchedulingRuleException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the pure-domain exception to HTTP; the domain itself knows nothing about HTTP. */
@RestControllerAdvice
class SchedulingExceptionHandler {

    @ExceptionHandler(SchedulingRuleException.class)
    ResponseEntity<Map<String, String>> schedulingRule(SchedulingRuleException e) {
        HttpStatus status = switch (e.code()) {
            case NO_ACTIVE_CYCLE, SLOT_TAKEN, QUOTA_EXCEEDED, CANCELLATION_WINDOW_CLOSED, CLASS_ALREADY_STARTED,
                 CLASS_NOT_STARTED, INVALID_STATE, ALREADY_MARKED, CYCLE_CLOSED, MODALITY_MISMATCH, EVENT_FULL,
                 ALREADY_BOOKED, EVENT_ALREADY_STARTED, CAPACITY_BELOW_OCCUPANCY -> HttpStatus.CONFLICT;
            case CLASS_IN_PAST, TOO_SOON, OUTSIDE_CYCLE, NOT_AVAILABLE, BLOCKED, REASON_REQUIRED,
                 CAPACITY_NOT_CONFIGURABLE, INVALID_CAPACITY -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of("code", e.code().name(), "message", e.getMessage()));
    }
}
