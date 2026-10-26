package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.common.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShiftResolverTest {

    private static final Duration MAX = Duration.ofHours(16);
    private static final LocalDate MON = LocalDate.of(2026, 10, 19);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final Instant MON_2200 = Instant.parse("2026-10-20T04:00:00Z"); // 22:00 CR on Monday

    private static TimeEntry entry(TimeEntryType type, Instant at, LocalDate workDate) {
        return new TimeEntry(1L, 1L, 1L, type, at, TimeEntrySource.MOBILE, UUID.randomUUID(), workDate);
    }

    @Test
    void openShiftWithinTheWindow() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, MON_2200, MON);
        Instant now = MON_2200.plus(Duration.ofHours(8));
        assertThat(ShiftResolver.openShift(List.of(in), now, MAX, TUE)).contains(in);
        assertThat(ShiftResolver.nextAction(List.of(in), now, MAX, TUE)).isEqualTo(NextAction.CLOCK_OUT);
    }

    @Test
    void expiredShiftOfAnEarlierDayIsForgottenAndDoesNotBlockToday() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, MON_2200, MON);
        Instant now = MON_2200.plus(Duration.ofHours(16)).plusSeconds(60);
        assertThat(ShiftResolver.openShift(List.of(in), now, MAX, TUE)).isEmpty();
        assertThat(ShiftResolver.forgottenShift(List.of(in), now, MAX, TUE)).contains(in);
        assertThat(ShiftResolver.nextAction(List.of(in), now, MAX, TUE)).isEqualTo(NextAction.CLOCK_IN);
        assertThat(ShiftResolver.decide(TimeEntryType.CLOCK_IN, List.of(in), now, MAX, TUE).violation()).isNull();
    }

    @Test
    void exactlyAtTheWindowEdgeTheShiftIsStale() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, MON_2200, MON);
        Instant now = MON_2200.plus(MAX);
        assertThat(ShiftResolver.isFresh(in, now, MAX)).isFalse();
        assertThat(ShiftResolver.isFresh(in, now.minusSeconds(1), MAX)).isTrue();
    }

    @Test
    void clockOutInheritsTheWorkDateOfTheOpenShift() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, MON_2200, MON);
        Instant now = MON_2200.plus(Duration.ofHours(8));
        ShiftResolver.Decision d = ShiftResolver.decide(TimeEntryType.CLOCK_OUT, List.of(in), now, MAX, TUE);
        assertThat(d.violation()).isNull();
        assertThat(d.workDate()).isEqualTo(MON);
        assertThat(d.shiftTooLong()).isFalse();
    }

    @Test
    void clockOutOfAnExpiredShiftIsAcceptedAndFlagged() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, MON_2200, MON);
        Instant now = MON_2200.plus(Duration.ofHours(20));
        ShiftResolver.Decision d = ShiftResolver.decide(TimeEntryType.CLOCK_OUT, List.of(in), now, MAX, TUE);
        assertThat(d.violation()).isNull();
        assertThat(d.workDate()).isEqualTo(MON);
        assertThat(d.shiftTooLong()).isTrue();
    }

    @Test
    void clockOutWithoutAnyClockInIsRejected() {
        assertThat(ShiftResolver.decide(TimeEntryType.CLOCK_OUT, List.of(), MON_2200, MAX, MON).violation())
                .isEqualTo(ErrorCode.NOT_CLOCKED_IN);
    }

    @Test
    void clockOutAfterTheDayIsClosedIsRejected() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, MON_2200, MON);
        TimeEntry out = entry(TimeEntryType.CLOCK_OUT, MON_2200.plusSeconds(3600), MON);
        assertThat(ShiftResolver.decide(TimeEntryType.CLOCK_OUT, List.of(out, in), MON_2200.plusSeconds(7200), MAX,
                MON).violation()).isEqualTo(ErrorCode.NOT_CLOCKED_IN);
    }

    @Test
    void doubleClockInAndCompletedDay() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, MON_2200, MON);
        Instant now = MON_2200.plusSeconds(3600);
        assertThat(ShiftResolver.decide(TimeEntryType.CLOCK_IN, List.of(in), now, MAX, MON).violation())
                .isEqualTo(ErrorCode.ALREADY_CLOCKED_IN);
        TimeEntry out = entry(TimeEntryType.CLOCK_OUT, now, MON);
        assertThat(ShiftResolver.decide(TimeEntryType.CLOCK_IN, List.of(out, in), now.plusSeconds(60), MAX, MON)
                .violation()).isEqualTo(ErrorCode.SHIFT_ALREADY_COMPLETED);
        assertThat(ShiftResolver.nextAction(List.of(out, in), now.plusSeconds(60), MAX, MON))
                .isEqualTo(NextAction.NONE);
    }

    @Test
    void clockInWhileAnOvernightShiftIsStillOpenIsRejected() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, MON_2200, MON);
        Instant now = MON_2200.plus(Duration.ofHours(8));
        assertThat(ShiftResolver.decide(TimeEntryType.CLOCK_IN, List.of(in), now, MAX, TUE).violation())
                .isEqualTo(ErrorCode.ALREADY_CLOCKED_IN);
    }

    @Test
    void clockInAfterAnExpiredSameDayClockInIsStillRejectedAndCanBeClosed() {
        // 00:30 local clock-in, still the same local day 17 hours later: the day cannot get a second CLOCK_IN.
        Instant earlyMorning = Instant.parse("2026-10-20T06:30:00Z"); // 00:30 CR on Tuesday
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, earlyMorning, TUE);
        Instant now = earlyMorning.plus(Duration.ofHours(17)); // 17:30 CR, still Tuesday
        assertThat(ShiftResolver.decide(TimeEntryType.CLOCK_IN, List.of(in), now, MAX, TUE).violation())
                .isEqualTo(ErrorCode.ALREADY_CLOCKED_IN);
        assertThat(ShiftResolver.nextAction(List.of(in), now, MAX, TUE)).isEqualTo(NextAction.CLOCK_OUT);
        ShiftResolver.Decision out = ShiftResolver.decide(TimeEntryType.CLOCK_OUT, List.of(in), now, MAX, TUE);
        assertThat(out.violation()).isNull();
        assertThat(out.shiftTooLong()).isTrue();
    }

    @Test
    void nextActionOnACleanDay() {
        assertThat(ShiftResolver.nextAction(List.of(), MON_2200, MAX, MON)).isEqualTo(NextAction.CLOCK_IN);
    }
}
