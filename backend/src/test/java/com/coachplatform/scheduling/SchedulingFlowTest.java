package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchedulingFlowTest extends SchedulingApiTest {

    // ---------------------------------------------------------------- booking

    @Test
    void aStudentSeesTheFreeSlotsAndBooksOne() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");

        var slots = mvc.perform(withToken(get("/api/student/slots").param("from", "2026-10-12").param("to", "2026-10-12"), ana.token()))
                .andExpect(status().isOk()).andReturn();
        List<String> starts = JsonPath.read(json(slots), "$[*].startsAt");
        assertThat(starts).hasSize(14).contains(at("2026-10-12", "10:00"));
        assertThat(JsonPath.<String>read(json(slots), "$[0].localTime")).isEqualTo("06:00");

        studentBooks(ana, at("2026-10-12", "10:00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.startsAt").value(at("2026-10-12", "10:00")))
                .andExpect(jsonPath("$.endsAt").value(at("2026-10-12", "11:00")))
                .andExpect(jsonPath("$.studentName").doesNotExist());

        List<String> after = JsonPath.read(json(mvc.perform(withToken(get("/api/student/slots").param("from", "2026-10-12").param("to", "2026-10-12"), ana.token())).andReturn()), "$[*].startsAt");
        assertThat(after).hasSize(13).doesNotContain(at("2026-10-12", "10:00"));
        assertThat(JsonPath.<List<?>>read(studentSessions(ana), "$")).hasSize(1);
    }

    @Test
    void withoutAnActiveCycleThereAreNoSlotsAndNoBooking() throws Exception {
        var coach = newCoach(8);
        var ana = newStudent(coach, "Ana");   // never paid

        mvc.perform(withToken(get("/api/student/slots").param("from", "2026-10-12").param("to", "2026-10-12"), ana.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        studentBooks(ana, at("2026-10-12", "10:00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_CYCLE"));
    }

    @Test
    void bookingRespectsLeadTimeAvailabilityAndTheCycleDeadline() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");   // now: Oct 6 12:00; cycle ends Nov 6

        studentBooks(ana, at("2026-10-06", "13:00")).andExpect(status().isUnprocessableEntity())      // 1 h ahead
                .andExpect(jsonPath("$.code").value("TOO_SOON"));
        studentBooks(ana, at("2026-10-06", "14:00")).andExpect(status().isCreated());                 // exactly 2 h ahead
        studentBooks(ana, at("2026-10-12", "10:30")).andExpect(status().isUnprocessableEntity())      // off the grid
                .andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
        studentBooks(ana, at("2026-10-12", "21:00")).andExpect(status().isUnprocessableEntity())      // outside the windows
                .andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
        studentBooks(ana, at("2026-10-05", "10:00")).andExpect(status().isUnprocessableEntity())      // the past
                .andExpect(jsonPath("$.code").value("CLASS_IN_PAST"));
        studentBooks(ana, at("2026-11-07", "10:00")).andExpect(status().isUnprocessableEntity())      // day after the deadline
                .andExpect(jsonPath("$.code").value("OUTSIDE_CYCLE"));
        studentBooks(ana, at("2026-11-06", "19:00")).andExpect(status().isCreated());                 // deadline day, evening
    }

    @Test
    void usedPlusScheduledNeverExceedsTheCycle() throws Exception {
        var coach = newCoach(2);
        var ana = newStudentWithCycle(coach, "Ana");

        String first = studentBooked(ana, at("2026-10-12", "10:00"));
        studentBooked(ana, at("2026-10-13", "10:00"));
        studentBooks(ana, at("2026-10-14", "10:00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));

        studentCancels(ana, first, null).andExpect(status().isOk());       // cancelling frees the place
        studentBooks(ana, at("2026-10-14", "10:00")).andExpect(status().isCreated());
    }

    @Test
    void twoStudentsCannotTakeTheSameSlot() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        var beto = newStudentWithCycle(coach, "Beto");

        studentBooks(ana, at("2026-10-12", "10:00")).andExpect(status().isCreated());
        studentBooks(beto, at("2026-10-12", "10:00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
        studentBooks(beto, at("2026-10-12", "11:00")).andExpect(status().isCreated());   // back to back is fine
    }

    @Test
    void theCoachBooksForAStudentWithoutTheLeadTime() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");

        coachBooks(coach, ana.id(), at("2026-10-06", "13:00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.studentName").value("Ana"));
        coachBooks(coach, ana.id(), at("2026-10-06", "12:00")).andExpect(status().isUnprocessableEntity());   // not in the future
    }

    // ---------------------------------------------------------------- student cancels / reschedules

    @Test
    void cancellingWithNoticeAndNoNewDateDeductsNothing() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        studentCancels(ana, id, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled.status").value("CANCELLED_ON_TIME"))
                .andExpect(jsonPath("$.replacement").doesNotExist());
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.classesUsed")).isZero();
    }

    @Test
    void cancellingWithANewDateReschedulesAtomically() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        var result = studentCancels(ana, id, at("2026-10-13", "15:00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled.status").value("RESCHEDULED"))
                .andExpect(jsonPath("$.replacement.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.replacement.rescheduledFrom").value(id))
                .andExpect(jsonPath("$.replacement.cycleId").exists()).andReturn();
        assertThat(JsonPath.<String>read(json(result), "$.replacement.startsAt")).isEqualTo(at("2026-10-13", "15:00"));
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.classesUsed")).isZero();
    }

    @Test
    void anInvalidNewSlotLeavesTheOriginalClassUntouched() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        var beto = newStudentWithCycle(coach, "Beto");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));
        studentBooked(beto, at("2026-10-13", "10:00"));

        studentCancels(ana, id, at("2026-11-09", "10:00")).andExpect(status().isUnprocessableEntity())      // after the deadline
                .andExpect(jsonPath("$.code").value("OUTSIDE_CYCLE"));
        studentCancels(ana, id, at("2026-10-13", "10:00")).andExpect(status().isConflict())                 // taken by Beto
                .andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
        studentCancels(ana, id, at("2026-10-13", "10:30")).andExpect(status().isUnprocessableEntity())      // off the grid
                .andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));

        List<String> statuses = JsonPath.read(studentSessions(ana), "$[*].status");
        assertThat(statuses).containsExactly("SCHEDULED");   // still the one original class, not cancelled
    }

    @Test
    void insideTheTwoHourWindowTheCancellationIsRefusedAndTheClassStaysScheduled() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        goTo("2026-10-12", "08:30");   // 1 h 30 before
        studentCancels(ana, id, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"));
        studentCancels(ana, id, at("2026-10-14", "10:00")).andExpect(status().isConflict());
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");

        goTo("2026-10-12", "08:00");   // exactly 2 h before: still allowed
        studentCancels(ana, id, null).andExpect(status().isOk());
    }

    @Test
    void aStudentCannotCancelAClassThatAlreadyStarted() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        goTo("2026-10-12", "10:30");
        studentCancels(ana, id, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLASS_ALREADY_STARTED"));
    }

    // ---------------------------------------------------------------- coach cancels

    @Test
    void theCoachForgivesALateCancellationByCancellingTheClassHimself() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        goTo("2026-10-12", "09:00");   // one hour before: the student's cancellation is refused
        studentCancels(ana, id, null).andExpect(status().isConflict());

        coachCancels(coach, id, "Perdonada: llovía muy fuerte", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled.status").value("CANCELLED_BY_COACH"))
                .andExpect(jsonPath("$.cancelled.cancelReason").value("Perdonada: llovía muy fuerte"));
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.classesUsed")).as("no class deducted").isZero();
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.classesRemaining")).isEqualTo(8);
    }

    @Test
    void theCoachMustGiveAReason() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        coachCancels(coach, id, "   ", null).andExpect(status().isBadRequest());
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
    }

    @Test
    void theCoachMayCancelAtAnyTimeEvenAfterTheClassStarted() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        goTo("2026-10-12", "10:45");
        coachCancels(coach, id, "Imprevisto del entrenador", null).andExpect(status().isOk());
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.classesUsed")).isZero();
    }

    @Test
    void theCoachCanCancelAndMoveAtOnceAndAnInvalidDateCancelsNothing() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        coachCancels(coach, id, "x", at("2026-11-20", "10:00")).andExpect(status().isUnprocessableEntity());
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");

        coachCancels(coach, id, "Entrenador enfermo", at("2026-10-14", "10:00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled.status").value("CANCELLED_BY_COACH"))
                .andExpect(jsonPath("$.replacement.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.replacement.rescheduledFrom").value(id));
    }

    // ---------------------------------------------------------------- attendance

    @Test
    void markingAttendanceUsesAClassAndCanBeSwitchedButNeverUndone() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        mark(coach, id, "ATTENDED").andExpect(status().isConflict())      // not started yet
                .andExpect(jsonPath("$.code").value("CLASS_NOT_STARTED"));

        goTo("2026-10-12", "10:00");
        mark(coach, id, "ATTENDED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ATTENDED"));
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.classesUsed")).isEqualTo(1);

        mark(coach, id, "NO_SHOW").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_SHOW"));
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.classesUsed")).as("switching keeps the count").isEqualTo(1);

        mark(coach, id, "NO_SHOW").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_MARKED"));
        mark(coach, id, "SCHEDULED").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        coachCancels(coach, id, "ya marcada", null).andExpect(status().isConflict());   // a mark cannot be undone
    }

    @Test
    void startedClassesAreListedAsPendingUntilTheyAreMarked() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        mvc.perform(withToken(get("/api/coach/sessions/pending"), coach.token())).andExpect(jsonPath("$.length()").value(0));
        goTo("2026-10-12", "12:00");
        mvc.perform(withToken(get("/api/coach/sessions/pending"), coach.token()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].studentName").value("Ana"));

        mark(coach, id, "NO_SHOW").andExpect(status().isOk());
        mvc.perform(withToken(get("/api/coach/sessions/pending"), coach.token())).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void markingTheLastClassCompletesTheCycleAndTheStudentCanRenewAtOnce() throws Exception {
        var coach = newCoach(1);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));

        goTo("2026-10-12", "10:30");
        mark(coach, id, "ATTENDED").andExpect(status().isOk());

        assertThat(JsonPath.<String>read(cycles(coach, ana), "$[0].status")).isEqualTo("COMPLETED");
        studentBooks(ana, at("2026-10-13", "10:00")).andExpect(status().isConflict())      // no active cycle any more
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_CYCLE"));
        assertThat(pay(coach.token(), ana.id(), coach.planId(), "").getResponse().getStatus()).isEqualTo(201);
        studentBooks(ana, at("2026-10-14", "10:00")).andExpect(status().isCreated());
    }

    @Test
    void theAgendaShowsClassesAndFreeSlotsForTheCoach() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        studentBooked(ana, at("2026-10-12", "10:00"));
        studentBooked(ana, at("2026-10-12", "11:00"));

        mvc.perform(withToken(get("/api/coach/agenda").param("from", "2026-10-12").param("to", "2026-10-12"), coach.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(2))
                .andExpect(jsonPath("$.sessions[0].studentName").value("Ana"))
                .andExpect(jsonPath("$.freeSlots.length()").value(12));
        mvc.perform(withToken(get("/api/coach/agenda").param("from", "2026-10-12").param("to", "2027-03-12"), coach.token()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        advance(Duration.ZERO);
    }
}
