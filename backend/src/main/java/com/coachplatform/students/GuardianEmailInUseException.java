package com.coachplatform.students;

import com.coachplatform.common.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The guardian's email is the login of each minor, and one email is one account on the whole platform. This replaces the
 * generic "email exists" errors when the student is a minor, with a message the coach can act on.
 */
public class GuardianEmailInUseException extends ApiException {

    static final String MESSAGE = "Este correo ya tiene una cuenta. Pide a tu entrenador que registre un correo distinto para cada alumno.";

    public GuardianEmailInUseException() {
        super(HttpStatus.CONFLICT, "GUARDIAN_EMAIL_IN_USE", Map.of("message", MESSAGE));
    }
}
