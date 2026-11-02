package cr.attendance.admin;

import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TodayResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record DashboardResponse(
        LocalDate date,
        LocalDate today,
        String timeZone,
        String companyName,
        Long branchId,
        Kpis kpis,
        List<DashboardRow> rows,
        Instant generatedAt) {

    public record Kpis(int present, int expected, int late, int notClocked, int notYetDue, int toReview,
            Map<ReviewReason, Integer> toReviewByReason) {
    }

    public record EmployeeRef(Long id, String code, String name, String jobTitle, boolean active) {
    }

    public record DashboardRow(
            EmployeeRef employee,
            String branchName,
            AdminEntrySummary clockIn,
            AdminEntrySummary clockOut,
            Long workedMinutes,
            boolean inProgress,
            Integer lateMinutes,
            DayStatus dayStatus,
            TodayResponse.ScheduleInfo schedule) {
    }
}
