package cr.attendance.auth;

import cr.attendance.common.InvalidCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cr.attendance.common.Role;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.company.Company;
import cr.attendance.company.CompanyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class LoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private CompanyRepository companies;
    private EmployeeRepository employees;
    private PasswordEncoder encoder;
    private TokenService tokens;
    private LoginService service;

    @BeforeEach
    void setUp() {
        companies = mock(CompanyRepository.class);
        employees = mock(EmployeeRepository.class);
        encoder = mock(PasswordEncoder.class);
        tokens = mock(TokenService.class);
        when(encoder.encode(any())).thenReturn("dummy-hash");
        SecurityProperties props = new SecurityProperties(4,
                new SecurityProperties.Lockout(5, Duration.ofMinutes(15)),
                new SecurityProperties.LoginRateLimit(20, Duration.ofMinutes(5)));
        service = new LoginService(companies, employees, encoder, tokens, props,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Company company(boolean activa) {
        Company e = new Company("DEMO", "Empresa Demo");
        ReflectionTestUtils.setField(e, "id", 1L);
        ReflectionTestUtils.setField(e, "active", activa);
        return e;
    }

    private Employee employee(boolean active, Instant lockedUntil) {
        Employee e = new Employee(1L, 10L, "1001", "María Rojas", "Cajera", "real-hash", Role.EMPLOYEE);
        ReflectionTestUtils.setField(e, "id", 5L);
        ReflectionTestUtils.setField(e, "active", active);
        ReflectionTestUtils.setField(e, "lockedUntil", lockedUntil);
        return e;
    }

    private void given(Company company, Employee employee) {
        when(companies.findByCode("DEMO")).thenReturn(Optional.ofNullable(company));
        when(employees.findByCompanyIdAndCode(1L, "1001")).thenReturn(Optional.ofNullable(employee));
    }

    private void assertFailure() {
        assertThatThrownBy(() -> service.login("DEMO", "1001", "48207315", "1.2.3.4"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void unknownCompanyComparesOnceAgainstDummyHash() {
        given(null, null);
        assertFailure();
        verify(encoder, times(1)).matches("48207315", "dummy-hash");
        verify(employees, never()).recordFailedLogin(anyLong(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void unknownEmployeeComparesOnceAgainstDummyHash() {
        given(company(true), null);
        assertFailure();
        verify(encoder, times(1)).matches("48207315", "dummy-hash");
        verify(employees, never()).recordFailedLogin(anyLong(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void inactiveEmployeeComparesOnceAgainstRealHashAndDoesNotCount() {
        given(company(true), employee(false, null));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        assertFailure();
        verify(encoder, times(1)).matches(any(), any());
        verify(encoder).matches("48207315", "real-hash");
        verify(employees, never()).recordFailedLogin(anyLong(), anyLong(), anyInt(), any(), any());
        verify(tokens, never()).issue(any());
    }

    @Test
    void inactiveCompanyComparesOnceAndDoesNotCount() {
        given(company(false), employee(true, null));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        assertFailure();
        verify(encoder, times(1)).matches(any(), any());
        verify(employees, never()).recordFailedLogin(anyLong(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void lockedEmployeeComparesOnceAgainstDummyEvenWithCorrectPinAndDoesNotCount() {
        given(company(true), employee(true, NOW.plusSeconds(60)));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        assertFailure();
        verify(encoder, times(1)).matches(any(), any());
        verify(encoder).matches("48207315", "dummy-hash");
        verify(employees, never()).recordFailedLogin(anyLong(), anyLong(), anyInt(), any(), any());
        verify(tokens, never()).issue(any());
    }

    @Test
    void expiredLockDoesNotBlock() {
        given(company(true), employee(true, NOW.minusSeconds(1)));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        when(employees.recordSuccessfulLogin(anyLong(), anyLong(), any())).thenReturn(1);
        when(tokens.issue(any())).thenReturn(new TokenService.IssuedToken("t", NOW.plusSeconds(10)));
        assertThat(service.login("DEMO", "1001", "48207315", "1.2.3.4").token()).isEqualTo("t");
    }

    @Test
    void wrongPinComparesOnceAndRegistersTheFailure() {
        given(company(true), employee(true, null));
        when(encoder.matches("48207315", "real-hash")).thenReturn(false);
        assertFailure();
        verify(encoder, times(1)).matches(any(), any());
        verify(employees).recordFailedLogin(eq(5L), eq(1L), eq(5), eq(NOW.plus(Duration.ofMinutes(15))), eq(NOW));
    }

    @Test
    void correctPinComparesOnceResetsCounterAndIssuesToken() {
        Employee e = employee(true, null);
        given(company(true), e);
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        when(employees.recordSuccessfulLogin(5L, 1L, NOW)).thenReturn(1);
        when(tokens.issue(e)).thenReturn(new TokenService.IssuedToken("jwt", NOW.plusSeconds(28800)));

        LoginResponse response = service.login("DEMO", "1001", "48207315", "1.2.3.4");

        verify(encoder, times(1)).matches(any(), any());
        verify(employees).recordSuccessfulLogin(5L, 1L, NOW);
        assertThat(response.token()).isEqualTo("jwt");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.employee().consentPending()).isTrue();
        assertThat(response.company().timeZone()).isEqualTo("America/Costa_Rica");
    }

    @Test
    void correctPinOnAnAccountLockedMeanwhileIsRejectedWithoutTokenAndStillComparesOnce() {
        given(company(true), employee(true, null));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        // 0 rows: the conditional UPDATE found an active lock committed during the BCrypt compare.
        when(employees.recordSuccessfulLogin(5L, 1L, NOW)).thenReturn(0);
        assertFailure();
        verify(encoder, times(1)).matches(any(), any());
        verify(tokens, never()).issue(any());
        verify(employees, never()).recordFailedLogin(anyLong(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void codesAreTrimmedAndUpperCased() {
        given(company(true), employee(true, null));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        when(employees.recordSuccessfulLogin(anyLong(), anyLong(), any())).thenReturn(1);
        when(tokens.issue(any())).thenReturn(new TokenService.IssuedToken("t", NOW.plusSeconds(10)));
        assertThat(service.login(" demo ", " 1001 ", "48207315", "1.2.3.4").token()).isEqualTo("t");
        verify(companies).findByCode("DEMO");
    }
}
