package cr.asistencia.empleado;

import cr.asistencia.common.EmpleadoPrincipal;
import cr.asistencia.common.RecursoNoEncontradoException;
import cr.asistencia.empresa.Empresa;
import cr.asistencia.empresa.EmpresaRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final EmpleadoRepository empleadoRepository;
    private final EmpresaRepository empresaRepository;
    private final Clock clock;

    public MeController(EmpleadoRepository empleadoRepository, EmpresaRepository empresaRepository, Clock clock) {
        this.empleadoRepository = empleadoRepository;
        this.empresaRepository = empresaRepository;
        this.clock = clock;
    }

    @GetMapping
    public PerfilResponse perfil(@AuthenticationPrincipal EmpleadoPrincipal principal) {
        Empleado empleado = empleadoRepository.findByIdAndEmpresaId(principal.empleadoId(), principal.empresaId())
                .orElseThrow(RecursoNoEncontradoException::new);
        Empresa empresa = empresaRepository.findById(principal.empresaId())
                .orElseThrow(RecursoNoEncontradoException::new);
        return new PerfilResponse(empleado.getId(), empleado.getCodigo(), empleado.getNombre(),
                empleado.getPuesto(), empleado.getRol(), empleado.getConsentimientoFecha() == null,
                new PerfilResponse.EmpresaInfo(empresa.getNombre(), empresa.getZonaHoraria()));
    }

    // Idempotent: only the first call sets the date (Ley 8968 consent).
    @PostMapping("/consentimiento")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void consentir(@AuthenticationPrincipal EmpleadoPrincipal principal) {
        empleadoRepository.registrarConsentimiento(principal.empleadoId(), principal.empresaId(),
                Instant.now(clock));
    }
}
