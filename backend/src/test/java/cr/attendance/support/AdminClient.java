package cr.attendance.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Helpers for the Stage 4 admin integration tests: an admin who is logged in, JSON calls and raw entry rows. */
@Component
public class AdminClient {

    public record Admin(TestData.Tenant tenant, TestData.TestUser user, String token) {
        public long id() {
            return user.id();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    /** An ADMIN without a branch (as the seeded 9001), logged in through the real endpoint. */
    public Admin admin(TestData.Tenant tenant) throws Exception {
        TestData.TestUser user = data.user(tenant, "ADMIN");
        return new Admin(tenant, user, data.login(tenant, user));
    }

    public String tokenFor(TestData.Tenant tenant, TestData.TestUser user) throws Exception {
        return data.login(tenant, user);
    }

    public ResultActions get(String token, String path) throws Exception {
        var builder = MockMvcRequestBuilders.get(path);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(builder);
    }

    public ResultActions post(String token, String path, Object body) throws Exception {
        var builder = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(builder);
    }

    public ResultActions put(String token, String path, Object body) throws Exception {
        var builder = MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(builder);
    }

    /** POST with no body at all (unlock, and a PIN reset without a reason). */
    public ResultActions postEmpty(String token, String path) throws Exception {
        var builder = MockMvcRequestBuilders.post(path);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(builder);
    }

    /** POST of a hand-written JSON text (values Jackson would not produce, e.g. the string "NaN"). */
    public ResultActions postRaw(String token, String path, String json) throws Exception {
        var builder = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(builder);
    }

    public JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** A consented employee of the tenant branch. */
    public long employee(TestData.Tenant tenant) {
        long id = data.employee(tenant.companyId(), tenant.branchId(), "EMPLOYEE");
        data.consent(id);
        return id;
    }

    /**
     * A MOBILE entry at the default test branch position (distance 0), received and effective at `at`.
     * reasonsCsv is the stored form, e.g. "OUTSIDE_GEOFENCE" (null for none).
     */
    public long entry(TestData.Tenant tenant, long employeeId, String type, LocalDate workDate, Instant at,
            String status, String reasonsCsv, Integer lateMinutes) {
        return entryOn(tenant.companyId(), employeeId, tenant.branchId(), type, workDate, at, status, reasonsCsv,
                lateMinutes);
    }

    public long entryOn(long companyId, long employeeId, long branchId, String type, LocalDate workDate, Instant at,
            String status, String reasonsCsv, Integer lateMinutes) {
        Timestamp ts = Timestamp.from(at);
        return jdbc.queryForObject("""
                insert into time_entry (company_id, employee_id, branch_id, type, source, status, client_uuid,
                                        work_date, server_time, effective_time, device_time, latitude, longitude,
                                        distance_meters, gps_accuracy_meters, skew_seconds, late_minutes,
                                        review_reasons)
                values (?, ?, ?, ?, 'MOBILE', ?, ?, ?, ?, ?, ?, 9.9325, -84.0796, 0, 10, 0, ?, ?) returning id
                """, Long.class, companyId, employeeId, branchId, type, status, UUID.randomUUID(), workDate, ts, ts, ts,
                "CLOCK_IN".equals(type) ? lateMinutes : null, reasonsCsv);
    }

    public int adjustmentCount(long timeEntryId) {
        return jdbc.queryForObject("select count(*) from adjustment where time_entry_id = ?", Integer.class,
                timeEntryId);
    }
}
