import { Route, Routes } from 'react-router'
import { AdminHome } from './pages/AdminHome'
import { EmployeeHome } from './pages/EmployeeHome'
import { NotFound } from './pages/NotFound'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<EmployeeHome />} />
      <Route path="/admin" element={<AdminHome />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}
