{/* 模块用途：ProjectListPage——项目管理页，表格+阶段筛选+新增项目弹窗+PM确认阶段+ADMIN重置 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Select/Tag */}
{/* 修改注意：确认/重置使用后端乐观锁，409冲突调用showConflictWarning，ADMIN专属操作用danger按钮 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Tag, Space, Modal, Form, Input, Select, message, Card,
} from 'antd';
import {
  PlusOutlined, FolderOutlined, CheckCircleOutlined, RollbackOutlined, ReloadOutlined, LinkOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showConfirm, showConflictWarning } from '../../components/ConfirmModal';
import client from '../../api/client';

const STAGE_OPTIONS = [
  { label: 'P1', value: 'P1' },
  { label: 'P2', value: 'P2' },
  { label: 'P3', value: 'P3' },
  { label: 'P4', value: 'P4' },
  { label: 'P5', value: 'P5' },
];

const STATUS_OPTIONS = [
  { label: '活跃', value: 'ACTIVE' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '归档', value: 'INACTIVE' },
];

const STAGE_COLOR_MAP = { 'P1': 'cyan', 'P2': 'blue', 'P3': 'green', 'P4': 'orange', 'P5': 'red' };
const STATUS_COLOR_MAP = { 'ACTIVE': 'green', 'COMPLETED': 'blue', 'INACTIVE': 'default' };

function ProjectListPage() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [filters, setFilters] = useState({ stage: '', status: '' });
  const [modalVisible, setModalVisible] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const mountedRef = useRef(true);
  const navigate = useNavigate();

  // 功能：分页获取项目列表——支持 stage 和 status 筛选
  const fetchProjects = useCallback(async (page, size, filterParams) => {
    setLoading(true);
    setError(null);
    try {
      const params = { page, size };
      if (filterParams?.stage) params.stage = filterParams.stage;
      if (filterParams?.status) params.status = filterParams.status;
      const res = await client.get('/projects', { params });
      if (mountedRef.current) {
        const pageData = res.data || {};
        setData(pageData.list || []);
        setPagination((prev) => ({
          ...prev,
          current: pageData.page || page,
          total: pageData.total || 0,
        }));
      }
    } catch (err) {
      if (mountedRef.current) {
        setError(err?.message || '加载失败');
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    fetchProjects(pagination.current, pagination.pageSize, filters);
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 功能：搜索——重置到第一页
  const handleSearch = () => {
    fetchProjects(1, pagination.pageSize, filters);
  };

  // 功能：重置筛选——清空后重新加载
  const handleReset = () => {
    const empty = { stage: '', status: '' };
    setFilters(empty);
    fetchProjects(1, pagination.pageSize, empty);
  };

  // 功能：表格翻页/每页条数变化
  const handleTableChange = (pag) => {
    const newPage = pag.current;
    const newSize = pag.pageSize;
    setPagination((prev) => ({ ...prev, current: newPage, pageSize: newSize }));
    fetchProjects(newPage, newSize, filters);
  };

  // 功能：打开新增弹窗——表单初始值为空，status默认ACTIVE
  const handleCreate = () => {
    form.resetFields();
    form.setFieldsValue({ status: 'ACTIVE' });
    setModalVisible(true);
  };

  // 功能：提交新增项目——POST /api/v1/projects
  const handleCreateSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await client.post('/projects', values);
      message.success({ content: '项目创建成功', duration: 3 });
      setModalVisible(false);
      fetchProjects(pagination.current, pagination.pageSize, filters);
    } catch (err) {
      if (err?.code === 409) {
        if (err?.message?.includes('已被他人修改')) {
          showConflictWarning('其他用户', '几');
        } else {
          message.error({ content: err.message });
        }
      } else if (err?.message) {
        message.error({ content: err.message });
      }
    } finally {
      setSubmitting(false);
    }
  };

  // 功能：PM确认阶段——PUT /api/v1/projects/{projectCode}/confirm-stage，二次确认后提交
  const handleConfirmStage = (project) => {
    showConfirm({
      title: `确认项目阶段 — ${project.projectCode}`,
      content: `确认后将锁定"${project.projectStage}"阶段，确认后只有ADMIN可重置。`,
      okText: '确认阶段',
      okType: 'primary',
      onOk: async () => {
        try {
          await client.put(`/projects/${project.projectCode}/${project.projectStage}/confirm-stage`);
          message.success({ content: `项目"${project.projectCode}" ${project.projectStage} 阶段已确认`, duration: 3 });
          fetchProjects(pagination.current, pagination.pageSize, filters);
        } catch (err) {
          if (err?.code === 409) {
            if (err?.message?.includes('已被他人修改')) {
              showConflictWarning('其他用户', '几');
            } else {
              message.error({ content: err.message });
            }
          } else if (err?.message) {
            message.error({ content: err.message });
          }
        }
      },
    });
  };

  // 功能：ADMIN强制重置——PUT /api/v1/projects/{projectCode}/reset-stage，danger样式二次确认
  const handleResetStage = (project) => {
    showConfirm({
      title: `强制重置阶段 — ${project.projectCode}`,
      content: `将清除"${project.projectStage}"阶段的确认状态，PM需要重新确认。`,
      okText: '强制重置',
      okType: 'danger',
      onOk: async () => {
        try {
          await client.put(`/projects/${project.projectCode}/${project.projectStage}/reset-stage`);
          message.success({ content: `项目"${project.projectCode}" ${project.projectStage} 阶段已重置`, duration: 3 });
          fetchProjects(pagination.current, pagination.pageSize, filters);
        } catch (err) {
          if (err?.code === 409) {
            if (err?.message?.includes('已被他人修改')) {
              showConflictWarning('其他用户', '几');
            } else {
              message.error({ content: err.message });
            }
          } else if (err?.message) {
            message.error({ content: err.message });
          }
        }
      },
    });
  };

  // 功能：表格列定义——编码/名称/阶段/状态/确认状态/确认人/确认时间/操作
  const columns = [
    { title: '项目编码', dataIndex: 'projectCode', key: 'projectCode', width: 140 },
    { title: '项目名称', dataIndex: 'projectName', key: 'projectName', width: 160 },
    {
      title: '阶段', dataIndex: 'projectStage', key: 'projectStage', width: 80,
      render: (s) => <Tag color={STAGE_COLOR_MAP[s] || 'default'}>{s || '-'}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (s) => <Tag color={STATUS_COLOR_MAP[s] || 'default'}>{s === 'ACTIVE' ? '活跃' : s === 'COMPLETED' ? '已完成' : s === 'INACTIVE' ? '归档' : (s || '-')}</Tag>,
    },
    {
      title: '阶段确认', dataIndex: 'stageConfirmed', key: 'stageConfirmed', width: 100,
      render: (confirmed) => (
        <Tag color={confirmed ? 'green' : 'orange'}>
          {confirmed ? '已确认' : '未确认'}
        </Tag>
      ),
    },
    { title: '确认人', dataIndex: 'confirmedBy', key: 'confirmedBy', width: 100, render: (v) => v || '-' },
    { title: '确认时间', dataIndex: 'confirmedAt', key: 'confirmedAt', width: 170,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN') : '-' },
    {
      title: '操作', key: 'action', width: 260,
      render: (_, record) => (
        <Space size="small">
          <Button type="link" size="small" icon={<LinkOutlined />} onClick={() => navigate(`/project/${record.projectCode}/roles`)}>
            角色分配
          </Button>
          {!record.stageConfirmed ? (
            <Button type="link" size="small" icon={<CheckCircleOutlined />} onClick={() => handleConfirmStage(record)}>
              确认阶段
            </Button>
          ) : (
            <Button type="link" size="small" danger icon={<RollbackOutlined />} onClick={() => handleResetStage(record)}>
              强制重置
            </Button>
          )}
        </Space>
      ),
    },
  ];

  const isEmpty = !loading && !error && data.length === 0 && !filters.stage && !filters.status;

  return (
    <div id="project-list-area">
      <PageHeader
        title="项目管理"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[
          { label: '新增项目', icon: <PlusOutlined />, type: 'primary', onClick: handleCreate },
        ]}
      />

      {/* 功能：筛选栏——阶段下拉+状态下拉+搜索/重置按钮 */}
      <Card id="project-search-bar" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Space wrap size="middle">
          <Select
            placeholder="项目阶段"
            value={filters.stage || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, stage: v || '' }))}
            allowClear
            style={{ width: 120 }}
            options={STAGE_OPTIONS}
          />
          <Select
            placeholder="状态"
            value={filters.status || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, status: v || '' }))}
            allowClear
            style={{ width: 120 }}
            options={STATUS_OPTIONS}
          />
          <Button type="primary" onClick={handleSearch}>搜索</Button>
          <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
        </Space>
      </Card>

      {/* 功能：错误提示——加载失败时显示重试 */}
      {error && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={() => fetchProjects(pagination.current, pagination.pageSize, filters)}>重试</Button>
        </div>
      )}

      {/* 功能：空状态——无项目且无筛选条件时显示引导 */}
      {isEmpty && (
        <EmptyState
          image={<FolderOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有任何项目"
          description="请先完成项目角色配置，再创建项目"
          primaryAction={{ label: '先去配置项目角色', onClick: () => navigate('/project-role') }}
        />
      )}

      {/* 功能：项目数据表格——斑马纹+分页器 */}
      {!isEmpty && (
        <Card id="project-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={data}
            rowKey={(r) => `${r.projectCode}_${r.projectStage}`}
            loading={loading}
            size="middle"
            rowClassName={(_, index) => index % 2 === 1 ? 'table-row-striped' : ''}
            onChange={handleTableChange}
            pagination={{
              current: pagination.current,
              pageSize: pagination.pageSize,
              total: pagination.total,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50],
              showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条，共 ${total} 条`,
            }}
            scroll={{ x: 1100 }}
          />
        </Card>
      )}

      {/* 功能：新增项目弹窗——编码+名称+阶段+描述+状态 */}
      <Modal
        title="新增项目"
        open={modalVisible}
        onOk={handleCreateSubmit}
        onCancel={() => setModalVisible(false)}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        width={480}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="projectCode" label="项目编码" rules={[{ required: true, message: '请输入项目编码' }]}>
            <Input placeholder="如 PRJ2025001" maxLength={50} />
          </Form.Item>
          <Form.Item name="projectName" label="项目名称" rules={[{ required: true, message: '请输入项目名称' }]}>
            <Input placeholder="项目名称" maxLength={100} />
          </Form.Item>
          <Form.Item name="projectStage" label="项目阶段" rules={[{ required: true, message: '请选择项目阶段' }]}>
            <Select placeholder="选择阶段" options={STAGE_OPTIONS} />
          </Form.Item>
          <Form.Item name="description" label="项目说明">
            <Input.TextArea placeholder="项目说明（可选）" maxLength={500} rows={3} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select placeholder="选择状态" options={STATUS_OPTIONS} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default ProjectListPage;
