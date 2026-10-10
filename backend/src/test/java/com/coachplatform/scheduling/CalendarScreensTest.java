package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The server side of the coach's calendar screens (week of the agenda, who can be booked and what, the off-schedule exception, plans
 * with their student count, one-day schedule edits and blocks that release classes). The clock starts at noon on Tue 2026-10-06; a
 * cycle opened that day ends on 2026-11-06; every test creates its own coach with 06:00-20:00 every day.
 */
class CalendarScreensTest extends SchedulingApiTest {

    private static final String MON = "2026-10-12";
    private static final String TUE = "2026-10-13";
    private static final String TEN = at(MON, "10:00");

    private ResultActions getAs(CoachCtx c, String path, String... params) throws Exception {
        var req = withToken(get(path), c.token());
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req);
    }

    private String week(CoachCtx c, String date) throws Exception {
        return json(getAs(c, "/api/coach/agenda/week", "date", date).andExpect(status().isOk()).andReturn());
    }

    private String options(CoachCtx c, StudentCtx s, String date) throws Exception {
        return json(getAs(c, "/api/coach/students/" + s.id() + "/booking-options", "date", date).andExpect(status().isOk()).andReturn());
    }

    private ResultActions putDay(CoachCtx c, int day, String windowsJson) throws Exception {
        return mvc.perform(withToken(put("/api/coach/availability/days/" + day), c.token()).contentType(MediaType.APPLICATION_JSON).content(windowsJson));
    }

    // ================================================================= the week

    @Test
    void theWeekListsEachDaysClassesFreeSlotsAndBlocksInTimeOrderWithTheCounts() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana Gómez");
        var beto = semi(coach, "Beto Ruiz");
        studentBooked(ana, TEN);
        studentBooked(beto, at(MON, "11:00"));
        createBlock(coach, MON, "14:00", "16:00", "Reunión", List.of()).andExpect(status().isCreated());

        String w = week(coach, "2026-10-14");                                           // a Wednesday: the week starts on the Monday before
        assertThat(JsonPath.<String>read(w, "$.weekStart")).isEqualTo(MON);
        assertThat(JsonPath.<List<?>>read(w, "$.days")).hasSize(7);
        assertThat(JsonPath.<String>read(w, "$.days[0].localDate")).isEqualTo(MON);
        assertThat(JsonPath.<Integer>read(w, "$.days[0].classCount")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(w, "$.days[0].freeCount")).as("14 slots - 2 classes - 2 blocked").isEqualTo(10);
        assertThat(JsonPath.<Boolean>read(w, "$.days[0].hasAvailability")).isTrue();
        assertThat(JsonPath.<List<String>>read(w, "$.days[0].items[?(@.kind=='EVENT')].event.attendees[*].studentName"))
                .containsExactly("Ana Gómez", "Beto Ruiz");                              // full names
        assertThat(JsonPath.<List<String>>read(w, "$.days[0].items[?(@.kind=='BLOCK')].blockReason")).containsExactly("Reunión");
        assertThat(JsonPath.<List<Boolean>>read(w, "$.days[0].items[?(@.kind=='BLOCK')].allDay")).containsExactly(false);
        List<String> starts = JsonPath.read(w, "$.days[0].items[*].startsAt");
        assertThat(starts).isSorted();
        assertThat(JsonPath.<List<String>>read(w, "$.days[0].items[*].localTime")).contains("10:00", "14:00").doesNotContain("15:00");   // 14-16 is blocked
        assertThat(JsonPath.<Integer>read(w, "$.days[1].classCount")).isZero();
        assertThat(JsonPath.<Integer>read(w, "$.days[1].freeCount")).isEqualTo(14);
    }

    @Test
    void anAllDayBlockIsOneRowAndADayWithoutWindowsSaysSo() throws Exception {
        var coach = newCoach(8);
        createBlock(coach, TUE, null, null, "Festivo", List.of()).andExpect(status().isCreated());
        putDay(coach, 7, "[]").andExpect(status().isOk());

        String w = week(coach, MON);
        assertThat(JsonPath.<List<String>>read(w, "$.days[1].items[*].kind")).containsExactly("BLOCK");
        assertThat(JsonPath.<List<Boolean>>read(w, "$.days[1].items[*].allDay")).containsExactly(true);
        assertThat(JsonPath.<Integer>read(w, "$.days[1].freeCount")).isZero();
        assertThat(JsonPath.<Boolean>read(w, "$.days[6].hasAvailability")).isFalse();
        assertThat(JsonPath.<List<?>>read(w, "$.days[6].items")).isEmpty();
    }

    @Test
    void theWeekOfOneCoachNeverShowsTheClassesOrBlocksOfAnother() throws Exception {
        var a = newCoach(8);
        var b = newCoach(8);
        studentBooked(personalized(a, "Ana"), TEN);
        createBlock(a, TUE, null, null, "Festivo", List.of()).andExpect(status().isCreated());

        String wb = week(b, MON);
        assertThat(JsonPath.<Integer>read(wb, "$.days[0].classCount")).isZero();
        assertThat(JsonPath.<List<?>>read(wb, "$.days[1].items[?(@.kind=='BLOCK')]")).isEmpty();
        assertThat(JsonPath.<Integer>read(wb, "$.days[1].freeCount")).isEqualTo(14);
    }

    // ================================================================= who can be booked

    @Test
    void theSelectorSaysWhoCanBeBookedAndWhyNot() throws Exception {
        var coach = newCoach(1);
        var ana = personalized(coach, "Ana");
        newStudent(coach, "Bruno");                                                      // never paid
        studentBooked(ana, TEN);                                                         // the only class of the plan is now booked

        String list = json(getAs(coach, "/api/coach/booking/students").andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<List<String>>read(list, "$[*].fullName")).containsExactly("Ana", "Bruno");
        assertThat(JsonPath.<Boolean>read(list, "$[0].canBook")).isFalse();
        assertThat(JsonPath.<String>read(list, "$[0].blockedReason")).isEqualTo("NO_CLASSES_LEFT");
        assertThat(JsonPath.<Integer>read(list, "$[0].classesAvailable")).isZero();
        assertThat(JsonPath.<String>read(list, "$[0].modality")).isEqualTo("PERSONALIZED");
        assertThat(JsonPath.<String>read(list, "$[1].blockedReason")).isEqualTo("NO_ACTIVE_CYCLE");
        assertThat(JsonPath.<Object>read(list, "$[1].modality")).isNull();
    }

    @Test
    void theSelectorCountsWhatIsFreeToBookNotWhatIsLeftToUse() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        studentBooked(ana, TEN);
        studentBooked(ana, at(MON, "11:00"));

        String list = json(getAs(coach, "/api/coach/booking/students").andReturn());
        assertThat(JsonPath.<Integer>read(list, "$[0].classesAvailable")).as("8 included - 2 booked").isEqualTo(6);
        assertThat(JsonPath.<Boolean>read(list, "$[0].canBook")).isTrue();
        assertThat(JsonPath.<Object>read(list, "$[0].blockedReason")).isNull();
    }

    // ================================================================= what can be booked

    @Test
    void theOptionsMarkWhichSlotsNeedAnExceptionAndWhichRule() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var carla = personalized(coach, "Carla");
        var dani = semi(coach, "Dani");
        var eva = semi(coach, "Eva");
        studentBooked(beto, TEN);                                                        // semi event with seats: Ana can join
        studentBooked(carla, at(MON, "11:00"));                                          // a personalized event: other modality for Ana
        String full = eventId(studentBooked(dani, at(MON, "12:00")));
        studentBooked(eva, at(MON, "12:00"));
        changeCapacity(coach, full, 2).andExpect(status().isOk());                       // 12:00 is now full
        studentBooked(ana, at(MON, "13:00"));                                            // Ana is already in 13:00

        String o = options(coach, ana, MON);
        assertThat(JsonPath.<Boolean>read(o, "$.canBook")).isTrue();
        assertThat(JsonPath.<Integer>read(o, "$.classesAvailable")).isEqualTo(7);
        assertThat(JsonPath.<String>read(o, "$.modality")).isEqualTo("SEMI_PERSONALIZED");
        assertThat(JsonPath.<List<String>>read(o, "$.slots[*].localTime")).hasSize(13).doesNotContain("13:00");   // 14 slots, she is in one
        assertThat(JsonPath.<List<Boolean>>read(o, "$.slots[?(@.localTime=='10:00')].needsOverride")).containsExactly(false);
        assertThat(JsonPath.<List<Integer>>read(o, "$.slots[?(@.localTime=='10:00')].occupied")).containsExactly(1);
        assertThat(JsonPath.<List<String>>read(o, "$.slots[?(@.localTime=='11:00')].blockedBy")).containsExactly("MODALITY_MISMATCH");
        assertThat(JsonPath.<List<String>>read(o, "$.slots[?(@.localTime=='12:00')].blockedBy")).containsExactly("EVENT_FULL");
        assertThat(JsonPath.<List<Object>>read(o, "$.slots[?(@.localTime=='08:00')].blockedBy")).containsExactly((Object) null);
        assertThat(JsonPath.<List<?>>read(o, "$.dayWindows")).hasSize(1);

        String oc = options(coach, carla, MON);                                          // personalized: a taken block is SLOT_TAKEN
        assertThat(JsonPath.<List<String>>read(oc, "$.slots[?(@.localTime=='11:00')]")).isEmpty();   // she is in it
        assertThat(JsonPath.<List<String>>read(oc, "$.slots[?(@.localTime=='10:00')].blockedBy")).containsExactly("MODALITY_MISMATCH");
    }

    @Test
    void aPersonalizedStudentSeesATakenPersonalizedSlotAsAnException() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        studentBooked(ana, TEN);

        String o = options(coach, beto, MON);
        assertThat(JsonPath.<List<String>>read(o, "$.slots[?(@.localTime=='10:00')].blockedBy")).containsExactly("SLOT_TAKEN");
        assertThat(JsonPath.<List<Boolean>>read(o, "$.slots[?(@.localTime=='10:00')].needsOverride")).containsExactly(true);
    }

    @Test
    void theOptionsNeverListThePastBlockedHoursOrDaysAfterTheCycle() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        createBlock(coach, MON, "14:00", "16:00", "Reunión", List.of()).andExpect(status().isCreated());

        String o = options(coach, ana, MON);
        assertThat(JsonPath.<List<String>>read(o, "$.slots[*].localTime")).doesNotContain("14:00", "15:00").hasSize(12);

        goTo(MON, "10:30");
        assertThat(JsonPath.<List<String>>read(options(coach, ana, MON), "$.slots[*].localTime")).doesNotContain("06:00", "10:00").contains("11:00");

        assertThat(JsonPath.<List<?>>read(options(coach, ana, "2026-11-09"), "$.slots")).as("the cycle ends on 2026-11-06").isEmpty();
        assertThat(JsonPath.<String>read(options(coach, ana, "2026-11-09"), "$.cycleEndDate")).isEqualTo("2026-11-06");
    }

    @Test
    void withoutAnActiveCycleThereAreNoOptionsAndAStudentOfAnotherCoachIs404() throws Exception {
        var a = newCoach(8);
        var b = newCoach(8);
        var bruno = newStudent(a, "Bruno");

        String o = options(a, bruno, MON);
        assertThat(JsonPath.<Boolean>read(o, "$.canBook")).isFalse();
        assertThat(JsonPath.<String>read(o, "$.blockedReason")).isEqualTo("NO_ACTIVE_CYCLE");
        assertThat(JsonPath.<List<?>>read(o, "$.slots")).isEmpty();
        getAs(b, "/api/coach/students/" + bruno.id() + "/booking-options", "date", MON).andExpect(status().isNotFound());
    }

    // ================================================================= the off-schedule exception

    @Test
    void theCoachCanBookOutsideTheScheduleOnAQuarterHourAndTheReasonIsInTheAudit() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");

        coachBooks(coach, ana, at(MON, "21:00"), false, null).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
        coachBooks(coach, ana, at(MON, "21:10"), true, "Reposición").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_START_TIME"));
        coachBooks(coach, ana, at(MON, "21:00"), true, "  ").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        coachBooks(coach, ana, at(MON, "21:00"), true, "x".repeat(201)).andExpect(status().isBadRequest());
        studentBooks(ana, at(MON, "21:00")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));   // never a student

        var booked = coachBooks(coach, ana, at(MON, "21:00"), true, "Reposición de la clase del jueves").andExpect(status().isCreated())
                .andExpect(jsonPath("$.override").value(true)).andExpect(jsonPath("$.overrideReason").value("Reposición de la clase del jueves")).andReturn();
        String audit = json(mvc.perform(withToken(get("/api/coach/attendances/" + attendanceId(booked) + "/audit"), coach.token()))
                .andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<List<String>>read(audit, "$[?(@.action=='BOOK')].reason")).containsExactly("Reposición de la clase del jueves");
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.classesUsed")).isZero();
        assertThat(JsonPath.<Integer>read(json(getAs(coach, "/api/coach/booking/students").andReturn()), "$[0].classesAvailable"))
                .as("it still takes one class of the plan").isEqualTo(7);
    }

    @Test
    void theExceptionNeverEntersABlockNorBeatsTheQuotaOrTheDeadline() throws Exception {
        var coach = newCoach(1);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        createBlock(coach, MON, "21:00", "22:00", "Reunión", List.of()).andExpect(status().isCreated());
        coachBooks(coach, ana, at(MON, "21:00"), true, "Reposición").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BLOCKED"));
        coachBooks(coach, ana, at("2026-11-09", "21:00"), true, "Reposición").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OUTSIDE_CYCLE"));
        studentBooked(beto, TEN);
        coachBooks(coach, beto, at(TUE, "21:00"), true, "Reposición").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
    }

    // ================================================================= plans

    @Test
    void aPlanSaysHowManyStudentsHaveACurrentCycleBoughtWithIt() throws Exception {
        var coach = newCoach(8);
        personalized(coach, "Ana");
        semi(coach, "Beto");
        semi(coach, "Carla");
        newStudent(coach, "Dani");                                                       // never paid: counts nowhere

        String plans = json(getAs(coach, "/api/coach/plans").andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<List<Integer>>read(plans, "$[?(@.id=='" + coach.semiPlan() + "')].activeStudents")).containsExactly(2);
        assertThat(JsonPath.<List<Integer>>read(plans, "$[?(@.id=='" + coach.personalizedPlan() + "')].activeStudents")).containsExactly(1);

        mvc.perform(withToken(patch("/api/coach/plans/" + coach.semiPlan() + "/active"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.activeStudents").value(2));

        goTo("2026-11-07", "10:00");                                                     // every cycle is past its deadline
        String later = json(getAs(coach, "/api/coach/plans").andReturn());
        assertThat(JsonPath.<List<Integer>>read(later, "$[*].activeStudents")).containsOnly(0);
    }

    @Test
    void anotherCoachsCyclesNeverCountForMyPlans() throws Exception {
        var a = newCoach(8);
        var b = newCoach(8);
        semi(b, "Bruno");

        assertThat(JsonPath.<List<Integer>>read(json(getAs(a, "/api/coach/plans").andReturn()), "$[*].activeStudents")).containsOnly(0);
    }

    // ================================================================= editing one day of the schedule

    @Test
    void editingOneDayChangesOnlyThatDayAndNeverMovesABookedClass() throws Exception {
        var coach = newCoach(8);
        var other = newCoach(8);
        var ana = personalized(coach, "Ana");
        studentBooked(ana, at(TUE, "12:00"));

        putDay(coach, 2, "[{\"start\":\"06:00\",\"end\":\"10:00\"},{\"start\":\"16:00\",\"end\":\"20:00\"}]").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));
        String weekly = json(getAs(coach, "/api/coach/availability").andReturn());
        assertThat(JsonPath.<List<String>>read(weekly, "$[?(@.dayOfWeek==2)].start")).containsExactly("06:00", "16:00");
        assertThat(JsonPath.<List<String>>read(weekly, "$[?(@.dayOfWeek==3)].start")).containsExactly("06:00");
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).as("12:00 is now outside the schedule, yet it stays").containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<?>>read(json(getAs(other, "/api/coach/availability").andReturn()), "$")).hasSize(7);   // another coach: untouched

        putDay(coach, 2, "[{\"start\":\"06:00\",\"end\":\"10:00\"},{\"start\":\"09:00\",\"end\":\"12:00\"}]").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVERLAPPING_AVAILABILITY"));
        putDay(coach, 2, "[{\"start\":\"10:00\",\"end\":\"09:00\"}]").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_AVAILABILITY"));
        putDay(coach, 8, "[]").andExpect(status().isUnprocessableEntity());
        assertThat(JsonPath.<List<String>>read(json(getAs(coach, "/api/coach/availability").andReturn()), "$[?(@.dayOfWeek==2)].start"))
                .as("a rejected edit changes nothing").containsExactly("06:00", "16:00");

        putDay(coach, 2, "[]").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
    }

    // ================================================================= blocks that release classes

    @Test
    void aBlockLeavesTheStudentTheirClassAndTheyCanBookAgain() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        String place = attendanceId(studentBooked(ana, TEN));

        var preview = previewBlock(coach, MON, "09:00", "12:00").andExpect(status().isOk()).andReturn();
        assertThat(JsonPath.<Integer>read(json(preview), "$.releasedCount")).isEqualTo(1);
        List<String> ids = JsonPath.read(json(preview), "$.affected[*].attendanceId");
        assertThat(ids).containsExactly(place);

        var created = createBlock(coach, MON, "09:00", "12:00", "Reunión", ids).andExpect(status().isCreated()).andReturn();
        assertThat(JsonPath.<String>read(json(created), "$.students[0].studentName")).isEqualTo("Ana");
        assertThat(JsonPath.<Integer>read(json(created), "$.students[0].releasedClasses")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(json(created), "$.students[0].classesLeftToSchedule")).as("the class is hers again").isEqualTo(8);
        assertThat(JsonPath.<Boolean>read(json(created), "$.students[0].atRisk")).isFalse();
        assertThat(JsonPath.<Boolean>read(json(created), "$.students[0].canExtend")).isTrue();
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.classesUsed")).isZero();
        assertThat(JsonPath.<Integer>read(json(getAs(coach, "/api/coach/booking/students").andReturn()), "$[0].classesAvailable")).isEqualTo(8);
        studentBooks(ana, at(MON, "12:00")).andExpect(status().isCreated());
        studentBooks(ana, TEN).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BLOCKED"));
    }

    @Test
    void aBlockNeverTouchesAMarkedClassOrOneThatStartedAndWaitsForItsMark() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        var carla = personalized(coach, "Carla");
        String anaPlace = attendanceId(coachBooks(coach, ana, at("2026-10-06", "13:00"), false, null).andExpect(status().isCreated()).andReturn());
        String betoPlace = attendanceId(coachBooks(coach, beto, at("2026-10-06", "14:00"), false, null).andExpect(status().isCreated()).andReturn());
        String carlaPlace = attendanceId(coachBooks(coach, carla, at("2026-10-06", "15:00"), false, null).andExpect(status().isCreated()).andReturn());
        goTo("2026-10-06", "14:30");
        mark(coach, anaPlace, "ATTENDED").andExpect(status().isOk());

        var preview = previewBlock(coach, "2026-10-06", "12:30", "16:00").andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedCount").value(1)).andExpect(jsonPath("$.markedUntouched").value(1))
                .andExpect(jsonPath("$.pendingUntouched").value(1)).andReturn();
        createBlock(coach, "2026-10-06", "12:30", "16:00", "Reunión", JsonPath.read(json(preview), "$.affected[*].attendanceId"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.markedUntouched").value(1)).andExpect(jsonPath("$.pendingUntouched").value(1));

        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("ATTENDED");
        assertThat(JsonPath.<List<String>>read(studentSessions(beto), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<String>>read(studentSessions(carla), "$[*].status")).containsExactly("CANCELLED_BY_COACH");
        assertThat(carlaPlace).isNotEqualTo(betoPlace);
    }

    @Test
    void ifTheAffectedClassesChangedSinceTheCoachReviewedThemNothingIsSavedAndTheNewListIsReturned() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        var preview = previewBlock(coach, MON, "09:00", "12:00").andExpect(status().isOk()).andReturn();
        List<String> seen = JsonPath.read(json(preview), "$.affected[*].attendanceId");
        assertThat(seen).containsExactly(anaPlace);

        String betoPlace = attendanceId(studentBooked(beto, at(MON, "11:00")));          // booked after the review

        var conflict = createBlock(coach, MON, "09:00", "12:00", "Reunión", seen).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BLOCK_AFFECTED_CHANGED")).andExpect(jsonPath("$.details.preview.releasedCount").value(2)).andReturn();
        assertThat(JsonPath.<List<String>>read(json(conflict), "$.details.preview.affected[*].attendanceId")).containsExactlyInAnyOrder(anaPlace, betoPlace);
        assertThat(JsonPath.<List<?>>read(upcomingBlocks(coach), "$")).as("no block was saved").isEmpty();
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<String>>read(studentSessions(beto), "$[*].status")).containsExactly("SCHEDULED");

        createBlock(coach, MON, "09:00", "12:00", "Reunión", List.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BLOCK_AFFECTED_CHANGED"));   // skipping the review is refused
        createBlock(coach, MON, "09:00", "12:00", "Reunión", List.of(anaPlace, betoPlace, "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isConflict());                                       // an id that is not affected is refused too
        createBlock(coach, MON, "09:00", "12:00", "Reunión", List.of(anaPlace, betoPlace)).andExpect(status().isCreated()).andExpect(jsonPath("$.cancelled.length()").value(2));
        assertThat(JsonPath.<List<?>>read(upcomingBlocks(coach), "$")).hasSize(1);
    }

    @Test
    void everyReleasedClassLeavesACancelAuditLineWithTheBlocksReason() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        String betoPlace = attendanceId(studentBooked(beto, TEN));
        int before = jdbc.queryForObject("select count(*) from attendance_audit", Integer.class);

        createBlock(coach, MON, "09:00", "12:00", "Festivo", List.of(anaPlace, betoPlace)).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select count(*) from attendance_audit", Integer.class)).isEqualTo(before + 2);
        for (String place : List.of(anaPlace, betoPlace)) {
            String audit = json(mvc.perform(withToken(get("/api/coach/attendances/" + place + "/audit"), coach.token())).andReturn());
            assertThat(JsonPath.<List<String>>read(audit, "$[?(@.action=='CANCEL')].reason")).containsExactly("Bloqueo de agenda: Festivo");
            assertThat(JsonPath.<List<String>>read(audit, "$[?(@.action=='CANCEL')].method")).containsExactly("COACH");
        }
    }

    @Test
    void aBadBlockIsRefusedAndNeverSaved() throws Exception {
        var coach = newCoach(8);
        List<String> none = new ArrayList<>();

        createBlock(coach, MON, "09:00", "12:00", null, none).andExpect(status().isBadRequest());
        createBlock(coach, MON, "09:00", "12:00", "   ", none).andExpect(status().isBadRequest());
        createBlock(coach, MON, "09:00", "12:00", "x".repeat(101), none).andExpect(status().isBadRequest());
        mvc.perform(withToken(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/coach/availability/blocks"), coach.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"localDate\":\"" + MON + "\",\"reason\":\"x\"}")).andExpect(status().isBadRequest());   // allDay is mandatory
        createBlock(coach, MON, "12:00", "09:00", "Reunión", none).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_BLOCK"));
        createBlock(coach, MON, "09:00", "09:00", "Reunión", none).andExpect(status().isUnprocessableEntity());
        createBlock(coach, MON, "25:00", "26:00", "Reunión", none).andExpect(status().isUnprocessableEntity());
        createBlock(coach, MON, "09:00", null, "Reunión", none).andExpect(status().isUnprocessableEntity());
        createBlock(coach, "2026-10-05", null, null, "Ayer", none).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_BLOCK"));   // already past
        assertThat(JsonPath.<List<?>>read(upcomingBlocks(coach), "$")).isEmpty();
    }

    @Test
    void theBlockIsReadBackInLocalTimeAndRemovingItFreesTheHoursButNotTheReleasedClasses() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, TEN));
        String all = json(createBlock(coach, TUE, null, null, "Festivo", List.of()).andExpect(status().isCreated()).andReturn());
        String hours = json(createBlock(coach, MON, "09:00", "12:00", "Reunión", List.of(place)).andExpect(status().isCreated()).andReturn());

        String upcoming = upcomingBlocks(coach);
        assertThat(JsonPath.<List<String>>read(upcoming, "$[*].localDate")).containsExactly(MON, TUE);   // soonest first
        assertThat(JsonPath.<String>read(upcoming, "$[0].startTime")).isEqualTo("09:00");
        assertThat(JsonPath.<String>read(upcoming, "$[0].endTime")).isEqualTo("12:00");
        assertThat(JsonPath.<Boolean>read(upcoming, "$[1].allDay")).isTrue();
        assertThat(JsonPath.<Object>read(upcoming, "$[1].startTime")).isNull();

        mvc.perform(withToken(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/coach/availability/blocks/" + JsonPath.<String>read(hours, "$.block.id")), coach.token()))
                .andExpect(status().isNoContent());
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("CANCELLED_BY_COACH");
        assertThat(JsonPath.<List<?>>read(upcomingBlocks(coach), "$")).hasSize(1);
        assertThat(all).contains("Festivo");
    }

    @Test
    void aStudentWithFewPlacesLeftBeforeTheDeadlineIsFlaggedAtRiskAndMayBeExtended() throws Exception {
        var coach = newCoach(8);
        for (int day : new int[] {1, 3, 4, 5, 6, 7}) {
            putDay(coach, day, "[]").andExpect(status().isOk());
        }
        putDay(coach, 2, "[{\"start\":\"06:00\",\"end\":\"07:00\"}]").andExpect(status().isOk());   // one slot a week: Tuesdays 06:00
        var ana = personalized(coach, "Ana");
        String place = attendanceId(coachBooks(coach, ana, at(TUE, "06:00"), false, null).andExpect(status().isCreated()).andReturn());

        var created = createBlock(coach, TUE, null, null, "Festivo", List.of(place)).andExpect(status().isCreated()).andReturn();
        // Tuesdays left before 2026-11-06 are the 20th, the 27th and Nov 3rd (the 13th is blocked now): 3 places for 8 classes
        assertThat(JsonPath.<Integer>read(json(created), "$.students[0].freeSlotsBeforeDeadline")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(json(created), "$.students[0].classesLeftToSchedule")).isEqualTo(8);
        assertThat(JsonPath.<Boolean>read(json(created), "$.students[0].atRisk")).isTrue();
        assertThat(JsonPath.<Boolean>read(json(created), "$.students[0].canExtend")).isTrue();
        assertThat(JsonPath.<String>read(json(created), "$.students[0].cycleEndDate")).isEqualTo("2026-11-06");
        assertThat(JsonPath.<String>read(activeCycle(coach, ana), "$.endDate")).as("nothing is extended automatically").isEqualTo("2026-11-06");
    }

    // ================================================================= isolation

    @Test
    void aBlockOfOneCoachNeverReleasesNorPreviewsTheClassesOfAnother() throws Exception {
        var a = newCoach(8);
        var b = newCoach(8);
        var ana = semi(a, "Ana");
        var bruno = semi(b, "Bruno");
        studentBooked(ana, TEN);
        studentBooked(bruno, TEN);

        assertThat(JsonPath.<List<String>>read(json(previewBlock(b, MON, "09:00", "12:00").andReturn()), "$.affected[*].studentName")).containsExactly("Bruno");
        createBlock(b, MON, "09:00", "12:00", "Reunión", JsonPath.read(json(previewBlock(b, MON, "09:00", "12:00").andReturn()), "$.affected[*].attendanceId"))
                .andExpect(status().isCreated());

        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).as("coach A's class is untouched").containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<String>>read(studentSessions(bruno), "$[*].status")).containsExactly("CANCELLED_BY_COACH");
        assertThat(JsonPath.<List<?>>read(upcomingBlocks(a), "$")).isEmpty();
        studentBooks(ana, at(MON, "11:00")).andExpect(status().isCreated());            // A's calendar has no block
    }

    // ================================================================= shared classes

    @Test
    void aRegisteredStudentCanJoinAPersonalizedClassAsAnExceptionAndEachOneUsesTheirOwnClass() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        String event = eventId(studentBooked(ana, at("2026-10-06", "14:00")));
        String betoPlace = attendanceId(coachBooks(coach, beto, at("2026-10-06", "14:00"), true, "Entreno compartido").andExpect(status().isCreated())
                .andExpect(jsonPath("$.override").value(true)).andReturn());

        String w = week(coach, "2026-10-06");
        assertThat(JsonPath.<List<Boolean>>read(w, "$.days[1].items[?(@.kind=='EVENT')].event.shared")).containsExactly(true);
        assertThat(JsonPath.<List<String>>read(w, "$.days[1].items[?(@.kind=='EVENT')].event.modality")).containsExactly("PERSONALIZED");
        assertThat(JsonPath.<List<Integer>>read(w, "$.days[1].items[?(@.kind=='EVENT')].event.capacity")).as("the class keeps its capacity").containsExactly(1);
        assertThat(JsonPath.<List<Integer>>read(w, "$.days[1].items[?(@.kind=='EVENT')].event.occupied")).containsExactly(2);

        goTo("2026-10-06", "14:30");
        markEvent(coach, event, JsonPath.<String>read(studentSessions(ana), "$[0].id"), "ATTENDED", betoPlace, "ATTENDED").andExpect(status().isOk());
        assertThat(classesUsed(coach, ana)).isEqualTo(1);
        assertThat(classesUsed(coach, beto)).isEqualTo(1);
    }

    @Test
    void aClassThatIsNotOverbookedIsNotMarkedShared() throws Exception {
        var coach = newCoach(8);
        studentBooked(semi(coach, "Ana"), TEN);
        studentBooked(semi(coach, "Beto"), TEN);
        assertThat(JsonPath.<List<Boolean>>read(week(coach, MON), "$.days[0].items[?(@.kind=='EVENT')].event.shared")).containsExactly(false);
    }

    @Test
    void theCeilingIsCapacityPlusTwoAndTheOptionsStopOfferingTheFullClass() throws Exception {
        var coach = newCoach(8);
        var a = personalized(coach, "A");
        var b = personalized(coach, "B");
        var c = personalized(coach, "C");
        var d = personalized(coach, "D");
        studentBooked(a, TEN);
        coachBooks(coach, b, TEN, true, "Entreno compartido").andExpect(status().isCreated());
        assertThat(JsonPath.<List<String>>read(options(coach, c, MON), "$.slots[?(@.localTime=='10:00')].blockedBy")).containsExactly("SLOT_TAKEN");
        coachBooks(coach, c, TEN, true, "Entreno compartido").andExpect(status().isCreated());              // 3 = capacity 1 + 2: the limit
        assertThat(JsonPath.<List<?>>read(options(coach, d, MON), "$.slots[?(@.localTime=='10:00')]")).isEmpty();
        coachBooks(coach, d, TEN, true, "Entreno compartido").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHARED_LIMIT_EXCEEDED"));
        studentBooks(d, TEN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));   // a student still gets the plain rule
        assertThat(JsonPath.<List<?>>read(studentSessions(d), "$")).isEmpty();
    }
}
