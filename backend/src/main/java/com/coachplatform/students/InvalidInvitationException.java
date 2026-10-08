package com.coachplatform.students;

import com.coachplatform.common.ApiException;
import org.springframework.http.HttpStatus;

/** Same answer for unknown, used, revoked and expired tokens: nothing is revealed about which one it was. */
public class InvalidInvitationException extends ApiException {

    public InvalidInvitationException() {
        super(HttpStatus.BAD_REQUEST, "INVALID_INVITATION");
    }
}
