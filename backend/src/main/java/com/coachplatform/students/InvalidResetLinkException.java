package com.coachplatform.students;

import com.coachplatform.common.ApiException;
import org.springframework.http.HttpStatus;

/** Same answer for unknown, used, revoked and expired links: nothing is revealed about which one it was. */
public class InvalidResetLinkException extends ApiException {

    public InvalidResetLinkException() {
        super(HttpStatus.BAD_REQUEST, "INVALID_RESET_LINK");
    }
}
