package cr.attendance.db;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V4 adds time_entry.effective_time NOT NULL: rows that already exist at V3 must come out with
 * effective_time equal to their server_time. Runs on its own throwaway database, migrated in two steps.
 */
class V4BackfillMigrationTest {

    private static Flyway flyway(DataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load();
    }

    @Test
    void existingEntriesGetEffectiveTimeEqualToTheirServerTime() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            DataSource ds = pg.getPostgresDatabase();
            flyway(ds, "3").migrate();

            Instant first = Instant.parse("2026-03-04T14:15:16.123456Z");
            Instant second = Instant.parse("2025-12-31T23:59:59Z");
            long companyId;
            long employeeId;
            long branchId;
            long entryOne;
            long entryTwo;
            try (Connection c = ds.getConnection()) {
                companyId = scalar(c, "insert into company (code, name) values ('BACKFILL1', 'Empresa') returning id");
                branchId = scalar(c, "insert into branch (company_id, name, latitude, longitude) values ("
                        + companyId + ", 'Sucursal', 9.9325, -84.0796) returning id");
                employeeId = scalar(c, "insert into employee (company_id, branch_id, code, name, job_title, pin_hash, role)"
                        + " values (" + companyId + ", " + branchId + ", 'EBACKFIL1', 'Persona', 'Puesto', 'h', 'EMPLOYEE')"
                        + " returning id");
                entryOne = insertEntry(c, companyId, employeeId, branchId, "CLOCK_IN", first, "2026-03-04");
                entryTwo = insertEntry(c, companyId, employeeId, branchId, "CLOCK_OUT", second, "2025-12-31");
            }

            flyway(ds, "4").migrate();

            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                assertEffective(st, entryOne, first);
                assertEffective(st, entryTwo, second);
                ResultSet nullable = st.executeQuery("select is_nullable from information_schema.columns"
                        + " where table_name = 'time_entry' and column_name = 'effective_time'");
                assertThat(nullable.next()).isTrue();
                assertThat(nullable.getString(1)).isEqualTo("NO");
            }
        }
    }

    private static long scalar(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long insertEntry(Connection c, long company, long employee, long branch, String type, Instant serverTime,
            String workDate) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                insert into time_entry (company_id, employee_id, branch_id, type, source, client_uuid, server_time, work_date)
                values (?, ?, ?, ?, 'MOBILE', ?, ?, ?::date) returning id
                """)) {
            ps.setLong(1, company);
            ps.setLong(2, employee);
            ps.setLong(3, branch);
            ps.setString(4, type);
            ps.setObject(5, UUID.randomUUID());
            ps.setTimestamp(6, Timestamp.from(serverTime));
            ps.setString(7, workDate);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void assertEffective(Statement st, long id, Instant expectedServerTime) throws Exception {
        try (ResultSet rs = st.executeQuery("select server_time, effective_time from time_entry where id = " + id)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp(1).toInstant()).isEqualTo(expectedServerTime);
            assertThat(rs.getTimestamp(2).toInstant()).isEqualTo(expectedServerTime);
        }
    }
}
