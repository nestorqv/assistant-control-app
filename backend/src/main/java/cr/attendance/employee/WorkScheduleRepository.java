package cr.attendance.employee;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkScheduleRepository extends Repository<WorkSchedule, Long> {

    WorkSchedule save(WorkSchedule workSchedule);

    List<WorkSchedule> findAllByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

    // Dashboard: everyone scheduled on one ISO weekday.
    List<WorkSchedule> findAllByCompanyIdAndDayOfWeek(Long companyId, Integer dayOfWeek);

    Optional<WorkSchedule> findByCompanyIdAndEmployeeIdAndDayOfWeek(Long companyId, Long employeeId,
            Integer dayOfWeek);

    // Employees with at least one scheduled day (the "has schedule" column of the admin list).
    @Query("select distinct w.employeeId from WorkSchedule w where w.companyId = :companyId")
    List<Long> findEmployeeIdsWithSchedule(@Param("companyId") Long companyId);

    // One bulk statement, not entity deletes: Hibernate runs entity deletes last in a flush, which is safe here
    // only because the days removed are never re-inserted in the same transaction. Callers pass a non-empty set.
    @Modifying(flushAutomatically = true)
    @Query("delete from WorkSchedule w where w.companyId = :companyId and w.employeeId = :employeeId "
            + "and w.dayOfWeek in :days")
    int deleteByCompanyIdAndEmployeeIdAndDayOfWeekIn(@Param("companyId") Long companyId,
            @Param("employeeId") Long employeeId, @Param("days") Collection<Integer> days);
}
