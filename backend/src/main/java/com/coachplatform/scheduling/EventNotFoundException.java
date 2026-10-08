package com.coachplatform.scheduling;

import com.coachplatform.common.ApiException;
import org.springframework.http.HttpStatus;

public class EventNotFoundException extends ApiException {

    public EventNotFoundException() {
        super(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
    }
}
