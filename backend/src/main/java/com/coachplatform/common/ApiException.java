package com.coachplatform.common;

import org.springframework.http.HttpStatus;

/** Base for business errors that map to an HTTP status and a stable error code for the frontend. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
