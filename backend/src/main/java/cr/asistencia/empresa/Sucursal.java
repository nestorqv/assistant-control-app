package cr.asistencia.empresa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sucursal")
public class Sucursal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "empresa_id", nullable = false)
    private Long empresaId;

    @Column(name = "nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "latitud", nullable = false)
    private Double latitud;

    @Column(name = "longitud", nullable = false)
    private Double longitud;

    @Column(name = "radio_metros", nullable = false)
    private Integer radioMetros = 100;

    protected Sucursal() {
    }

    public Sucursal(Long empresaId, String nombre, Double latitud, Double longitud, Integer radioMetros) {
        this.empresaId = empresaId;
        this.nombre = nombre;
        this.latitud = latitud;
        this.longitud = longitud;
        this.radioMetros = radioMetros;
    }

    public Long getId() {
        return id;
    }

    public Long getEmpresaId() {
        return empresaId;
    }

    public String getNombre() {
        return nombre;
    }

    public Double getLatitud() {
        return latitud;
    }

    public Double getLongitud() {
        return longitud;
    }

    public Integer getRadioMetros() {
        return radioMetros;
    }
}
