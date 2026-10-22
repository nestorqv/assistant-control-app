package cr.attendance.seed;

import cr.attendance.auth.PinPolicy;
import cr.attendance.common.Role;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.employee.WorkSchedule;
import cr.attendance.employee.WorkScheduleRepository;
import cr.attendance.company.Company;
import cr.attendance.company.CompanyRepository;
import cr.attendance.company.Branch;
import cr.attendance.company.BranchRepository;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the blueprint demo company. Runs only with the dev profile active; startup fails if prod is also active. */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    static final String DEMO_COMPANY_CODE = "DEMO";
    static final String PIN_MESSAGE =
            "DEV_SEED_PIN is required for the dev profile: exactly 8 digits, not a sequence, repeated pattern or date";

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private record Demo(String code, String name, String jobTitle, Role role, LocalTime start, LocalTime end) {
    }

    private static final LocalTime OFFICE_START = LocalTime.of(8, 0);
    private static final LocalTime OFFICE_END = LocalTime.of(17, 0);

    private static final Demo[] DEMO_EMPLOYEES = {
        new Demo("1001", "María Rojas", "Cajera", Role.EMPLOYEE, OFFICE_START, OFFICE_END),
        new Demo("1002", "José Vargas", "Bodega", Role.EMPLOYEE, OFFICE_START, OFFICE_END),
        new Demo("1003", "Ana Chaves", "Ventas", Role.EMPLOYEE, OFFICE_START, OFFICE_END),
        new Demo("1004", "Luis Mora", "Ventas", Role.EMPLOYEE, OFFICE_START, OFFICE_END),
        new Demo("1005", "Karla Solís", "Limpieza", Role.EMPLOYEE, LocalTime.of(6, 0), LocalTime.of(14, 0)),
        new Demo("1006", "Diego Arias", "Bodega", Role.EMPLOYEE, OFFICE_START, OFFICE_END),
        new Demo("9001", "Administrador Demo", "Administración", Role.ADMIN, OFFICE_START, OFFICE_END),
    };

    private final Environment environment;
    private final SeedProperties props;
    private final CompanyRepository companyRepository;
    private final BranchRepository branchRepository;
    private final EmployeeRepository employeeRepository;
    private final WorkScheduleRepository workScheduleRepository;
    private final PasswordEncoder passwordEncoder;

    public DevDataSeeder(Environment environment, SeedProperties props, CompanyRepository companyRepository,
            BranchRepository branchRepository, EmployeeRepository employeeRepository,
            WorkScheduleRepository workScheduleRepository, PasswordEncoder passwordEncoder) {
        this.environment = environment;
        this.props = props;
        this.companyRepository = companyRepository;
        this.branchRepository = branchRepository;
        this.employeeRepository = employeeRepository;
        this.workScheduleRepository = workScheduleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Fail closed: seed only when dev is explicitly active, and refuse to start if prod is also active.
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("The dev seed must never run with the prod profile");
        }
        if (!environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException("The dev seed runs only when the dev profile is explicitly active");
        }
        String pin = props.pin();
        if (!PinPolicy.isValid(pin)) {
            throw new IllegalStateException(PIN_MESSAGE);
        }
        if (companyRepository.findByCode(DEMO_COMPANY_CODE).isPresent()) {
            log.info("Dev seed already present");
            return;
        }

        Company company = companyRepository.save(new Company(DEMO_COMPANY_CODE, "Empresa Demo"));
        // Placeholder San José coordinates.
        Branch branch = branchRepository.save(
                new Branch(company.getId(), "Sucursal Centro", 9.9325, -84.0796, 100));
        String pinHash = passwordEncoder.encode(pin);

        for (Demo demo : DEMO_EMPLOYEES) {
            Long branchId = demo.role() == Role.ADMIN ? null : branch.getId();
            Employee employee = employeeRepository.save(new Employee(company.getId(), branchId, demo.code(),
                    demo.name(), demo.jobTitle(), pinHash, demo.role()));
            for (int day = 1; day <= 5; day++) {
                workScheduleRepository.save(new WorkSchedule(company.getId(), employee.getId(), day, demo.start(),
                        demo.end(), 5));
            }
        }
        log.info("Dev seed created: company DEMO, 7 employees (codes 1001-1006, 9001)");
    }
}
