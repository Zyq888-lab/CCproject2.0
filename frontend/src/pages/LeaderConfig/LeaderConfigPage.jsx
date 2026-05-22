{/* 模块用途：LeaderConfigPage——直属上级配置页，搜索筛选+分页表格+批量编辑上级 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Select/Input */}
{/* 修改注意：批量选中行后统一分配上级，逐行PUT /employees/{id}携带version；上级下拉仅显示在职员工 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Input, Select, Space, Tag, Modal, Form, message, Card,
} from 'antd';
import {
  SearchOutlined, ReloadOutlined, TeamOutlined, EditOutlined,
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
  const [selectedRowKeys, setSelectedRowKeys] = useState([]);
  const [batchModalVisible, setBatchModalVisible] = useState(false);
  const [batchSubmitting, setBatchSubmitting] = useState(false);
  const [batchForm] = Form.useForm();
  const mountedRef = useRef(true);

  // 功能：分页获取员工列表——支持关键字、岗位分类、状态筛选，用于主表格展示
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

  // 功能：获取全部员工列表（不分页）——用于上级候选人下拉数据源
  const fetchAllEmployees = useCallback(async () => {
    try {
      const res = await client.get('/employees', { params: { page: 1, size: 9999 } });
      if (mountedRef.current) {
        const pageData = res.data || {};
        setAllEmployees(pageData.list || []);
      }
    } catch (_) { /* 非关键数据，失败不影响主流程 */ }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    fetchEmployees(pagination.current, pagination.pageSize, filters);
    fetchAllEmployees();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 功能：搜索——重置到第一页并清空已选行
  const handleSearch = () => {
    setSelectedRowKeys([]);
    fetchEmployees(1, pagination.pageSize, filters);
  };

  // 功能：重置筛选——清空筛选条件、已选行，重新加载数据
  const handleReset = () => {
    setSelectedRowKeys([]);
    const empty = { keyword: '', category: '', status: '' };
    setFilters(empty);
    fetchEmployees(1, pagination.pageSize, empty);
  };

  // 功能：翻页/每页条数变化——清空已选行后加载新页数据
  const handleTableChange = (pag) => {
    setSelectedRowKeys([]);
    const newPage = pag.current;
    const newSize = pag.pageSize;
    setPagination((prev) => ({ ...prev, current: newPage, pageSize: newSize }));
    fetchEmployees(newPage, newSize, filters);
  };

  // 功能：打开批量编辑弹窗——重置表单，显示已选行数
  const handleBatchEdit = () => {
    batchForm.resetFields();
    setBatchModalVisible(true);
  };

  // 功能：提交批量编辑——逐行PUT更新选中员工的直属上级，统计成功/失败数
  const handleBatchSubmit = async () => {
    try {
      const values = await batchForm.validateFields();
      setBatchSubmitting(true);
      const newLeaderId = values.leaderId || null;
      let success = 0;
      let fail = 0;
      for (const key of selectedRowKeys) {
        const record = data.find((d) => d.employeeId === key);
        if (!record) { fail++; continue; }
        try {
          await client.put(`/employees/${record.employeeId}`, {
            name: record.name,
            employeeId: record.employeeId,
            email: record.email,
            category: record.category,
            position: record.position,
            orgName: record.orgName,
            status: record.status,
            directLeaderId: newLeaderId,
            version: record.version,
          });
          success++;
        } catch (err) {
          fail++;
          if (err?.code === 409) {
            showConflictWarning('其他用户', '几');
          }
        }
      }
      if (success > 0) {
        message.success({ content: `已更新 ${success} 人` + (fail > 0 ? `，${fail} 人失败` : ''), duration: 3 });
      }
      setBatchModalVisible(false);
      setSelectedRowKeys([]);
      fetchEmployees(pagination.current, pagination.pageSize, filters);
    } catch (_) {
      // validateFields rejected — form validation error, Ant Design shows inline message
    } finally {
      if (mountedRef.current) setBatchSubmitting(false);
    }
  };

  const activeEmployees = allEmployees.filter((e) => e.status === '在职');

  // 功能：构建上级候选人下拉选项——排除已选行中的员工，防止自我指派
  const selectedIds = new Set(selectedRowKeys);
  const leaderOptions = activeEmployees
    .filter((e) => !selectedIds.has(e.employeeId))
    .map((e) => ({
      label: `${e.employeeId} — ${e.name}`,
      value: e.employeeId,
    }));

  // 功能：根据直属上级工号解析显示名称——"工号 — 姓名"格式
  const getLeaderName = (leaderId) => {
    if (!leaderId) return '-';
    const leader = allEmployees.find((e) => e.employeeId === leaderId);
    return leader ? `${leader.employeeId} — ${leader.name}` : leaderId;
  };

  // 功能：表格行选择配置——checkbox列，用于批量选中员工
  const rowSelection = {
    selectedRowKeys,
    onChange: (keys) => setSelectedRowKeys(keys),
  };

  const columns = [
    { title: '工号', dataIndex: 'employeeId', key: 'employeeId', width: 120 },
    { title: '姓名', dataIndex: 'name', key: 'name', width: 100 },
    { title: '岗位分类', dataIndex: 'category', key: 'category', width: 110 },
    { title: '岗位', dataIndex: 'position', key: 'position', width: 140 },
    { title: '部门', dataIndex: 'orgName', key: 'orgName', width: 140, ellipsis: true },
    {
      title: '直属上级', dataIndex: 'directLeaderId', key: 'directLeaderId', width: 200,
      render: (v) => getLeaderName(v),
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (s) => <Tag color={STATUS_COLOR_MAP[s] || 'default'}>{s || '-'}</Tag>,
    },
  ];

  const isEmpty = !loading && !error && data.length === 0 && !filters.keyword && !filters.category && !filters.status;

  return (
    <div id="leader-config-area">
      <PageHeader
        title="直属上级配置"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
      />

      {/* 功能：搜索筛选栏——关键字搜索+岗位分类+状态下拉+搜索/重置/批量编辑按钮 */}
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
          <Button type="primary" icon={<EditOutlined />} onClick={handleBatchEdit}
            disabled={selectedRowKeys.length === 0}>
            批量编辑上级 ({selectedRowKeys.length})
          </Button>
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

      {/* 功能：数据表格——带checkbox行选择，选中后点击"批量编辑上级"统一分配 */}
      {!isEmpty && (
        <Card id="leader-config-table-card" style={{ borderRadius: 8 }}>
          {error && data.length > 0 && (
            <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
              {error}
              <Button type="link" onClick={() => fetchEmployees(pagination.current, pagination.pageSize, filters)}>重试</Button>
            </div>
          )}
          <Table
            rowSelection={rowSelection}
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
            scroll={{ x: 950 }}
          />
        </Card>
      )}

      {/* 功能：批量编辑弹窗——选择上级后确认，逐行PUT更新所有选中员工 */}
      <Modal
        title={`批量编辑直属上级 — 已选 ${selectedRowKeys.length} 人`}
        open={batchModalVisible}
        onOk={handleBatchSubmit}
        onCancel={() => setBatchModalVisible(false)}
        confirmLoading={batchSubmitting}
        okText="保存"
        cancelText="取消"
        width={440}
      >
        <Form form={batchForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="leaderId" label="直属上级" rules={[{ required: true, message: '请选择直属上级' }]}>
            <Select
              placeholder="选择上级（可选留空表示清空）"
              options={leaderOptions}
              showSearch
              optionFilterProp="label"
              allowClear
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default LeaderConfigPage;
