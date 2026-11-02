import { Navigate, Route, Routes } from 'react-router'
import { Announcer } from '../components/Announcer'
import './admin.css'
import './admin-content.css'
import './admin-dialogs.css'
import { AdminLayout } from './AdminLayout'
import { DashboardPage } from './pages/DashboardPage'
import { ReviewQueuePage } from './pages/ReviewQueuePage'

/**
 * The whole admin console: one lazy chunk, not precached by the service worker. The guard (RequireAdmin) and
 * the chunk error boundary live in the employee bundle, so they exist even when this chunk cannot be loaded.
 */
export function AdminApp() {
  return (
    <Announcer>
      <AdminLayout>
        <Routes>
          <Route index element={<DashboardPage />} />
          <Route path="review" element={<ReviewQueuePage />} />
          <Route path="*" element={<Navigate to="/admin" replace />} />
        </Routes>
      </AdminLayout>
    </Announcer>
  )
}
