{/* 模块用途：UserRolePage——用户管理页，以员工为行展示账号激活状态，支持勾选批量一键激活 + 单员工激活 + 角色分配 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Select/Tag/Checkbox */}
{/* 修改注意：表格数据源为 GET /users/overview（employee 分页 + 左连接 sys_user），已激活行禁用勾选、未激活可批量激活 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Tag, Space, Modal, Form, Select, Checkbox, message, Card,
} from 'antd';
import {
  UserOutlined, PlusOutlined, UsergroupAddOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

const ROLE_OPTIONS = [
  { label: '管理员 (ADMIN)', value: 'ADMIN' },
  { label: '总裁', value: '总裁' },
  { label: 'PD负责人 (PD)', value: 'PD' },
  { label: '项目经理 (PM)', value: 'PM' },
  { label: '评估人', value: '评估人' },
  { label: '员工', value: '员工' },
];

const ROLE_COLOR_MAP = {
  'ADMIN': 'red',
  '总裁': 'gold',
  'PD': 'blue',
  'PM': 'green',
  '评估人': 'purple',
  '员工': 'default',
};

const ROLE_LABEL_MAP = {};
ROLE_OPTIONS.forEach((r) => { ROLE_LABEL_MAP[r.value] = r.label; });

function UserRolePage() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [roleModalVisible, setRoleModalVisible] = useState(false);
  const [editingUser, setEditingUser] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [employees, setEmployees] = useState([]);
  const [form] = Form.useForm();
  const [roleForm] = Form.useForm();
  const [selectedRowKeys, setSelectedRowKeys] = useState([]);
  const [batchModalVisible, setBatchModalVisible] = useState(false);
  const [batchSubmitting, setBatchSubmitting] = useState(false);
  const [batchForm] = Form.useForm();
  const mountedRef = useRef(true);

  // 功能：分页获取员工账号总览——GET /api/v1/users/overview?page=&size=（employee 分页 + 左连接激活状态）
  const fetchUsers = useCallback(async (page, size) => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/users/overview', { params: { page, size } });
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

  // 功能：获取员工下拉列表——用于新增用户时选择关联员工
  const fetchEmployees = useCallback(async () => {
    try {
      const res = await client.get('/employees', { params: { page: 1, size: 500 } });
      if (mountedRef.current) {
        const list = (res.data && res.data.list) ? res.data.list : [];
        setEmployees(list.map((e) => ({
          label: `${e.name} (${e.employeeId})`,
          value: e.employeeId,
        })));
      }
    } catch (_) {
      // 员工列表获取失败不影响主流程
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    fetchUsers(pagination.current, pagination.pageSize);
    fetchEmployees();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 功能：表格翻页/每页条数变化
  const handleTableChange = (pag) => {
    const newPage = pag.current;
    const newSize = pag.pageSize;
    setPagination((prev) => ({ ...prev, current: newPage, pageSize: newSize }));
    fetchUsers(newPage, newSize);
  };

  // 功能：打开新增用户弹窗——表单初始值为空
  const handleCreate = () => {
    form.resetFields();
    setCreateModalVisible(true);
  };

  // 功能：提交激活账号——POST /api/v1/users/activate（工号/默认密码/强制改密由后端生成）
  const handleCreateSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await client.post('/users/activate', {
        employeeId: values.employeeId,
        roleTypes: values.roleTypes,
      });
      message.success({ content: '账号激活成功，工号+初始密码可登录', duration: 3 });
      setCreateModalVisible(false);
      fetchUsers(pagination.current, pagination.pageSize);
    } catch (err) {
      if (err?.message) {
        message.error({ content: err.message });
      }
    } finally {
      setSubmitting(false);
    }
  };

  // 功能：打开角色分配弹窗——回填当前角色列表
  const handleRoleAssign = (user) => {
    setEditingUser(user);
    roleForm.resetFields();
    roleForm.setFieldsValue({ roleTypes: user.roles || [] });
    setRoleModalVisible(true);
  };

  // 功能：提交角色分配——PUT /api/v1/users/{userId}/roles
  const handleRoleSubmit = async () => {
    try {
      const values = await roleForm.validateFields();
      setSubmitting(true);
      await client.put(`/users/${editingUser.userId}/roles`, values);
      message.success({ content: '角色已更新', duration: 3 });
      setRoleModalVisible(false);
      fetchUsers(pagination.current, pagination.pageSize);
    } catch (err) {
      message.error({ content: err?.message || '角色更新失败' });
    } finally {
      setSubmitting(false);
    }
  };

  // 功能：打开批量激活弹窗——勾选未激活员工后统一选择角色
  const handleBatchActivate = () => {
    if (selectedRowKeys.length === 0) return;
    batchForm.resetFields();
    setBatchModalVisible(true);
  };

  // 功能：提交批量激活——POST /api/v1/users/activate-batch，返回逐员工成败
  const handleBatchSubmit = async () => {
    try {
      const values = await batchForm.validateFields();
      setBatchSubmitting(true);
      const res = await client.post('/users/activate-batch', {
        employeeIds: selectedRowKeys,
        roleTypes: values.roleTypes,
      });
      const result = res.data || {};
      const successCount = result.successCount || 0;
      const skippedCount = result.skippedCount || 0;
      const failedCount = result.failedCount || 0;
      const parts = [];
      if (successCount > 0) parts.push(`成功 ${successCount} 人`);
      if (skippedCount > 0) parts.push(`跳过 ${skippedCount} 人`);
      if (failedCount > 0) parts.push(`失败 ${failedCount} 人`);
      if (failedCount > 0) {
        message.warning({ content: `批量激活完成：${parts.join('，')}`, duration: 4 });
      } else {
        message.success({ content: `批量激活完成：${parts.join('，')}`, duration: 3 });
      }
      setBatchModalVisible(false);
      setSelectedRowKeys([]);
      fetchUsers(pagination.current, pagination.pageSize);
    } catch (err) {
      message.error({ content: err?.message || '批量激活失败' });
    } finally {
      setBatchSubmitting(false);
    }
  };

  // 功能：勾选配置——已激活行禁用勾选，未激活行可批量激活
  const rowSelection = {
    selectedRowKeys,
    onChange: setSelectedRowKeys,
    getCheckboxProps: (record) => ({ disabled: record.activated }),
  };

  // 功能：表格列定义——工号/姓名/部门/账号状态/角色标签/操作
  const columns = [
    { title: '工号', dataIndex: 'employeeId', key: 'employeeId', width: 120 },
    { title: '姓名', dataIndex: 'name', key: 'name', width: 100 },
    { title: '部门', dataIndex: 'orgName', key: 'orgName', width: 140, ellipsis: true },
    {
      title: '账号状态', dataIndex: 'activated', key: 'activated', width: 100,
      render: (v) => (v ? <Tag color="green">已激活</Tag> : <Tag color="default">未激活</Tag>),
    },
    {
      title: '角色', dataIndex: 'roles', key: 'roles', width: 280,
      render: (roles, r) => (r.activated ? (
        <Space size={4} wrap>
          {(roles || []).map((role) => (
            <Tag key={role} color={ROLE_COLOR_MAP[role] || 'default'}>
              {ROLE_LABEL_MAP[role] || role}
            </Tag>
          ))}
        </Space>
      ) : '-'),
    },
    {
      title: '操作', key: 'action', width: 120,
      render: (_, record) => (record.activated ? (
        <Button type="link" size="small" onClick={() => handleRoleAssign(record)}>
          分配角色
        </Button>
      ) : null),
    },
  ];

  const isEmpty = !loading && !error && data.length === 0;

  return (
    <div id="user-role-area">
      <PageHeader
        title="用户管理"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[
          {
            label: selectedRowKeys.length ? `批量激活 (${selectedRowKeys.length})` : '批量激活',
            icon: <UsergroupAddOutlined />,
            onClick: handleBatchActivate,
            disabled: selectedRowKeys.length === 0,
          },
          { label: '激活账号', icon: <PlusOutlined />, type: 'primary', onClick: handleCreate },
        ]}
      />

      {/* 功能：错误提示——加载失败时显示重试 */}
      {error && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={() => fetchUsers(pagination.current, pagination.pageSize)}>重试</Button>
        </div>
      )}

      {/* 功能：空状态——无员工数据时显示引导 */}
      {isEmpty && (
        <EmptyState
          image={<UserOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有任何员工数据"
          description="请先在员工管理中导入或新增员工，再为员工激活登录账号"
          primaryAction={{ label: '激活账号', onClick: handleCreate }}
        />
      )}

      {/* 功能：员工账号总览表格——勾选未激活员工后批量激活，已激活行展示角色与分配角色按钮 */}
      {!isEmpty && (
        <Card id="user-table-card" style={{ borderRadius: 8 }}>
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
            scroll={{ x: 900 }}
          />
        </Card>
      )}

      {/* 功能：激活账号弹窗——选择员工+选择角色，工号/默认密码/强制改密由后端生成 */}
      <Modal
        title="激活账号"
        open={createModalVisible}
        onOk={handleCreateSubmit}
        onCancel={() => setCreateModalVisible(false)}
        confirmLoading={submitting}
        okText="激活"
        cancelText="取消"
        width={480}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="employeeId"
            label="关联员工"
            rules={[{ required: true, message: '请选择关联员工' }]}
          >
            <Select
              placeholder="选择员工（登录账号 = 工号）"
              showSearch
              filterOption={(input, option) =>
                (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
              options={employees}
            />
          </Form.Item>
          <Form.Item
            name="roleTypes"
            label="分配角色"
            rules={[{ required: true, message: '请至少选择一个角色' }]}
          >
            <Select
              mode="multiple"
              placeholder="选择角色"
              options={ROLE_OPTIONS}
              allowClear
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 功能：批量激活弹窗——统一选择角色后批量激活勾选的未激活员工 */}
      <Modal
        title={`批量激活 — 已选 ${selectedRowKeys.length} 人`}
        open={batchModalVisible}
        onOk={handleBatchSubmit}
        onCancel={() => setBatchModalVisible(false)}
        confirmLoading={batchSubmitting}
        okText="激活"
        cancelText="取消"
        width={480}
      >
        <Form form={batchForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="roleTypes"
            label="分配角色"
            rules={[{ required: true, message: '请至少选择一个角色' }]}
          >
            <Select
              mode="multiple"
              placeholder="选择角色（对所有勾选员工生效）"
              options={ROLE_OPTIONS}
              allowClear
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 功能：角色分配弹窗——多选框展示6种角色，覆盖式保存 */}
      <Modal
        title={`分配角色 — ${editingUser?.username || ''}`}
        open={roleModalVisible}
        onOk={handleRoleSubmit}
        onCancel={() => setRoleModalVisible(false)}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        width={400}
      >
        <Form form={roleForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="roleTypes"
            label="选择角色"
            rules={[{ required: true, message: '请至少选择一个角色' }]}
          >
            <Checkbox.Group options={ROLE_OPTIONS} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default UserRolePage;
