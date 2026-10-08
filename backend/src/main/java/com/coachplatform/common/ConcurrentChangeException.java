package com.coachplatform.common;

import org.springframework.http.HttpStatus;

/**
 * Something this request had read changed before it could lock it (e.g. an event was cancelled between the read and the
 * lock). The operation is safe to repeat from scratch: {@link ConflictRetry} does so automatically a few times; only if it
 * keeps happening does the client see this 409.
 */
public class ConcurrentChangeException extends ApiException {

    public ConcurrentChangeException() {
        super(HttpStatus.CONFLICT, "CONCURRENT_CHANGE");
    }
}
