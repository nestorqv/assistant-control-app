package cr.asistencia.marca;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ajuste")
public class Ajuste {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "empresa_id", nullable = false)
    private Long empresaId;

    @Column(name = "marca_id", nullable = false)
    private Long marcaId;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "motivo", nullable = false, columnDefinition = "text")
    private String motivo;

    // Name of the changed field, e.g. "estado" or "hora_servidor".
    @Column(name = "campo", nullable = false, columnDefinition = "text")
    private String campo;

    // Null when the adjustment creates a mark. Plain text, not a Jackson-bound JSON type.
    @Column(name = "valor_anterior", columnDefinition = "text")
    private String valorAnterior;

    @Column(name = "valor_nuevo", nullable = false, columnDefinition = "text")
    private String valorNuevo;

    @Column(name = "fecha", nullable = false)
    private Instant fecha;

    protected Ajuste() {
    }

    public Ajuste(Long empresaId, Long marcaId, Long adminId, String motivo, String campo,
            String valorAnterior, String valorNuevo, Instant fecha) {
        this.empresaId = empresaId;
        this.marcaId = marcaId;
        this.adminId = adminId;
        this.motivo = motivo;
        this.campo = campo;
        this.valorAnterior = valorAnterior;
        this.valorNuevo = valorNuevo;
        this.fecha = fecha;
    }

    public Long getId() {
        return id;
    }

    public Long getEmpresaId() {
        return empresaId;
    }

    public Long getMarcaId() {
        return marcaId;
    }

    public Long getAdminId() {
        return adminId;
    }

    public String getMotivo() {
        return motivo;
    }

    public String getCampo() {
        return campo;
    }

    public String getValorAnterior() {
        return valorAnterior;
    }

    public String getValorNuevo() {
        return valorNuevo;
    }

    public Instant getFecha() {
        return fecha;
    }
}
