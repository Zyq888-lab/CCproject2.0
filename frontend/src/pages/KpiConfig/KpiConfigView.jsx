import { Tabs } from 'antd';
import { LineChartOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import ProjectKpiPage from './ProjectKpiPage';
import FuncKpiPage from './FuncKpiPage';

const tabItems = [
  { key: 'project', label: '项目KPI配置', children: <ProjectKpiPage /> },
  { key: 'functional', label: '职能KPI配置', children: <FuncKpiPage /> },
];

function KpiConfigView() {
  return (
    <div id="kpi-config-area">
      <PageHeader
        title="KPI配置"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
      />
      <Tabs
        defaultActiveKey="project"
        items={tabItems}
        destroyOnHidden={false}
        style={{ marginTop: -8 }}
      />
    </div>
  );
}

export default KpiConfigView;
