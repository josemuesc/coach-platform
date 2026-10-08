package com.coachplatform.billing;

import com.coachplatform.billing.api.PendingSession;
import com.coachplatform.common.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Renewal is blocked until the coach marks the classes that already started; the answer lists them. */
public class PendingSessionsException extends ApiException {

    public PendingSessionsException(List<PendingSession> pending) {
        super(HttpStatus.CONFLICT, "PENDING_SESSIONS_TO_MARK", Map.of("pendingSessions", pending));
    }
}
