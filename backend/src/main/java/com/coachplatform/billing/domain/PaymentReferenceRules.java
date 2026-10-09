package com.coachplatform.billing.domain;

import static com.coachplatform.billing.domain.CycleRuleException.Code.INVALID_PAYMENT_REFERENCE;
import static com.coachplatform.billing.domain.CycleRuleException.Code.PAYMENT_REFERENCE_HAS_LONG_NUMBER;

import java.util.regex.Pattern;

/**
 * The optional reference of a payment (the receipt number of the Nequi / transfer). It is kept forever (the payment is immutable), so
 * what may be written there is strict: one line of plain text, up to 100 characters, and never a sequence of 12 or more digits in a row,
 * which could be a card or account number. Pure rules: no clock, no framework.
 */
public final class PaymentReferenceRules {

    public static final int MAX_LENGTH = 100;
    /** 12+ digits in a row, of any script (Arabic-Indic, fullwidth... also count as digits). */
    /** Leading / trailing blanks of any kind: String.strip() leaves the non-breaking space (U+00A0, U+202F...) alone. */
    private static final Pattern EDGE_SPACE = Pattern.compile("^[\\p{Z}\\s]+|[\\p{Z}\\s]+$");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\p{Nd}{12,}");

    private PaymentReferenceRules() {
    }

    /** @return the reference trimmed, or null when there is none (null or blank); throws {@link CycleRuleException} when it may not be stored */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String reference = EDGE_SPACE.matcher(raw).replaceAll("");
        if (reference.isEmpty()) {
            return null;
        }
        if (reference.length() > MAX_LENGTH || reference.codePoints().anyMatch(PaymentReferenceRules::isControlOrLineBreak)) {
            throw new CycleRuleException(INVALID_PAYMENT_REFERENCE, "The payment reference must be one line of up to " + MAX_LENGTH + " characters");
        }
        if (LONG_NUMBER.matcher(reference).find()) {
            throw new CycleRuleException(PAYMENT_REFERENCE_HAS_LONG_NUMBER, "The payment reference must not contain a card or account number");
        }
        return reference;
    }

    private static boolean isControlOrLineBreak(int cp) {
        return Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || Character.getType(cp) == Character.FORMAT;
    }
}
