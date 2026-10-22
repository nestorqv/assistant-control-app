package cr.asistencia.empleado;

import cr.asistencia.common.Rol;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "empleado")
public class Empleado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "empresa_id", nullable = false)
    private Long empresaId;

    // Null only for ADMIN (database CHECK).
    @Column(name = "sucursal_id")
    private Long sucursalId;

    @Column(name = "codigo", nullable = false, length = 20)
    private String codigo;

    @Column(name = "nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "puesto", length = 80)
    private String puesto;

    @Column(name = "pin_hash", nullable = false, length = 100)
    private String pinHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "rol", nullable = false, length = 10)
    private Rol rol;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "consentimiento_fecha")
    private Instant consentimientoFecha;

    // Lock state is changed only by the atomic queries in EmpleadoRepository.
    @Column(name = "intentos_fallidos", nullable = false)
    private Integer intentosFallidos = 0;

    @Column(name = "bloqueado_hasta")
    private Instant bloqueadoHasta;

    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 0;

    protected Empleado() {
    }

    public Empleado(Long empresaId, Long sucursalId, String codigo, String nombre, String puesto,
            String pinHash, Rol rol) {
        this.empresaId = empresaId;
        this.sucursalId = sucursalId;
        this.codigo = codigo;
        this.nombre = nombre;
        this.puesto = puesto;
        this.pinHash = pinHash;
        this.rol = rol;
    }

    public Long getId() {
        return id;
    }

    public Long getEmpresaId() {
        return empresaId;
    }

    public Long getSucursalId() {
        return sucursalId;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getNombre() {
        return nombre;
    }

    public String getPuesto() {
        return puesto;
    }

    public String getPinHash() {
        return pinHash;
    }

    public Rol getRol() {
        return rol;
    }

    public boolean isActivo() {
        return activo;
    }

    public Instant getConsentimientoFecha() {
        return consentimientoFecha;
    }

    public Integer getIntentosFallidos() {
        return intentosFallidos;
    }

    public Instant getBloqueadoHasta() {
        return bloqueadoHasta;
    }

    public Integer getTokenVersion() {
        return tokenVersion;
    }
}
