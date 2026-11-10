package cr.attendance.admin;

import cr.attendance.audit.AuditLogEntry;
import cr.attendance.audit.AuditLogRepository;
import cr.attendance.common.AuditAction;
import cr.attendance.common.AuditTargetType;
import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.company.Branch;
import cr.attendance.company.BranchRepository;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.employee.WorkScheduleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds the admin views of employees. Every lookup is scoped by company. */
@Component
public class AdminEmployeeViews {

    private final EmployeeRepository employeeRepository;
    private final BranchRepository branchRepository;
    private final WorkScheduleRepository workScheduleRepository;
    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public AdminEmployeeViews(EmployeeRepository employeeRepository, BranchRepository branchRepository,
            WorkScheduleRepository workScheduleRepository, AuditLogRepository auditLogRepository, Clock clock) {
        this.employeeRepository = employeeRepository;
        this.branchRepository = branchRepository;
        this.workScheduleRepository = workScheduleRepository;
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    public List<AdminEmployeeView> list(Long companyId) {
        Instant now = Instant.now(clock);
        Map<Long, Branch> branches = branchRepository.findAllByCompanyIdOrderByName(companyId).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));
        Set<Long> scheduled = new HashSet<>(workScheduleRepository.findEmployeeIdsWithSchedule(companyId));
        return employeeRepository.findAllByCompanyIdOrderByName(companyId).stream()
                .map(e -> AdminEmployeeView.of(e, e.getBranchId() == null ? null : branches.get(e.getBranchId()),
                        scheduled.contains(e.getId()), now))
                .toList();
    }

    public AdminEmployeeView view(Employee e) {
        Branch branch = e.getBranchId() == null ? null
                : branchRepository.findByIdAndCompanyId(e.getBranchId(), e.getCompanyId()).orElse(null);
        boolean hasSchedule = !workScheduleRepository.findAllByCompanyIdAndEmployeeId(e.getCompanyId(), e.getId())
                .isEmpty();
        return AdminEmployeeView.of(e, branch, hasSchedule, Instant.now(clock));
    }

    public AdminEmployeeDetail detail(Employee e) {
        return new AdminEmployeeDetail(view(e), lastPinReset(e));
    }

    // Another tenant's id is indistinguishable from a missing one: 404, never 403.
    public AdminEmployeeDetail detail(Long companyId, Long id) {
        return detail(employeeRepository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(ResourceNotFoundException::new));
    }

    private AdminEmployeeDetail.LastPinReset lastPinReset(Employee target) {
        AuditLogEntry row = auditLogRepository
                .findFirstByCompanyIdAndActionAndTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc(
                        target.getCompanyId(), AuditAction.PIN_RESET.name(), AuditTargetType.EMPLOYEE.name(),
                        target.getId())
                .orElse(null);
        if (row == null) {
            return null;
        }
        String byName = employeeRepository.findByIdAndCompanyId(row.getActorId(), target.getCompanyId())
                .map(Employee::getName).orElse(null);
        return new AdminEmployeeDetail.LastPinReset(row.getCreatedAt(), byName, row.getReasonCode(),
                row.getReasonNote());
    }
}
