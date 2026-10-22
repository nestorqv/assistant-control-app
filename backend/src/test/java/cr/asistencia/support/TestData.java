package cr.asistencia.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Creates data with random unique codes: the cached Spring context shares one database across
 * test classes, so tests never assume an empty database.
 */
@Component
public class TestData {

    /** Test-only PIN; valid under PinPolicy. */
    public static final String PIN = "48207315";

    public record Tenant(long empresaId, String empresaCodigo, long sucursalId) {
    }

    public record Usuario(long id, String codigo, String pin) {
    }

    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String pinHash;

    public String codigoAleatorio(String prefijo) {
        StringBuilder sb = new StringBuilder(prefijo);
        for (int i = 0; i < 8; i++) {
            sb.append(ALFABETO.charAt(ThreadLocalRandom.current().nextInt(ALFABETO.length())));
        }
        return sb.toString();
    }

    public synchronized String pinHash() {
        if (pinHash == null) {
            pinHash = passwordEncoder.encode(PIN);
        }
        return pinHash;
    }

    public long empresa() {
        return empresa(codigoAleatorio("T"));
    }

    public long empresa(String codigo) {
        return jdbc.queryForObject(
                "insert into empresa (codigo, nombre) values (?, 'Empresa de prueba') returning id",
                Long.class, codigo);
    }

    public String codigoEmpresa(long empresaId) {
        return jdbc.queryForObject("select codigo from empresa where id = ?", String.class, empresaId);
    }

    public long sucursal(long empresaId) {
        return jdbc.queryForObject("""
                insert into sucursal (empresa_id, nombre, latitud, longitud)
                values (?, ?, 9.9325, -84.0796) returning id
                """, Long.class, empresaId, "Sucursal " + UUID.randomUUID());
    }

    public Tenant tenant() {
        long empresaId = empresa();
        return new Tenant(empresaId, codigoEmpresa(empresaId), sucursal(empresaId));
    }

    public long empleado(long empresaId, Long sucursalId, String rol) {
        return jdbc.queryForObject("""
                insert into empleado (empresa_id, sucursal_id, codigo, nombre, puesto, pin_hash, rol)
                values (?, ?, ?, 'Persona de prueba', 'Puesto', ?, ?) returning id
                """, Long.class, empresaId, sucursalId, codigoAleatorio("E"), pinHash(), rol);
    }

    public Usuario usuario(Tenant tenant, String rol) {
        Long sucursalId = "ADMIN".equals(rol) ? null : tenant.sucursalId();
        long id = empleado(tenant.empresaId(), sucursalId, rol);
        String codigo = jdbc.queryForObject("select codigo from empleado where id = ?", String.class, id);
        return new Usuario(id, codigo, PIN);
    }

    public long marca(long empresaId, long empleadoId, long sucursalId) {
        return jdbc.queryForObject("""
                insert into marca (empresa_id, empleado_id, sucursal_id, tipo, origen, client_uuid)
                values (?, ?, ?, 'ENTRADA', 'MOVIL', ?) returning id
                """, Long.class, empresaId, empleadoId, sucursalId, UUID.randomUUID());
    }

    /** Logs in through the real endpoint and returns the bearer token. */
    public String login(Tenant tenant, Usuario usuario) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "empresaCodigo", tenant.empresaCodigo(),
                "empleadoCodigo", usuario.codigo(),
                "pin", usuario.pin()));
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }
}
