package cr.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

/** The constraint name comes from the driver's structured field first; the message regex is the fallback. */
class ConstraintsTest {

    @Test
    void readsTheStructuredConstraintField() {
        // Wire format of an ErrorResponse: field code + value, NUL separated. 'n' is the constraint name.
        ServerErrorMessage server = new ServerErrorMessage(
                "SERROR\0VERROR\0C23505\0Mduplicate key value violates unique constraint\0nuq_employee_company_code\0");
        PSQLException cause = new PSQLException(server);

        assertThat(Constraints.nameOf(new DataIntegrityViolationException("x", cause)))
                .isEqualTo("uq_employee_company_code");
    }

    @Test
    void fallsBackToTheMessageWhenThereIsNoStructuredField() {
        PSQLException cause = new PSQLException(
                "ERROR: new row for relation \"employee\" violates check constraint \"ck_employee_name\"",
                PSQLState.CHECK_VIOLATION);

        assertThat(Constraints.nameOf(new DataIntegrityViolationException("x", cause))).isEqualTo("ck_employee_name");
    }

    @Test
    void returnsEmptyWhenNothingNamesAConstraint() {
        assertThat(Constraints.nameOf(new DataIntegrityViolationException("plain", new RuntimeException("boom"))))
                .isEmpty();
    }
}
