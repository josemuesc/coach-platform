package com.coachplatform.billing;

import com.coachplatform.billing.domain.CycleRuleException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the pure-domain exception to HTTP; the domain itself knows nothing about HTTP. */
@RestControllerAdvice(basePackageClasses = BillingController.class)
class BillingExceptionHandler {

    @ExceptionHandler(CycleRuleException.class)
    ResponseEntity<Map<String, String>> cycleRule(CycleRuleException e) {
        HttpStatus status = switch (e.code()) {
            case ACTIVE_CYCLE_EXISTS, CYCLE_NOT_ACTIVE -> HttpStatus.CONFLICT;
            case INVALID_PAYMENT_DATE, INVALID_EXTENSION, EXTENSION_LIMIT_EXCEEDED, INVALID_PLAN -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of("code", e.code().name(), "message", e.getMessage()));
    }
}
