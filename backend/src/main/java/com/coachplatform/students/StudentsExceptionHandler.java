package com.coachplatform.students;

import com.coachplatform.students.domain.StudentRuleException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the pure-domain exception of the students module to HTTP; the domain itself knows nothing about HTTP. */
@RestControllerAdvice
class StudentsExceptionHandler {

    @ExceptionHandler(StudentRuleException.class)
    ResponseEntity<Map<String, String>> studentRule(StudentRuleException e) {
        HttpStatus status = switch (e.code()) {
            case INVALID_BIRTH_DATE, GUARDIAN_REQUIRED, GUARDIAN_INCOMPLETE, DATA_CONSENT_REQUIRED, CONSENT_NOT_APPLICABLE ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case AUDIENCE_CHANGE_BLOCKED, CONSENT_VERSION_MISMATCH, CONSENT_ALREADY_ACTIVE, CONSENT_NOT_ACTIVE, ANONYMIZATION_PENDING ->
                    HttpStatus.CONFLICT;
            case GUARDIAN_CONSENT_NOT_ALLOWED, ADULT_CONSENT_NOT_ALLOWED -> HttpStatus.FORBIDDEN;
        };
        return ResponseEntity.status(status).body(Map.of("code", e.code().name(), "message", e.getMessage()));
    }
}
