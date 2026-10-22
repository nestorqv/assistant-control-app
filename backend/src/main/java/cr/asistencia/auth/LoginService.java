package cr.asistencia.auth;

import cr.asistencia.empleado.Empleado;
import cr.asistencia.empleado.EmpleadoRepository;
import cr.asistencia.empresa.Empresa;
import cr.asistencia.empresa.EmpresaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Not @Transactional on purpose: the failure counter must commit even though the request ends in 401.
 * Exactly one PasswordEncoder.matches runs per attempt, so every failure costs the same.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final EmpresaRepository empresaRepository;
    private final EmpleadoRepository empleadoRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final SecurityProperties props;
    private final Clock clock;
    private final String dummyHash;

    public LoginService(EmpresaRepository empresaRepository, EmpleadoRepository empleadoRepository,
            PasswordEncoder passwordEncoder, TokenService tokenService, SecurityProperties props, Clock clock) {
        this.empresaRepository = empresaRepository;
        this.empleadoRepository = empleadoRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.props = props;
        this.clock = clock;
        // Same strength as real hashes, used when there is no real hash to compare against.
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(String empresaCodigo, String empleadoCodigo, String pin, String ip) {
        String codigoEmpresa = normalizar(empresaCodigo);
        String codigoEmpleado = normalizar(empleadoCodigo);
        Instant ahora = Instant.now(clock);

        Optional<Empresa> empresa = empresaRepository.findByCodigo(codigoEmpresa);
        Optional<Empleado> encontrado = empresa
                .flatMap(e -> empleadoRepository.findByEmpresaIdAndCodigo(e.getId(), codigoEmpleado));
        Empleado empleado = encontrado.orElse(null);

        boolean bloqueado = empleado != null && empleado.getBloqueadoHasta() != null
                && empleado.getBloqueadoHasta().isAfter(ahora);
        // Unknown or locked: compare against the dummy hash. Inactive: real hash, result ignored.
        String hash = empleado != null && !bloqueado ? empleado.getPinHash() : dummyHash;
        boolean pinCorrecto = passwordEncoder.matches(pin, hash);

        if (empleado == null) {
            log.warn("Login failed: unknown empresa or empleado, ip={}", ip);
            throw new CredencialesInvalidasException();
        }
        if (bloqueado) {
            log.warn("Login rejected: locked account, empresa={}, empleado={}, ip={}",
                    empresa.get().getCodigo(), empleado.getCodigo(), ip);
            throw new CredencialesInvalidasException();
        }
        if (!empleado.isActivo() || !empresa.get().isActivo()) {
            log.warn("Login rejected: inactive account, empresa={}, empleado={}, ip={}",
                    empresa.get().getCodigo(), empleado.getCodigo(), ip);
            throw new CredencialesInvalidasException();
        }
        if (!pinCorrecto) {
            SecurityProperties.Lockout lockout = props.lockout();
            empleadoRepository.registrarFallo(empleado.getId(), empleado.getEmpresaId(),
                    lockout.maxIntentos(), ahora.plus(lockout.duracion()), ahora);
            log.warn("Login failed: wrong PIN, empresa={}, empleado={}, ip={}",
                    empresa.get().getCodigo(), empleado.getCodigo(), ip);
            throw new CredencialesInvalidasException();
        }

        // The lock was checked before the slow BCrypt compare. This conditional UPDATE re-checks it
        // atomically, so a correct guess that raced a concurrent lock gets no token and clears nothing.
        if (empleadoRepository.registrarExito(empleado.getId(), empleado.getEmpresaId(), ahora) == 0) {
            log.warn("Login rejected: account locked during the attempt, empresa={}, empleado={}, ip={}",
                    empresa.get().getCodigo(), empleado.getCodigo(), ip);
            throw new CredencialesInvalidasException();
        }
        TokenService.EmitidoToken emitido = tokenService.emitir(empleado);
        return new LoginResponse(
                emitido.token(),
                "Bearer",
                emitido.expiraEn(),
                new LoginResponse.EmpleadoInfo(empleado.getId(), empleado.getNombre(), empleado.getPuesto(),
                        empleado.getRol(), empleado.getConsentimientoFecha() == null),
                new LoginResponse.EmpresaInfo(empresa.get().getNombre(), empresa.get().getZonaHoraria()));
    }

    private static String normalizar(String codigo) {
        return codigo.trim().toUpperCase(Locale.ROOT);
    }
}
