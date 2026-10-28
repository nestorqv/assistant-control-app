import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router'
import { RequireSession } from './auth/RequireSession'
import { AppHeader } from './components/AppHeader'
import { ConsentPage } from './pages/ConsentPage'
import { EmployeeHome } from './pages/EmployeeHome'
import { LoginPage } from './pages/LoginPage'
import { NotFound } from './pages/NotFound'

// Lazy so the employee bundle does not grow when the admin screens arrive in Stage 4.
const AdminHome = lazy(() => import('./pages/AdminHome').then((m) => ({ default: m.AdminHome })))

export default function App() {
  return (
    <>
      <AppHeader />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/consent" element={<ConsentPage />} />
        <Route
          path="/"
          element={
            <RequireSession>
              <EmployeeHome />
            </RequireSession>
          }
        />
        <Route
          path="/admin"
          element={
            <Suspense fallback={null}>
              <AdminHome />
            </Suspense>
          }
        />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </>
  )
}
