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

@Entity
@Table(name = "evidencia")
public class Evidencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "empresa_id", nullable = false)
    private Long empresaId;

    @Column(name = "marca_id", nullable = false)
    private Long marcaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 10)
    private TipoEvidencia tipo;

    // Storage key, not a public URL. Null for GPS evidence.
    @Column(name = "url_archivo", length = 500)
    private String urlArchivo;

    @Column(name = "creada", nullable = false)
    private Instant creada;

    protected Evidencia() {
    }

    public Evidencia(Long empresaId, Long marcaId, TipoEvidencia tipo, String urlArchivo, Instant creada) {
        this.empresaId = empresaId;
        this.marcaId = marcaId;
        this.tipo = tipo;
        this.urlArchivo = urlArchivo;
        this.creada = creada;
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

    public TipoEvidencia getTipo() {
        return tipo;
    }

    public String getUrlArchivo() {
        return urlArchivo;
    }

    public Instant getCreada() {
        return creada;
    }
}
