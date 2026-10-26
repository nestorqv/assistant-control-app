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
import java.util.Optional;
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

    static final String LOCATION_MESSAGE = "DEV_SEED_LAT and DEV_SEED_LNG must be set together, as decimal degrees "
            + "(latitude -90..90, longitude -180..180); DEV_SEED_RADIUS must be between 10 and 5000";
    private static final String BRANCH_NAME = "Sucursal Centro";
    private static final double DEFAULT_LATITUDE = 9.9325;
    private static final double DEFAULT_LONGITUDE = -84.0796;
    private static final int DEFAULT_RADIUS = 100;

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
        validateLocation();
        Optional<Company> existing = companyRepository.findByCode(DEMO_COMPANY_CODE);
        if (existing.isPresent()) {
            updateBranchLocation(existing.get());
            log.info("Dev seed already present");
            return;
        }

        Company company = companyRepository.save(new Company(DEMO_COMPANY_CODE, "Empresa Demo"));
        // San José coordinates unless DEV_SEED_LAT / DEV_SEED_LNG / DEV_SEED_RADIUS say otherwise.
        Branch branch = branchRepository.save(new Branch(company.getId(), BRANCH_NAME,
                props.branchLatitude() != null ? props.branchLatitude() : DEFAULT_LATITUDE,
                props.branchLongitude() != null ? props.branchLongitude() : DEFAULT_LONGITUDE,
                props.branchRadiusMeters() != null ? props.branchRadiusMeters() : DEFAULT_RADIUS));
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

    private void validateLocation() {
        Double lat = props.branchLatitude();
        Double lng = props.branchLongitude();
        Integer radius = props.branchRadiusMeters();
        if ((lat == null) != (lng == null)
                || (lat != null && (!Double.isFinite(lat) || !Double.isFinite(lng)
                        || lat < -90 || lat > 90 || lng < -180 || lng > 180))
                || (radius != null && (radius < 10 || radius > 5000))) {
            throw new IllegalStateException(LOCATION_MESSAGE);
        }
    }

    // The dev database is usually seeded already, so the env vars must also move the existing branch.
    private void updateBranchLocation(Company company) {
        if (props.branchLatitude() == null && props.branchRadiusMeters() == null) {
            return;
        }
        for (Branch branch : branchRepository.findAllByCompanyIdOrderByName(company.getId())) {
            if (BRANCH_NAME.equals(branch.getName())) {
                branchRepository.updateLocation(branch.getId(), company.getId(),
                        props.branchLatitude() != null ? props.branchLatitude() : branch.getLatitude(),
                        props.branchLongitude() != null ? props.branchLongitude() : branch.getLongitude(),
                        props.branchRadiusMeters() != null ? props.branchRadiusMeters() : branch.getRadiusMeters());
                log.info("Dev seed: branch location updated");
            }
        }
    }
}
