package cr.asistencia.seed;

import cr.asistencia.auth.PinPolicy;
import cr.asistencia.common.Rol;
import cr.asistencia.empleado.Empleado;
import cr.asistencia.empleado.EmpleadoRepository;
import cr.asistencia.empleado.Horario;
import cr.asistencia.empleado.HorarioRepository;
import cr.asistencia.empresa.Empresa;
import cr.asistencia.empresa.EmpresaRepository;
import cr.asistencia.empresa.Sucursal;
import cr.asistencia.empresa.SucursalRepository;
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

    static final String EMPRESA_CODIGO = "DEMO";
    static final String MENSAJE_PIN =
            "DEV_SEED_PIN is required for the dev profile: exactly 8 digits, not a sequence, repeated pattern or date";

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private record Demo(String codigo, String nombre, String puesto, Rol rol, LocalTime entrada, LocalTime salida) {
    }

    private static final LocalTime OFICINA_ENTRADA = LocalTime.of(8, 0);
    private static final LocalTime OFICINA_SALIDA = LocalTime.of(17, 0);

    private static final Demo[] EMPLEADOS = {
        new Demo("1001", "María Rojas", "Cajera", Rol.EMPLEADO, OFICINA_ENTRADA, OFICINA_SALIDA),
        new Demo("1002", "José Vargas", "Bodega", Rol.EMPLEADO, OFICINA_ENTRADA, OFICINA_SALIDA),
        new Demo("1003", "Ana Chaves", "Ventas", Rol.EMPLEADO, OFICINA_ENTRADA, OFICINA_SALIDA),
        new Demo("1004", "Luis Mora", "Ventas", Rol.EMPLEADO, OFICINA_ENTRADA, OFICINA_SALIDA),
        new Demo("1005", "Karla Solís", "Limpieza", Rol.EMPLEADO, LocalTime.of(6, 0), LocalTime.of(14, 0)),
        new Demo("1006", "Diego Arias", "Bodega", Rol.EMPLEADO, OFICINA_ENTRADA, OFICINA_SALIDA),
        new Demo("9001", "Administrador Demo", "Administración", Rol.ADMIN, OFICINA_ENTRADA, OFICINA_SALIDA),
    };

    private final Environment environment;
    private final SeedProperties props;
    private final EmpresaRepository empresaRepository;
    private final SucursalRepository sucursalRepository;
    private final EmpleadoRepository empleadoRepository;
    private final HorarioRepository horarioRepository;
    private final PasswordEncoder passwordEncoder;

    public DevDataSeeder(Environment environment, SeedProperties props, EmpresaRepository empresaRepository,
            SucursalRepository sucursalRepository, EmpleadoRepository empleadoRepository,
            HorarioRepository horarioRepository, PasswordEncoder passwordEncoder) {
        this.environment = environment;
        this.props = props;
        this.empresaRepository = empresaRepository;
        this.sucursalRepository = sucursalRepository;
        this.empleadoRepository = empleadoRepository;
        this.horarioRepository = horarioRepository;
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
        if (!PinPolicy.esValido(pin)) {
            throw new IllegalStateException(MENSAJE_PIN);
        }
        if (empresaRepository.findByCodigo(EMPRESA_CODIGO).isPresent()) {
            log.info("Dev seed already present");
            return;
        }

        Empresa empresa = empresaRepository.save(new Empresa(EMPRESA_CODIGO, "Empresa Demo"));
        // Placeholder San José coordinates.
        Sucursal sucursal = sucursalRepository.save(
                new Sucursal(empresa.getId(), "Sucursal Centro", 9.9325, -84.0796, 100));
        String pinHash = passwordEncoder.encode(pin);

        for (Demo demo : EMPLEADOS) {
            Long sucursalId = demo.rol() == Rol.ADMIN ? null : sucursal.getId();
            Empleado empleado = empleadoRepository.save(new Empleado(empresa.getId(), sucursalId, demo.codigo(),
                    demo.nombre(), demo.puesto(), pinHash, demo.rol()));
            for (int dia = 1; dia <= 5; dia++) {
                horarioRepository.save(new Horario(empresa.getId(), empleado.getId(), dia, demo.entrada(),
                        demo.salida(), 5));
            }
        }
        log.info("Dev seed created: empresa DEMO, 7 empleados (codes 1001-1006, 9001)");
    }
}
