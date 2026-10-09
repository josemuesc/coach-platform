package com.coachplatform.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coachplatform.billing.domain.CycleRuleException.Code;
import org.junit.jupiter.api.Test;

class PaymentReferenceRulesTest {

    private static Code codeOf(String raw) {
        try {
            PaymentReferenceRules.normalize(raw);
        } catch (CycleRuleException e) {
            return e.code();
        }
        throw new AssertionError("expected a CycleRuleException for: " + raw);
    }

    @Test
    void noReferenceOrABlankOneBecomesNone() {
        assertThat(PaymentReferenceRules.normalize(null)).isNull();
        assertThat(PaymentReferenceRules.normalize("")).isNull();
        assertThat(PaymentReferenceRules.normalize("   \t ")).isNull();
        assertThat(PaymentReferenceRules.normalize("   ")).isNull();   // non-breaking and wide spaces count as blank
    }

    @Test
    void aReferenceIsTrimmedAndOtherwiseKeptAsWritten() {
        assertThat(PaymentReferenceRules.normalize("  M12345678  ")).isEqualTo("M12345678");
        assertThat(PaymentReferenceRules.normalize("\u00a0M1\u202f")).isEqualTo("M1");   // non-breaking spaces at the edges
        assertThat(PaymentReferenceRules.normalize("Nequi comprobante #98 – Ana")).isEqualTo("Nequi comprobante #98 – Ana");
    }

    @Test
    void aHundredCharactersIsTheLimit() {
        assertThat(PaymentReferenceRules.normalize("a".repeat(100))).hasSize(100);
        assertThat(PaymentReferenceRules.normalize(" " + "a".repeat(100) + " ")).hasSize(100);   // the padding is not counted
        assertThat(codeOf("a".repeat(101))).isEqualTo(Code.INVALID_PAYMENT_REFERENCE);
    }

    @Test
    void lineBreaksAndControlCharactersAreRefusedWhereverTheyAre() {
        for (String bad : new String[] {"a\nb", "a\r\nb", "a\tb", "a\u0000b", "a\u001bb", "a\u007fb", "a\u0085b", "a b", "a b", "a​b", "a‮b"}) {
            assertThat(codeOf(bad)).as(bad.replaceAll("\\p{C}", "?")).isEqualTo(Code.INVALID_PAYMENT_REFERENCE);
        }
    }

    @Test
    void twelveOrMoreDigitsInARowAreRefusedButElevenAreFine() {
        assertThat(PaymentReferenceRules.normalize("12345678901")).isEqualTo("12345678901");
        assertThat(codeOf("123456789012")).isEqualTo(Code.PAYMENT_REFERENCE_HAS_LONG_NUMBER);
        assertThat(codeOf("Pago tarjeta 4111111111111111 ok")).isEqualTo(Code.PAYMENT_REFERENCE_HAS_LONG_NUMBER);
        assertThat(PaymentReferenceRules.normalize("12345 67890 123")).isEqualTo("12345 67890 123");   // only a run counts (a known, accepted limit)
    }

    @Test
    void digitsOfOtherScriptsCountAsDigitsToo() {
        assertThat(codeOf("١٢٣٤٥٦٧٨٩٠١٢")).isEqualTo(Code.PAYMENT_REFERENCE_HAS_LONG_NUMBER);          // Arabic-Indic
        assertThat(codeOf("１２３４５６７８９０１２")).isEqualTo(Code.PAYMENT_REFERENCE_HAS_LONG_NUMBER);   // fullwidth
    }

    @Test
    void theRefusalNeverEchoesTheValue() {
        assertThatThrownBy(() -> PaymentReferenceRules.normalize("4111111111111111")).hasMessageNotContaining("4111");
    }
}
