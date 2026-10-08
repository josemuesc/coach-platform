package com.coachplatform.billing;

import com.coachplatform.common.ApiException;
import org.springframework.http.HttpStatus;

public class PlanNotFoundException extends ApiException {

    public PlanNotFoundException() {
        super(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND");
    }
}
