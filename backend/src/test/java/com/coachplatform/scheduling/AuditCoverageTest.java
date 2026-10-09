package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every path that changes an attendance leaves exactly the audit lines it should - and a path that fails leaves none. The clock
 * is fixed (it only moves when a test says so), so the time of each line is asserted exactly, not "about now".
 */
class AuditCoverageTest extends SchedulingApiTest {

    private static final String DAY = "2026-10-10";

    private int totalAuditRows() {
        return jdbc.queryForObject("select count(*) from attendance_audit", Integer.class);
    }

    private String audit(CoachCtx coach, String attendanceId) throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/attendances/" + attendanceId + "/audit"), coach.token()))
                .andExpect(status().isOk()).andReturn());
    }

    /** "ACTION/METHOD/ACTOR_ROLE" per line, oldest first. */
    private List<String> lines(CoachCtx coach, String attendanceId) throws Exception {
        String body = audit(coach, attendanceId);
        List<String> actions = JsonPath.read(body, "$[*].action");
        List<String> methods = JsonPath.read(body, "$[*].method");
        List<String> roles = JsonPath.read(body, "$[*].actorRole");
        return java.util.stream.IntStream.range(0, actions.size()).mapToObj(i -> actions.get(i) + "/" + methods.get(i) + "/" + roles.get(i)).toList();
    }

    // ================================================================= booking

    @Test
    void aStudentBookingLeavesABookLineWithTheFixedClockTime() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        Instant now = clock.instant();
        String place = attendanceId(studentBooked(ana, at(DAY, "10:00")));

        assertThat(lines(coach, place)).containsExactly("BOOK/STUDENT/STUDENT");
        assertThat(Instant.parse(JsonPath.read(audit(coach, place), "$[0].occurredAt"))).isEqualTo(now);
        assertThat(JsonPath.<Object>read(audit(coach, place), "$[0].previousStatus")).isNull();
        assertThat(JsonPath.<String>read(audit(coach, place), "$[0].newStatus")).isEqualTo("SCHEDULED");
    }

    @Test
    void theTimeOfALineFollowsTheClockWhenItMoves() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at(DAY, "10:00")));
        goTo("2026-10-08", "09:30");
        studentCancels(ana, place, null).andExpect(status().isOk());

        assertThat(Instant.parse(JsonPath.read(audit(coach, place), "$[1].occurredAt"))).isEqualTo(Instant.parse(at("2026-10-08", "09:30")));
    }

    @Test
    void aCoachBookingLeavesABookLineByTheCoach() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = JsonPath.read(json(coachBooks(coach, ana, at(DAY, "10:00"), false, null).andExpect(status().isCreated()).andReturn()), "$.id");

        assertThat(lines(coach, place)).containsExactly("BOOK/COACH/COACH");
    }

    @Test
    void aRejectedBookingLeavesNoLines() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        studentBooked(ana, at(DAY, "10:00"));
        int before = totalAuditRows();

        studentBooks(beto, at(DAY, "10:00")).andExpect(status().isConflict());           // SLOT_TAKEN
        studentBooks(beto, at("2026-10-01", "10:00")).andExpect(status().is4xxClientError());   // in the past

        assertThat(totalAuditRows()).isEqualTo(before);
    }

    // ================================================================= cancelling and rescheduling

    @Test
    void aStudentCancellationLeavesACancelLine() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at(DAY, "10:00")));
        studentCancels(ana, place, null).andExpect(status().isOk());

        assertThat(lines(coach, place)).containsExactly("BOOK/STUDENT/STUDENT", "CANCEL/STUDENT/STUDENT");
        assertThat(JsonPath.<String>read(audit(coach, place), "$[1].newStatus")).isEqualTo("CANCELLED_ON_TIME");
    }

    @Test
    void aStudentRescheduleLeavesARescheduleLineOnTheOldPlaceAndABookLineOnTheNewOne() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at(DAY, "10:00")));
        var moved = studentCancels(ana, place, at(DAY, "15:00")).andExpect(status().isOk()).andReturn();
        String replacement = JsonPath.read(json(moved), "$.replacement.id");

        assertThat(lines(coach, place)).containsExactly("BOOK/STUDENT/STUDENT", "RESCHEDULE/STUDENT/STUDENT");
        assertThat(JsonPath.<String>read(audit(coach, place), "$[1].newStatus")).isEqualTo("RESCHEDULED");
        assertThat(lines(coach, replacement)).containsExactly("BOOK/STUDENT/STUDENT");
    }

    @Test
    void anAtomicRescheduleThatFailsLeavesNoLinesAtAll() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        String place = attendanceId(studentBooked(ana, at(DAY, "10:00")));
        studentBooked(beto, at(DAY, "15:00"));
        int before = totalAuditRows();

        studentCancels(ana, place, at(DAY, "15:00")).andExpect(status().isConflict());   // the new slot is taken: SLOT_TAKEN

        assertThat(totalAuditRows()).as("not even a half-written line").isEqualTo(before);
        assertThat(lines(coach, place)).containsExactly("BOOK/STUDENT/STUDENT");
    }

    @Test
    void aCancellationRefusedByTheWindowLeavesNoLines() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at(DAY, "10:00")));
        goTo(DAY, "09:00");                                                               // one hour before: inside the 2 h window
        int before = totalAuditRows();

        studentCancels(ana, place, null).andExpect(status().isConflict());

        assertThat(totalAuditRows()).isEqualTo(before);
    }

    @Test
    void aCoachCancellationLeavesACancelLineWithItsReason() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at(DAY, "10:00")));
        coachCancelsAttendance(coach, place, "Me enfermé", null).andExpect(status().isOk());

        assertThat(lines(coach, place)).containsExactly("BOOK/STUDENT/STUDENT", "CANCEL/COACH/COACH");
        assertThat(JsonPath.<String>read(audit(coach, place), "$[1].reason")).isEqualTo("Me enfermé");
        assertThat(JsonPath.<String>read(audit(coach, place), "$[1].newStatus")).isEqualTo("CANCELLED_BY_COACH");
    }

    @Test
    void aCoachCancellationWithANewDateLeavesACancelLineAndABookLineOfTheReplacement() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at(DAY, "10:00")));
        var moved = coachCancelsAttendance(coach, place, "Cambio de horario", at(DAY, "16:00")).andExpect(status().isOk()).andReturn();
        String replacement = JsonPath.read(json(moved), "$.replacement.id");

        assertThat(lines(coach, place)).containsExactly("BOOK/STUDENT/STUDENT", "CANCEL/COACH/COACH");
        assertThat(lines(coach, replacement)).containsExactly("BOOK/COACH/COACH");
    }

    @Test
    void cancellingAWholeEventLeavesOneCancelLinePerAttendanceAndNoneForMarkedOnes() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var carla = semi(coach, "Carla");
        var first = studentBooked(ana, at(DAY, "10:00"));
        String event = eventId(first);
        String anaPlace = attendanceId(first);
        String betoPlace = attendanceId(studentBooked(beto, at(DAY, "10:00")));
        String carlaPlace = attendanceId(studentBooked(carla, at(DAY, "10:00")));

        coachCancelsEvent(coach, event, "Cierre del gimnasio").andExpect(status().isOk());

        for (String place : List.of(anaPlace, betoPlace, carlaPlace)) {
            assertThat(lines(coach, place)).containsExactly("BOOK/STUDENT/STUDENT", "CANCEL/COACH/COACH");
            assertThat(JsonPath.<String>read(audit(coach, place), "$[1].reason")).isEqualTo("Cierre del gimnasio");
        }
    }

    @Test
    void cancellingAnEventWithAMarkedClassLeavesCancelLinesOnlyForTheUnmarkedPlaces() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var first = studentBooked(ana, at("2026-10-07", "10:00"));
        String event = eventId(first);
        String anaPlace = attendanceId(first);
        String betoPlace = attendanceId(studentBooked(beto, at("2026-10-07", "10:00")));
        goTo("2026-10-07", "10:30");
        mark(coach, anaPlace, "ATTENDED").andExpect(status().isOk());

        coachCancelsEvent(coach, event, "Cierre").andExpect(status().isOk());

        assertThat(lines(coach, anaPlace)).as("a marked class is not touched").containsExactly("BOOK/STUDENT/STUDENT", "MARK/COACH/COACH");
        assertThat(lines(coach, betoPlace)).containsExactly("BOOK/STUDENT/STUDENT", "CANCEL/COACH/COACH");
    }

    // ================================================================= renewal transfer

    @Test
    void aRenewalLeavesATransferLineNamingTheSourceAndTheDestinationCycle() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at("2026-11-06", "19:00")));
        String oldCycle = JsonPath.read(activeCycle(coach, ana), "$.id");

        goTo("2026-11-06", "12:00");
        String newCycle = JsonPath.read(json(pay(coach.token(), ana.id(), coach.personalizedPlan(), "")), "$.cycleId");

        assertThat(lines(coach, place)).containsExactly("BOOK/STUDENT/STUDENT", "TRANSFER/COACH/COACH");
        String reason = JsonPath.read(audit(coach, place), "$[1].reason");
        assertThat(reason).contains(oldCycle).contains(newCycle);
        assertThat(JsonPath.<String>read(audit(coach, place), "$[1].previousStatus")).isEqualTo("SCHEDULED");
        assertThat(JsonPath.<String>read(audit(coach, place), "$[1].newStatus")).isEqualTo("SCHEDULED");
    }

    @Test
    void aForcedModalityTransferAlsoMentionsTheOverrideReason() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at("2026-11-06", "19:00")));
        goTo("2026-11-06", "12:00");
        pay(coach.token(), ana.id(), coach.semiPlan(), ",\"overrideModality\":true,\"overrideReason\":\"Pasa a grupal\"");

        assertThat(lines(coach, place)).containsExactly("BOOK/STUDENT/STUDENT", "TRANSFER/COACH/COACH");
        assertThat(JsonPath.<String>read(audit(coach, place), "$[1].reason")).contains("Pasa a grupal");
    }

    @Test
    void aRenewalThatIsRefusedLeavesNoTransferLines() throws Exception {
        var coach = newCoach(8);
        String smallPlan = createPlan(coach.token(), "1 clase", 1, 100_000, "PERSONALIZED");
        var ana = personalized(coach, "Ana");
        studentBooked(ana, at("2026-11-06", "18:00"));
        studentBooked(ana, at("2026-11-06", "19:00"));
        goTo("2026-11-06", "12:00");
        int before = totalAuditRows();

        assertThat(pay(coach.token(), ana.id(), smallPlan, "").getResponse().getStatus()).isEqualTo(422);      // TRANSFER_EXCEEDS_PLAN
        assertThat(pay(coach.token(), ana.id(), coach.semiPlan(), "").getResponse().getStatus()).isEqualTo(409); // MODALITY_CONFLICT

        assertThat(totalAuditRows()).isEqualTo(before);
    }

    @Test
    void aRenewalWithNothingToMoveLeavesNoTransferLines() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        goTo("2026-11-06", "12:00");
        int before = totalAuditRows();

        assertThat(pay(coach.token(), ana.id(), coach.personalizedPlan(), "").getResponse().getStatus()).isEqualTo(201);

        assertThat(totalAuditRows()).isEqualTo(before);
    }

    // ================================================================= the whole life of a place

    @Test
    void theWholeLifeOfAPlaceIsReadableInOrder() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String first = attendanceId(studentBooked(ana, at("2026-10-07", "10:00")));
        String second = JsonPath.read(json(studentCancels(ana, first, at("2026-10-07", "12:00")).andReturn()), "$.replacement.id");
        goTo("2026-10-07", "12:30");
        mark(coach, second, "ATTENDED").andExpect(status().isOk());

        assertThat(lines(coach, first)).containsExactly("BOOK/STUDENT/STUDENT", "RESCHEDULE/STUDENT/STUDENT");
        assertThat(lines(coach, second)).containsExactly("BOOK/STUDENT/STUDENT", "MARK/COACH/COACH");
    }
}
