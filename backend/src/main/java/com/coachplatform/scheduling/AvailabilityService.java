package com.coachplatform.scheduling;

import com.coachplatform.common.ApiException;
import com.coachplatform.scheduling.api.BlockCreated;
import com.coachplatform.scheduling.api.BlockInput;
import com.coachplatform.scheduling.api.BlockSummary;
import com.coachplatform.scheduling.api.SessionSummary;
import com.coachplatform.scheduling.api.WindowInput;
import com.coachplatform.scheduling.api.WindowView;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentSummary;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The coach's weekly windows and blocks. Neither operation ever cancels or moves a class that is already booked. */
@Service
public class AvailabilityService {

    private final AvailabilityRuleRepository rules;
    private final AvailabilityBlockRepository blocks;
    private final ClassSessionRepository sessions;
    private final StudentService students;

    AvailabilityService(AvailabilityRuleRepository rules, AvailabilityBlockRepository blocks,
                        ClassSessionRepository sessions, StudentService students) {
        this.rules = rules;
        this.blocks = blocks;
        this.sessions = sessions;
        this.students = students;
    }

    @Transactional(readOnly = true)
    public List<WindowView> weekly() {
        return rules.findAllByOrderByDayOfWeekAscStartTimeAsc().stream()
                .map(r -> new WindowView(r.getDayOfWeek(), r.getStartTime().toString(), r.getEndTime().toString())).toList();
    }

    /**
     * Replaces the whole weekly schedule. Classes already booked are left exactly as they are, even if they now fall
     * outside the new windows: this only changes what can be booked from now on.
     */
    @Transactional
    public List<WindowView> replaceWeekly(List<WindowInput> input) {
        List<AvailabilityRule> parsed = new ArrayList<>();
        for (WindowInput w : input) {
            LocalTime start = parse(w.start());
            LocalTime end = parse(w.end());
            if (!end.isAfter(start)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AVAILABILITY");
            }
            parsed.add(new AvailabilityRule(w.dayOfWeek(), start, end));
        }
        for (int i = 0; i < parsed.size(); i++) {
            for (int j = i + 1; j < parsed.size(); j++) {
                AvailabilityRule a = parsed.get(i);
                AvailabilityRule b = parsed.get(j);
                if (a.getDayOfWeek() == b.getDayOfWeek() && a.getStartTime().isBefore(b.getEndTime())
                        && b.getStartTime().isBefore(a.getEndTime())) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OVERLAPPING_AVAILABILITY");
                }
            }
        }
        rules.deleteAll(rules.findAll());
        rules.flush();
        rules.saveAll(parsed);
        return weekly();
    }

    @Transactional(readOnly = true)
    public List<BlockSummary> blocks(Instant from, Instant to) {
        return blocks.findOverlapping(from, to).stream().map(AvailabilityService::summary).toList();
    }

    /** Creates the block and LISTS the classes already booked inside it; it does not cancel them. */
    @Transactional
    public BlockCreated createBlock(BlockInput input, UUID createdBy) {
        if (!input.endsAt().isAfter(input.startsAt())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BLOCK");
        }
        AvailabilityBlock block = blocks.save(new AvailabilityBlock(input.startsAt(), input.endsAt(),
                input.reason() == null || input.reason().isBlank() ? null : input.reason().trim(), createdBy));
        Map<UUID, String> names = students.list().stream().collect(Collectors.toMap(StudentSummary::id, StudentSummary::fullName));
        List<SessionSummary> affected = sessions.findScheduledOverlapping(input.startsAt(), input.endsAt()).stream()
                .map(s -> SchedulingService.summary(s, names)).toList();
        return new BlockCreated(summary(block), affected);
    }

    @Transactional
    public void deleteBlock(UUID blockId) {
        AvailabilityBlock block = blocks.findById(blockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND"));
        blocks.delete(block);
    }

    private static LocalTime parse(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AVAILABILITY");
        }
    }

    private static BlockSummary summary(AvailabilityBlock b) {
        return new BlockSummary(b.getId(), b.getStartsAt(), b.getEndsAt(), b.getReason());
    }
}
