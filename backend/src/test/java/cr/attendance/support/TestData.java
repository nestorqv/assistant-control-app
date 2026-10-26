package cr.attendance.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
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

    public record Tenant(long companyId, String companyCode, long branchId) {
    }

    public record TestUser(long id, String code, String pin) {
    }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String pinHash;

    public String randomCode(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public synchronized String pinHash() {
        if (pinHash == null) {
            pinHash = passwordEncoder.encode(PIN);
        }
        return pinHash;
    }

    public long company() {
        return company(randomCode("T"));
    }

    public long company(String code) {
        return jdbc.queryForObject(
                "insert into company (code, name) values (?, 'Empresa de prueba') returning id",
                Long.class, code);
    }

    public String companyCode(long companyId) {
        return jdbc.queryForObject("select code from company where id = ?", String.class, companyId);
    }

    public long branch(long companyId) {
        return jdbc.queryForObject("""
                insert into branch (company_id, name, latitude, longitude)
                values (?, ?, 9.9325, -84.0796) returning id
                """, Long.class, companyId, "Sucursal " + UUID.randomUUID());
    }

    public Tenant tenant() {
        long companyId = company();
        return new Tenant(companyId, companyCode(companyId), branch(companyId));
    }

    public long employee(long companyId, Long branchId, String role) {
        return jdbc.queryForObject("""
                insert into employee (company_id, branch_id, code, name, job_title, pin_hash, role)
                values (?, ?, ?, 'Persona de prueba', 'Puesto', ?, ?) returning id
                """, Long.class, companyId, branchId, randomCode("E"), pinHash(), role);
    }

    public TestUser user(Tenant tenant, String role) {
        Long branchId = "ADMIN".equals(role) ? null : tenant.branchId();
        long id = employee(tenant.companyId(), branchId, role);
        String code = jdbc.queryForObject("select code from employee where id = ?", String.class, id);
        return new TestUser(id, code, PIN);
    }

    public long timeEntry(long companyId, long employeeId, long branchId) {
        return timeEntry(companyId, employeeId, branchId, "CLOCK_IN", LocalDate.now());
    }

    public long timeEntry(long companyId, long employeeId, long branchId, String type, LocalDate workDate) {
        return jdbc.queryForObject("""
                insert into time_entry (company_id, employee_id, branch_id, type, source, client_uuid, work_date)
                values (?, ?, ?, ?, 'MOBILE', ?, ?) returning id
                """, Long.class, companyId, employeeId, branchId, type, UUID.randomUUID(), workDate);
    }

    /** Accepts the photo/location notice, as POST /api/me/consent does. */
    public void consent(long employeeId) {
        jdbc.update("update employee set consent_at = now() where id = ?", employeeId);
    }

    public void schedule(long companyId, long employeeId, int isoDay, LocalTime start, LocalTime end, int grace) {
        jdbc.update("""
                insert into work_schedule (company_id, employee_id, day_of_week, start_time, end_time, grace_minutes)
                values (?, ?, ?, ?, ?, ?)
                """, companyId, employeeId, isoDay, start, end, grace);
    }

    /** A branch at the given position (the default test branch is at 9.9325, -84.0796). */
    public long branchAt(long companyId, double lat, double lng, int radiusMeters) {
        return jdbc.queryForObject("""
                insert into branch (company_id, name, latitude, longitude, radius_meters)
                values (?, ?, ?, ?, ?) returning id
                """, Long.class, companyId, "Sucursal " + UUID.randomUUID(), lat, lng, radiusMeters);
    }

    /** Logs in through the real endpoint and returns the bearer token. */
    public String login(Tenant tenant, TestUser user) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "companyCode", tenant.companyCode(),
                "employeeCode", user.code(),
                "pin", user.pin()));
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }
}
