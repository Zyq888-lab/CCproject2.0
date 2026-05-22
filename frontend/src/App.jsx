{/* 模块用途：应用根组件——路由配置，AppLayout包裹所有需登录页面 */}
{/* 依赖组件：AppLayout, LoginPage, DashboardPage, RoleAssignmentPage, react-router-dom */}
{/* 修改注意：新增页面时在此添加Route + lazy import，公开页面(login)不走AppLayout */}
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import LoginPage from './pages/Login/LoginPage';
import DashboardPage from './pages/Dashboard/DashboardPage';
import SetupWizardPage from './pages/SetupWizard/SetupWizardPage';
import EmployeeListPage from './pages/Employee/EmployeeListPage';
import UserRolePage from './pages/UserRole/UserRolePage';
import ProjectRolePage from './pages/ProjectRole/ProjectRolePage';
import ProjectListPage from './pages/Project/ProjectListPage';
import RoleAssignmentPage from './pages/RoleAssignment/RoleAssignmentPage';

// 功能：临时占位页——T20-T32实现各页面后逐一替换
function PlaceholderPage({ title }) {
  return (
    <div id="placeholder-page-area" style={{
      background: '#FFFFFF',
      borderRadius: 8,
      padding: 48,
      textAlign: 'center',
    }}>
      <h2 style={{ color: '#8C8C8C' }}>{title} — 待实现 (T20-T32)</h2>
    </div>
  );
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* 功能：登录页——公开访问，不走AppLayout包裹 */}
        <Route path="/login" element={<LoginPage />} />

        {/* 功能：所有需登录页面——由AppLayout包裹，通过<Outlet />渲染子路由 */}
        <Route element={<AppLayout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/setup-wizard" element={<SetupWizardPage />} />
          <Route path="/employee/list" element={<EmployeeListPage />} />
          <Route path="/project-role" element={<ProjectRolePage />} />
          <Route path="/project/list" element={<ProjectListPage />} />
          <Route path="/project/:id/roles" element={<RoleAssignmentPage />} />
          <Route path="/position-config" element={<PlaceholderPage title="岗位配置" />} />
          <Route path="/kpi-config/project" element={<PlaceholderPage title="项目KPI" />} />
          <Route path="/kpi-config/functional" element={<PlaceholderPage title="职能KPI" />} />
          <Route path="/period-config" element={<PlaceholderPage title="考核周期" />} />
          <Route path="/user-role" element={<UserRolePage />} />
          <Route path="/system-param" element={<PlaceholderPage title="系统参数" />} />
          <Route path="/leader-config" element={<PlaceholderPage title="直属上级" />} />
        </Route>

        {/* 功能：根路径重定向到仪表盘 */}
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
