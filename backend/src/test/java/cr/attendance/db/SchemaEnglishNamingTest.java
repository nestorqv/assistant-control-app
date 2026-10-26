package cr.attendance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.attendance.common.Role;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import cr.attendance.timeentry.EvidenceRepository;
import cr.attendance.timeentry.EvidenceType;
import cr.attendance.timeentry.TimeEntryRepository;
import cr.attendance.timeentry.TimeEntrySource;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stage 1b: English schema names, exact CHECK values, column widths and entity/enum mapping. */
@IntegrationTest
class SchemaEnglishNamingTest {

    private static final List<String> EXPECTED_CONSTRAINTS = List.of(
            "uq_company_code", "ck_company_code", "ck_company_name",
            "fk_branch_company", "uq_branch_id_company", "uq_branch_company_name",
            "ck_branch_latitude", "ck_branch_longitude", "ck_branch_radius",
            "fk_employee_company", "fk_employee_branch_company", "uq_employee_company_code",
            "uq_employee_id_company", "ck_employee_code", "ck_employee_role", "ck_employee_failed_attempts",
            "ck_employee_branch_role",
            "fk_work_schedule_employee_company", "uq_work_schedule_employee_day", "ck_work_schedule_day",
            "ck_work_schedule_times", "ck_work_schedule_grace",
            "fk_time_entry_employee_company", "fk_time_entry_branch_company",
            "uq_time_entry_company_client_uuid", "uq_time_entry_id_company", "ck_time_entry_type",
            "ck_time_entry_source", "ck_time_entry_status", "ck_time_entry_latitude", "ck_time_entry_longitude",
            "ck_time_entry_distance",
            "ck_time_entry_location_pair", "ck_time_entry_late_minutes", "ck_time_entry_gps_accuracy",
            "ck_time_entry_review_reasons",
            "fk_evidence_time_entry_company", "ck_evidence_type", "uq_evidence_time_entry_type",
            "ck_evidence_storage_key_type", "ck_evidence_storage_key_format", "ck_evidence_storage_key_company",
            "fk_adjustment_time_entry_company", "fk_adjustment_admin_company", "ck_adjustment_reason");

    private static final List<String> EXPECTED_INDEXES = List.of(
            "ix_employee_company_active", "ix_time_entry_company_server_time",
            "ix_time_entry_employee_server_time", "ix_time_entry_pending_review", "ix_evidence_time_entry",
            "ix_adjustment_time_entry", "ix_time_entry_company_work_date");

    private final AtomicInteger dayCounter = new AtomicInteger();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private TimeEntryRepository timeEntries;

    @Autowired
    private EvidenceRepository evidences;

    @Test
    void constraintNamesAreExactlyTheFortyFiveEnglishOnes() {
        List<String> names = jdbc.queryForList("""
                select c.conname from pg_constraint c
                  join pg_class t on t.oid = c.conrelid
                  join pg_namespace n on n.oid = t.relnamespace
                 where n.nspname = 'public' and c.contype in ('u', 'c', 'f')
                   and t.relname <> 'flyway_schema_history'
                """, String.class);
        assertThat(names).hasSize(45).containsExactlyInAnyOrderElementsOf(EXPECTED_CONSTRAINTS);
    }

