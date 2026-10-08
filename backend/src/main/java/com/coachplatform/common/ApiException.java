package com.coachplatform.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Base for business errors that map to an HTTP status and a stable error code for the frontend. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code) {
        this(status, code, Map.of());
    }

    /** details: extra data the client can show (e.g. the list of pending classes); must hold no secrets. */
    public ApiException(HttpStatus status, String code, Map<String, Object> details) {
        super(code);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public Map<String, Object> details() { return details; }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
