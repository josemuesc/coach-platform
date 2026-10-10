package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A block and the release of its classes are ONE operation: if releasing the last class fails, the block and the earlier releases are undone. */
class BlockAtomicityTest extends SchedulingApiTest {

    @MockitoSpyBean AttendanceAuditWriter auditWriter;

    @AfterEach
    void restore() {
        reset(auditWriter);
    }

    @Test
    void aFailureWhileReleasingTheSecondClassLeavesNoBlockAndNoCancelledClass() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, at("2026-10-12", "10:00")));
        String betoPlace = attendanceId(studentBooked(beto, at("2026-10-12", "11:00")));
        int auditBefore = jdbc.queryForObject("select count(*) from attendance_audit", Integer.class);

        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("boom while releasing the second class");
            }
            return invocation.callRealMethod();
        }).when(auditWriter).record(any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> createBlock(coach, "2026-10-12", "09:00", "12:00", "Reunión", List.of(anaPlace, betoPlace)))
                .hasRootCauseMessage("boom while releasing the second class");

        reset(auditWriter);
        assertThat(JsonPath.<List<?>>read(upcomingBlocks(coach), "$")).as("the block was rolled back").isEmpty();
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).as("the first release was undone").containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<String>>read(studentSessions(beto), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(jdbc.queryForObject("select count(*) from attendance_audit", Integer.class)).as("no audit line survives").isEqualTo(auditBefore);
        studentBooks(ana, at("2026-10-12", "12:00")).andExpect(status().isCreated());   // and the hours are not blocked
    }
}
