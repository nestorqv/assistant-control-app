package cr.attendance.admin;

import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TimeEntryStatus;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** The four dashboard cards, computed over the rows that are left after the branch filter. */
public final class KpiCalculator {

    private KpiCalculator() {
    }

    public static DashboardResponse.Kpis compute(List<DashboardResponse.DashboardRow> rows) {
        int present = 0;
        int expected = 0;
        int late = 0;
        int notClocked = 0;
        int notYetDue = 0;
        int toReview = 0;
        Map<ReviewReason, Integer> byReason = new EnumMap<>(ReviewReason.class);
        for (DashboardResponse.DashboardRow row : rows) {
            boolean isPresent = row.clockIn() != null && row.clockIn().status() != TimeEntryStatus.REJECTED;
            if (isPresent) {
                present++;
                if (row.lateMinutes() != null && row.lateMinutes() > 0) {
                    late++;
                }
            }
            if (isPresent || row.schedule() != null) {
                expected++;
            }
            if (row.dayStatus() == DayStatus.NOT_CLOCKED) {
                notClocked++;
            } else if (row.dayStatus() == DayStatus.NOT_YET_DUE) {
                notYetDue++;
            }
            for (AdminEntrySummary entry : Stream.of(row.clockIn(), row.clockOut()).filter(Objects::nonNull)
                    .toList()) {
                if (entry.status() == TimeEntryStatus.PENDING_REVIEW) {
                    toReview++;
                    for (ReviewReason reason : entry.reviewReasons()) {
                        byReason.merge(reason, 1, Integer::sum);
                    }
                }
            }
        }
        return new DashboardResponse.Kpis(present, expected, late, notClocked, notYetDue, toReview, byReason);
    }
}