    @Test
    void indexNamesAreTheSevenEnglishOnesAndThePartialIndexTargetsPendingReview() {
        List<String> names = jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = 'public' and indexname like 'ix\\_%'",
                String.class);
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_INDEXES);
        String definition = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'ix_time_entry_pending_review'", String.class);
        assertThat(definition).contains("PENDING_REVIEW");
    }

    @Test
    void onlyTheSevenEnglishTablesExistAndNoSpanishNamesRemain() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).doesNotContain("empresa", "sucursal", "empleado", "horario", "marca", "evidencia",
                "ajuste");
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public'", String.class);
        assertThat(columns).doesNotContain("empresa_id", "marca_id", "estado", "tipo", "origen", "rol", "nombre",
                "codigo", "distancia_m", "intentos_fallidos", "bloqueado_hasta", "hora_servidor", "puesto");
        assertThat(columns).contains("company_id", "time_entry_id", "status", "type", "source", "role", "name",
                "code", "distance_meters", "failed_attempts", "locked_until", "server_time", "job_title",
                "token_version", "radius_meters", "grace_minutes");
    }

    @Test
    void enumColumnsAreWideEnoughForTheirLongestJavaValue() {
        assertThat(width("employee", "role")).isGreaterThanOrEqualTo(longest(Role.values()));
        assertThat(width("time_entry", "type")).isGreaterThanOrEqualTo(longest(TimeEntryType.values()));
        assertThat(width("time_entry", "source")).isGreaterThanOrEqualTo(longest(TimeEntrySource.values()));
        assertThat(width("time_entry", "status")).isGreaterThanOrEqualTo(longest(TimeEntryStatus.values()));
        assertThat(width("evidence", "type")).isGreaterThanOrEqualTo(longest(EvidenceType.values()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPLEADO", "ADMINISTRADOR", "employee", "Admin", "ADMIN "})
    void legacyOrMisspelledRolesAreRejected(String role) {
        TestData.Tenant t = data.tenant();
        assertThatThrownBy(() -> data.employee(t.companyId(), t.branchId(), role))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENTRADA", "SALIDA", "clock_in", "IN"})
    void legacyOrUnknownTimeEntryTypesAreRejected(String type) {
        assertThatThrownBy(() -> insertTimeEntry(type, "MOBILE", "VALID")).isInstanceOf(
                DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOVIL", "TABLETA", "mobile"})
    void legacyOrUnknownSourcesAreRejected(String source) {
        assertThatThrownBy(() -> insertTimeEntry("CLOCK_IN", source, "VALID")).isInstanceOf(
                DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VALIDA", "POR_REVISAR", "RECHAZADA", "valid", "PENDING"})
    void legacyOrUnknownStatusesAreRejected(String status) {
        assertThatThrownBy(() -> insertTimeEntry("CLOCK_IN", "MOBILE", status)).isInstanceOf(
                DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOTO", "photo", "VIDEO"})
    void legacyOrUnknownEvidenceTypesAreRejected(String type) {
        long timeEntryId = insertTimeEntry("CLOCK_IN", "MOBILE", "VALID");
        assertThatThrownBy(() -> insertEvidence(timeEntryId, type)).isInstanceOf(
                DataIntegrityViolationException.class);
    }

    @Test
    void everyJavaEnumValueIsStoredAndReadBackThroughTheEntities() {
        for (Role role : Role.values()) {
            TestData.Tenant t = data.tenant();
            Long branch = role == Role.ADMIN ? null : t.branchId();
            long id = data.employee(t.companyId(), branch, role.name());
            assertThat(employees.findByIdAndCompanyId(id, t.companyId()).orElseThrow().getRole()).isEqualTo(role);
        }
        TestData.Tenant t = data.tenant();
        long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        for (TimeEntryType type : TimeEntryType.values()) {
            for (TimeEntrySource source : TimeEntrySource.values()) {
                for (TimeEntryStatus status : TimeEntryStatus.values()) {
                    long id = insertTimeEntry(t, employeeId, type.name(), source.name(), status.name());
                    var entry = timeEntries.findByIdAndCompanyId(id, t.companyId()).orElseThrow();
                    assertThat(entry.getType()).isEqualTo(type);
                    assertThat(entry.getSource()).isEqualTo(source);
                    assertThat(entry.getStatus()).isEqualTo(status);
                }
            }
        }
        long timeEntryId = insertTimeEntry(t, employeeId, "CLOCK_OUT", "QR", "PENDING_REVIEW");
        for (EvidenceType type : EvidenceType.values()) {
            // Only PHOTO may carry a key, and it must have the {companyId}/{yyyy-MM}/{uuid}.jpg shape.
            String key = type == EvidenceType.PHOTO ? t.companyId() + "/2026-10/" + UUID.randomUUID() + ".jpg" : null;
            long id = jdbc.queryForObject("""
                    insert into evidence (company_id, time_entry_id, type, storage_key) values (?, ?, ?, ?) returning id
                    """, Long.class, t.companyId(), timeEntryId, type.name(), key);
            assertThat(evidences.findByIdAndCompanyId(id, t.companyId()).orElseThrow().getType()).isEqualTo(type);
        }
    }

    @Test
    void javaEnumsMatchTheDocumentedEnglishValues() {
        assertThat(Role.values()).extracting(Enum::name).containsExactly("EMPLOYEE", "ADMIN");
        assertThat(TimeEntryType.values()).extracting(Enum::name).containsExactly("CLOCK_IN", "CLOCK_OUT");
        assertThat(TimeEntrySource.values()).extracting(Enum::name).containsExactly("MOBILE", "TABLET", "QR");
        assertThat(TimeEntryStatus.values()).extracting(Enum::name)
                .containsExactly("VALID", "PENDING_REVIEW", "REJECTED");
        assertThat(EvidenceType.values()).extracting(Enum::name).containsExactly("PHOTO", "GPS", "QR");
    }

    @Test
    void timeEntryStatusDefaultsToValidAndNegativeDistanceIsRejected() {
        TestData.Tenant t = data.tenant();
        long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long id = data.timeEntry(t.companyId(), employeeId, t.branchId());
        assertThat(jdbc.queryForObject("select status from time_entry where id = ?", String.class, id))
                .isEqualTo("VALID");
        assertThatThrownBy(() -> jdbc.update("update time_entry set distance_meters = -1 where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update time_entry set distance_meters = 0 where id = ?", id);
    }

    private int width(String table, String column) {
        return jdbc.queryForObject("""
                select character_maximum_length from information_schema.columns
                 where table_schema = 'public' and table_name = ? and column_name = ?
                """, Integer.class, table, column);
    }

    private static int longest(Enum<?>[] values) {
        int max = 0;
        for (Enum<?> value : values) {
            max = Math.max(max, value.name().length());
        }
        return max;
    }

    private long insertTimeEntry(String type, String source, String status) {
        TestData.Tenant t = data.tenant();
        long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        return insertTimeEntry(t, employeeId, type, source, status);
    }

    private long insertTimeEntry(TestData.Tenant t, long employeeId, String type, String source, String status) {
        return jdbc.queryForObject("""
                insert into time_entry (company_id, employee_id, branch_id, type, source, status, client_uuid, work_date)
                values (?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, t.companyId(), employeeId, t.branchId(), type, source, status, UUID.randomUUID(),
                // A distinct day per row: one CLOCK_IN/CLOCK_OUT per employee per work_date is unique.
                LocalDate.now().minusDays(dayCounter.incrementAndGet()));
    }

    private void insertEvidence(long timeEntryId, String type) {
        long companyId = jdbc.queryForObject("select company_id from time_entry where id = ?", Long.class,
                timeEntryId);
        jdbc.update("insert into evidence (company_id, time_entry_id, type) values (?, ?, ?)", companyId,
                timeEntryId, type);
    }
}
