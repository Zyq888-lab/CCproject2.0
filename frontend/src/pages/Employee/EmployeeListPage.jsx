{/* 模块用途：EmployeeListPage——员工管理页，表格+搜索筛选+分页+新增编辑删除弹窗 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Select/Tag */}
{/* 修改注意：编辑时携带version用于乐观锁，409冲突调用showConflictWarning */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Input, Select, Space, Tag, Modal, Form, message, Card,
} from 'antd';
import {
  SearchOutlined, PlusOutlined, ReloadOutlined, TeamOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showDeleteConfirm, showConflictWarning } from '../../components/ConfirmModal';
import client from '../../api/client';

const STATUS_OPTIONS = [
  { label: '在职', value: '在职' },
  { label: '离职', value: '离职' },
];

const STATUS_COLOR_MAP = {
  '在职': 'green',
  '离职': 'red',
};

function EmployeeListPage() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [filters, setFilters] = useState({ keyword: '', category: '', status: '' });
  const [modalVisible, setModalVisible] = useState(false);
  const [editingEmployee, setEditingEmployee] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const mountedRef = useRef(true);

  // 功能：分页获取员工列表——支持关键字、岗位分类、状态筛选
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
    fetchEmployees(pagination.current, pagination.pageSize, filters);
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 功能：搜索——重置到第一页
  const handleSearch = () => {
    fetchEmployees(1, pagination.pageSize, filters);
  };

  // 功能：重置筛选条件——清空后重新加载
  const handleReset = () => {
    const empty = { keyword: '', category: '', status: '' };
    setFilters(empty);
    fetchEmployees(1, pagination.pageSize, empty);
  };

  // 功能：表格翻页/每页条数变化
  const handleTableChange = (pag) => {
    const newPage = pag.current;
    const newSize = pag.pageSize;
    setPagination((prev) => ({ ...prev, current: newPage, pageSize: newSize }));
    fetchEmployees(newPage, newSize, filters);
  };

  // 功能：打开新增弹窗——表单初始值为空
  const handleAdd = () => {
    setEditingEmployee(null);
    form.resetFields();
    setModalVisible(true);
  };

  // 功能：打开编辑弹窗——GET员工详情回填表单
  const handleEdit = async (employeeId) => {
    setEditingEmployee(null);
    form.resetFields();
    setModalVisible(true);
    try {
      const res = await client.get(`/employees/${employeeId}`);
      const emp = res.data;
      if (emp && mountedRef.current) {
        setEditingEmployee(emp);
        form.setFieldsValue(emp);
      }
    } catch (err) {
      message.error(err?.message || '获取员工信息失败');
      setModalVisible(false);
    }
  };

  // 功能：提交新增/编辑——POST或PUT，携带version用于乐观锁
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (editingEmployee) {
        await client.put(`/employees/${editingEmployee.employeeId}`, {
          ...values,
          version: editingEmployee.version,
        });
        message.success('保存成功', 3);
      } else {
        await client.post('/employees', values);
        message.success('保存成功', 3);
      }
      setModalVisible(false);
      fetchEmployees(pagination.current, pagination.pageSize, filters);
    } catch (err) {
      if (err?.code === 409) {
        showConflictWarning('其他用户', '几');
      } else if (err?.message) {
        message.error(err.message);
      }
    } finally {
      setSubmitting(false);
    }
  };

  // 功能：删除确认——调用DELETE后刷新列表
  const handleDelete = (employeeId) => {
    showDeleteConfirm(async () => {
      try {
        await client.delete(`/employees/${employeeId}`);
        message.success('已删除', 3);
        fetchEmployees(pagination.current, pagination.pageSize, filters);
      } catch (err) {
        message.error(err?.message || '删除失败');
      }
    }, `员工 ${employeeId}`);
  };

  // 功能：表格列定义——工号/姓名/邮箱/分类/岗位/部门/上级/状态/操作
  const columns = [
    { title: '工号', dataIndex: 'employeeId', key: 'employeeId', width: 120 },
    { title: '姓名', dataIndex: 'name', key: 'name', width: 100 },
    { title: '邮箱', dataIndex: 'email', key: 'email', width: 200, ellipsis: true },
    { title: '岗位分类', dataIndex: 'category', key: 'category', width: 100 },
    { title: '岗位', dataIndex: 'position', key: 'position', width: 120 },
    { title: '部门', dataIndex: 'orgName', key: 'orgName', width: 120, ellipsis: true },
    { title: '直属上级', dataIndex: 'directLeaderId', key: 'directLeaderId', width: 100 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (s) => <Tag color={STATUS_COLOR_MAP[s] || 'default'}>{s || '-'}</Tag>,
    },
    {
      title: '操作', key: 'action', width: 150,
      render: (_, record) => (
        <Space size="small">
          <Button type="link" size="small" onClick={() => handleEdit(record.employeeId)}>编辑</Button>
          <Button type="link" size="small" danger onClick={() => handleDelete(record.employeeId)}>删除</Button>
        </Space>
      ),
    },
  ];

  const isEmpty = !loading && !error && data.length === 0 && !filters.keyword && !filters.category && !filters.status;

  return (
    <div id="employee-list-area">
      <PageHeader
        title="员工管理"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[{ label: '新增员工', icon: <PlusOutlined />, type: 'primary', onClick: handleAdd }]}
      />

      {/* 功能：搜索筛选栏——关键字搜索+岗位分类下拉+状态下拉+搜索/重置按钮 */}
      <Card id="employee-search-bar" style={{ marginBottom: 16, borderRadius: 8 }}>
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

      {/* 功能：空状态——无数据且无筛选条件时显示引导 */}
      {isEmpty && (
        <EmptyState
          image={<TeamOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有任何员工数据"
          description="导入继峰现有员工Excel或手动新增"
          primaryAction={{ label: '批量导入Excel', onClick: () => message.info('导入功能请在员工管理中使用') }}
          secondaryAction={{ label: '手动新增', onClick: handleAdd }}
        />
      )}

      {/* 功能：数据表格——斑马纹+分页器 */}
      {!isEmpty && (
        <Card id="employee-table-card" style={{ borderRadius: 8 }}>
          {error && (
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
            scroll={{ x: 1000 }}
          />
        </Card>
      )}

      {/* 功能：新增/编辑弹窗——编辑时禁用工号字段 */}
      <Modal
        title={editingEmployee ? '编辑员工' : '新增员工'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        destroyOnClose
        width={560}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="employeeId" label="工号" rules={[{ required: true, message: '请输入工号' }]}>
            <Input disabled={!!editingEmployee} placeholder="如 EMP001" maxLength={20} />
          </Form.Item>
          <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input placeholder="员工姓名" maxLength={50} />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '请输入有效邮箱' }]}>
            <Input placeholder="如 zhangsan@jifeng.com" maxLength={100} />
          </Form.Item>
          <Form.Item name="category" label="岗位分类">
            <Select placeholder="选择岗位分类" allowClear options={[
              { label: '研发技术类', value: '研发技术类' },
              { label: '生产制造类', value: '生产制造类' },
              { label: '质量管理类', value: '质量管理类' },
              { label: '项目管理类', value: '项目管理类' },
            ]} />
          </Form.Item>
          <Form.Item name="position" label="岗位">
            <Input placeholder="如 整椅研发工程师" maxLength={50} />
          </Form.Item>
          <Form.Item name="orgName" label="部门">
            <Input placeholder="如 研发中心" maxLength={100} />
          </Form.Item>
          <Form.Item name="directLeaderId" label="直属上级工号">
            <Input placeholder="上级工号，如 EMP002" maxLength={20} />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true, message: '请选择状态' }]}>
            <Select placeholder="选择状态" options={STATUS_OPTIONS} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default EmployeeListPage;
