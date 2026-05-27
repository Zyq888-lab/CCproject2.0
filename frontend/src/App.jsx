{/* 模块用途：应用根组件——路由配置，AppLayout包裹所有需登录页面 */}
{/* 依赖组件：AppLayout, LoginPage, DashboardPage, RoleAssignmentPage, react-router-dom */}
{/* 修改注意：新增页面时在此添加Route + lazy import，公开页面(login)不走AppLayout */}
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import LoginPage from './pages/Login/LoginPage';
import DashboardPage from './pages/Dashboard/DashboardPage';
import EmployeeListPage from './pages/Employee/EmployeeListPage';
import UserRolePage from './pages/UserRole/UserRolePage';
import ProjectRolePage from './pages/ProjectRole/ProjectRolePage';
import ProjectListPage from './pages/Project/ProjectListPage';
import RoleAssignmentPage from './pages/RoleAssignment/RoleAssignmentPage';
import PositionConfigPage from './pages/PositionConfig/PositionConfigPage';
import ProjectKpiPage from './pages/KpiConfig/ProjectKpiPage';
import FuncKpiPage from './pages/KpiConfig/FuncKpiPage';
import LeaderConfigPage from './pages/LeaderConfig/LeaderConfigPage';
import SystemParamPage from './pages/SystemParam/SystemParamPage';
import PeriodConfigPage from './pages/PeriodConfig/PeriodConfigPage';

function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* 功能：登录页——公开访问，不走AppLayout包裹 */}
        <Route path="/login" element={<LoginPage />} />

        {/* 功能：所有需登录页面——由AppLayout包裹，通过<Outlet />渲染子路由 */}
        <Route element={<AppLayout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/employee/list" element={<EmployeeListPage />} />
          <Route path="/project-role" element={<ProjectRolePage />} />
          <Route path="/project/list" element={<ProjectListPage />} />
          <Route path="/project/:id/roles" element={<RoleAssignmentPage />} />
          <Route path="/role-assignment" element={<Navigate to="/project/list" replace />} />
          <Route path="/position-config" element={<PositionConfigPage />} />
          <Route path="/kpi-config/project" element={<ProjectKpiPage />} />
          <Route path="/kpi-config/functional" element={<FuncKpiPage />} />
          <Route path="/period-config" element={<PeriodConfigPage />} />
          <Route path="/user-role" element={<UserRolePage />} />
          <Route path="/system-param" element={<SystemParamPage />} />
          <Route path="/leader-config" element={<LeaderConfigPage />} />
        </Route>

        {/* 功能：根路径重定向到仪表盘 */}
        <Route path="/" element={<Navigate to="/dashboard" replace />} />
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
