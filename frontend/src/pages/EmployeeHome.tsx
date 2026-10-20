import { Link } from 'react-router'
import { HealthStatus } from '../components/HealthStatus'

export function EmployeeHome() {
  return (
    <main>
      <h1>Control de Asistencia</h1>
      <p>Pantalla del empleado (en construcción)</p>
      <HealthStatus />
      <Link to="/admin">Ir al panel del administrador</Link>
    </main>
  )
}
