package cr.asistencia.marca;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "marca")
public class Marca {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "empresa_id", nullable = false)
    private Long empresaId;

    @Column(name = "empleado_id", nullable = false)
    private Long empleadoId;

    @Column(name = "sucursal_id", nullable = false)
    private Long sucursalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 10)
    private TipoMarca tipo;

    @Column(name = "hora_servidor", nullable = false)
    private Instant horaServidor;

    @Column(name = "hora_dispositivo")
    private Instant horaDispositivo;

    @Column(name = "latitud")
    private Double latitud;

    @Column(name = "longitud")
    private Double longitud;

    @Column(name = "distancia_m")
    private Integer distanciaM;

    @Enumerated(EnumType.STRING)
    @Column(name = "origen", nullable = false, length = 10)
    private OrigenMarca origen;

    @Column(name = "sincronizada_tarde", nullable = false)
    private boolean sincronizadaTarde;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 12)
    private EstadoMarca estado = EstadoMarca.VALIDA;

    @Column(name = "client_uuid", nullable = false)
    private UUID clientUuid;

    protected Marca() {
    }

    public Marca(Long empresaId, Long empleadoId, Long sucursalId, TipoMarca tipo, Instant horaServidor,
            OrigenMarca origen, UUID clientUuid) {
        this.empresaId = empresaId;
        this.empleadoId = empleadoId;
        this.sucursalId = sucursalId;
        this.tipo = tipo;
        this.horaServidor = horaServidor;
        this.origen = origen;
        this.clientUuid = clientUuid;
    }

    public Long getId() {
        return id;
    }

    public Long getEmpresaId() {
        return empresaId;
    }

    public Long getEmpleadoId() {
        return empleadoId;
    }

    public Long getSucursalId() {
        return sucursalId;
    }

    public TipoMarca getTipo() {
        return tipo;
    }

    public Instant getHoraServidor() {
        return horaServidor;
    }

    public Instant getHoraDispositivo() {
        return horaDispositivo;
    }

    public Double getLatitud() {
        return latitud;
    }

    public Double getLongitud() {
        return longitud;
    }

    public Integer getDistanciaM() {
        return distanciaM;
    }

    public OrigenMarca getOrigen() {
        return origen;
    }

    public boolean isSincronizadaTarde() {
        return sincronizadaTarde;
    }

    public EstadoMarca getEstado() {
        return estado;
    }

    public UUID getClientUuid() {
        return clientUuid;
    }
}
