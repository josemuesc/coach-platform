package com.coachplatform.auth;

import com.coachplatform.common.ApiException;
import org.springframework.http.HttpStatus;

public class TooManyAttemptsException extends ApiException {

    public TooManyAttemptsException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS");
    }
}
