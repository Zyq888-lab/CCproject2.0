{/* 模块用途：TaskListPage——考核任务列表页，双Tab(待评分/我的进度)+周期/状态/项目编码筛选+状态Tag */}
{/* 依赖组件：PageHeader, EmptyState, client.js, Ant Design Tabs/Table/Select/Input/Tag/Space/Card */}
{/* 修改注意：状态Tag颜色 PENDING=orange/IN_PROGRESS=blue/SUBMITTED=green/RETURNED=red/CONFIRMED=cyan/CANCELED=default */}
import { useState, useEffect, useRef } from 'react';
import {
  Table, Button, Select, Input, Space, Tag, Card, Tabs, message,
} from 'antd';
import {
  ReloadOutlined, CarryOutOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

const STATUS_OPTIONS = [
  { label: '待评分', value: 'PENDING' },
  { label: '评分中', value: 'IN_PROGRESS' },
  { label: '已提交', value: 'SUBMITTED' },
  { label: '已退回', value: 'RETURNED' },
  { label: '已确认', value: 'CONFIRMED' },
  { label: '已取消', value: 'CANCELED' },
];

const STATUS_LABEL_MAP = {
  'PENDING': '待评分',
  'IN_PROGRESS': '评分中',
  'SUBMITTED': '已提交',
  'RETURNED': '已退回',
  'CONFIRMED': '已确认',
  'CANCELED': '已取消',
};

const STATUS_COLOR_MAP = {
  'PENDING': 'orange',
  'IN_PROGRESS': 'blue',
  'SUBMITTED': 'green',
  'RETURNED': 'red',
  'CONFIRMED': 'cyan',
  'CANCELED': 'default',
};

const TASK_TYPE_LABEL = { 'PROJECT': '项目考核', 'FUNCTIONAL': '职能考核' };

function TaskListPage() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [filters, setFilters] = useState({ periodId: '', status: '', projectCode: '' });
  const [activeTab, setActiveTab] = useState('pending');
  const [periods, setPeriods] = useState([]);
  const mountedRef = useRef(true);

  // 功能：分页获取考核任务列表——支持周期/状态/项目编码筛选
  const fetchTasks = async (page, size, filterParams) => {
    setLoading(true);
    setError(null);
    try {
      const params = { page, size };
      if (filterParams?.periodId) params.periodId = filterParams.periodId;
      if (filterParams?.status) params.status = filterParams.status;
      if (filterParams?.projectCode) params.projectCode = filterParams.projectCode;
      const res = await client.get('/tasks', { params });
      if (mountedRef.current) {
        const pageData = res.data || {};
        setData(pageData.list || []);
        setPagination((prev) => ({ ...prev, current: pageData.page || page, total: pageData.total || 0 }));
      }
    } catch (err) {
      if (mountedRef.current) setError(err?.message || '加载失败');
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  };

  // 功能：获取考核周期列表（筛选用）
  const fetchPeriods = async () => {
    try {
      const res = await client.get('/periods');
      if (mountedRef.current) setPeriods(Array.isArray(res.data) ? res.data : []);
    } catch (_) { /* 非关键 */ }
  };

  useEffect(() => {
    mountedRef.current = true;
    fetchTasks(pagination.current, pagination.pageSize, filters);
    fetchPeriods();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = () => fetchTasks(1, pagination.pageSize, filters);
  const handleReset = () => {
    const empty = { periodId: '', status: '', projectCode: '' };
    setFilters(empty);
    fetchTasks(1, pagination.pageSize, empty);
  };

  const handleTableChange = (pag) => {
    fetchTasks(pag.current, pag.pageSize, filters);
  };

  // 功能：双 Tab 切换——待评分=非终态任务，我的进度=全部任务
  const handleTabChange = (key) => {
    setActiveTab(key);
    if (key === 'pending') {
      const f = { ...filters, status: '' };
      setFilters(f);
      fetchTasks(1, pagination.pageSize, f);
    } else {
      const f = { ...filters, status: '' };
      setFilters(f);
      fetchTasks(1, pagination.pageSize, f);
    }
  };

  // 功能：开始评分——PENDING → IN_PROGRESS，跳转到打分页（T10 实现后接入）
  const handleStart = async (record) => {
    try {
      await client.put(`/tasks/${record.id}/start`);
      message.success({ content: '已开始评分', duration: 2 });
      fetchTasks(pagination.current, pagination.pageSize, filters);
    } catch (err) {
      message.error({ content: err?.message || '操作失败' });
    }
  };

  const columns = [
    { title: '被考核人', dataIndex: 'assesseeId', key: 'assesseeId', width: 120 },
    { title: '项目编码', dataIndex: 'projectCode', key: 'projectCode', width: 120,
      render: (v) => v || '-' },
    { title: '类型', dataIndex: 'taskType', key: 'taskType', width: 100,
      render: (t) => TASK_TYPE_LABEL[t] || t || '-' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s) => <Tag color={STATUS_COLOR_MAP[s] || 'default'}>{STATUS_LABEL_MAP[s] || s || '-'}</Tag> },
    { title: '退回次数', dataIndex: 'returnCount', key: 'returnCount', width: 90,
      render: (v, r) => `${v ?? 0}/${r.maxReturns ?? 3}` },
    { title: '操作', key: 'action', width: 120,
      render: (_, record) => (
        record.status === 'PENDING' ? (
          <Button type="link" size="small" onClick={() => handleStart(record)}>开始评分</Button>
        ) : record.status === 'IN_PROGRESS' || record.status === 'RETURNED' ? (
          <Button type="link" size="small" onClick={() => message.info({ content: '打分页 T10 实现后接入' })}>继续评分</Button>
        ) : (
          <span style={{ color: '#BFBFBF' }}>-</span>
        )
      ),
    },
  ];

  const isEmpty = !loading && !error && data.length === 0 && !filters.periodId && !filters.status && !filters.projectCode;

  return (
    <div id="task-list-page-area">
      <PageHeader
        title="考核任务"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
      />

      {/* 功能：双 Tab——待评分 / 我的进度 */}
      <Card id="task-tabs-card" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Tabs
          activeKey={activeTab}
          onChange={handleTabChange}
          items={[
            { key: 'pending', label: '待评分' },
            { key: 'progress', label: '我的进度' },
          ]}
        />

        {/* 功能：筛选栏——周期 + 状态 + 项目编码 */}
        <Space wrap size="middle" style={{ marginTop: 8 }}>
          <Select
            placeholder="考核周期"
            value={filters.periodId || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, periodId: v || '' }))}
            allowClear
            style={{ width: 200 }}
            options={periods.map((p) => ({ label: p.periodName, value: p.periodId }))}
          />
          <Select
            placeholder="状态"
            value={filters.status || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, status: v || '' }))}
            allowClear
            style={{ width: 140 }}
            options={STATUS_OPTIONS}
          />
          <Input
            placeholder="项目编码"
            value={filters.projectCode}
            onChange={(e) => setFilters((f) => ({ ...f, projectCode: e.target.value }))}
            allowClear
            style={{ width: 160 }}
          />
          <Button type="primary" onClick={handleSearch}>搜索</Button>
          <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
        </Space>
      </Card>

      {error && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={() => fetchTasks(pagination.current, pagination.pageSize, filters)}>重试</Button>
        </div>
      )}

      {/* 功能：引导式空状态——无任务且无筛选条件时显示 */}
      {isEmpty && (
        <EmptyState
          image={<CarryOutOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title={activeTab === 'pending' ? '所有考核任务已完成 ✓' : '暂无考核任务'}
          description={activeTab === 'pending' ? '没有待评分的任务，等待管理员发起新考核' : '暂无被分配的考核任务'}
        />
      )}

      {!isEmpty && (
        <Card id="task-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={data}
            rowKey="id"
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
            scroll={{ x: 750 }}
          />
        </Card>
      )}
    </div>
  );
}

export default TaskListPage;
