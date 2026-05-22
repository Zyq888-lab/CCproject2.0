{/* 模块用途：LeaderConfigPage——直属上级配置页，搜索筛选+分页表格+行内编辑上级 */}
{/* 依赖组件：PageHeader, EmptyState, client.js, Ant Design Table/Select/Input */}
{/* 修改注意：逐行保存调用PUT /employees/{id}携带version；上级下拉仅显示在职员工 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Input, Select, Space, Tag, message, Card,
} from 'antd';
import {
  SearchOutlined, ReloadOutlined, TeamOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showConflictWarning } from '../../components/ConfirmModal';
import client from '../../api/client';

const STATUS_OPTIONS = [
  { label: '在职', value: '在职' },
  { label: '离职', value: '离职' },
];

const STATUS_COLOR_MAP = {
  '在职': 'green',
  '离职': 'red',
};

function LeaderConfigPage() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [filters, setFilters] = useState({ keyword: '', category: '', status: '' });
  const [allEmployees, setAllEmployees] = useState([]);
  const [editingId, setEditingId] = useState(null);
  const [savingId, setSavingId] = useState(null);
  const [leaderValues, setLeaderValues] = useState({});
  const mountedRef = useRef(true);

  const fetchEmployees = useCallback(async (page, size, filterParams) => {
    setLoading(true);
    setError(null);
    try {
      const params = { page, size };
      if (filterParams?.keyword) params.keyword = filterParams.keyword;
      if (filterParams?.category) params.category = filterParams.category;
      if (filterParams?.status) params.status = filterParams.status;
      const res = await client.get('/employees', { params });
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
      if (mountedRef.current) setError(err?.message || '加载失败');
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, []);

  const fetchAllEmployees = useCallback(async () => {
    try {
      const res = await client.get('/employees', { params: { page: 1, size: 9999 } });
      if (mountedRef.current) {
        const pageData = res.data || {};
        setAllEmployees(pageData.list || []);
      }
    } catch (_) { /* 非关键数据 */ }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    fetchEmployees(pagination.current, pagination.pageSize, filters);
    fetchAllEmployees();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = () => { setEditingId(null); fetchEmployees(1, pagination.pageSize, filters); };
  const handleReset = () => {
    setEditingId(null);
    const empty = { keyword: '', category: '', status: '' };
    setFilters(empty);
    fetchEmployees(1, pagination.pageSize, empty);
  };

  const handleTableChange = (pag) => {
    setEditingId(null);
    const newPage = pag.current;
    const newSize = pag.pageSize;
    setPagination((prev) => ({ ...prev, current: newPage, pageSize: newSize }));
    fetchEmployees(newPage, newSize, filters);
  };

  const handleStartEdit = (record) => {
    setEditingId(record.employeeId);
    setLeaderValues((prev) => ({ ...prev, [record.employeeId]: record.directLeaderId || undefined }));
  };

  const handleCancelEdit = () => {
    setEditingId(null);
    setSavingId(null);
  };

  const handleLeaderChange = (employeeId, value) => {
    setLeaderValues((prev) => ({ ...prev, [employeeId]: value }));
  };

  const handleSave = async (record) => {
    const newLeaderId = leaderValues[record.employeeId];
    setSavingId(record.employeeId);
    try {
      await client.put(`/employees/${record.employeeId}`, {
        name: record.name,
        employeeId: record.employeeId,
        email: record.email,
        category: record.category,
        position: record.position,
        orgName: record.orgName,
        status: record.status,
        directLeaderId: newLeaderId || null,
        version: record.version,
      });
      message.success({ content: '保存成功', duration: 2 });
      setEditingId(null);
      fetchEmployees(pagination.current, pagination.pageSize, filters);
    } catch (err) {
      if (err?.code === 409) {
        showConflictWarning('其他用户', '几');
      } else if (err?.message) {
        message.error({ content: err.message });
      }
    } finally {
      if (mountedRef.current) setSavingId(null);
    }
  };

  const activeEmployees = allEmployees.filter((e) => e.status === '在职');
  const leaderOptions = activeEmployees.map((e) => ({
    label: `${e.employeeId} — ${e.name}`,
    value: e.employeeId,
  }));

  const getLeaderName = (leaderId) => {
    if (!leaderId) return '-';
    const leader = allEmployees.find((e) => e.employeeId === leaderId);
    return leader ? `${leader.employeeId} — ${leader.name}` : leaderId;
  };

  const columns = [
    { title: '工号', dataIndex: 'employeeId', key: 'employeeId', width: 120 },
    { title: '姓名', dataIndex: 'name', key: 'name', width: 100 },
    { title: '岗位分类', dataIndex: 'category', key: 'category', width: 110 },
    { title: '岗位', dataIndex: 'position', key: 'position', width: 140 },
    { title: '部门', dataIndex: 'orgName', key: 'orgName', width: 140, ellipsis: true },
    {
      title: '直属上级', dataIndex: 'directLeaderId', key: 'directLeaderId', width: 220,
      render: (v, record) => {
        if (editingId === record.employeeId) {
          return (
            <Select
              value={leaderValues[record.employeeId]}
              onChange={(val) => handleLeaderChange(record.employeeId, val)}
              options={leaderOptions.filter((o) => o.value !== record.employeeId)}
              showSearch
              optionFilterProp="label"
              allowClear
              placeholder="选择上级"
              style={{ width: 200 }}
            />
          );
        }
        return getLeaderName(v);
      },
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (s) => <Tag color={STATUS_COLOR_MAP[s] || 'default'}>{s || '-'}</Tag>,
    },
    {
      title: '操作', key: 'action', width: 120,
      render: (_, record) => {
        if (editingId === record.employeeId) {
          return (
            <Space size="small">
              <Button type="link" size="small" loading={savingId === record.employeeId}
                onClick={() => handleSave(record)}>保存</Button>
              <Button type="link" size="small" disabled={savingId === record.employeeId}
                onClick={handleCancelEdit}>取消</Button>
            </Space>
          );
        }
        return (
          <Button type="link" size="small" onClick={() => handleStartEdit(record)}
            disabled={editingId !== null}>编辑</Button>
        );
      },
    },
  ];

  const isEmpty = !loading && !error && data.length === 0 && !filters.keyword && !filters.category && !filters.status;

  return (
    <div id="leader-config-area">
      <PageHeader
        title="直属上级配置"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
      />

      <Card id="leader-config-search-bar" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Space wrap size="middle">
          <Input
            placeholder="搜索姓名或工号"
            prefix={<SearchOutlined />}
            value={filters.keyword}
            onChange={(e) => setFilters((f) => ({ ...f, keyword: e.target.value }))}
            onPressEnter={handleSearch}
            style={{ width: 220 }}
            allowClear
          />
          <Select
            placeholder="岗位分类"
            value={filters.category || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, category: v || '' }))}
            allowClear
            style={{ width: 140 }}
            options={[
              { label: '研发技术类', value: '研发技术类' },
              { label: '生产制造类', value: '生产制造类' },
              { label: '质量管理类', value: '质量管理类' },
              { label: '项目管理类', value: '项目管理类' },
            ]}
          />
          <Select
            placeholder="状态"
            value={filters.status || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, status: v || '' }))}
            allowClear
            style={{ width: 100 }}
            options={STATUS_OPTIONS}
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>搜索</Button>
          <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
        </Space>
      </Card>

      {error && data.length === 0 && (
        <Card style={{ borderRadius: 8, textAlign: 'center', padding: 48 }}>
          <div style={{ color: '#FF4D4F', marginBottom: 16 }}>{error}</div>
          <Button type="primary" onClick={() => fetchEmployees(pagination.current, pagination.pageSize, filters)}>重试</Button>
        </Card>
      )}

      {isEmpty && (
        <EmptyState
          image={<TeamOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有员工数据"
          description="导入员工数据后，可在此配置直属上级关系"
          primaryAction={{ label: '去导入员工', onClick: () => window.location.href = '/employee/list' }}
        />
      )}

      {!isEmpty && (
        <Card id="leader-config-table-card" style={{ borderRadius: 8 }}>
          {error && data.length > 0 && (
            <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
              {error}
              <Button type="link" onClick={() => fetchEmployees(pagination.current, pagination.pageSize, filters)}>重试</Button>
            </div>
          )}
          <Table
            columns={columns}
            dataSource={data}
            rowKey="employeeId"
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
            scroll={{ x: 1030 }}
          />
        </Card>
      )}
    </div>
  );
}

export default LeaderConfigPage;
