package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.AdminClient;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.TestImages;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Branch create / edit / activate / deactivate: validation, versions, audit rows, tenant scope. */
@IntegrationTest
class AdminBranchManagementIntegrationTest {

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private CheckInClient checkIn;

    @Autowired
    private JdbcTemplate jdbc;

    private static Map<String, Object> body(String name, Object lat, Object lng, Object radius) {
        return Map.of("name", name, "latitude", lat, "longitude", lng, "radiusMeters", radius);
    }

    private static Map<String, Object> withVersion(Map<String, Object> base, int version) {
        Map<String, Object> copy = new java.util.HashMap<>(base);
        copy.put("expectedVersion", version);
        return copy;
    }

    private long createBranch(AdminClient.Admin admin, String name) throws Exception {
        return admins.json(admins.post(admin.token(), "/api/admin/branches", body(name, 9.5, -84.1, 120))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private int auditCount(long companyId, String action) {
        return jdbc.queryForObject("select count(*) from audit_log where company_id = ? and action = ?", Integer.class,
                companyId, action);
    }

    @Test
    void createReturns201WithVersionZeroAndWritesAnAuditRowWithoutValues() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        String name = "Heredia " + System.nanoTime();
        JsonNode created = admins.json(admins.post(admin.token(), "/api/admin/branches", body(name, 10.0024, -84.1165, 150))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true)));
        assertThat(created.get("version").asInt()).isZero();
        assertThat(created.get("activeEmployees").asInt()).isZero();
        long id = created.get("id").asLong();
        String details = jdbc.queryForObject("select coalesce(details, '') from audit_log where action = 'BRANCH_CREATE' and target_id = ?",
                String.class, id);
        assertThat(details).isEmpty();
        assertThat(jdbc.queryForObject("select actor_id from audit_log where action = 'BRANCH_CREATE' and target_id = ?",
                Long.class, id)).isEqualTo(admin.id());
    }

    @Test
    void updateBumpsTheVersionAndTheAuditDetailsNameFieldsNeverValues() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long id = createBranch(admin, "Original " + System.nanoTime());
        JsonNode updated = admins.json(admins.put(admin.token(), "/api/admin/branches/" + id,
                withVersion(body("Renombrada " + System.nanoTime(), 9.123456, -83.654321, 200), 0))
                .andExpect(status().isOk()));
        assertThat(updated.get("version").asInt()).isEqualTo(1);
        String details = jdbc.queryForObject("select details from audit_log where action = 'BRANCH_UPDATE' and target_id = ?",
                String.class, id);
        assertThat(details).isEqualTo("fields=name,latitude,longitude,radiusMeters");
        assertThat(details).doesNotContain("9.12").doesNotContain("83.65");
    }

    @Test
    void anUpdateThatChangesNothingIsANoOpWithoutAuditOrVersionBump() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        String name = "Igual " + System.nanoTime();
        long id = createBranch(admin, name);
        JsonNode same = admins.json(admins.put(admin.token(), "/api/admin/branches/" + id,
                withVersion(body("  " + name + " ", 9.5, -84.1, 120), 0)).andExpect(status().isOk()));
        assertThat(same.get("version").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'BRANCH_UPDATE' and target_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void invalidBodiesGive400WithoutEchoingTheValue() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        String path = "/api/admin/branches";
        String ok = "\"name\":\"Valida\",\"latitude\":9.9,\"longitude\":-84.0,\"radiusMeters\":100";
        admins.postRaw(admin.token(), path, "{\"name\":\"X\",\"latitude\":\"NaN\",\"longitude\":-84.0,\"radiusMeters\":100}")
                .andExpect(status().isBadRequest());
        admins.postRaw(admin.token(), path, "{\"name\":\"X\",\"latitude\":91,\"longitude\":-84.0,\"radiusMeters\":100}")
                .andExpect(status().isBadRequest());
        admins.postRaw(admin.token(), path, "{\"name\":\"X\",\"latitude\":9.9,\"longitude\":-181,\"radiusMeters\":100}")
                .andExpect(status().isBadRequest());
        admins.postRaw(admin.token(), path, "{\"name\":\"X\",\"latitude\":\"9,93\",\"longitude\":-84.0,\"radiusMeters\":100}")
                .andExpect(status().isBadRequest());
        for (String radius : new String[] {"9", "5001", "null"}) {
            admins.postRaw(admin.token(), path, "{\"name\":\"X\",\"latitude\":9.9,\"longitude\":-84.0,\"radiusMeters\":" + radius + "}")
                    .andExpect(status().isBadRequest());
        }
        admins.postRaw(admin.token(), path, "{\"latitude\":9.9,\"longitude\":-84.0,\"radiusMeters\":100}")
                .andExpect(status().isBadRequest());
        admins.postRaw(admin.token(), path, "{\"name\":\"   \"," + ok.substring(ok.indexOf(",") + 1) + "}")
                .andExpect(status().isBadRequest());
        admins.postRaw(admin.token(), path, "{\"name\":\"Bidi \\u202E\"," + ok.substring(ok.indexOf(",") + 1) + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        String tooLong = "x".repeat(121);
        String response = admins.postRaw(admin.token(), path, "{\"name\":\"" + tooLong + "\"," + ok.substring(ok.indexOf(",") + 1) + "}")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(tooLong);
        assertThat(jdbc.queryForObject("select count(*) from branch where company_id = ? and id <> ?", Integer.class,
                t.companyId(), t.branchId())).isZero();
    }

    @Test
    void aDuplicateNameEvenDifferingInCaseGives409AndRenamingToItsOwnNameIsANoOp() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        String name = "Central " + System.nanoTime();
        long id = createBranch(admin, name);
        admins.post(admin.token(), "/api/admin/branches", body(name.toUpperCase(), 9.5, -84.1, 120))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BRANCH_NAME_TAKEN"));
        admins.put(admin.token(), "/api/admin/branches/" + id, withVersion(body(name, 9.5, -84.1, 120), 0))
                .andExpect(status().isOk());
        long other = createBranch(admin, "Otra " + System.nanoTime());
        admins.put(admin.token(), "/api/admin/branches/" + other, withVersion(body(name.toLowerCase(), 9.5, -84.1, 120), 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BRANCH_NAME_TAKEN"));
    }

    @Test
    void aStaleExpectedVersionGives409RecordChangedAndWritesNothing() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long id = createBranch(admin, "Version " + System.nanoTime());
        admins.put(admin.token(), "/api/admin/branches/" + id,
                withVersion(body("Cambiada " + System.nanoTime(), 9.5, -84.1, 120), 0)).andExpect(status().isOk());
        int audits = auditCount(t.companyId(), "BRANCH_UPDATE");
        admins.put(admin.token(), "/api/admin/branches/" + id,
                withVersion(body("Otra vez " + System.nanoTime(), 9.5, -84.1, 120), 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_CHANGED"));
        assertThat(auditCount(t.companyId(), "BRANCH_UPDATE")).isEqualTo(audits);
        admins.post(admin.token(), "/api/admin/branches/" + id + "/deactivate", Map.of("expectedVersion", 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_CHANGED"));
    }

    @Test
    void deactivateIsRefusedWhileActiveEmployeesAreOnTheBranchAndAllowedOtherwise() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long branch = createBranch(admin, "Uso " + System.nanoTime());
        long employee = data.employee(t.companyId(), branch, "EMPLOYEE");
        String path = "/api/admin/branches/" + branch;
        admins.post(admin.token(), path + "/deactivate", Map.of("expectedVersion", 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BRANCH_IN_USE"));
        jdbc.update("update employee set active = false where id = ?", employee);
        JsonNode off = admins.json(admins.post(admin.token(), path + "/deactivate", Map.of("expectedVersion", 0))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false)));
        assertThat(off.get("version").asInt()).isEqualTo(1);
        JsonNode on = admins.json(admins.post(admin.token(), path + "/activate", Map.of("expectedVersion", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true)));
        assertThat(on.get("version").asInt()).isEqualTo(2);
        assertThat(auditCount(t.companyId(), "BRANCH_DEACTIVATE")).isEqualTo(1);
        assertThat(auditCount(t.companyId(), "BRANCH_ACTIVATE")).isEqualTo(1);
    }

    @Test
    void deactivatingAnInactiveBranchIsANoOp() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long branch = createBranch(admin, "Ya inactiva " + System.nanoTime());
        String path = "/api/admin/branches/" + branch + "/deactivate";
        admins.post(admin.token(), path, Map.of("expectedVersion", 0)).andExpect(status().isOk());
        admins.post(admin.token(), path, Map.of("expectedVersion", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(auditCount(t.companyId(), "BRANCH_DEACTIVATE")).isEqualTo(1);
    }

    @Test
    void anotherTenantsBranchIsNotFoundOnEveryRouteAndEmployeesAreForbidden() throws Exception {
        TestData.Tenant mine = data.tenant();
        TestData.Tenant other = data.tenant();
        AdminClient.Admin admin = admins.admin(mine);
        String path = "/api/admin/branches/" + other.branchId();
        admins.put(admin.token(), path, withVersion(body("X", 9.5, -84.1, 120), 0)).andExpect(status().isNotFound());
        admins.post(admin.token(), path + "/activate", Map.of("expectedVersion", 0)).andExpect(status().isNotFound());
        admins.post(admin.token(), path + "/deactivate", Map.of("expectedVersion", 0)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select active from branch where id = ?", Boolean.class, other.branchId())).isTrue();

        String employeeToken = admins.tokenFor(mine, data.user(mine, "EMPLOYEE"));
        admins.post(employeeToken, "/api/admin/branches", body("X", 9.5, -84.1, 120)).andExpect(status().isForbidden());
    }

    @Test
    void extraJsonPropertiesHaveNoEffect() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long other = data.company();
        admins.postRaw(admin.token(), "/api/admin/branches",
                "{\"name\":\"Extra " + System.nanoTime() + "\",\"latitude\":9.9,\"longitude\":-84.0,\"radiusMeters\":100,"
                        + "\"companyId\":" + other + ",\"active\":false,\"version\":7}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0));
        assertThat(jdbc.queryForObject("select count(*) from branch where company_id = ?", Integer.class, other)).isZero();
    }

    @Test
    void twoConcurrentCreatesWithTheSameNameGiveOneCreatedOneConflictAndOneAuditRow() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin first = admins.admin(t);
        AdminClient.Admin second = admins.admin(t);
        String name = "Carrera " + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> admins.post(first.token(), "/api/admin/branches", body(name, 9.5, -84.1, 120))
                            .andReturn().getResponse().getStatus(),
                    () -> admins.post(second.token(), "/api/admin/branches", body(name, 9.5, -84.1, 120))
                            .andReturn().getResponse().getStatus());
            int created = 0;
            int conflict = 0;
            for (Future<Integer> f : pool.invokeAll(calls)) {
                int s = f.get();
                if (s == 201) {
                    created++;
                } else if (s == 409) {
                    conflict++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflict).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from branch where company_id = ? and name = ?", Integer.class,
                t.companyId(), name)).isEqualTo(1);
        assertThat(auditCount(t.companyId(), "BRANCH_CREATE")).isEqualTo(1);
    }

    @Test
    void activateChecksTheVersionToo() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long id = createBranch(admin, "Reactivar " + System.nanoTime());
        admins.post(admin.token(), "/api/admin/branches/" + id + "/deactivate", Map.of("expectedVersion", 0)).andExpect(status().isOk());
        int version = jdbc.queryForObject("select version from branch where id = ?", Integer.class, id);
        assertThat(version).isGreaterThan(0);

        admins.post(admin.token(), "/api/admin/branches/" + id + "/activate", Map.of("expectedVersion", 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_CHANGED"));
        assertThat(jdbc.queryForObject("select active from branch where id = ?", Boolean.class, id)).isFalse();
        admins.post(admin.token(), "/api/admin/branches/" + id + "/activate", Map.of("expectedVersion", version))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select version from branch where id = ?", Integer.class, id)).isGreaterThan(version);
    }

    @Test
    void movingTheBranchChangesTheNextClockInOnlyAndNeverTheStoredEntries() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        CheckInClient.Actor employee = checkIn.actor(t, "EMPLOYEE", true);
        JsonNode first = admins.json(checkIn.post(employee, checkIn.form("CLOCK_IN", Instant.now()), TestImages.jpeg())
                .andExpect(status().isCreated()));
        assertThat(first.get("insideGeofence").asBoolean()).isTrue();
        long firstId = first.get("id").asLong();
        int version = jdbc.queryForObject("select version from branch where id = ?", Integer.class, t.branchId());

        // The branch moves about 70 km away.
        admins.put(admin.token(), "/api/admin/branches/" + t.branchId(), withVersion(body("Movida " + System.nanoTime(), 10.5, -85.0, 100), version))
                .andExpect(status().isOk());

        Map<String, Object> stored = jdbc.queryForMap("select distance_meters, status from time_entry where id = ?", firstId);
        assertThat(stored.get("distance_meters")).isEqualTo(first.get("distanceMeters").asInt());
        assertThat(stored.get("status")).isEqualTo(first.get("status").asText());

        JsonNode next = admins.json(checkIn.post(employee, checkIn.form("CLOCK_OUT", Instant.now()), TestImages.jpeg())
                .andExpect(status().isCreated()));
        assertThat(next.get("insideGeofence").asBoolean()).isFalse();
        assertThat(next.get("distanceMeters").asInt()).isGreaterThan(1000);
    }
}
