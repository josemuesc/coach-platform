package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The coach's board (list of students with counts) and the student profile, with the flags the server computes: nullability field by
 * field, the status precedence, and that the profile's "can the coach pay / extend" always agrees with what the server then accepts.
 */
class BoardAndProfileTest extends SchedulingApiTest {

    private String board(CoachCtx coach) throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/students/board"), coach.token())).andExpect(status().isOk()).andReturn());
    }

    private String profile(CoachCtx coach, String studentId) throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/students/" + studentId + "/profile"), coach.token())).andExpect(status().isOk()).andReturn());
    }

    private <T> T row(String board, String name, String field) {
        List<T> values = JsonPath.read(board, "$.students[?(@.fullName=='" + name + "')]." + field);
        assertThat(values).as(name + "." + field).hasSize(1);
        return values.get(0);
    }

    private String minor(CoachCtx coach, String name) throws Exception {
        String json = json(mvc.perform(withToken(post("/api/coach/students"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + name + "\",\"birthDate\":\"2010-05-01\",\"guardian\":{\"name\":\"Marta Pérez\",\"relationship\":\"madre\","
                        + "\"phone\":\"3001234567\",\"email\":\"" + uniqueEmail("madre") + "\"}}")).andExpect(status().isCreated()).andReturn());
        return JsonPath.read(json, "$.student.id");
    }

    private void suspend(CoachCtx coach, String studentId, String email) throws Exception {
        mvc.perform(withToken(put("/api/coach/students/" + studentId), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\":{\"fullName\":\"Gus\",\"email\":\"" + email + "\",\"whatsappPhone\":\"3001234567\",\"birthDate\":\"1990-05-01\"},\"active\":false}"))
                .andExpect(status().isOk());
    }

    // ================================================================= the board

    @Test
    void theBoardClassifiesEveryStudentCountsThemAndListsSuspendedOnesLast() throws Exception {
        var coach = newCoach(8);
        String oneClass = createPlan(coach.token(), "1 clase", 1, 100_000, "PERSONALIZED");
        String twoClasses = createPlan(coach.token(), "2 clases", 2, 200_000, "PERSONALIZED");

        goTo("2026-10-06", "09:00");
        var beto = withCycle(coach, newStudent(coach, "Beto"), coach.personalizedPlan());       // cycle Oct 6 - Nov 6
        var ila = withCycle(coach, newStudent(coach, "Ila"), oneClass);                          // 1 class AND ends Nov 6

        goTo("2026-10-20", "09:00");
        var ana = withCycle(coach, newStudent(coach, "Ana"), coach.personalizedPlan());         // ends Nov 20: plenty
        var cata = withCycle(coach, newStudent(coach, "Cata"), twoClasses);
        var dani = withCycle(coach, newStudent(coach, "Dani"), oneClass);
        var enzo = newStudent(coach, "Enzo");                                                   // account, never paid
        var fabi = createStudentJson(coach.token(), "Fabi", uniqueEmail("fabi"));                // invitation not accepted
        String fabiId = JsonPath.read(fabi, "$.student.id");
        pay(coach.token(), fabiId, coach.personalizedPlan(), "");
        String mateoId = minor(coach, "Mateo");
        pay(coach.token(), mateoId, coach.personalizedPlan(), "");
        String gusEmail = uniqueEmail("gus");
        String gusId = JsonPath.read(createStudentJson(coach.token(), "Gus", gusEmail), "$.student.id");
        pay(coach.token(), gusId, coach.personalizedPlan(), "");
        suspend(coach, gusId, gusEmail);
        String catasClass = attendanceId(studentBooked(cata, at("2026-11-01", "08:00")));
        String danisClass = attendanceId(studentBooked(dani, at("2026-11-01", "07:00")));

        goTo("2026-11-01", "09:00");                                                            // Beto and Ila: 5 days left
        mark(coach, catasClass, "ATTENDED").andExpect(status().isOk());                          // Cata: 1 of 2 used
        mark(coach, danisClass, "ATTENDED").andExpect(status().isOk());                          // Dani: 1 of 1 used -> COMPLETED
        String board = board(coach);

        assertThat(JsonPath.<List<String>>read(board, "$.students[*].fullName"))
                .as("by name, the suspended one last").containsExactly("Ana", "Beto", "Cata", "Dani", "Enzo", "Fabi", "Ila", "Mateo", "Gus");
        assertThat(JsonPath.<Integer>read(board, "$.counts.all")).as("active students only").isEqualTo(8);
        assertThat(JsonPath.<Integer>read(board, "$.counts.expiring")).isEqualTo(4);               // Beto, Cata, Dani, Ila
        assertThat(JsonPath.<Integer>read(board, "$.counts.noPlan")).isEqualTo(1);                 // Enzo
        assertThat(JsonPath.<Integer>read(board, "$.counts.minors")).isEqualTo(1);                 // Mateo

        assertThat(this.<String>row(board, "Ana", "status")).isEqualTo("AL_DIA");
        assertThat(this.<Object>row(board, "Ana", "expiringBy")).as("null unless about to expire").isNull();
        assertThat(this.<Integer>row(board, "Ana", "daysUntilEnd")).isEqualTo(19);
        assertThat(this.<String>row(board, "Beto", "status")).isEqualTo("POR_VENCER");
        assertThat(this.<String>row(board, "Beto", "expiringBy")).isEqualTo("DAYS");
        assertThat(this.<Integer>row(board, "Beto", "daysUntilEnd")).isEqualTo(5);
        assertThat(this.<String>row(board, "Cata", "expiringBy")).isEqualTo("CLASSES");
        assertThat(this.<Integer>row(board, "Cata", "classesRemaining")).isEqualTo(1);
        assertThat(this.<String>row(board, "Ila", "expiringBy")).isEqualTo("BOTH");
        // COMPLETED before its deadline: "about to expire by classes", no classes left, no days to count
        assertThat(this.<String>row(board, "Dani", "status")).isEqualTo("POR_VENCER");
        assertThat(this.<String>row(board, "Dani", "expiringBy")).isEqualTo("CLASSES");
        assertThat(this.<Integer>row(board, "Dani", "classesRemaining")).isZero();
        assertThat(this.<Object>row(board, "Dani", "daysUntilEnd")).isNull();
        assertThat(this.<String>row(board, "Dani", "planModality")).isEqualTo("PERSONALIZED");
        // no plan: everything about the cycle is null, never zero
        assertThat(this.<String>row(board, "Enzo", "status")).isEqualTo("SIN_PLAN");
        assertThat(this.<Boolean>row(board, "Enzo", "noPlan")).isTrue();
        for (String field : List.of("expiringBy", "planModality", "classesIncluded", "classesRemaining", "endDate", "daysUntilEnd")) {
            assertThat(this.<Object>row(board, "Enzo", field)).as("Enzo." + field).isNull();
        }
        assertThat(this.<Integer>row(board, "Enzo", "pendingMarks")).isZero();
        // not activated beats everything below it, but the minor flag and the plan are still there
        assertThat(this.<String>row(board, "Fabi", "status")).isEqualTo("SIN_ACTIVAR");
        assertThat(this.<Boolean>row(board, "Fabi", "hasAccount")).isFalse();
        assertThat(this.<String>row(board, "Mateo", "status")).isEqualTo("SIN_ACTIVAR");
        assertThat(this.<Boolean>row(board, "Mateo", "minor")).isTrue();
        assertThat(this.<String>row(board, "Mateo", "planModality")).isEqualTo("PERSONALIZED");
        // suspended: its own chip, out of every bucket
        assertThat(this.<String>row(board, "Gus", "status")).isEqualTo("SUSPENDIDO");
        assertThat(this.<Boolean>row(board, "Gus", "active")).isFalse();
        assertThat(this.<Boolean>row(board, "Gus", "expiringSoon")).isFalse();
        assertThat(this.<Boolean>row(board, "Gus", "noPlan")).isFalse();
    }

    @Test
    void theBoardOfAnotherCoachShowsNoneOfThem() throws Exception {
        var coach = newCoach(8);
        personalized(coach, "Ana");
        var other = newCoach(8);
        String board = board(other);
        assertThat(JsonPath.<List<Object>>read(board, "$.students")).isEmpty();
        assertThat(JsonPath.<Integer>read(board, "$.counts.all")).isZero();
    }

    @Test
    void anExpiredCycleIsNoPlanAndAnOverdueOneWithUnmarkedClassesStaysAboutToExpire() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-06", "09:00");
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        String betosClass = attendanceId(studentBooked(beto, at("2026-11-05", "10:00")));
        goTo("2026-11-09", "09:00");                                  // both are 3 days past the deadline; Beto's class was never marked
        String board = board(coach);
        assertThat(this.<String>row(board, "Ana", "status")).isEqualTo("SIN_PLAN");
        assertThat(this.<Object>row(board, "Ana", "classesRemaining")).isNull();
        assertThat(this.<String>row(board, "Beto", "status")).as("held open by its unmarked class").isEqualTo("POR_VENCER");
        assertThat(this.<Integer>row(board, "Beto", "daysUntilEnd")).isEqualTo(-3);
        assertThat(this.<Integer>row(board, "Beto", "pendingMarks")).isEqualTo(1);
        mark(coach, betosClass, "ATTENDED").andExpect(status().isOk());
        assertThat(this.<String>row(board(coach), "Beto", "status")).isEqualTo("SIN_PLAN");
    }

    // ================================================================= the profile

    @Test
    void aStudentWhoNeverPaidHasNoCycleAndMayBeCharged() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-09", "10:00");
        String id = JsonPath.read(createStudentJson(coach.token(), "Laura", uniqueEmail("laura")), "$.student.id");
        String p = profile(coach, id);

        assertThat(JsonPath.<String>read(p, "$.student.id")).isEqualTo(id);
        assertThat(JsonPath.<Object>read(p, "$.cycle.cycle")).isNull();
        assertThat(JsonPath.<Object>read(p, "$.cycle.planName")).isNull();
        assertThat(JsonPath.<Object>read(p, "$.cycle.lastPayment")).isNull();
        assertThat(JsonPath.<Boolean>read(p, "$.cycle.payment.canRegisterPayment")).isTrue();
        assertThat(JsonPath.<Object>read(p, "$.cycle.payment.blockedBy")).isNull();
        assertThat(JsonPath.<Object>read(p, "$.cycle.payment.opensOn")).isNull();
        assertThat(JsonPath.<String>read(p, "$.cycle.payment.paidOnMin")).isEqualTo("2026-10-06");
        assertThat(JsonPath.<String>read(p, "$.cycle.payment.paidOnMax")).isEqualTo("2026-10-09");
        assertThat(JsonPath.<Boolean>read(p, "$.cycle.extension.canExtendCycle")).isFalse();
        assertThat(JsonPath.<Object>read(p, "$.cycle.extension.extendFrom")).isNull();
        assertThat(JsonPath.<Object>read(p, "$.cycle.extension.extendUntil")).isNull();
        assertThat(JsonPath.<Boolean>read(p, "$.account.hasAccount")).isFalse();
        assertThat(JsonPath.<Object>read(p, "$.account.passwordChangedAt")).isNull();
        assertThat(JsonPath.<Object>read(p, "$.account.passwordChangedBy")).isNull();
        assertThat(JsonPath.<Object>read(p, "$.account.openResetLinkUntil")).isNull();
        assertThat(JsonPath.<List<Object>>read(p, "$.upcoming")).isEmpty();
        assertThat(JsonPath.<List<String>>read(p, "$.consents.consents[*].type")).isNotEmpty();
    }

    @Test
    void withAnActiveCycleThePaymentIsBlockedUntilItsLastDayAndTheServerAgrees() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-09", "10:00");
        var laura = personalized(coach, "Laura");                       // cycle Oct 9 - Nov 9
        pay(coach.token(), laura.id(), coach.personalizedPlan(), "");   // refused: the profile must have said so first
        String p = profile(coach, laura.id());

        assertThat(JsonPath.<String>read(p, "$.cycle.cycle.status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<String>read(p, "$.cycle.cycle.endDate")).isEqualTo("2026-11-09");
        assertThat(JsonPath.<String>read(p, "$.cycle.planName")).isEqualTo("personalizado 8");
        assertThat(JsonPath.<Integer>read(p, "$.cycle.lastPayment.amountCop")).isEqualTo(520000);
        assertThat(JsonPath.<Boolean>read(p, "$.cycle.payment.canRegisterPayment")).isFalse();
        assertThat(JsonPath.<String>read(p, "$.cycle.payment.blockedBy")).isEqualTo("ACTIVE_CYCLE");
        assertThat(JsonPath.<String>read(p, "$.cycle.payment.opensOn")).isEqualTo("2026-11-09");
        assertThat(JsonPath.<Object>read(p, "$.cycle.payment.paidOnMin")).isNull();
        assertThat(JsonPath.<Object>read(p, "$.cycle.payment.paidOnMax")).isNull();
        var refused = pay(coach.token(), laura.id(), coach.personalizedPlan(), "");
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(json(refused), "$.code")).isEqualTo("ACTIVE_CYCLE_EXISTS");

        // extension: after the current last day, up to the original one plus the coach's cap (60 days)
        assertThat(JsonPath.<Boolean>read(p, "$.cycle.extension.canExtendCycle")).isTrue();
        assertThat(JsonPath.<String>read(p, "$.cycle.extension.extendFrom")).isEqualTo("2026-11-10");
        assertThat(JsonPath.<String>read(p, "$.cycle.extension.extendUntil")).isEqualTo("2027-01-08");
        extend(coach, JsonPath.read(p, "$.cycle.cycle.id"), "2026-11-09").andExpect(status().isUnprocessableEntity());   // below extendFrom
        extend(coach, JsonPath.read(p, "$.cycle.cycle.id"), "2027-01-09").andExpect(status().isUnprocessableEntity());   // beyond extendUntil
        extend(coach, JsonPath.read(p, "$.cycle.cycle.id"), "2026-11-10").andExpect(status().isOk());
        extend(coach, JsonPath.read(p, "$.cycle.cycle.id"), "2027-01-08").andExpect(status().isOk());
        String after = profile(coach, laura.id());
        assertThat(JsonPath.<Boolean>read(after, "$.cycle.extension.canExtendCycle")).as("at the cap").isFalse();
        assertThat(JsonPath.<Object>read(after, "$.cycle.extension.extendFrom")).isNull();
        // the payment still opens on the (new) last day
        assertThat(JsonPath.<String>read(after, "$.cycle.payment.opensOn")).isEqualTo("2027-01-08");
    }

    private ResultActions extend(CoachCtx coach, String cycleId, String newEndDate) throws Exception {
        return mvc.perform(withToken(post("/api/coach/cycles/" + cycleId + "/extend"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newEndDate\":\"" + newEndDate + "\",\"reason\":\"Viaje\"}"));
    }

    @Test
    void usingTheLastClassOpensThePaymentTheSameDayAndTheOldCycleIsLeftAsItWas() throws Exception {
        var coach = newCoach(8);
        String oneClass = createPlan(coach.token(), "1 clase", 1, 100_000, "PERSONALIZED");
        goTo("2026-10-09", "06:00");
        var laura = withCycle(coach, newStudent(coach, "Laura"), oneClass);                      // cycle Oct 9 - Nov 9
        String place = attendanceId(studentBooked(laura, at("2026-10-09", "09:00")));

        goTo("2026-10-09", "09:30");                                                              // the class started and is NOT marked
        String unmarked = profile(coach, laura.id());
        assertThat(JsonPath.<Boolean>read(unmarked, "$.cycle.payment.canRegisterPayment")).as("an unmarked class is not a used class").isFalse();
        assertThat(pay(coach.token(), laura.id(), oneClass, "").getResponse().getStatus()).isEqualTo(409);

        mark(coach, place, "ATTENDED").andExpect(status().isOk());                                // the last class is used
        String p = profile(coach, laura.id());
        assertThat(JsonPath.<String>read(p, "$.cycle.cycle.status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<Integer>read(p, "$.cycle.cycle.classesRemaining")).isZero();
        assertThat(JsonPath.<Boolean>read(p, "$.cycle.payment.canRegisterPayment")).isTrue();
        assertThat(JsonPath.<String>read(p, "$.cycle.payment.paidOnMin")).isEqualTo("2026-10-09");   // not before the day the last class was used
        assertThat(JsonPath.<String>read(p, "$.cycle.payment.paidOnMax")).isEqualTo("2026-10-09");
        assertThat(JsonPath.<Boolean>read(p, "$.cycle.extension.canExtendCycle")).as("a completed cycle is not extended").isFalse();

        assertThat(pay(coach.token(), laura.id(), oneClass, ",\"paidOn\":\"2026-10-08\"").getResponse().getStatus()).isEqualTo(422);
        var paid = pay(coach.token(), laura.id(), oneClass, ",\"paidOn\":\"2026-10-09\"");
        assertThat(paid.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(json(paid), "$.startDate")).isEqualTo("2026-10-09");
        assertThat(JsonPath.<String>read(json(paid), "$.endDate")).isEqualTo("2026-11-09");

        var cycles = json(mvc.perform(withToken(get("/api/coach/students/" + laura.id() + "/cycles"), coach.token())).andReturn());
        assertThat(JsonPath.<List<String>>read(cycles, "$[*].status")).containsExactlyInAnyOrder("COMPLETED", "ACTIVE");
        assertThat(JsonPath.<List<String>>read(cycles, "$[?(@.status=='COMPLETED')].startDate")).containsExactly("2026-10-09");
        assertThat(JsonPath.<List<String>>read(cycles, "$[?(@.status=='COMPLETED')].endDate")).containsExactly("2026-11-09");
    }

    @Test
    void upcomingListsTheNextBookedClassesSoonestFirstWithTodayDecidedByTheServer() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-09", "06:00");
        var laura = personalized(coach, "Laura");
        studentBooked(laura, at("2026-10-12", "07:00"));
        String later = attendanceId(studentBooked(laura, at("2026-10-09", "17:00")));
        String done = attendanceId(studentBooked(laura, at("2026-10-09", "09:00")));
        goTo("2026-10-09", "10:30");
        mark(coach, done, "ATTENDED").andExpect(status().isOk());                                  // ended and marked: not "upcoming"
        String p = profile(coach, laura.id());
        assertThat(JsonPath.<List<String>>read(p, "$.upcoming[*].startsAt")).containsExactly(at("2026-10-09", "17:00"), at("2026-10-12", "07:00"));
        assertThat(JsonPath.<List<Boolean>>read(p, "$.upcoming[*].today")).containsExactly(true, false);
        assertThat(JsonPath.<String>read(p, "$.upcoming[0].attendanceId")).isEqualTo(later);
        assertThat(JsonPath.<List<String>>read(p, "$.upcoming[*].modality")).containsOnly("PERSONALIZED");
    }

    @Test
    void theAccountShowsWhoLastChangedThePasswordAndTheOpenResetLink() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-09", "10:00");
        var laura = personalized(coach, "Laura");
        String p = profile(coach, laura.id());
        assertThat(JsonPath.<Boolean>read(p, "$.account.hasAccount")).isTrue();
        assertThat(JsonPath.<Boolean>read(p, "$.account.active")).isTrue();
        assertThat(JsonPath.<Object>read(p, "$.account.passwordChangedBy")).as("never changed after accepting the invitation").isNull();

        mvc.perform(withToken(post("/api/auth/change-password"), laura.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Otra-clave-segura-2\"}")).andExpect(status().isOk());
        String selfChanged = profile(coach, laura.id());
        assertThat(JsonPath.<String>read(selfChanged, "$.account.passwordChangedBy")).isEqualTo("SELF");
        assertThat(JsonPath.<String>read(selfChanged, "$.account.passwordChangedAt")).isNotNull();

        // a reset link: open until it expires, gone when revoked, never reported once expired
        mvc.perform(withToken(post("/api/coach/students/" + laura.id() + "/password-reset"), coach.token())).andExpect(status().isCreated());
        assertThat(JsonPath.<String>read(profile(coach, laura.id()), "$.account.openResetLinkUntil")).isEqualTo("2026-10-10T15:00:00Z");
        mvc.perform(withToken(delete("/api/coach/students/" + laura.id() + "/password-reset"), coach.token())).andExpect(status().isNoContent());
        assertThat(JsonPath.<Object>read(profile(coach, laura.id()), "$.account.openResetLinkUntil")).isNull();
        String url = JsonPath.read(json(mvc.perform(withToken(post("/api/coach/students/" + laura.id() + "/password-reset"), coach.token()))
                .andExpect(status().isCreated()).andReturn()), "$.resetUrl");
        advance(Duration.ofHours(25));
        assertThat(JsonPath.<Object>read(profile(coach, laura.id()), "$.account.openResetLinkUntil")).as("expired").isNull();
        goTo("2026-10-09", "11:00");
        String fresh = JsonPath.read(json(mvc.perform(withToken(post("/api/coach/students/" + laura.id() + "/password-reset"), coach.token()))
                .andExpect(status().isCreated()).andReturn()), "$.resetUrl");
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + fresh.substring(fresh.lastIndexOf('/') + 1) + "\",\"newPassword\":\"Clave-por-enlace-3\"}")).andExpect(status().isNoContent());
        String viaLink = profile(coach, laura.id());
        assertThat(JsonPath.<String>read(viaLink, "$.account.passwordChangedBy")).isEqualTo("COACH_LINK");
        assertThat(JsonPath.<Object>read(viaLink, "$.account.openResetLinkUntil")).isNull();
        assertThat(url).contains("/reset/");
    }

    @Test
    void theProfileOfAnotherCoachsStudentIsNotFound() throws Exception {
        var coach = newCoach(8);
        var laura = personalized(coach, "Laura");
        var other = newCoach(8);
        mvc.perform(withToken(get("/api/coach/students/" + laura.id() + "/profile"), other.token())).andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/students/" + laura.id() + "/profile"), laura.token())).andExpect(status().isForbidden());
    }

    @Test
    void theLastPaymentCarriesItsMethodAndReference() throws Exception {
        var coach = newCoach(8);
        var laura = newStudent(coach, "Laura");
        pay(coach.token(), laura.id(), coach.personalizedPlan(), ",\"reference\":\"M1234567\"");
        mvc.perform(withToken(get("/api/coach/students/" + laura.id() + "/profile"), coach.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.cycle.lastPayment.reference").value("M1234567")).andExpect(jsonPath("$.cycle.lastPayment.method").value("NEQUI"));
    }
}
