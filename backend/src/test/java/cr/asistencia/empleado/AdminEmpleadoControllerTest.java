package cr.asistencia.empleado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AdminEmpleadoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void employeeTokenIsForbidden() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        mockMvc.perform(get("/api/admin/empleados").header("Authorization", bearer(data.login(t, u))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminSeesOnlyItsOwnEmpresa() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.Usuario adminA = data.usuario(a, "ADMIN");
        TestData.Usuario empA = data.usuario(a, "EMPLEADO");
        TestData.Usuario empB = data.usuario(b, "EMPLEADO");

        String json = mockMvc.perform(get("/api/admin/empleados")
                        .header("Authorization", bearer(data.login(a, adminA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(json).contains("\"id\":" + adminA.id(), "\"id\":" + empA.id());
        assertThat(json).doesNotContain("\"id\":" + empB.id());
        assertThat(json).doesNotContainIgnoringCase("pinHash").doesNotContain("intentosFallidos")
                .doesNotContain("bloqueadoHasta").doesNotContain("$2");
    }

    @Test
    void employeeOfAnotherEmpresaIsNotFound() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.Usuario adminA = data.usuario(a, "ADMIN");
        TestData.Usuario empB = data.usuario(b, "EMPLEADO");
        String token = bearer(data.login(a, adminA));

        mockMvc.perform(get("/api/admin/empleados/" + empB.id()).header("Authorization", token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/empleados/" + adminA.id()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("ADMIN"));
    }

    @Test
    void roleChangeInTheDatabaseAppliesToAnIssuedToken() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario admin = data.usuario(t, "ADMIN");
        String token = bearer(data.login(t, admin));
        mockMvc.perform(get("/api/admin/empleados").header("Authorization", token)).andExpect(status().isOk());

        // The CHECK requires a sucursal for non-admins, so set it together with the demotion.
        jdbc.update("update empleado set rol = 'EMPLEADO', sucursal_id = ? where id = ?", t.sucursalId(), admin.id());
        mockMvc.perform(get("/api/admin/empleados").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void typeMismatchOnPathVariableIsBadRequestWithCodigoAndDoesNotEchoTheInput() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario admin = data.usuario(t, "ADMIN");
        String respuesta = mockMvc.perform(get("/api/admin/empleados/{id}", "abc<script>")
                        .header("Authorization", bearer(data.login(t, admin))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
                .andReturn().getResponse().getContentAsString();
        assertThat(respuesta).doesNotContain("abc").doesNotContain("script").doesNotContain("Failed to convert");
    }
}
