package cr.asistencia.empleado;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EmpleadoRepository extends Repository<Empleado, Long> {

    Empleado save(Empleado empleado);

    Optional<Empleado> findByIdAndEmpresaId(Long id, Long empresaId);

    List<Empleado> findAllByEmpresaIdOrderByNombre(Long empresaId);

    // Login lookup: empresaId comes from the already-resolved empresa, not from the request.
    Optional<Empleado> findByEmpresaIdAndCodigo(Long empresaId, String codigo);

    // One atomic UPDATE, in its own transaction so it commits even though the request ends in 401.
    // An already-locked account is left untouched (no increment, no extension).
    @Transactional
    @Modifying
    @Query("""
            update Empleado e
               set e.intentosFallidos = case when e.intentosFallidos + 1 >= :maxIntentos
                                             then 0 else e.intentosFallidos + 1 end,
                   e.bloqueadoHasta = case when e.intentosFallidos + 1 >= :maxIntentos
                                           then :bloqueoHasta else e.bloqueadoHasta end
             where e.id = :id and e.empresaId = :empresaId
               and (e.bloqueadoHasta is null or e.bloqueadoHasta <= :ahora)
            """)
    int registrarFallo(@Param("id") Long id, @Param("empresaId") Long empresaId,
            @Param("maxIntentos") int maxIntentos, @Param("bloqueoHasta") Instant bloqueoHasta,
            @Param("ahora") Instant ahora);

    // Never clears an active lock: if the account got locked while this attempt was comparing the PIN,
    // 0 rows are updated and the caller must reject the login. An expired lock is cleared.
    @Transactional
    @Modifying
    @Query("""
            update Empleado e set e.intentosFallidos = 0, e.bloqueadoHasta = null
             where e.id = :id and e.empresaId = :empresaId
               and (e.bloqueadoHasta is null or e.bloqueadoHasta <= :ahora)
            """)
    int registrarExito(@Param("id") Long id, @Param("empresaId") Long empresaId,
            @Param("ahora") Instant ahora);

    @Transactional
    @Modifying
    @Query("""
            update Empleado e set e.consentimientoFecha = :fecha
             where e.id = :id and e.empresaId = :empresaId and e.consentimientoFecha is null
            """)
    int registrarConsentimiento(@Param("id") Long id, @Param("empresaId") Long empresaId,
            @Param("fecha") Instant fecha);
}
