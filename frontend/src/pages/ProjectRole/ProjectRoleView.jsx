import { useState, useEffect, useRef } from 'react';
import { Tabs, Select, Spin } from 'antd';
import { AimOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import client from '../../api/client';
import ProjectRolePage from './ProjectRolePage';
import RoleAssignmentPage from '../RoleAssignment/RoleAssignmentPage';
import ProjectRoleSummaryPage from '../Project/ProjectRoleSummaryPage';

function ProjectRoleView() {
  const [projects, setProjects] = useState([]);
  const [loadingProjects, setLoadingProjects] = useState(true);
  const [selectedProject, setSelectedProject] = useState(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    client.get('/projects', { params: { page: 1, size: 9999 } })
      .then((res) => {
        if (mountedRef.current) {
          const list = res.data?.list || [];
          setProjects(list);
          if (list.length > 0) setSelectedProject(list[0].projectCode);
        }
      })
      .catch(() => {})
      .finally(() => {
        if (mountedRef.current) setLoadingProjects(false);
      });
    return () => { mountedRef.current = false; };
  }, []);

  const projectOptions = projects.map((p) => ({
    label: `${p.projectCode} — ${p.projectName}`,
    value: p.projectCode,
  }));

  const tabItems = [
    {
      key: 'role-definition',
      label: '角色定义',
      children: <ProjectRolePage />,
    },
    {
      key: 'role-assignment',
      label: '角色分配',
      children: loadingProjects ? (
        <div style={{ textAlign: 'center', padding: 60 }}>
          <Spin size="large"><div style={{ padding: 20, color: '#8C8C8C' }}>加载项目列表…</div></Spin>
        </div>
      ) : (
        <div>
          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ whiteSpace: 'nowrap', fontWeight: 500 }}>选择项目：</span>
            <Select
              placeholder="选择项目"
              value={selectedProject}
              onChange={setSelectedProject}
              options={projectOptions}
              showSearch
              optionFilterProp="label"
              style={{ width: 320 }}
            />
          </div>
          {selectedProject ? (
            <RoleAssignmentPage key={selectedProject} projectCode={selectedProject} />
          ) : (
            <div style={{ textAlign: 'center', padding: 60, color: '#8C8C8C' }}>
              请选择一个项目以查看角色分配
            </div>
          )}
        </div>
      ),
    },
    {
      key: 'assignment-summary',
      label: '角色分配汇总',
      children: <ProjectRoleSummaryPage hideHeader />,
    },
  ];

  return (
    <div id="project-role-view-area">
      <PageHeader
        title="项目角色管理"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
      />
      <Tabs
        defaultActiveKey="role-definition"
        items={tabItems}
        destroyOnHidden={false}
        style={{ marginTop: -8 }}
      />
    </div>
  );
}

export default ProjectRoleView;
