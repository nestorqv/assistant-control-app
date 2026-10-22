package cr.attendance.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.employee.EmployeeRepository;
import cr.attendance.employee.WorkScheduleRepository;
import cr.attendance.company.BranchRepository;
import cr.attendance.timeentry.AdjustmentRepository;
import cr.attendance.timeentry.EvidenceRepository;
import cr.attendance.timeentry.TimeEntryRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

/** Every repository method must take a "companyId" parameter, except save (and CompanyRepository.findByCode). */
class TenantRepositoryConventionTest {

    private static final List<Class<?>> TENANT_REPOSITORIES = List.of(
            BranchRepository.class, EmployeeRepository.class, WorkScheduleRepository.class,
            TimeEntryRepository.class, EvidenceRepository.class, AdjustmentRepository.class);

    @Test
    void tenantRepositoriesExtendTheBareRepositoryInterface() {
        for (Class<?> repo : TENANT_REPOSITORIES) {
            assertThat(Repository.class).isAssignableFrom(repo);
            assertThat(CrudRepository.class.isAssignableFrom(repo)).as(repo.getSimpleName()).isFalse();
        }
    }

    @Test
    void everyMethodExceptSaveDeclaresACompanyIdParameter() {
        for (Class<?> repo : TENANT_REPOSITORIES) {
            for (Method method : repo.getDeclaredMethods()) {
                if (method.getName().equals("save")) {
                    continue;
                }
                boolean scoped = Arrays.stream(method.getParameters())
                        .anyMatch(p -> p.isNamePresent() && p.getName().equals("companyId"));
                assertThat(scoped).as(repo.getSimpleName() + "." + method.getName()).isTrue();
            }
        }
    }
}
