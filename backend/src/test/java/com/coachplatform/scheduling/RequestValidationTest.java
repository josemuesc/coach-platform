package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A mandatory field that is absent is a 400 - never a silent 0 / false. A missing OPTIONAL field takes its explicit default.
 * (The mapper is strict: Jackson 3's check for primitives is NOT turned off globally.)
 */
class RequestValidationTest extends SchedulingApiTest {

    private static final String DAY = "2026-10-12";
    private static final String TEN = at(DAY, "10:00");

    @Autowired Environment environment;

    private ResultActions postJson(String path, String token, String body) throws Exception {
        return mvc.perform(withToken(post(path), token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putJson(String path, String token, String body) throws Exception {
        return mvc.perform(withToken(put(path), token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ================================================================= the strict mapper stays strict

    @Test
    void jacksonIsNotRelaxedGlobally() {
        assertThat(environment.getProperty("spring.jackson.deserialization.fail-on-null-for-primitives"))
                .as("no global switch that lets an absent primitive become 0/false").isNull();
    }

    // ================================================================= payments

    @Test
    void aPaymentWithoutAmountPlanOrMethodIsRejected() throws Exception {
        var coach = newCoach(8);
        var ana = newStudent(coach, "Ana");
        String path = "/api/coach/students/" + ana.id() + "/payments";
        String plan = coach.personalizedPlan();

        postJson(path, coach.token(), "{\"planId\":\"" + plan + "\",\"method\":\"NEQUI\"}").andExpect(status().isBadRequest());                       // no amountCop
        postJson(path, coach.token(), "{\"method\":\"NEQUI\",\"amountCop\":520000}").andExpect(status().isBadRequest());                               // no planId
        postJson(path, coach.token(), "{\"planId\":\"" + plan + "\",\"amountCop\":520000}").andExpect(status().isBadRequest());                        // no method
        postJson(path, coach.token(), "{\"planId\":\"" + plan + "\",\"method\":\"NEQUI\",\"amountCop\":0}").andExpect(status().isBadRequest());       // not positive
        postJson(path, coach.token(), "{\"planId\":\"" + plan + "\",\"method\":\"NEQUI\",\"amountCop\":-5}").andExpect(status().isBadRequest());
        postJson(path, coach.token(), "{\"planId\":\"" + plan + "\",\"method\":\"NEQUI\",\"amountCop\":null}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from payment where student_id = ?", Integer.class, java.util.UUID.fromString(ana.id()))).isZero();

        postJson(path, coach.token(), "{\"planId\":\"" + plan + "\",\"method\":\"NEQUI\",\"amountCop\":520000}").andExpect(status().isCreated());
    }

    @Test
    void theOptionalPaymentFlagsDefaultToFalseWhenOmitted() throws Exception {
        var coach = newCoach(8);
        var ana = newStudent(coach, "Ana");
        // paidOn and overrideModality are optional: omitting them is fine
        postJson("/api/coach/students/" + ana.id() + "/payments", coach.token(),
                "{\"planId\":\"" + coach.personalizedPlan() + "\",\"method\":\"CASH\",\"amountCop\":520000}").andExpect(status().isCreated());
    }

    // ================================================================= plans

    @Test
    void aPlanWithoutClassesIncludedOrModalityOrPriceIsRejected() throws Exception {
        var coach = newCoach(8);
        String create = "/api/coach/plans";

        postJson(create, coach.token(), "{\"name\":\"a\",\"priceCop\":1,\"modality\":\"PERSONALIZED\"}").andExpect(status().isBadRequest());           // no classesIncluded
        postJson(create, coach.token(), "{\"name\":\"b\",\"classesIncluded\":8,\"priceCop\":1}").andExpect(status().isBadRequest());                    // no modality
        postJson(create, coach.token(), "{\"name\":\"c\",\"priceCop\":1}").andExpect(status().isBadRequest());                                          // neither
        postJson(create, coach.token(), "{\"name\":\"d\",\"classesIncluded\":8,\"modality\":\"PERSONALIZED\"}").andExpect(status().isBadRequest());     // no priceCop
        postJson(create, coach.token(), "{\"classesIncluded\":8,\"priceCop\":1,\"modality\":\"PERSONALIZED\"}").andExpect(status().isBadRequest());     // no name
        postJson(create, coach.token(), "{\"name\":\"e\",\"classesIncluded\":null,\"priceCop\":1,\"modality\":\"PERSONALIZED\"}").andExpect(status().isBadRequest());
        postJson(create, coach.token(), "{\"name\":\"f\",\"classesIncluded\":0,\"priceCop\":1,\"modality\":\"PERSONALIZED\"}").andExpect(status().isBadRequest());
        postJson(create, coach.token(), "{\"name\":\"g\",\"classesIncluded\":8,\"priceCop\":1,\"modality\":\"GRUPAL\"}").andExpect(status().isBadRequest());

        // updating a plan is held to the same standard
        putJson("/api/coach/plans/" + coach.personalizedPlan(), coach.token(), "{\"name\":\"x\",\"priceCop\":1,\"modality\":\"PERSONALIZED\"}").andExpect(status().isBadRequest());
        putJson("/api/coach/plans/" + coach.personalizedPlan(), coach.token(), "{\"name\":\"x\",\"classesIncluded\":8,\"modality\":\"PERSONALIZED\"}").andExpect(status().isBadRequest());

        postJson(create, coach.token(), "{\"name\":\"ok\",\"classesIncluded\":8,\"priceCop\":0,\"modality\":\"SEMI_PERSONALIZED\"}").andExpect(status().isCreated());
    }

    @Test
    void togglingAPlanNeedsTheActiveFlag() throws Exception {
        var coach = newCoach(8);
        mvc.perform(withToken(patch("/api/coach/plans/" + coach.personalizedPlan() + "/active"), coach.token())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(withToken(patch("/api/coach/plans/" + coach.personalizedPlan() + "/active"), coach.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());
    }

    // ================================================================= students

    @Test
    void aStudentWithoutEmailOrNameIsRejected() throws Exception {
        var coach = newCoach(8);
        postJson("/api/coach/students", coach.token(), "{\"fullName\":\"Sin Email\"}").andExpect(status().isBadRequest());
        postJson("/api/coach/students", coach.token(), "{\"fullName\":\"Sin Email\",\"email\":null}").andExpect(status().isBadRequest());
        postJson("/api/coach/students", coach.token(), "{\"fullName\":\"Sin Email\",\"email\":\"  \"}").andExpect(status().isBadRequest());
        postJson("/api/coach/students", coach.token(), "{\"fullName\":\"Mal Email\",\"email\":\"no-es-un-email\"}").andExpect(status().isBadRequest());
        postJson("/api/coach/students", coach.token(), "{\"email\":\"sin.nombre@test.co\"}").andExpect(status().isBadRequest());
        postJson("/api/coach/students", coach.token(), "{\"fullName\":\"Ok\",\"email\":\"ok@test.co\"}").andExpect(status().isCreated());
    }

    @Test
    void updatingAStudentWithoutTheDataIsA400NotA500() throws Exception {
        var coach = newCoach(8);
        var ana = newStudent(coach, "Ana");
        putJson("/api/coach/students/" + ana.id(), coach.token(), "{}").andExpect(status().isBadRequest());
        putJson("/api/coach/students/" + ana.id(), coach.token(), "{\"active\":false}").andExpect(status().isBadRequest());
        putJson("/api/coach/students/" + ana.id(), coach.token(), "{\"data\":{\"fullName\":\"Sin email\"}}").andExpect(status().isBadRequest());
        putJson("/api/coach/students/" + ana.id(), coach.token(), "{\"data\":{\"fullName\":\"Ana R\",\"email\":\"" + ana.email() + "\"}}").andExpect(status().isOk());
    }

    // ================================================================= settings: a PUT replaces everything, so every field is required

    @Test
    void theSettingsRequireEveryField() throws Exception {
        var coach = newCoach(8);
        List<String> fields = List.of("cancelWindowHours", "classDurationMinutes", "expiringSoonDays", "expiringSoonClasses",
                "maxExtensionDays", "defaultGroupCapacity");
        java.util.Map<String, Integer> valid = java.util.Map.of("cancelWindowHours", 2, "classDurationMinutes", 60, "expiringSoonDays", 5,
                "expiringSoonClasses", 1, "maxExtensionDays", 60, "defaultGroupCapacity", 4);
        for (String missing : fields) {
            StringBuilder body = new StringBuilder("{");
            for (String f : fields) {
                if (!f.equals(missing)) {
                    body.append(body.length() > 1 ? "," : "").append('"').append(f).append("\":").append(valid.get(f));
                }
            }
            putJson("/api/coach/settings", coach.token(), body.append("}").toString()).andExpect(status().isBadRequest());
        }
        putJson("/api/coach/settings", coach.token(), "{}").andExpect(status().isBadRequest());
    }

    // ================================================================= scheduling

    @Test
    void anEventCapacityChangeAndAnAvailabilityWindowNeedTheirNumbers() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        String event = eventId(studentBooked(ana, TEN));

        putJson("/api/coach/events/" + event + "/capacity", coach.token(), "{}").andExpect(status().isBadRequest());
        putJson("/api/coach/events/" + event + "/capacity", coach.token(), "{\"capacity\":null}").andExpect(status().isBadRequest());
        putJson("/api/coach/events/" + event + "/capacity", coach.token(), "{\"capacity\":5}").andExpect(status().isOk());

        putJson("/api/coach/availability", coach.token(), "[{\"start\":\"06:00\",\"end\":\"09:00\"}]").andExpect(status().isBadRequest());               // no dayOfWeek
        putJson("/api/coach/availability", coach.token(), "[{\"dayOfWeek\":1,\"end\":\"09:00\"}]").andExpect(status().isBadRequest());                 // no start
    }

    @Test
    void bookingRequestsNeedTheirStartTime() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        postJson("/api/coach/students/" + ana.id() + "/sessions", coach.token(), "{}").andExpect(status().isBadRequest());
        postJson("/api/coach/students/" + ana.id() + "/sessions", coach.token(), "{\"override\":true}").andExpect(status().isBadRequest());
        postJson("/api/student/sessions", ana.token(), "{}").andExpect(status().isBadRequest());
    }

    @Test
    void theOptionalOverrideFlagDefaultsToFalseWhenOmitted() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));

        // no "override" at all: it is a normal booking (and not an override)
        postJson("/api/coach/students/" + beto.id() + "/sessions", coach.token(), "{\"startsAt\":\"" + TEN + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.override").value(false));
        // no "override" on a coach cancellation either
        postJson("/api/coach/attendances/" + anaPlace + "/cancel", coach.token(), "{\"reason\":\"Entrenador enfermo\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelled.status").value("CANCELLED_BY_COACH"));
        // and an explicit null means the same as absent
        var carla = semi(coach, "Carla");
        String body = "{\"startsAt\":\"" + TEN + "\",\"override\":null}";
        assertThat(JsonPath.<Boolean>read(json(postJson("/api/coach/students/" + carla.id() + "/sessions", coach.token(), body)
                .andExpect(status().isCreated()).andReturn()), "$.override")).isFalse();
    }
}
