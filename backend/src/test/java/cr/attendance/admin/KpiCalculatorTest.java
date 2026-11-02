package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TimeEntrySource;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import cr.attendance.timeentry.TodayResponse;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KpiCalculatorTest {

    private static final TodayResponse.ScheduleInfo SCHEDULE =
            new TodayResponse.ScheduleInfo(LocalTime.of(8, 0), LocalTime.of(17, 0), 5, false);

    private static AdminEntrySummary summary(TimeEntryType type, TimeEntryStatus status, Set<ReviewReason> reasons) {
        return new AdminEntrySummary(1L, type, Instant.EPOCH, Instant.EPOCH, status, reasons, TimeEntrySource.MOBILE,
                null, null, true, true, false);
    }

    private static DashboardResponse.DashboardRow row(AdminEntrySummary in, AdminEntrySummary out, Integer late,
            DayStatus status, boolean scheduled) {
        return new DashboardResponse.DashboardRow(new DashboardResponse.EmployeeRef(1L, "1", "n", null, true), "b",
                in, out, null, false, late, status, scheduled ? SCHEDULE : null);
    }

    private static AdminEntrySummary valid() {
        return summary(TimeEntryType.CLOCK_IN, TimeEntryStatus.VALID, Set.of());
    }

    /** The blueprint (page 2): 12 people, 9 present, 2 late, 3 not clocked, 1 outside the area. */
    private static List<DashboardResponse.DashboardRow> mockup() {
        List<DashboardResponse.DashboardRow> rows = new ArrayList<>();
        rows.add(row(valid(), null, 0, DayStatus.ON_TIME, true));                        // Maria
        rows.add(row(valid(), null, 17, DayStatus.LATE, true));                          // Jose, 17 min late
        rows.add(row(valid(), null, 0, DayStatus.ON_TIME, true));                        // Ana (tablet)
        rows.add(row(summary(TimeEntryType.CLOCK_IN, TimeEntryStatus.PENDING_REVIEW, Set.of(ReviewReason.OUTSIDE_GEOFENCE)),
                null, 0, DayStatus.OUTSIDE_AREA, true));                                 // Luis, 420 m
        rows.add(row(valid(), summary(TimeEntryType.CLOCK_OUT, TimeEntryStatus.VALID, Set.of()), 0,
                DayStatus.COMPLETE, true));                                              // Karla
        rows.add(row(null, null, null, DayStatus.NOT_CLOCKED, true));                    // Diego
        rows.add(row(valid(), null, 0, DayStatus.ON_TIME, true));
        rows.add(row(valid(), null, 0, DayStatus.ON_TIME, true));
        rows.add(row(valid(), null, 12, DayStatus.LATE, true));
        rows.add(row(valid(), null, 0, DayStatus.ON_TIME, true));
        rows.add(row(null, null, null, DayStatus.NOT_CLOCKED, true));
        rows.add(row(null, null, null, DayStatus.NOT_CLOCKED, true));
        return rows;
    }

    @Test
    void theBlueprintGivesNineOfTwelveTwoLateThreeNotClockedOneToReview() {
        DashboardResponse.Kpis k = KpiCalculator.compute(mockup());
        assertThat(k.present()).isEqualTo(9);
        assertThat(k.expected()).isEqualTo(12);
        assertThat(k.late()).isEqualTo(2);
        assertThat(k.notClocked()).isEqualTo(3);
        assertThat(k.notYetDue()).isZero();
        assertThat(k.toReview()).isEqualTo(1);
        assertThat(k.toReviewByReason()).containsOnly(java.util.Map.entry(ReviewReason.OUTSIDE_GEOFENCE, 1));
    }

    @Test
    void aDayOffEmployeeWhoClockedInCountsAsExpected() {
        DashboardResponse.Kpis k = KpiCalculator.compute(List.of(
                row(valid(), null, null, DayStatus.UNSCHEDULED, false),
                row(null, null, null, DayStatus.DAY_OFF, false)));
        assertThat(k.present()).isEqualTo(1);
        assertThat(k.expected()).isEqualTo(1);
    }

    @Test
    void aRejectedClockInIsNotPresentAndNotLate() {
        AdminEntrySummary rejected = summary(TimeEntryType.CLOCK_IN, TimeEntryStatus.REJECTED, Set.of());
        DashboardResponse.Kpis k = KpiCalculator.compute(List.of(row(rejected, null, 30, DayStatus.REJECTED, true)));
        assertThat(k.present()).isZero();
        assertThat(k.late()).isZero();
        assertThat(k.expected()).isEqualTo(1);
    }

    @Test
    void notYetDueRowsAreCountedSeparately() {
        DashboardResponse.Kpis k = KpiCalculator.compute(List.of(
                row(null, null, null, DayStatus.NOT_YET_DUE, true), row(null, null, null, DayStatus.NOT_CLOCKED, true)));
        assertThat(k.notClocked()).isEqualTo(1);
        assertThat(k.notYetDue()).isEqualTo(1);
    }

    @Test
    void emptyCompanyIsZeroOfZero() {
        DashboardResponse.Kpis k = KpiCalculator.compute(List.of());
        assertThat(k.present()).isZero();
        assertThat(k.expected()).isZero();
        assertThat(k.toReviewByReason()).isEmpty();
    }
}
