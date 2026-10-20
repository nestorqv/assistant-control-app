import { Link } from 'react-router'
import { HealthStatus } from '../components/HealthStatus'

export function AdminHome() {
  return (
    <main>
      <h1>Panel del administrador</h1>
      <p>En construcción</p>
      <HealthStatus />
      <Link to="/">Volver al inicio</Link>
    </main>
  )
}
