package cr.asistencia.auth;

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

import cr.asistencia.common.Rol;
import cr.asistencia.empleado.Empleado;
import cr.asistencia.empleado.EmpleadoRepository;
import cr.asistencia.empresa.Empresa;
import cr.asistencia.empresa.EmpresaRepository;
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

    private static final Instant AHORA = Instant.parse("2026-10-21T12:00:00Z");

    private EmpresaRepository empresas;
    private EmpleadoRepository empleados;
    private PasswordEncoder encoder;
    private TokenService tokens;
    private LoginService service;

    @BeforeEach
    void setUp() {
        empresas = mock(EmpresaRepository.class);
        empleados = mock(EmpleadoRepository.class);
        encoder = mock(PasswordEncoder.class);
        tokens = mock(TokenService.class);
        when(encoder.encode(any())).thenReturn("dummy-hash");
        SecurityProperties props = new SecurityProperties(4,
                new SecurityProperties.Lockout(5, Duration.ofMinutes(15)),
                new SecurityProperties.LoginRateLimit(20, Duration.ofMinutes(5)));
        service = new LoginService(empresas, empleados, encoder, tokens, props,
                Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private Empresa empresa(boolean activa) {
        Empresa e = new Empresa("DEMO", "Empresa Demo");
        ReflectionTestUtils.setField(e, "id", 1L);
        ReflectionTestUtils.setField(e, "activo", activa);
        return e;
    }

    private Empleado empleado(boolean activo, Instant bloqueadoHasta) {
        Empleado e = new Empleado(1L, 10L, "1001", "María Rojas", "Cajera", "real-hash", Rol.EMPLEADO);
        ReflectionTestUtils.setField(e, "id", 5L);
        ReflectionTestUtils.setField(e, "activo", activo);
        ReflectionTestUtils.setField(e, "bloqueadoHasta", bloqueadoHasta);
        return e;
    }

    private void dado(Empresa empresa, Empleado empleado) {
        when(empresas.findByCodigo("DEMO")).thenReturn(Optional.ofNullable(empresa));
        when(empleados.findByEmpresaIdAndCodigo(1L, "1001")).thenReturn(Optional.ofNullable(empleado));
    }

    private void falla() {
        assertThatThrownBy(() -> service.login("DEMO", "1001", "48207315", "1.2.3.4"))
                .isInstanceOf(CredencialesInvalidasException.class);
    }

    @Test
    void unknownEmpresaComparesOnceAgainstDummyHash() {
        dado(null, null);
        falla();
        verify(encoder, times(1)).matches("48207315", "dummy-hash");
        verify(empleados, never()).registrarFallo(anyLong(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void unknownEmpleadoComparesOnceAgainstDummyHash() {
        dado(empresa(true), null);
        falla();
        verify(encoder, times(1)).matches("48207315", "dummy-hash");
        verify(empleados, never()).registrarFallo(anyLong(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void inactiveEmpleadoComparesOnceAgainstRealHashAndDoesNotCount() {
        dado(empresa(true), empleado(false, null));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        falla();
        verify(encoder, times(1)).matches(any(), any());
        verify(encoder).matches("48207315", "real-hash");
        verify(empleados, never()).registrarFallo(anyLong(), anyLong(), anyInt(), any(), any());
        verify(tokens, never()).emitir(any());
    }

    @Test
    void inactiveEmpresaComparesOnceAndDoesNotCount() {
        dado(empresa(false), empleado(true, null));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        falla();
        verify(encoder, times(1)).matches(any(), any());
        verify(empleados, never()).registrarFallo(anyLong(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void lockedEmpleadoComparesOnceAgainstDummyEvenWithCorrectPinAndDoesNotCount() {
        dado(empresa(true), empleado(true, AHORA.plusSeconds(60)));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        falla();
        verify(encoder, times(1)).matches(any(), any());
        verify(encoder).matches("48207315", "dummy-hash");
        verify(empleados, never()).registrarFallo(anyLong(), anyLong(), anyInt(), any(), any());
        verify(tokens, never()).emitir(any());
    }

    @Test
    void expiredLockDoesNotBlock() {
        dado(empresa(true), empleado(true, AHORA.minusSeconds(1)));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        when(empleados.registrarExito(anyLong(), anyLong(), any())).thenReturn(1);
        when(tokens.emitir(any())).thenReturn(new TokenService.EmitidoToken("t", AHORA.plusSeconds(10)));
        assertThat(service.login("DEMO", "1001", "48207315", "1.2.3.4").token()).isEqualTo("t");
    }

    @Test
    void wrongPinComparesOnceAndRegistersTheFailure() {
        dado(empresa(true), empleado(true, null));
        when(encoder.matches("48207315", "real-hash")).thenReturn(false);
        falla();
        verify(encoder, times(1)).matches(any(), any());
        verify(empleados).registrarFallo(eq(5L), eq(1L), eq(5), eq(AHORA.plus(Duration.ofMinutes(15))), eq(AHORA));
    }

    @Test
    void correctPinComparesOnceResetsCounterAndIssuesToken() {
        Empleado e = empleado(true, null);
        dado(empresa(true), e);
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        when(empleados.registrarExito(5L, 1L, AHORA)).thenReturn(1);
        when(tokens.emitir(e)).thenReturn(new TokenService.EmitidoToken("jwt", AHORA.plusSeconds(28800)));

        LoginResponse response = service.login("DEMO", "1001", "48207315", "1.2.3.4");

        verify(encoder, times(1)).matches(any(), any());
        verify(empleados).registrarExito(5L, 1L, AHORA);
        assertThat(response.token()).isEqualTo("jwt");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.empleado().consentimientoPendiente()).isTrue();
        assertThat(response.empresa().zonaHoraria()).isEqualTo("America/Costa_Rica");
    }

    @Test
    void correctPinOnAnAccountLockedMeanwhileIsRejectedWithoutTokenAndStillComparesOnce() {
        dado(empresa(true), empleado(true, null));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        // 0 rows: the conditional UPDATE found an active lock committed during the BCrypt compare.
        when(empleados.registrarExito(5L, 1L, AHORA)).thenReturn(0);
        falla();
        verify(encoder, times(1)).matches(any(), any());
        verify(tokens, never()).emitir(any());
        verify(empleados, never()).registrarFallo(anyLong(), anyLong(), anyInt(), any(), any());
    }

    @Test
    void codesAreTrimmedAndUpperCased() {
        dado(empresa(true), empleado(true, null));
        when(encoder.matches("48207315", "real-hash")).thenReturn(true);
        when(empleados.registrarExito(anyLong(), anyLong(), any())).thenReturn(1);
        when(tokens.emitir(any())).thenReturn(new TokenService.EmitidoToken("t", AHORA.plusSeconds(10)));
        assertThat(service.login(" demo ", " 1001 ", "48207315", "1.2.3.4").token()).isEqualTo("t");
        verify(empresas).findByCodigo("DEMO");
    }
}
