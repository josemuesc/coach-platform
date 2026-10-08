package com.coachplatform.billing;

import com.coachplatform.billing.api.FutureAttendance;
import com.coachplatform.billing.api.Modality;
import com.coachplatform.common.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Renewing on the deadline day moves the classes already booked to the new cycle, but some of them are in events of
 * another modality than the new plan. The answer explains the conflict and the coach's two ways out.
 */
public class ModalityConflictOnRenewalException extends ApiException {

    public ModalityConflictOnRenewalException(Modality newModality, List<FutureAttendance> conflicting) {
        super(HttpStatus.CONFLICT, "MODALITY_CONFLICT_ON_RENEWAL", Map.of(
                "newPlanModality", newModality.name(),
                "conflictingAttendances", conflicting,
                "explanation", conflicting.size() + " class(es) already booked for today or later belong to events of another "
                        + "modality than the new plan (" + newModality + "), and they would move to the new cycle.",
                "options", List.of(
                        Map.of("code", "CANCEL_WITHOUT_PENALTY",
                                "description", "Cancel those classes as the coach (no class is deducted), then register the payment again."),
                        Map.of("code", "OVERRIDE",
                                "description", "Register the payment again with overrideModality=true and an overrideReason: "
                                        + "the classes move to the new cycle and are flagged as overrides in the agenda."))));
    }
}
