package com.coachplatform.billing;

import com.coachplatform.common.ApiException;
import org.springframework.http.HttpStatus;

public class CycleNotFoundException extends ApiException {

    public CycleNotFoundException() {
        super(HttpStatus.NOT_FOUND, "CYCLE_NOT_FOUND");
    }
}
