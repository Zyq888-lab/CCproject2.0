{/* 模块用途：AppLayout——全局布局组件，左侧边栏+顶部导航+内容区域的三明治结构 */}
{/* 依赖组件：react-router-dom, Ant Design Menu/Layout */}
{/* 修改注意：菜单项变更时同步更新 menuItems 数组和路由配置 */}
import { useState } from 'react';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { Layout, Menu } from 'antd';
import {
  DashboardOutlined,
  ExperimentOutlined,
  TeamOutlined,
  AimOutlined,
  FolderOutlined,
  LinkOutlined,
  SettingOutlined,
  LineChartOutlined,
  CalendarOutlined,
  UserOutlined,
  ToolOutlined,
  OrderedListOutlined,
} from '@ant-design/icons';

const { Sider, Header, Content } = Layout;

const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '仪表盘', group: 'top' },
  { type: 'divider', label: '配置中心', group: 'config' },
  { key: '/setup-wizard', icon: <ExperimentOutlined />, label: '配置向导' },
  { key: '/employee/list', icon: <TeamOutlined />, label: '员工管理' },
  { key: '/project-role', icon: <AimOutlined />, label: '项目角色' },
  { key: '/project/list', icon: <FolderOutlined />, label: '项目管理' },
  { key: '/project/:id/roles', icon: <LinkOutlined />, label: '角色分配' },
  { key: '/position-config', icon: <SettingOutlined />, label: '岗位配置' },
  { key: '/kpi-config/project', icon: <LineChartOutlined />, label: '项目KPI配置' },
  { key: '/kpi-config/functional', icon: <LineChartOutlined />, label: '职能KPI配置' },
  { key: '/period-config', icon: <CalendarOutlined />, label: '考核周期' },
  { type: 'divider', label: '系统设置', group: 'system' },
  { key: '/user-role', icon: <UserOutlined />, label: '用户管理' },
  { key: '/system-param', icon: <ToolOutlined />, label: '系统参数' },
  { key: '/leader-config', icon: <OrderedListOutlined />, label: '直属上级' },
];

function AppLayout() {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  // 功能：选中当前路径对应的菜单项，匹配不到时回退到仪表盘
  const selectedKey = menuItems.find(
    (item) => item.key && location.pathname.startsWith(item.key.split('/:')[0])
  )?.key || '/dashboard';

  // 功能：点击菜单项跳转到对应页面，参数化路径（含:id）跳转到列表页
  const handleMenuClick = ({ key }) => {
    if (key.includes('/:id')) {
      navigate(key.replace(/\/:id.*$/, '/list'));
    } else {
      navigate(key);
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {/* 功能：侧边栏——200px深色导航，平板端折叠为60px纯图标模式 */}
      <Sider
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        width={200}
        style={{ background: '#001529' }}
        breakpoint="lg"
        collapsedWidth={60}
      >
        <div id="sidebar-logo-area" style={{
          height: 64,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: '#fff',
          fontSize: collapsed ? 14 : 16,
          fontWeight: 500,
          borderBottom: '1px solid rgba(255,255,255,0.1)',
          cursor: 'pointer',
        }}
          onClick={() => navigate('/dashboard')}
        >
          {collapsed ? '🏠' : '🏠 继峰考核'}
        </div>

        {/* 功能：导航菜单——配置中心组+系统设置组，当前页高亮 */}
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={handleMenuClick}
          style={{ background: '#001529' }}
        />
      </Sider>

      <Layout>
        {/* 功能：顶部栏——系统名称+用户名 */}
        <Header id="top-bar-area" style={{
          background: '#FFFFFF',
          padding: '0 24px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'flex-end',
          borderBottom: '1px solid #F0F0F0',
          height: 56,
        }}>
          <span style={{ color: '#8C8C8C', fontSize: 14 }}>
            <UserOutlined style={{ marginRight: 6 }} />
            管理员
          </span>
        </Header>

        {/* 功能：内容区——页面背景#F5F5F5，内边距24px */}
        <Content id="main-content-area" style={{
          background: '#F5F5F5',
          padding: 24,
          minHeight: 'calc(100vh - 56px)',
        }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}

export default AppLayout;
