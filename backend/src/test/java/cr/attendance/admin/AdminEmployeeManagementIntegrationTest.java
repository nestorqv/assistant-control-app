package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Employee create / edit / activate / deactivate / PIN reset / unlock. No assertion message or log check ever
 * prints a PIN: PINs are only matched against patterns or used to log in.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AdminEmployeeManagementIntegrationTest {

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    private static Map<String, Object> createBody(String code, String name, String role, Long branchId) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("jobTitle", "Cajera");
        body.put("role", role);
        body.put("branchId", branchId);
        return body;
    }

    private static Map<String, Object> updateBody(String name, String role, Long branchId, int version) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("jobTitle", null);
        body.put("role", role);
        body.put("branchId", branchId);
        body.put("expectedVersion", version);
        return body;
    }

    private JsonNode createEmployee(AdminClient.Admin admin, String code, String name, Long branchId) throws Exception {
        return admins.json(admins.post(admin.token(), "/api/admin/employees", createBody(code, name, "EMPLOYEE", branchId))
                .andExpect(status().isCreated()));
    }

    private int loginStatus(TestData.Tenant t, String code, String pin) throws Exception {
        String json = "{\"companyCode\":\"" + t.companyCode() + "\",\"employeeCode\":\"" + code + "\",\"pin\":\"" + pin + "\"}";
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getStatus();
    }

    private int audits(long companyId, String action) {
        return jdbc.queryForObject("select count(*) from audit_log where company_id = ? and action = ?", Integer.class,
                companyId, action);
    }

    private int tokenVersion(long id) {
        return jdbc.queryForObject("select token_version from employee where id = ?", Integer.class, id);
    }

    private int version(long id) {
        return jdbc.queryForObject("select version from employee where id = ?", Integer.class, id);
    }

    @Test
    void createGivesAPolicyValidPinOnceWithNoStoreAndNothingElseEverShowsIt(CapturedOutput output) throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        String code = data.randomCode("N");
        var result = admins.post(admin.token(), "/api/admin/employees", createBody(" " + code.toLowerCase() + " ", "Nombre Apellido", "EMPLOYEE", t.branchId()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        JsonNode body = admins.json(result);
        String pin = body.get("pin").asText();
        assertThat(pin.matches("\\d{8}")).as("PIN format").isTrue();
        assertThat(cr.attendance.auth.PinPolicy.isValid(pin)).isTrue();
        long id = body.get("employee").get("id").asLong();
        assertThat(body.get("employee").get("code").asText()).isEqualTo(code);
        assertThat(body.get("employee").toString().contains(pin)).as("the employee view carries the PIN").isFalse();
        assertThat(body.get("employee").toString()).doesNotContain("pinHash");

        assertThat(loginStatus(t, code, pin)).isEqualTo(200);

        // The PIN is nowhere else: not in the audit row, not in any later read, not in the logs.
        String auditText = jdbc.queryForObject("select coalesce(details, '') || coalesce(reason_note, '') from audit_log where target_id = ? and action = 'EMPLOYEE_CREATE'",
                String.class, id);
        assertThat(auditText.contains(pin)).as("the audit row carries the PIN").isFalse();
        for (String path : new String[] {"/api/admin/employees", "/api/admin/employees/" + id, "/api/admin/employees/" + id + "/schedule"}) {
            String json = admins.get(admin.token(), path).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.contains(pin)).as("a later GET carries the PIN").isFalse();
        }
        assertThat(output.getAll().contains(pin)).as("the logs carry the PIN").isFalse();
        assertThat(output.getAll()).doesNotContain("$2a$").doesNotContain("$2b$");
        assertThat(audits(t.companyId(), "EMPLOYEE_CREATE")).isEqualTo(1);
    }

    @Test
    void aDuplicateCodeInAnyCaseGives409WithoutEchoingTheCodeOrNameAndDeactivatedCodesStayTaken() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        String code = data.randomCode("D");
        long id = createEmployee(admin, code, "Nombre Original", t.branchId()).get("employee").get("id").asLong();
        String response = admins.post(admin.token(), "/api/admin/employees", createBody(code.toLowerCase(), "Otro Nombre Distinto", "EMPLOYEE", t.branchId()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMPLOYEE_CODE_TAKEN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContainIgnoringCase(code).doesNotContain("Otro Nombre");
        jdbc.update("update employee set active = false where id = ?", id);
        admins.post(admin.token(), "/api/admin/employees", createBody(code, "Reuso", "EMPLOYEE", t.branchId()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMPLOYEE_CODE_TAKEN"));
    }

    @Test
    void branchRulesOnCreate() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        admins.post(admin.token(), "/api/admin/employees", createBody(data.randomCode("R"), "Sin Sucursal", "EMPLOYEE", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BRANCH_REQUIRED"));
        admins.post(admin.token(), "/api/admin/employees", createBody(data.randomCode("R"), "Admin Sin Sucursal", "ADMIN", null))
                .andExpect(status().isCreated());
        long foreign = data.tenant().branchId();
        admins.post(admin.token(), "/api/admin/employees", createBody(data.randomCode("R"), "Otra Empresa", "EMPLOYEE", foreign))
                .andExpect(status().isNotFound());
        long retired = data.branch(t.companyId());
        jdbc.update("update branch set active = false where id = ?", retired);
        admins.post(admin.token(), "/api/admin/employees", createBody(data.randomCode("R"), "Inactiva", "EMPLOYEE", retired))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BRANCH_INACTIVE"));
    }

    @Test
    void putCleansTextIgnoresTheCodeAndRefusesInvisibleCharacters() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        String code = data.randomCode("P");
        JsonNode created = createEmployee(admin, code, "Nombre Uno", t.branchId()).get("employee");
        long id = created.get("id").asLong();
        String decomposed = "José Perez";
        Map<String, Object> body = updateBody("  " + decomposed + "  ", "EMPLOYEE", t.branchId(), created.get("version").asInt());
        body.put("jobTitle", "   ");
        body.put("code", "HACKED");
        JsonNode updated = admins.json(admins.put(admin.token(), "/api/admin/employees/" + id, body).andExpect(status().isOk()));
        assertThat(updated.get("name").asText()).isEqualTo("José Perez");
        assertThat(updated.get("jobTitle").isNull()).isTrue();
        assertThat(updated.get("code").asText()).isEqualTo(code);
        admins.put(admin.token(), "/api/admin/employees/" + id, updateBody("Con​oculto", "EMPLOYEE", t.branchId(), updated.get("version").asInt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aStaleVersionGives409AndPinResetAndUnlockDoNotMoveTheVersion() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        JsonNode created = createEmployee(admin, data.randomCode("V"), "Nombre Version", t.branchId()).get("employee");
        long id = created.get("id").asLong();
        int v0 = created.get("version").asInt();
        admins.postEmpty(admin.token(), "/api/admin/employees/" + id + "/pin-reset").andExpect(status().isOk());
        jdbc.update("update employee set locked_until = now() + interval '10 minutes', failed_attempts = 3 where id = ?", id);
        admins.postEmpty(admin.token(), "/api/admin/employees/" + id + "/unlock").andExpect(status().isOk());
        assertThat(version(id)).isEqualTo(v0);
        // A form loaded before the reset and the unlock is still valid.
        JsonNode saved = admins.json(admins.put(admin.token(), "/api/admin/employees/" + id,
                updateBody("Nombre Nuevo", "EMPLOYEE", t.branchId(), v0)).andExpect(status().isOk()));
        assertThat(saved.get("version").asInt()).isEqualTo(v0 + 1);
        admins.put(admin.token(), "/api/admin/employees/" + id, updateBody("Otro Nombre", "EMPLOYEE", t.branchId(), v0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_CHANGED"));
    }

    @Test
    void aRoleChangeEndsTheSessionsOnceAndANameChangeDoesNot() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        TestData.TestUser user = data.user(t, "EMPLOYEE");
        String userToken = data.login(t, user);
        int before = tokenVersion(user.id());
        int version = version(user.id());
        admins.put(admin.token(), "/api/admin/employees/" + user.id(), updateBody("Solo Nombre", "EMPLOYEE", t.branchId(), version))
                .andExpect(status().isOk());
        assertThat(tokenVersion(user.id())).isEqualTo(before);
        admins.get(userToken, "/api/me").andExpect(status().isOk());

        admins.put(admin.token(), "/api/admin/employees/" + user.id(), updateBody("Solo Nombre", "ADMIN", null, version + 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        assertThat(tokenVersion(user.id())).isEqualTo(before + 1);
        admins.get(userToken, "/api/me").andExpect(status().isUnauthorized());
        assertThat(audits(t.companyId(), "EMPLOYEE_ROLE_CHANGE")).isEqualTo(1);
    }

    @Test
    void onePutThatChangesRoleAndNameWritesTwoAuditRowsAndANoOpWritesNone() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        TestData.TestUser user = data.user(t, "EMPLOYEE");
        int version = version(user.id());
        admins.put(admin.token(), "/api/admin/employees/" + user.id(), updateBody("Dos Cambios", "ADMIN", null, version))
                .andExpect(status().isOk());
        assertThat(audits(t.companyId(), "EMPLOYEE_ROLE_CHANGE")).isEqualTo(1);
        assertThat(audits(t.companyId(), "EMPLOYEE_UPDATE")).isEqualTo(1);
        int rows = jdbc.queryForObject("select count(*) from audit_log where company_id = ?", Integer.class, t.companyId());
        admins.put(admin.token(), "/api/admin/employees/" + user.id(), updateBody("Dos Cambios", "ADMIN", null, version + 1))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where company_id = ?", Integer.class, t.companyId()))
                .isEqualTo(rows);
        String details = jdbc.queryForObject("select details from audit_log where action = 'EMPLOYEE_UPDATE' and target_id = ?",
                String.class, user.id());
        assertThat(details).doesNotContain("Dos Cambios");
    }

    @Test
    void selfDemotionAndSelfDeactivationAreRefusedButSelfNameAndBranchChangesWork() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        admins.admin(t); // a second admin, so only the self guard can answer
        int version = version(admin.id());
        admins.put(admin.token(), "/api/admin/employees/" + admin.id(), updateBody("Admin", "EMPLOYEE", t.branchId(), version))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_CHANGE_NOT_ALLOWED"));
        admins.post(admin.token(), "/api/admin/employees/" + admin.id() + "/deactivate", Map.of("expectedVersion", version))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_CHANGE_NOT_ALLOWED"));
        admins.put(admin.token(), "/api/admin/employees/" + admin.id(), updateBody("Nombre Propio", "ADMIN", t.branchId(), version))
                .andExpect(status().isOk());
    }

    @Test
    void deactivateEndsTokensAndLoginAndReactivatingDoesNotReviveTheOldToken() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        TestData.TestUser user = data.user(t, "EMPLOYEE");
        String oldToken = data.login(t, user);
        int version = version(user.id());
        admins.post(admin.token(), "/api/admin/employees/" + user.id() + "/deactivate", Map.of("expectedVersion", version))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        admins.get(oldToken, "/api/me").andExpect(status().isUnauthorized());
        assertThat(loginStatus(t, user.code(), user.pin())).isEqualTo(401);
        admins.post(admin.token(), "/api/admin/employees/" + user.id() + "/activate", Map.of("expectedVersion", version + 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        assertThat(loginStatus(t, user.code(), user.pin())).isEqualTo(200);
        admins.get(oldToken, "/api/me").andExpect(status().isUnauthorized());
    }

    @Test
    void activatingOnAnInactiveBranchIsRefusedButEditingTheNameOfThatEmployeeWorks() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long branch = data.branch(t.companyId());
        long employee = data.employee(t.companyId(), branch, "EMPLOYEE");
        jdbc.update("update employee set active = false where id = ?", employee);
        jdbc.update("update branch set active = false where id = ?", branch);
        admins.post(admin.token(), "/api/admin/employees/" + employee + "/activate", Map.of("expectedVersion", 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BRANCH_INACTIVE"));
        admins.put(admin.token(), "/api/admin/employees/" + employee, updateBody("Nombre Nuevo", "EMPLOYEE", branch, 0))
                .andExpect(status().isOk()).andExpect(jsonPath("$.branchActive").value(false));
    }

    @Test
    void editingAnEmployeeOntoADifferentInactiveBranchIsRefusedAndNothingChanges() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long inactive = data.branch(t.companyId());
        jdbc.update("update branch set active = false where id = ?", inactive);
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        admins.put(admin.token(), "/api/admin/employees/" + employee, updateBody("Nombre Nuevo", "EMPLOYEE", inactive, 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BRANCH_INACTIVE"));
        assertThat(jdbc.queryForObject("select branch_id from employee where id = ?", Long.class, employee)).isEqualTo(t.branchId());
        assertThat(version(employee)).isZero();
    }

    @Test
    void pinResetWorksAndEndsSessionsAndAdminsCanResetTheirOwnPin() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        TestData.TestUser user = data.user(t, "EMPLOYEE");
        String oldToken = data.login(t, user);
        jdbc.update("update employee set failed_attempts = 3 where id = ?", user.id());
        var result = admins.postEmpty(admin.token(), "/api/admin/employees/" + user.id() + "/pin-reset")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        JsonNode body = admins.json(result);
        String pin = body.get("pin").asText();
        assertThat(pin.matches("\\d{8}")).as("PIN format").isTrue();
        assertThat(loginStatus(t, user.code(), user.pin())).isEqualTo(401);
        assertThat(loginStatus(t, user.code(), pin)).isEqualTo(200);
        admins.get(oldToken, "/api/me").andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class, user.id())).isZero();
        assertThat(body.get("employee").get("lastPinReset").isNull()).isFalse();

        // Own account: allowed, and the caller's token stops working.
        admins.postEmpty(admin.token(), "/api/admin/employees/" + admin.id() + "/pin-reset").andExpect(status().isOk());
        admins.get(admin.token(), "/api/admin/employees").andExpect(status().isUnauthorized());
    }

    @Test
    void aPinResetClearsTheLockSoTheNewPinLogsInAtOnce() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        TestData.TestUser user = data.user(t, "EMPLOYEE");
        jdbc.update("update employee set locked_until = now() + interval '10 minutes', failed_attempts = 5 where id = ?", user.id());
        assertThat(loginStatus(t, user.code(), user.pin())).isEqualTo(401);
        JsonNode body = admins.json(admins.postEmpty(admin.token(), "/api/admin/employees/" + user.id() + "/pin-reset").andExpect(status().isOk()));
        String pin = body.get("pin").asText();
        assertThat(jdbc.queryForObject("select locked_until is null from employee where id = ?", Boolean.class, user.id()))
                .as("locked_until after the reset").isTrue();
        assertThat(jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class, user.id())).isZero();
        assertThat(loginStatus(t, user.code(), pin)).isEqualTo(200);
    }

    @Test
    void theOptionalResetReasonIsStoredAsGivenAndInvalidOnesAreRefusedWithoutResetting() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        TestData.TestUser user = data.user(t, "EMPLOYEE");
        String path = "/api/admin/employees/" + user.id() + "/pin-reset";

        admins.postEmpty(admin.token(), path).andExpect(status().isOk());
        admins.postRaw(admin.token(), path, "{}").andExpect(status().isOk());
        Map<String, Object> row = jdbc.queryForMap("select reason_code, reason_note, details from audit_log where target_id = ? and action = 'PIN_RESET' order by id desc limit 1", user.id());
        assertThat(row.get("reason_code")).isNull();
        assertThat(row.get("reason_note")).isNull();
        assertThat(row.get("details")).isNull();

        admins.post(admin.token(), path, Map.of("reasonCode", "FORGOT_PIN")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select reason_code from audit_log where target_id = ? and action = 'PIN_RESET' order by id desc limit 1", String.class, user.id())).isEqualTo("FORGOT_PIN");

        JsonNode noted = admins.json(admins.post(admin.token(), path, Map.of("reasonCode", "OTHER", "note", "  Solicitado por la gerencia  "))
                .andExpect(status().isOk()));
        String note = jdbc.queryForObject("select reason_note from audit_log where target_id = ? and action = 'PIN_RESET' order by id desc limit 1", String.class, user.id());
        assertThat(note).isEqualTo("Solicitado por la gerencia");
        assertThat(note.contains(noted.get("pin").asText())).as("the audit note carries the PIN").isFalse();
        JsonNode detail = admins.json(admins.get(admin.token(), "/api/admin/employees/" + user.id()).andExpect(status().isOk()));
        assertThat(detail.get("lastPinReset").get("reasonCode").asText()).isEqualTo("OTHER");
        assertThat(detail.get("lastPinReset").get("note").asText()).isEqualTo("Solicitado por la gerencia");
        assertThat(detail.get("lastPinReset").get("byName").asText()).isNotBlank();
        JsonNode list = admins.json(admins.get(admin.token(), "/api/admin/employees").andExpect(status().isOk()));
        assertThat(list.toString()).doesNotContain("lastPinReset");

        // Refused: nothing resets, the current PIN still works.
        String[] bad = {
            "{\"reasonCode\":\"OTHER\",\"note\":\"a\\u202Eb\"}",
            "{\"reasonCode\":\"OTHER\",\"note\":\"a\\u200Bb\"}",
            "{\"reasonCode\":\"OTHER\",\"note\":\"PIN 1234 5678\"}",
            "{\"reasonCode\":\"OTHER\",\"note\":\"tel 8888-8888\"}",
            "{\"reasonCode\":\"OTHER\",\"note\":\"1234\"}",
            "{\"note\":\"sin motivo\"}",
            "{\"reasonCode\":\"INVENTED\"}",
            "{\"reasonCode\":\"OTHER\",\"note\":\"" + "x".repeat(201) + "\"}"};
        int resets = audits(t.companyId(), "PIN_RESET");
        for (String json : bad) {
            String response = admins.postRaw(admin.token(), path, json).andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("8888").doesNotContain("sin motivo");
        }
        assertThat(audits(t.companyId(), "PIN_RESET")).isEqualTo(resets);
        admins.postRaw(admin.token(), path, "{\"reasonCode\":\"OTHER\",\"note\":\"123\"}").andExpect(status().isOk());
    }

    @Test
    void anotherActiveUnlockedAdminsPinCannotBeResetButALockedOrInactiveOneCan() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        AdminClient.Admin other = admins.admin(t);
        String path = "/api/admin/employees/" + other.id() + "/pin-reset";
        String hash = jdbc.queryForObject("select pin_hash from employee where id = ?", String.class, other.id());
        String es = admins.postEmpty(admin.token(), path).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_PIN_RESET_NOT_ALLOWED")).andReturn().getResponse().getContentAsString();
        assertThat(es).contains("administrador");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + admin.token()).header("Accept-Language", "en"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("PIN reset not allowed"));
        assertThat(jdbc.queryForObject("select pin_hash from employee where id = ?", String.class, other.id())).isEqualTo(hash);
        assertThat(audits(t.companyId(), "PIN_RESET")).isZero();

        jdbc.update("update employee set locked_until = now() + interval '10 minutes' where id = ?", other.id());
        admins.postEmpty(admin.token(), path).andExpect(status().isOk());
        jdbc.update("update employee set locked_until = null, active = false where id = ?", other.id());
        admins.postEmpty(admin.token(), path).andExpect(status().isOk());
        admins.postEmpty(admin.token(), "/api/admin/employees/" + data.user(t, "EMPLOYEE").id() + "/pin-reset").andExpect(status().isOk());
    }

    @Test
    void unlockClearsTheLockAndAnUnlockedAccountIsANoOpWithoutAudit() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        TestData.TestUser user = data.user(t, "EMPLOYEE");
        jdbc.update("update employee set locked_until = now() + interval '10 minutes', failed_attempts = 4 where id = ?", user.id());
        assertThat(loginStatus(t, user.code(), user.pin())).isEqualTo(401);
        admins.postEmpty(admin.token(), "/api/admin/employees/" + user.id() + "/unlock").andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
        assertThat(loginStatus(t, user.code(), user.pin())).isEqualTo(200);
        admins.postEmpty(admin.token(), "/api/admin/employees/" + user.id() + "/unlock").andExpect(status().isOk());
        assertThat(audits(t.companyId(), "EMPLOYEE_UNLOCK")).isEqualTo(1);
    }

    @Test
    void theListShowsStateWithoutCredentialsAndAnExpiredLockShowsUnlocked() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        TestData.TestUser locked = data.user(t, "EMPLOYEE");
        TestData.TestUser expired = data.user(t, "EMPLOYEE");
        jdbc.update("update employee set locked_until = now() + interval '10 minutes' where id = ?", locked.id());
        jdbc.update("update employee set locked_until = now() - interval '10 minutes' where id = ?", expired.id());
        data.schedule(t.companyId(), locked.id(), 1, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(17, 0), 5);
        data.consent(locked.id());
        String json = admins.get(admin.token(), "/api/admin/employees").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode list = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        JsonNode lockedRow = null;
        JsonNode expiredRow = null;
        for (JsonNode row : list) {
            if (row.get("id").asLong() == locked.id()) {
                lockedRow = row;
            }
            if (row.get("id").asLong() == expired.id()) {
                expiredRow = row;
            }
        }
        assertThat(lockedRow.get("locked").asBoolean()).isTrue();
        assertThat(lockedRow.get("lockedUntil").isNull()).isFalse();
        assertThat(lockedRow.get("hasSchedule").asBoolean()).isTrue();
        assertThat(lockedRow.get("consentGiven").asBoolean()).isTrue();
        assertThat(lockedRow.get("branchName").asText()).isNotBlank();
        assertThat(expiredRow.get("locked").asBoolean()).isFalse();
        assertThat(expiredRow.get("lockedUntil").isNull()).isTrue();
        assertThat(json).doesNotContain("pinHash").doesNotContain("failedAttempts").doesNotContain("tokenVersion")
                .doesNotContain("$2");
    }

    @Test
    void errorBodiesNeverEchoTheSubmittedValuesAndOnlyAdminsReadEmployees(CapturedOutput output) throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        String code = data.randomCode("Q");
        String name = "Nombre Muy Reconocible";
        JsonNode created = createEmployee(admin, code, name, t.branchId()).get("employee");
        long id = created.get("id").asLong();
        String[] responses = {
            admins.post(admin.token(), "/api/admin/employees", createBody(code, name, "EMPLOYEE", t.branchId())).andReturn().getResponse().getContentAsString(),
            admins.post(admin.token(), "/api/admin/employees", createBody("bad code!", name, "EMPLOYEE", t.branchId())).andReturn().getResponse().getContentAsString(),
            admins.post(admin.token(), "/api/admin/employees", createBody(data.randomCode("Q"), name, "EMPLOYEE", null)).andReturn().getResponse().getContentAsString(),
            admins.put(admin.token(), "/api/admin/employees/" + id, updateBody(name, "EMPLOYEE", t.branchId(), 99)).andReturn().getResponse().getContentAsString(),
        };
        for (String response : responses) {
            assertThat(response).doesNotContain(name).doesNotContainIgnoringCase(code).doesNotContain("bad code");
        }
        // A failed login names ids in the log, never the code.
        assertThat(loginStatus(t, code, "13572468")).isEqualTo(401);
        assertThat(output.getAll()).doesNotContainIgnoringCase(code).doesNotContain(name);
        assertThat(output.getAll()).contains("employee=" + id);

        TestData.TestUser plain = data.user(t, "EMPLOYEE");
        String token = data.login(t, plain);
        admins.get(token, "/api/admin/employees").andExpect(status().isForbidden());
        admins.get(token, "/api/admin/employees/" + id).andExpect(status().isForbidden());
    }

    @Test
    void extraJsonPropertiesHaveNoEffectOnCreateAndUpdate() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long other = data.company();
        String code = data.randomCode("X");
        admins.postRaw(admin.token(), "/api/admin/employees",
                "{\"code\":\"" + code + "\",\"name\":\"Extra Props\",\"role\":\"EMPLOYEE\",\"branchId\":" + t.branchId()
                        + ",\"companyId\":" + other + ",\"pinHash\":\"x\",\"tokenVersion\":9,\"active\":false,\"failedAttempts\":4}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.employee.active").value(true));
        Map<String, Object> row = jdbc.queryForMap("select company_id, token_version, failed_attempts, pin_hash from employee where code = ? and company_id = ?", code, t.companyId());
        assertThat(row.get("token_version")).isEqualTo(0);
        assertThat(row.get("failed_attempts")).isEqualTo(0);
        assertThat((String) row.get("pin_hash")).startsWith("$2");
    }

    @Test
    void otherTenantsEmployeesAreNotFoundOnEveryManagementRoute() throws Exception {
        TestData.Tenant mine = data.tenant();
        TestData.Tenant other = data.tenant();
        AdminClient.Admin admin = admins.admin(mine);
        long foreign = data.user(other, "EMPLOYEE").id();
        String base = "/api/admin/employees/" + foreign;
        admins.put(admin.token(), base, updateBody("X", "EMPLOYEE", mine.branchId(), 0)).andExpect(status().isNotFound());
        admins.post(admin.token(), base + "/activate", Map.of("expectedVersion", 0)).andExpect(status().isNotFound());
        admins.post(admin.token(), base + "/deactivate", Map.of("expectedVersion", 0)).andExpect(status().isNotFound());
        admins.postEmpty(admin.token(), base + "/pin-reset").andExpect(status().isNotFound());
        admins.postEmpty(admin.token(), base + "/unlock").andExpect(status().isNotFound());
        admins.get(admin.token(), base + "/schedule").andExpect(status().isNotFound());
        assertThat(audits(mine.companyId(), "PIN_RESET")).isZero();
    }

    @Test
    void deactivateAndActivateCheckAndBumpTheVersion() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        JsonNode created = createEmployee(admin, data.randomCode("W"), "Nombre Estado", t.branchId()).get("employee");
        long id = created.get("id").asLong();
        int v0 = created.get("version").asInt();
        String base = "/api/admin/employees/" + id;

        admins.post(admin.token(), base + "/deactivate", Map.of("expectedVersion", v0 + 5))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_CHANGED"));
        assertThat(jdbc.queryForObject("select active from employee where id = ?", Boolean.class, id)).isTrue();
        assertThat(version(id)).isEqualTo(v0);

        JsonNode off = admins.json(admins.post(admin.token(), base + "/deactivate", Map.of("expectedVersion", v0))
                .andExpect(status().isOk()));
        assertThat(off.get("version").asInt()).isGreaterThan(v0);

        // The same stale form cannot act again, in either direction.
        admins.post(admin.token(), base + "/activate", Map.of("expectedVersion", v0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_CHANGED"));
        assertThat(jdbc.queryForObject("select active from employee where id = ?", Boolean.class, id)).isFalse();
        JsonNode on = admins.json(admins.post(admin.token(), base + "/activate", Map.of("expectedVersion", off.get("version").asInt()))
                .andExpect(status().isOk()));
        assertThat(on.get("version").asInt()).isGreaterThan(off.get("version").asInt());
        admins.post(admin.token(), base + "/deactivate", Map.of("expectedVersion", off.get("version").asInt()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_CHANGED"));
    }
}
