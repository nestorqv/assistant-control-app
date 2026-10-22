package cr.attendance.employee;

import java.util.List;
import org.springframework.data.repository.Repository;

public interface WorkScheduleRepository extends Repository<WorkSchedule, Long> {

    WorkSchedule save(WorkSchedule workSchedule);

    List<WorkSchedule> findAllByCompanyIdAndEmployeeId(Long companyId, Long employeeId);
}
