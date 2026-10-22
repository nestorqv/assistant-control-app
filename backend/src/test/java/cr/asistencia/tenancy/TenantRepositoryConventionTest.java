package cr.asistencia.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import cr.asistencia.empleado.EmpleadoRepository;
import cr.asistencia.empleado.HorarioRepository;
import cr.asistencia.empresa.SucursalRepository;
import cr.asistencia.marca.AjusteRepository;
import cr.asistencia.marca.EvidenciaRepository;
import cr.asistencia.marca.MarcaRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

/** Every repository method must take an "empresaId" parameter, except save (and EmpresaRepository.findByCodigo). */
class TenantRepositoryConventionTest {

    private static final List<Class<?>> TENANT_REPOSITORIES = List.of(
            SucursalRepository.class, EmpleadoRepository.class, HorarioRepository.class,
            MarcaRepository.class, EvidenciaRepository.class, AjusteRepository.class);

    @Test
    void tenantRepositoriesExtendTheBareRepositoryInterface() {
        for (Class<?> repo : TENANT_REPOSITORIES) {
            assertThat(Repository.class).isAssignableFrom(repo);
            assertThat(CrudRepository.class.isAssignableFrom(repo)).as(repo.getSimpleName()).isFalse();
        }
    }

    @Test
    void everyMethodExceptSaveDeclaresAnEmpresaIdParameter() {
        for (Class<?> repo : TENANT_REPOSITORIES) {
            for (Method method : repo.getDeclaredMethods()) {
                if (method.getName().equals("save")) {
                    continue;
                }
                boolean scoped = Arrays.stream(method.getParameters())
                        .anyMatch(p -> p.isNamePresent() && p.getName().equals("empresaId"));
                assertThat(scoped).as(repo.getSimpleName() + "." + method.getName()).isTrue();
            }
        }
    }
}
