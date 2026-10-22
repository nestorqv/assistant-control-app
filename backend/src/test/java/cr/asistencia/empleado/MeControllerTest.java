package cr.asistencia.empleado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void meReturnsTheCallersOwnProfileAndEmpresaZone() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        TestData.Usuario otro = data.usuario(t, "EMPLEADO");
        String token = data.login(t, u);

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(u.id()))
                .andExpect(jsonPath("$.codigo").value(u.codigo()))
                .andExpect(jsonPath("$.rol").value("EMPLEADO"))
                .andExpect(jsonPath("$.consentimientoPendiente").value(true))
                .andExpect(jsonPath("$.empresa.zonaHoraria").value("America/Costa_Rica"))
                .andExpect(jsonPath("$.pinHash").doesNotExist());
        assertThat(otro.id()).isNotEqualTo(u.id());
    }

    @Test
    void consentIsIdempotentAndKeepsTheFirstDate() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        String token = data.login(t, u);

        mockMvc.perform(post("/api/me/consentimiento").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        String primera = jdbc.queryForObject("select consentimiento_fecha::text from empleado where id = ?",
                String.class, u.id());
        assertThat(primera).isNotNull();

        mockMvc.perform(post("/api/me/consentimiento").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        String segunda = jdbc.queryForObject("select consentimiento_fecha::text from empleado where id = ?",
                String.class, u.id());
        assertThat(segunda).isEqualTo(primera);

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.consentimientoPendiente").value(false));
    }

    @Test
    void consentRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/me/consentimiento")).andExpect(status().isUnauthorized());
    }
}
