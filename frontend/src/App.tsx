import { lazy, Suspense } from 'react'
import { Route, Routes, useLocation } from 'react-router'
import { RequireAdmin } from './auth/RequireAdmin'
import { RequireSession } from './auth/RequireSession'
import { AppHeader } from './components/AppHeader'
import { ChunkErrorBoundary } from './components/ChunkErrorBoundary'
import { ConsentPage } from './pages/ConsentPage'
import { EmployeeHome } from './pages/EmployeeHome'
import { LoginPage } from './pages/LoginPage'
import { NotFound } from './pages/NotFound'
import { useI18n } from './i18n/useI18n'

// Lazy and excluded from the service worker precache, so phones never download the admin console.
const AdminApp = lazy(() => import('./admin/AdminApp').then((m) => ({ default: m.AdminApp })))

function AdminLoading() {
  const { t } = useI18n()
  return (
    <p role="status" className="screen">
      {t('adminChunk.loading')}
    </p>
  )
}

export default function App() {
  const { pathname } = useLocation()
  // The admin shell has its own header (language, logout, my clock).
  const inAdmin = pathname === '/admin' || pathname.startsWith('/admin/')
  return (
    <>
      {!inAdmin && <AppHeader />}
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
        {/* Outside RequireSession: the consent gate does not apply to the console. */}
        <Route
          path="/admin/*"
          element={
            <RequireAdmin>
              <ChunkErrorBoundary>
                <Suspense fallback={<AdminLoading />}>
                  <AdminApp />
                </Suspense>
              </ChunkErrorBoundary>
            </RequireAdmin>
          }
        />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </>
  )
}
