package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The flags the server computes so the client never re-implements a rule: phase, canMark, canShowQr, minor and canCancel. Event:
 * Monday 2026-10-12 10:00-11:00 Bogota; the cancellation window is 2 h, the QR opens 15 min before and closes 2 h after the end.
 */
class TodayFlagsTest extends SchedulingApiTest {

    private static final String DAY = "2026-10-12";
    private static final String TEN = at(DAY, "10:00");

    private String today(CoachCtx coach) throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/today"), coach.token())).andExpect(status().isOk()).andReturn());
    }

    @Test
    void phaseFollowsTheServerClockToTheSecond() throws Exception {
        var coach = newCoach(8);
        studentBooked(personalized(coach, "Ana"), TEN);
        String[][] cases = {{"09:59:59", "UPCOMING"}, {"10:00", "NOW"}, {"10:59:59", "NOW"}, {"11:00", "PAST"}};
        for (String[] c : cases) {
            goTo(DAY, c[0]);
            assertThat(JsonPath.<String>read(today(coach), "$.events[0].phase")).as(c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    void canShowQrFollowsTheCoachWindowAndACancelledEventNeverHasIt() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        studentBooked(ana, TEN);
        goTo(DAY, "09:44:59");
        assertThat(JsonPath.<Boolean>read(today(coach), "$.events[0].canShowQr")).isFalse();
        goTo(DAY, "09:45");
        assertThat(JsonPath.<Boolean>read(today(coach), "$.events[0].canShowQr")).isTrue();
        goTo(DAY, "13:00");
        assertThat(JsonPath.<Boolean>read(today(coach), "$.events[0].canShowQr")).isTrue();
        goTo(DAY, "13:00:01");
        assertThat(JsonPath.<Boolean>read(today(coach), "$.events[0].canShowQr")).isFalse();
    }

    @Test
    void canMarkIsPerAttendeeAndNeedsTheStartAndAnActiveCycle() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        studentBooked(ana, TEN);
        studentBooked(beto, TEN);
        goTo(DAY, "09:59");
        assertThat(JsonPath.<List<Boolean>>read(today(coach), "$.events[0].attendees[*].canMark")).containsExactly(false, false);
        goTo(DAY, "10:00");
        assertThat(JsonPath.<List<Boolean>>read(today(coach), "$.events[0].attendees[*].canMark")).containsExactly(true, true);
    }

    @Test
    void canMarkIsFalseWhenTheStudentsCycleIsNoLongerActive() throws Exception {
        var coach = newCoach(1);
        var ana = personalized(coach, "Ana");
        var booked = studentBooked(ana, TEN);
        goTo(DAY, "10:30");
        mark(coach, attendanceId(booked), "ATTENDED").andExpect(status().isOk());       // last class: the cycle completes
        assertThat(JsonPath.<Boolean>read(today(coach), "$.events[0].attendees[0].canMark")).isFalse();
    }

    @Test
    void minorIsDecidedByTheServerPerStudent() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        String guardianEmail = uniqueEmail("madre");
        String json = json(mvc.perform(withToken(post("/api/coach/students"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Mateo\",\"birthDate\":\"2010-05-01\",\"guardian\":{\"name\":\"Marta Perez\","
                        + "\"relationship\":\"madre\",\"phone\":\"3001234567\",\"email\":\"" + guardianEmail + "\"}}"))
                .andExpect(status().isCreated()).andReturn());
        String mateoId = JsonPath.read(json, "$.student.id");
        pay(coach.token(), mateoId, coach.semiPlan(), "");
        studentBooked(ana, TEN);
        coachBooks(coach, new StudentCtx(mateoId, "Mateo", guardianEmail, null), TEN, false, null).andExpect(status().isCreated());
        goTo(DAY, "09:00");
        String body = today(coach);
        assertThat(JsonPath.<List<Boolean>>read(body, "$.events[0].attendees[?(@.studentName=='Ana')].minor")).containsExactly(false);
        assertThat(JsonPath.<List<Boolean>>read(body, "$.events[0].attendees[?(@.studentName=='Mateo')].minor")).containsExactly(true);
    }

    @Test
    void canCancelIsTheStudentsWindowAndOnlyForAScheduledClass() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String attendance = attendanceId(studentBooked(ana, TEN));
        goTo(DAY, "08:00");
        assertThat(JsonPath.<List<Boolean>>read(studentSessions(ana), "$[*].canCancel")).containsExactly(true);
        goTo(DAY, "08:00:01");
        assertThat(JsonPath.<List<Boolean>>read(studentSessions(ana), "$[*].canCancel")).containsExactly(false);
        // agrees with what the server actually does
        studentCancels(ana, attendance, null).andExpect(status().isConflict());
    }

    @Test
    void pendingAttendancesCarryCanMark() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        studentBooked(ana, TEN);
        goTo(DAY, "12:00");
        String body = json(mvc.perform(withToken(get("/api/coach/attendances/pending"), coach.token())).andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<List<Boolean>>read(body, "$[*].canMark")).containsExactly(true);
    }

    @Test
    void theCodeSaysWhenItIsAvailable() throws Exception {
        var coach = newCoach(8);
        String event = eventId(studentBooked(personalized(coach, "Ana"), TEN));
        goTo(DAY, "10:00");
        String body = json(mvc.perform(withToken(get("/api/coach/events/" + event + "/qr"), coach.token())).andExpect(status().isOk()).andReturn());
        assertThat(java.time.Instant.parse(JsonPath.read(body, "$.availableFrom"))).isEqualTo(java.time.Instant.parse(at(DAY, "09:45")));
        assertThat(java.time.Instant.parse(JsonPath.read(body, "$.availableUntil"))).isEqualTo(java.time.Instant.parse(at(DAY, "13:00")));
    }
}
