package com.coachplatform.common;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", e.code());
        if (!e.details().isEmpty()) {
            body.put("details", e.details());
        }
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<Map<String, String>> badCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "INVALID_CREDENTIALS"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "VALIDATION_ERROR"));
    }
}
