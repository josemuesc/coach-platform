package com.coachplatform.auth;

import com.coachplatform.common.ApiException;
import org.springframework.http.HttpStatus;

public class EmailAlreadyUsedException extends ApiException {

    public EmailAlreadyUsedException() {
        super(HttpStatus.CONFLICT, "EMAIL_ALREADY_USED");
    }
}
