package com.coachplatform.scheduling;

import com.coachplatform.common.ApiException;
import com.coachplatform.scheduling.api.BlockSummary;
import com.coachplatform.scheduling.api.DayWindowInput;
import com.coachplatform.scheduling.api.WindowInput;
import com.coachplatform.scheduling.api.WindowView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The coach's weekly windows and the list of blocks. Changing the schedule never cancels or moves a class that is already booked;
 * creating a block (which can release classes) lives in {@link BlockService}.
 */
@Service
public class AvailabilityService {

    private final AvailabilityRuleRepository rules;
    private final AvailabilityBlockRepository blocks;
    private final BlockViews blockViews;
    private final Clock clock;

    AvailabilityService(AvailabilityRuleRepository rules, AvailabilityBlockRepository blocks, BlockViews blockViews, Clock clock) {
        this.rules = rules;
        this.blocks = blocks;
        this.blockViews = blockViews;
        this.clock = clock;
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
            parsed.add(parseWindow(w.dayOfWeek(), w.start(), w.end()));
        }
        requireNoOverlap(parsed);
        rules.deleteAll(rules.findAll());
        rules.flush();
        rules.saveAll(parsed);
        return weekly();
    }

    /**
     * Replaces the windows of ONE weekday (an empty list = no classes that day) and leaves the other days alone, so editing Monday never
     * overwrites a change made to another day from another device. Booked classes stay as they are, as in {@link #replaceWeekly}.
     */
    @Transactional
    public List<WindowView> replaceDay(int dayOfWeek, List<DayWindowInput> input) {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AVAILABILITY");
        }
        List<AvailabilityRule> parsed = new ArrayList<>();
        for (DayWindowInput w : input) {
            parsed.add(parseWindow(dayOfWeek, w.start(), w.end()));
        }
        requireNoOverlap(parsed);
        rules.deleteAll(rules.findAll().stream().filter(r -> r.getDayOfWeek() == dayOfWeek).toList());
        rules.flush();
        rules.saveAll(parsed);
        return weekly();
    }

    @Transactional(readOnly = true)
    public List<BlockSummary> blocks(Instant from, Instant to) {
        return blocks.findOverlapping(from, to).stream().map(blockViews::summary).toList();
    }

    /** Blocks still running or yet to come, by the server's clock. */
    @Transactional(readOnly = true)
    public List<BlockSummary> upcomingBlocks() {
        return blocks.findUpcoming(clock.instant()).stream().map(blockViews::summary).toList();
    }

    /** Removing a block frees the hours again; classes it released stay cancelled. */
    @Transactional
    public void deleteBlock(UUID blockId) {
        AvailabilityBlock block = blocks.findById(blockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND"));
        blocks.delete(block);
    }

    private static AvailabilityRule parseWindow(int dayOfWeek, String startText, String endText) {
        LocalTime start = parse(startText);
        LocalTime end = parse(endText);
        if (!end.isAfter(start)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AVAILABILITY");
        }
        return new AvailabilityRule(dayOfWeek, start, end);
    }

    private static void requireNoOverlap(List<AvailabilityRule> parsed) {
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
    }

    private static LocalTime parse(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AVAILABILITY");
        }
    }
}
