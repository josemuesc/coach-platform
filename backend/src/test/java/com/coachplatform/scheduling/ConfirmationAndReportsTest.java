package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lote B over HTTP: the rotating code and its windows, confirming by QR and afterwards, what the coach marks, the audit it leaves,
 * and the coach's day / sheet and the student's history. Event: Monday 2026-10-12 10:00-11:00 Bogota.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConfirmationAndReportsTest extends SchedulingApiTest {

    private static final String DAY = "2026-10-12";
    private static final String TEN = at(DAY, "10:00");

    // ---- helpers ---------------------------------------------------------------------------------------------

    private ResultActions qr(CoachCtx coach, String eventId) throws Exception {
        return mvc.perform(withToken(get("/api/coach/events/" + eventId + "/qr"), coach.token()));
    }

    private String token(CoachCtx coach, String eventId) throws Exception {
        return JsonPath.read(json(qr(coach, eventId).andExpect(status().isOk()).andReturn()), "$.token");
    }

    private ResultActions scan(StudentCtx student, String token) throws Exception {
        return mvc.perform(withToken(post("/api/student/attendances/confirm-qr"), student.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"));
    }

    private ResultActions confirmLater(StudentCtx student, String attendanceId) throws Exception {
        return mvc.perform(withToken(post("/api/student/attendances/" + attendanceId + "/confirm"), student.token()));
    }

    private void goTo(String time) {
        goTo(DAY, time);
    }

    private List<String> auditActions(CoachCtx coach, String attendanceId) throws Exception {
        String body = json(mvc.perform(withToken(get("/api/coach/attendances/" + attendanceId + "/audit"), coach.token()))
                .andExpect(status().isOk()).andReturn());
        return JsonPath.read(body, "$[*].action");
    }

    private List<String> auditLines(CoachCtx coach, String attendanceId) throws Exception {
        String body = json(mvc.perform(withToken(get("/api/coach/attendances/" + attendanceId + "/audit"), coach.token()))
                .andExpect(status().isOk()).andReturn());
        List<String> lines = JsonPath.read(body, "$[*].action");
        List<String> methods = JsonPath.read(body, "$[*].method");
        return java.util.stream.IntStream.range(0, lines.size()).mapToObj(i -> lines.get(i) + "/" + methods.get(i)).toList();
    }

    private int classesUsedOf(CoachCtx coach, StudentCtx student) throws Exception {
        return JsonPath.read(activeCycle(coach, student), "$.classesUsed");
    }

    private ResultActions attendanceOf(StudentCtx student, String attendanceId) throws Exception {
        return mvc.perform(withToken(get("/api/student/sessions"), student.token()));
    }

    private String myStatus(StudentCtx student) throws Exception {
        return JsonPath.<List<String>>read(studentSessions(student), "$[0].status").get(0) == null ? "" : JsonPath.<List<String>>read(studentSessions(student), "$[*].status").get(0);
    }

    // ================================================================= the code and its windows

    @Test
    void theCoachGetsTheCodeFromFifteenMinutesBeforeUntilTwoHoursAfterTheEnd() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String event = eventId(studentBooked(ana, TEN));

        goTo("09:44");
        qr(coach, event).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QR_NOT_OPEN_YET"));
        goTo("09:45");
        var view = qr(coach, event).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
        String body = json(view);
        assertThat(JsonPath.<Integer>read(body, "$.validForSeconds")).isBetween(31, 60);
        assertThat(JsonPath.<String>read(body, "$.url")).endsWith("/qr#t=" + JsonPath.<String>read(body, "$.token")).doesNotContain("?");   // fragment, not query
        goTo("13:00");                                                                   // exactly two hours after the end
        qr(coach, event).andExpect(status().isOk());
        clock.set(Instant.parse(at(DAY, "13:00")).plusSeconds(1));
        qr(coach, event).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QR_WINDOW_CLOSED"));
    }

    @Test
    void theCodeRotatesAndOnlyTheOwningCoachCanAskForIt() throws Exception {
        var coach = newCoach(8);
        var other = newCoach(8);
        var ana = personalized(coach, "Ana");
        String event = eventId(studentBooked(ana, TEN));
        goTo("10:00");
        String first = token(coach, event);
        clock.advance(java.time.Duration.ofSeconds(30));
        assertThat(token(coach, event)).isNotEqualTo(first);
        qr(other, event).andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/events/" + event + "/qr"), ana.token())).andExpect(status().isForbidden());
    }

    @Test
    void aCancelledEventHasNoCode() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String event = eventId(studentBooked(ana, TEN));
        coachCancelsEvent(coach, event, "Lluvia").andExpect(status().isOk());
        goTo("09:50");
        qr(coach, event).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    // ================================================================= scanning

    @Test
    void aScanBeforeTheClassStartsIsRefusedAndChangesNothing() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String attendance = attendanceId(studentBooked(ana, TEN));
        goTo("09:50");
        scan(ana, token(coach, eventIdOf(coach))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLASS_NOT_STARTED"));
        assertThat(classesUsedOf(coach, ana)).isZero();
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(auditActions(coach, attendance)).doesNotContain("MARK", "CONFIRM");
    }

    private String eventIdOf(CoachCtx coach) throws Exception {
        return JsonPath.<List<String>>read(agenda(coach, DAY, DAY), "$.events[*].id").get(0);
    }

    @Test
    void aValidScanMarksAttendedUsesTheClassConfirmsAndLeavesTheAudit() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var booked = studentBooked(ana, TEN);
        String attendance = attendanceId(booked);
        goTo("10:05");
        scan(ana, token(coach, eventId(booked))).andExpect(status().isOk()).andExpect(jsonPath("$.markedAttended").value(true))
                .andExpect(jsonPath("$.alreadyConfirmed").value(false)).andExpect(jsonPath("$.attendance.status").value("ATTENDED"))
                .andExpect(jsonPath("$.attendance.studentConfirmed").value(true)).andExpect(jsonPath("$.attendance.confirmationMethod").value("QR"))
                .andExpect(jsonPath("$.attendance.onlyMarkedByCoach").value(false)).andExpect(jsonPath("$.attendance.studentName").doesNotExist());
        assertThat(classesUsedOf(coach, ana)).isEqualTo(1);
        assertThat(auditLines(coach, attendance)).contains("MARK/QR", "CONFIRM/QR");
        assertThat(auditLines(coach, attendance).indexOf("MARK/QR")).isLessThan(auditLines(coach, attendance).indexOf("CONFIRM/QR"));
    }

    @Test
    void theLastClassScannedClosesTheCycleAsCompleted() throws Exception {
        var coach = newCoach(1);
        var ana = personalized(coach, "Ana");
        var booked = studentBooked(ana, TEN);
        goTo("10:05");
        scan(ana, token(coach, eventId(booked))).andExpect(status().isOk());
        assertThat(JsonPath.<String>read(json(mvc.perform(withToken(get("/api/coach/students/" + ana.id() + "/cycles"), coach.token()))
                .andExpect(status().isOk()).andReturn()), "$[0].status")).isEqualTo("COMPLETED");
    }

    @Test
    void aSecondScanIsIdempotentNeverASecondDeduction() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var booked = studentBooked(ana, TEN);
        String attendance = attendanceId(booked);
        goTo("10:05");
        String token = token(coach, eventId(booked));
        scan(ana, token).andExpect(status().isOk());
        scan(ana, token).andExpect(status().isOk()).andExpect(jsonPath("$.markedAttended").value(false)).andExpect(jsonPath("$.alreadyConfirmed").value(true));
        assertThat(classesUsedOf(coach, ana)).isEqualTo(1);
        assertThat(auditLines(coach, attendance).stream().filter(l -> l.startsWith("MARK") || l.startsWith("CONFIRM")).count()).isEqualTo(2);
    }

    @Test
    void aClassTheCoachAlreadyMarkedOnlyGetsTheConfirmationAndANoShowIsNotFlipped() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        var anaBooked = studentBooked(ana, TEN);
        var betoBooked = studentBooked(beto, at(DAY, "12:00"));
        String anaPlace = attendanceId(anaBooked);
        String betoPlace = attendanceId(betoBooked);

        goTo("10:30");
        mark(coach, anaPlace, "ATTENDED").andExpect(status().isOk()).andExpect(jsonPath("$.onlyMarkedByCoach").value(true));
        scan(ana, token(coach, eventId(anaBooked))).andExpect(status().isOk()).andExpect(jsonPath("$.markedAttended").value(false))
                .andExpect(jsonPath("$.attendance.status").value("ATTENDED")).andExpect(jsonPath("$.attendance.onlyMarkedByCoach").value(false));
        assertThat(classesUsedOf(coach, ana)).isEqualTo(1);
        assertThat(auditLines(coach, anaPlace)).contains("MARK/COACH", "CONFIRM/QR").doesNotContain("MARK/QR");

        goTo("12:30");
        mark(coach, betoPlace, "NO_SHOW").andExpect(status().isOk());
        scan(beto, token(coach, eventId(betoBooked))).andExpect(status().isOk()).andExpect(jsonPath("$.markedAttended").value(false))
                .andExpect(jsonPath("$.attendance.status").value("NO_SHOW")).andExpect(jsonPath("$.attendance.studentConfirmed").value(true));
        assertThat(classesUsedOf(coach, beto)).isEqualTo(1);
    }

    @Test
    void anExpiredForgedForeignOrOutsiderCodeIsRefused() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        var carla = personalized(coach, "Carla");
        var anaBooked = studentBooked(ana, TEN);
        var betoBooked = studentBooked(beto, at(DAY, "12:00"));
        goTo("10:05");
        String anaToken = token(coach, eventId(anaBooked));
        goTo("12:05");
        String betoToken = token(coach, eventId(betoBooked));

        // a code of ANOTHER event: genuine, but Ana is not in that event -> the same 404 as any place that is not hers
        scan(ana, betoToken).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_FOUND"));
        // a student who does not belong to the event
        goTo("10:05");
        scan(carla, token(coach, eventId(anaBooked))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_FOUND"));
        // expired: more than one minute old
        clock.set(Instant.parse(at(DAY, "10:05")).plusSeconds(61));
        scan(ana, anaToken).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QR"));
        // forged / garbage
        scan(ana, anaToken.substring(0, anaToken.length() - 2) + "AA").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QR"));
        scan(ana, "no-es-un-codigo").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QR"));
        mvc.perform(withToken(post("/api/student/attendances/confirm-qr"), ana.token()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(classesUsedOf(coach, ana)).isZero();
    }

    @Test
    void aCodeOfAnotherCoachDoesNotReachTheStudentOfThisOne() throws Exception {
        var coachA = newCoach(8);
        var coachB = newCoach(8);
        var ana = personalized(coachA, "Ana");
        var zoe = personalized(coachB, "Zoe");
        var booked = studentBooked(ana, TEN);
        goTo("10:05");
        scan(zoe, token(coachA, eventId(booked))).andExpect(status().isNotFound());       // tenant filter: the place simply does not exist for Zoe
    }

    @Test
    void theScanClosesTwoHoursAfterTheEndEvenWithAStillValidCode() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var booked = studentBooked(ana, TEN);
        clock.set(Instant.parse(at(DAY, "12:59")).plusSeconds(50));
        String token = token(coach, eventId(booked));                                    // issued 10 s before the window closes
        clock.set(Instant.parse(at(DAY, "13:00")).plusSeconds(1));
        scan(ana, token).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QR_WINDOW_CLOSED"));
        assertThat(classesUsedOf(coach, ana)).isZero();
    }

    @Test
    void failedScansAreThrottledPerStudentAndTheTokenNeverReachesTheLogs(CapturedOutput output) throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        var booked = studentBooked(ana, TEN);
        goTo("10:05");
        String genuine = token(coach, eventId(booked));
        String secretLooking = "SECRETO-QUE-NO-DEBE-VERSE-EN-LOS-LOGS";
        for (int i = 0; i < 10; i++) {
            mvc.perform(fromIp(withToken(post("/api/student/attendances/confirm-qr"), beto.token()), "10.77.0." + i).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"" + secretLooking + "\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(fromIp(withToken(post("/api/student/attendances/confirm-qr"), beto.token()), "10.77.1.1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + genuine + "\"}")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        scan(ana, genuine).andExpect(status().isOk());                                    // another student is not affected
        assertThat(output.getAll()).doesNotContain(genuine).doesNotContain(secretLooking);
    }

    @Test
    void theClientAddressAlsoThrottlesTheScanEndpoint() throws Exception {
        var coach = newCoach(8);
        var students = new StudentCtx[6];
        for (int i = 0; i < students.length; i++) {
            students[i] = personalized(coach, "Alumno" + i);
        }
        for (int i = 0; i < 31; i++) {
            var s = students[i % students.length];
            var result = mvc.perform(fromIp(withToken(post("/api/student/attendances/confirm-qr"), s.token()), "10.88.8.8").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"basura\"}")).andReturn();
            if (i < 30) {
                assertThat(result.getResponse().getStatus()).isIn(400, 429);          // 429 from a student's own limit is possible too
            }
        }
        mvc.perform(fromIp(withToken(post("/api/student/attendances/confirm-qr"), students[0].token()), "10.88.8.8").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"basura\"}")).andExpect(status().isTooManyRequests());
    }

    // ================================================================= confirming afterwards

    @Test
    void confirmingLaterOnlyRecordsItAndNeverMarksNorDeducts() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var booked = studentBooked(ana, TEN);
        String place = attendanceId(booked);

        goTo("09:59");
        confirmLater(ana, place).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLASS_NOT_STARTED"));
        goTo("10:30");                                                                   // started and still unmarked: confirming does not mark it
        confirmLater(ana, place).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyConfirmed").value(false))
                .andExpect(jsonPath("$.attendance.status").value("SCHEDULED")).andExpect(jsonPath("$.attendance.confirmationMethod").value("LATER"));
        assertThat(classesUsedOf(coach, ana)).isZero();
        confirmLater(ana, place).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyConfirmed").value(true));
        assertThat(auditLines(coach, place)).containsOnlyOnce("CONFIRM/LATER");

        // the coach sees it among the pending marks, flagged as confirmed
        mvc.perform(withToken(get("/api/coach/attendances/pending"), coach.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].studentConfirmed").value(true)).andExpect(jsonPath("$[0].confirmationMethod").value("LATER"))
                .andExpect(jsonPath("$[0].status").value("SCHEDULED"));
        // marking it afterwards works as always and deducts once
        mark(coach, place, "ATTENDED").andExpect(status().isOk()).andExpect(jsonPath("$.onlyMarkedByCoach").value(false));
        assertThat(classesUsedOf(coach, ana)).isEqualTo(1);
    }

    @Test
    void theConfirmationWindowFollowsTheCoachSettingAndTheExactLimitStillWorks() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        String betoPlace = attendanceId(studentBooked(beto, at(DAY, "12:00")));
        mark(coach, anaPlace, "ATTENDED").andExpect(status().is4xxClientError());        // not started yet: refused
        goTo("10:05");
        mark(coach, anaPlace, "ATTENDED").andExpect(status().isOk());

        clock.set(Instant.parse(at("2026-10-15", "10:00")));                             // 72 h after the start: still allowed
        confirmLater(ana, anaPlace).andExpect(status().isOk());
        clock.set(Instant.parse(at("2026-10-15", "12:00")).plusSeconds(1));              // Beto's class started at 12:00 -> one second past 72 h
        mark(coach, betoPlace, "NO_SHOW").andExpect(status().isOk());
        confirmLater(beto, betoPlace).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIRMATION_WINDOW_CLOSED"));
    }

    @Test
    void aStudentCannotConfirmAnotherStudentsClassOrACancelledOne() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        String betoPlace = attendanceId(studentBooked(beto, at(DAY, "12:00")));
        goTo("10:30");
        confirmLater(beto, anaPlace).andExpect(status().isNotFound());                   // same answer as a place that does not exist
        confirmLater(ana, "00000000-0000-0000-0000-000000000000").andExpect(status().isNotFound());
        coachCancelsAttendance(coach, anaPlace, "Perdonada", null).andExpect(status().isOk());
        confirmLater(ana, anaPlace).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(betoPlace).isNotEqualTo(anaPlace);
    }

    // ================================================================= the audit of the coach's own marking

    @Test
    void theCoachsMarksLeaveAuditLinesAlsoWhenMarkingAWholeEventAndFlippingTheResult() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var anaBooked = studentBooked(ana, TEN);
        String anaPlace = attendanceId(anaBooked);
        String betoPlace = attendanceId(studentBooked(beto, TEN));
        goTo("10:30");
        markEvent(coach, eventId(anaBooked), anaPlace, "ATTENDED", betoPlace, "NO_SHOW").andExpect(status().isOk());
        mark(coach, betoPlace, "ATTENDED").andExpect(status().isOk());                    // NO_SHOW -> ATTENDED: no new deduction, one more line
        assertThat(auditLines(coach, anaPlace)).containsOnlyOnce("MARK/COACH");
        assertThat(auditLines(coach, betoPlace).stream().filter("MARK/COACH"::equals).count()).isEqualTo(2);
        assertThat(classesUsedOf(coach, beto)).isEqualTo(1);
        // a mark that fails writes nothing
        mark(coach, anaPlace, "ATTENDED").andExpect(status().isConflict());
        assertThat(auditLines(coach, anaPlace)).containsOnlyOnce("MARK/COACH");
    }

    @Test
    void onlyTheOwningCoachReadsTheAuditAndNeverAStudent() throws Exception {
        var coachA = newCoach(8);
        var coachB = newCoach(8);
        var ana = personalized(coachA, "Ana");
        String place = attendanceId(studentBooked(ana, TEN));
        mvc.perform(withToken(get("/api/coach/attendances/" + place + "/audit"), coachB.token())).andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/attendances/" + place + "/audit"), ana.token())).andExpect(status().isForbidden());
        mvc.perform(withToken(get("/api/coach/attendances/00000000-0000-0000-0000-000000000000/audit"), coachA.token())).andExpect(status().isNotFound());
    }

    // ================================================================= the coach's day, the sheet, the history

    @Test
    void todayIsTheBogotaDayWithAttendeesConfirmationAndSeats() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var anaBooked = studentBooked(ana, TEN);
        studentBooked(beto, TEN);
        studentBooked(ana, at("2026-10-13", "06:00"));                                   // tomorrow: not in today
        goTo("10:05");
        scan(ana, token(coach, eventId(anaBooked))).andExpect(status().isOk());

        var today = mvc.perform(withToken(get("/api/coach/today"), coach.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(DAY)).andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].occupied").value(2)).andExpect(jsonPath("$.events[0].capacity").value(4))
                .andExpect(jsonPath("$.events[0].freeSeats").value(2)).andReturn();
        String body = json(today);
        assertThat(JsonPath.<List<String>>read(body, "$.events[0].attendees[*].studentName")).containsExactlyInAnyOrder("Ana", "Beto");
        assertThat(JsonPath.<List<Boolean>>read(body, "$.events[0].attendees[?(@.studentName=='Ana')].studentConfirmed")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(body, "$.events[0].attendees[?(@.studentName=='Beto')].studentConfirmed")).containsExactly(false);
        assertThat(JsonPath.<List<String>>read(body, "$.events[0].attendees[?(@.studentName=='Ana')].confirmationMethod")).containsExactly("QR");
    }

    @Test
    void todayUsesTheBogotaDayNotTheUtcDay() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        studentBooked(ana, at(DAY, "19:00"));
        clock.set(Instant.parse("2026-10-13T03:30:00Z"));                                 // 22:30 of the 12th in Bogota, already the 13th in UTC
        mvc.perform(withToken(get("/api/coach/today"), coach.token())).andExpect(jsonPath("$.date").value(DAY))
                .andExpect(jsonPath("$.events.length()").value(1));
        clock.set(Instant.parse("2026-10-13T05:30:00Z"));                                 // 00:30 of the 13th in Bogota
        mvc.perform(withToken(get("/api/coach/today"), coach.token())).andExpect(jsonPath("$.date").value("2026-10-13"))
                .andExpect(jsonPath("$.events.length()").value(0));
    }

    @Test
    void theSheetHasTheStructureOfTheSpreadsheet() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var first = studentBooked(ana, TEN);
        String second = attendanceId(studentBooked(ana, at("2026-10-13", "10:00")));
        studentBooked(ana, at("2026-10-14", "10:00"));
        goTo("10:05");
        scan(ana, token(coach, eventId(first))).andExpect(status().isOk());
        coachCancelsAttendance(coach, second, "Lluvia", null).andExpect(status().isOk());

        String body = json(mvc.perform(withToken(get("/api/coach/students/" + ana.id() + "/sheet"), coach.token())).andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<String>read(body, "$.header.fullName")).isEqualTo("Ana");
        assertThat(JsonPath.<String>read(body, "$.header.whatsappPhone")).isEqualTo("3001234567");
        assertThat(JsonPath.<String>read(body, "$.header.cycleStart")).isEqualTo("2026-10-06");
        assertThat(JsonPath.<String>read(body, "$.header.cycleEnd")).isEqualTo("2026-11-06");
        assertThat(JsonPath.<List<Integer>>read(body, "$.classes[*].number")).containsExactly(1, 2);
        assertThat(JsonPath.<List<String>>read(body, "$.classes[*].date")).containsExactly(DAY, "2026-10-14");
        assertThat(JsonPath.<List<String>>read(body, "$.classes[*].time")).containsExactly("10:00", "10:00");
        assertThat(JsonPath.<List<String>>read(body, "$.classes[*].status")).containsExactly("ATTENDED", "SCHEDULED");
        assertThat(JsonPath.<List<Boolean>>read(body, "$.classes[*].studentConfirmed")).containsExactly(true, false);
        assertThat(JsonPath.<List<String>>read(body, "$.otherEntries[*].status")).containsExactly("CANCELLED_BY_COACH");
        assertThat(JsonPath.<List<Object>>read(body, "$.otherEntries[*].number")).containsOnlyNulls();
        assertThat(body).doesNotContain("measure").doesNotContain("medida");               // no measurements in this system
    }

    @Test
    void theSheetIsOfTheCoachsOwnStudentAndItsCycleMustBeTheirs() throws Exception {
        var coachA = newCoach(8);
        var coachB = newCoach(8);
        var ana = personalized(coachA, "Ana");
        var beto = personalized(coachA, "Beto");
        String betoCycle = JsonPath.read(activeCycle(coachA, beto), "$.id");
        mvc.perform(withToken(get("/api/coach/students/" + ana.id() + "/sheet"), coachB.token())).andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/students/" + ana.id() + "/sheet").param("cycleId", betoCycle), coachA.token()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CYCLE_NOT_FOUND"));
        mvc.perform(withToken(get("/api/coach/students/" + ana.id() + "/sheet"), ana.token())).andExpect(status().isForbidden());
        // a student without any cycle still has a sheet (header only)
        var luis = newStudent(coachA, "Luis");
        mvc.perform(withToken(get("/api/coach/students/" + luis.id() + "/sheet"), coachA.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.cycle").doesNotExist()).andExpect(jsonPath("$.classes.length()").value(0));
    }

    @Test
    void theStudentsHistoryHasTheirClassesPerCycleAndNeverAnybodyElse() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var anaBooked = studentBooked(ana, TEN);
        studentBooked(beto, TEN);                                                         // shares the event with Ana
        goTo("10:30");

        String body = json(mvc.perform(withToken(get("/api/student/history"), ana.token())).andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<String>read(body, "$.activeCycle.status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<Integer>read(body, "$.cycles.length()")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(body, "$.cycles[0].classes[*].date")).containsExactly(DAY);
        assertThat(JsonPath.<List<String>>read(body, "$.cycles[0].classes[*].time")).containsExactly("10:00");
        assertThat(JsonPath.<List<Boolean>>read(body, "$.cycles[0].classes[*].canConfirm")).containsExactly(true);   // started, in the window, unconfirmed
        // nothing of Beto anywhere in Ana's history
        assertThat(body).doesNotContain("Beto").doesNotContain(beto.id()).doesNotContain(beto.email());
        String betoPlace = JsonPath.<List<String>>read(json(mvc.perform(withToken(get("/api/student/history"), beto.token())).andReturn()), "$.cycles[0].classes[*].attendanceId").get(0);
        assertThat(body).doesNotContain(betoPlace);

        confirmLater(ana, attendanceId(anaBooked)).andExpect(status().isOk());
        mvc.perform(withToken(get("/api/student/history"), ana.token())).andExpect(jsonPath("$.cycles[0].classes[0].studentConfirmed").value(true))
                .andExpect(jsonPath("$.cycles[0].classes[0].canConfirm").value(false))
                .andExpect(jsonPath("$.cycles[0].classes[0].confirmationMethod").value("LATER"));
        mvc.perform(withToken(get("/api/student/history"), coach.token())).andExpect(status().isForbidden());
    }
}
