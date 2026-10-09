package com.coachplatform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** How a payment was received (OTHER included) and its optional receipt reference, over HTTP. */
class PaymentReferenceTest extends ApiIntegrationTest {

    private record World(String coach, String plan, String student) {
    }

    private World world() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        return new World(coach, createPlan(coach, "Plan 8", 8, 520_000, "PERSONALIZED"), createStudent(coach, "Ana", uniqueEmail("ana")));
    }

    private ResultActions payWith(World w, String token, String method, String referenceJson) throws Exception {
        return mvc.perform(withToken(post("/api/coach/students/" + w.student() + "/payments"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planId\":\"" + w.plan() + "\",\"amountCop\":520000,\"method\":\"" + method + "\"" + referenceJson + "}"));
    }

    private String payments(World w, String token) throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/payments").param("studentId", w.student()), token)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void theReferenceIsTrimmedStoredAndShownInFullToTheOwningCoach() throws Exception {
        World w = world();
        payWith(w, w.coach(), "TRANSFER", ",\"reference\":\"  Comprobante 884411  \"").andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("Comprobante 884411"));
        String list = payments(w, w.coach());
        assertThat(JsonPath.<List<String>>read(list, "$[*].reference")).containsExactly("Comprobante 884411");
        assertThat(JsonPath.<List<String>>read(list, "$[*].method")).containsExactly("TRANSFER");
    }

    @Test
    void noReferenceOrABlankOneIsStoredAsNothing() throws Exception {
        World w = world();
        payWith(w, w.coach(), "CASH", ",\"reference\":\"   \"").andExpect(status().isCreated()).andExpect(jsonPath("$.reference").doesNotExist());
        assertThat(JsonPath.<List<Object>>read(payments(w, w.coach()), "$[*].reference")).containsExactly((Object) null);
        assertThat(jdbc.queryForObject("select count(*) from payment where student_id = ? and reference is null", Integer.class,
                java.util.UUID.fromString(w.student()))).isEqualTo(1);
    }

    @Test
    void otherIsAValidWayOfReceivingAPayment() throws Exception {
        World w = world();
        payWith(w, w.coach(), "OTHER", ",\"reference\":\"datáfono del gimnasio\"").andExpect(status().isCreated());
        assertThat(JsonPath.<List<String>>read(payments(w, w.coach()), "$[*].method")).containsExactly("OTHER");
    }

    @Test
    void anUnknownMethodIsStillRefused() throws Exception {
        World w = world();
        payWith(w, w.coach(), "BITCOIN", "").andExpect(status().isBadRequest());
    }

    @Test
    void aLongNumberIsRefusedWithAClearMessageAndNothingIsRecorded() throws Exception {
        World w = world();
        payWith(w, w.coach(), "NEQUI", ",\"reference\":\"tarjeta 4111111111111111\"").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFERENCE_HAS_LONG_NUMBER"));
        assertThat(JsonPath.<List<Object>>read(payments(w, w.coach()), "$")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from cycle where student_id = ?", Integer.class, java.util.UUID.fromString(w.student()))).isZero();
        // the 11-digit receipt is fine, and the student can still pay afterwards (nothing was half-done)
        payWith(w, w.coach(), "NEQUI", ",\"reference\":\"12345678901\"").andExpect(status().isCreated());
    }

    @Test
    void lineBreaksControlCharactersAndTooLongTextAreRefused() throws Exception {
        World w = world();
        for (String bad : new String[] {"a\\nb", "a\\r\\nb", "a\\tb", "a\\u0000b", "a\\u2028b"}) {
            payWith(w, w.coach(), "NEQUI", ",\"reference\":\"" + bad + "\"").andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_REFERENCE"));
        }
        payWith(w, w.coach(), "NEQUI", ",\"reference\":\"" + "a".repeat(101) + "\"").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_REFERENCE"));
        payWith(w, w.coach(), "NEQUI", ",\"reference\":\"" + "a".repeat(301) + "\"").andExpect(status().isBadRequest());   // bound on the raw body
        assertThat(JsonPath.<List<Object>>read(payments(w, w.coach()), "$")).isEmpty();
    }

    @Test
    void anotherCoachCannotReadTheReference() throws Exception {
        World w = world();
        payWith(w, w.coach(), "NEQUI", ",\"reference\":\"SECRETO-77\"").andExpect(status().isCreated());
        String other = registerCoach(uniqueEmail("otro"));
        mvc.perform(withToken(get("/api/coach/payments").param("studentId", w.student()), other)).andExpect(status().isNotFound());
    }
}
