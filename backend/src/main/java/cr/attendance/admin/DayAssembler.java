package cr.attendance.admin;

import cr.attendance.company.Branch;
import cr.attendance.employee.Employee;
import cr.attendance.employee.WorkSchedule;
import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import cr.attendance.timeentry.TodayResponse;
import java.text.Collator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the per-employee day rows from batched data. Pure (no repositories): the dashboard and the Excel
 * export share it, so a chip and a report column can never disagree.
 */
public final class DayAssembler {

    private DayAssembler() {
    }

    /**
     * @param employees      everyone who may appear: active with a branch, plus anyone with an entry that day
     * @param schedules      schedules of the ISO weekday of date, by employee id
     * @param entries        every time entry of the company for date
     * @param photoEntryIds  entries that have a PHOTO evidence row with a stored file
     * @param adjustedIds    entries that have at least one adjustment
     * @param branches       the company branches by id
     * @param branchFilter   null for all branches; otherwise the entry branch when an entry exists, else the
     *                       current branch of the employee
     */
    public record Input(List<Employee> employees, Map<Long, WorkSchedule> schedules, List<TimeEntry> entries,
            Set<Long> photoEntryIds, Set<Long> adjustedIds, Map<Long, Branch> branches, LocalDate date,
            Instant now, ZoneId zone, Duration maxShift, Long branchFilter) {
    }

    public static List<DashboardResponse.DashboardRow> assemble(Input in) {
        Map<Long, Map<TimeEntryType, TimeEntry>> byEmployee = new HashMap<>();
        for (TimeEntry e : in.entries()) {
            byEmployee.computeIfAbsent(e.getEmployeeId(), k -> new HashMap<>()).put(e.getType(), e);
        }
        List<DashboardResponse.DashboardRow> rows = new ArrayList<>();
        for (Employee employee : in.employees()) {
            Map<TimeEntryType, TimeEntry> day = byEmployee.getOrDefault(employee.getId(), Map.of());
            TimeEntry clockIn = day.get(TimeEntryType.CLOCK_IN);
            TimeEntry clockOut = day.get(TimeEntryType.CLOCK_OUT);
            Long rowBranchId = clockIn != null ? clockIn.getBranchId()
                    : clockOut != null ? clockOut.getBranchId() : employee.getBranchId();
            if (in.branchFilter() != null && !in.branchFilter().equals(rowBranchId)) {
                continue;
            }
            WorkSchedule schedule = in.schedules().get(employee.getId());
            Integer lateMinutes = clockIn != null ? clockIn.getLateMinutes() : null;
            DayStatus status = DayStatusResolver.resolve(clockIn, clockOut, schedule, lateMinutes, in.date(),
                    in.now(), in.zone(), in.maxShift());
            boolean inProgress = clockIn != null && clockOut == null
                    && clockIn.getStatus() != TimeEntryStatus.REJECTED
                    && !in.now().isAfter(clockIn.getEffectiveTime().plus(in.maxShift()))
                    && (status == DayStatus.LATE || status == DayStatus.ON_TIME || status == DayStatus.UNSCHEDULED
                            || status == DayStatus.NEEDS_REVIEW || status == DayStatus.OUTSIDE_AREA);
            Branch branch = rowBranchId != null ? in.branches().get(rowBranchId) : null;
            rows.add(new DashboardResponse.DashboardRow(
                    new DashboardResponse.EmployeeRef(employee.getId(), employee.getCode(), employee.getName(),
                            employee.getJobTitle(), employee.isActive()),
                    branch != null ? branch.getName() : null,
                    summary(clockIn, in), summary(clockOut, in),
                    WorkedTime.workedMinutes(clockIn, clockOut), inProgress, lateMinutes, status,
                    schedule == null ? null : scheduleInfo(schedule)));
        }
        Collator collator = Collator.getInstance(Locale.forLanguageTag("es"));
        // Days off go last, then by name.
        rows.sort(Comparator
                .comparing((DashboardResponse.DashboardRow r) -> r.dayStatus() == DayStatus.DAY_OFF)
                .thenComparing(r -> r.employee().name(), collator));
        return rows;
    }

    private static AdminEntrySummary summary(TimeEntry entry, Input in) {
        if (entry == null) {
            return null;
        }
        return AdminEntrySummary.of(entry, in.photoEntryIds().contains(entry.getId()),
                in.adjustedIds().contains(entry.getId()));
    }

    static TodayResponse.ScheduleInfo scheduleInfo(WorkSchedule s) {
        return new TodayResponse.ScheduleInfo(s.getStartTime(), s.getEndTime(), s.getGraceMinutes(),
                s.getEndTime().isBefore(s.getStartTime()));
    }
}
