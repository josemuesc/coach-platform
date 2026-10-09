package com.coachplatform.common;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

class PasswordPolicyValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && value.codePointCount(0, value.length()) >= ValidPassword.MIN_CHARS
                && value.getBytes(StandardCharsets.UTF_8).length <= ValidPassword.MAX_BYTES;
    }
}
