package cr.attendance.employee;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface WorkScheduleRepository extends Repository<WorkSchedule, Long> {

    WorkSchedule save(WorkSchedule workSchedule);

    List<WorkSchedule> findAllByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

    Optional<WorkSchedule> findByCompanyIdAndEmployeeIdAndDayOfWeek(Long companyId, Long employeeId,
            Integer dayOfWeek);
}
