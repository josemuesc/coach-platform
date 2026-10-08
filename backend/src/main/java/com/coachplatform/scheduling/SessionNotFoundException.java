package com.coachplatform.scheduling;

import com.coachplatform.common.ApiException;
import org.springframework.http.HttpStatus;

/** Also used for a class that exists but belongs to another student or tenant: nothing is revealed. */
public class SessionNotFoundException extends ApiException {

    public SessionNotFoundException() {
        super(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
    }
}
