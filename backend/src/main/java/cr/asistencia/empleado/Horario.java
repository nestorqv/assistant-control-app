package cr.asistencia.empleado;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

@Entity
@Table(name = "horario")
public class Horario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "empresa_id", nullable = false)
    private Long empresaId;

    @Column(name = "empleado_id", nullable = false)
    private Long empleadoId;

    // ISO: 1 = lunes.
    @Column(name = "dia_semana", nullable = false)
    private Integer diaSemana;

    // LocalTimeJdbcType binds the LocalTime as-is (setObject). The default TIME binding goes through
    // java.sql.Time and shifts the value by the JVM zone because hibernate.jdbc.time_zone is UTC.
    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "hora_entrada", nullable = false)
    private LocalTime horaEntrada;

    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "hora_salida", nullable = false)
    private LocalTime horaSalida;

    @Column(name = "tolerancia_min", nullable = false)
    private Integer toleranciaMin = 0;

    protected Horario() {
    }

    public Horario(Long empresaId, Long empleadoId, Integer diaSemana, LocalTime horaEntrada,
            LocalTime horaSalida, Integer toleranciaMin) {
        this.empresaId = empresaId;
        this.empleadoId = empleadoId;
        this.diaSemana = diaSemana;
        this.horaEntrada = horaEntrada;
        this.horaSalida = horaSalida;
        this.toleranciaMin = toleranciaMin;
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

    public Integer getDiaSemana() {
        return diaSemana;
    }

    public LocalTime getHoraEntrada() {
        return horaEntrada;
    }

    public LocalTime getHoraSalida() {
        return horaSalida;
    }

    public Integer getToleranciaMin() {
        return toleranciaMin;
    }
}
