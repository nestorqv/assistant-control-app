package cr.asistencia.empleado;

import cr.asistencia.common.EmpleadoPrincipal;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/empleados")
public class AdminEmpleadoController {

    private final EmpleadoConsultaService service;

    public AdminEmpleadoController(EmpleadoConsultaService service) {
        this.service = service;
    }

    @GetMapping
    public List<EmpleadoResumen> listar(@AuthenticationPrincipal EmpleadoPrincipal principal) {
        return service.listar(principal.empresaId());
    }

    @GetMapping("/{id}")
    public EmpleadoResumen obtener(@AuthenticationPrincipal EmpleadoPrincipal principal, @PathVariable Long id) {
        return service.obtener(principal.empresaId(), id);
    }
}
