package com.coachplatform.common;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * THE password policy, used by every endpoint that sets a password (coach registration, accepting an invitation, changing it and
 * resetting it): at least 10 characters and at most 72 BYTES in UTF-8 (BCrypt ignores or rejects the rest). A missing value is
 * invalid. Combine with nothing else: this constraint alone decides.
 */
@Documented
@Constraint(validatedBy = PasswordPolicyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    int MIN_CHARS = 10;
    int MAX_BYTES = 72;

    String message() default "invalid password";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
