{/* 模块用途：UserRolePage——用户管理页，用户分页表格+新增用户弹窗+角色分配弹窗 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Select/Tag/Checkbox */}
{/* 修改注意：角色选项与后端RoleType枚举同步，分配角色时覆盖式更新 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Tag, Space, Modal, Form, Input, Select, Checkbox, message, Card,
} from 'antd';
import {
  UserOutlined, PlusOutlined, ReloadOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showConflictWarning } from '../../components/ConfirmModal';
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
  const mountedRef = useRef(true);

  // 功能：分页获取系统用户列表——GET /api/v1/users?page=&size=
  const fetchUsers = useCallback(async (page, size) => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/users', { params: { page, size } });
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

  // 功能：提交新增用户——POST /api/v1/users
  const handleCreateSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await client.post('/users', values);
      message.success({ content: '用户创建成功', duration: 3 });
      setCreateModalVisible(false);
      fetchUsers(pagination.current, pagination.pageSize);
    } catch (err) {
      if (err?.code === 409) {
        showConflictWarning('其他用户', '几');
      } else if (err?.message) {
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

  // 功能：表格列定义——用户名/关联员工/工号/角色标签/操作
  const columns = [
    { title: '用户名', dataIndex: 'username', key: 'username', width: 120 },
    { title: '关联员工', dataIndex: 'employeeName', key: 'employeeName', width: 120 },
    { title: '员工工号', dataIndex: 'employeeId', key: 'employeeId', width: 120 },
    {
      title: '角色', dataIndex: 'roles', key: 'roles', width: 280,
      render: (roles) => (
        <Space size={4} wrap>
          {(roles || []).map((role) => (
            <Tag key={role} color={ROLE_COLOR_MAP[role] || 'default'}>
              {ROLE_LABEL_MAP[role] || role}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: '操作', key: 'action', width: 120,
      render: (_, record) => (
        <Button type="link" size="small" onClick={() => handleRoleAssign(record)}>
          分配角色
        </Button>
      ),
    },
  ];

  const isEmpty = !loading && !error && data.length === 0;

  return (
    <div id="user-role-area">
      <PageHeader
        title="用户管理"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[{ label: '新增用户', icon: <PlusOutlined />, type: 'primary', onClick: handleCreate }]}
      />

      {/* 功能：错误提示——加载失败时显示重试 */}
      {error && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={() => fetchUsers(pagination.current, pagination.pageSize)}>重试</Button>
        </div>
      )}

      {/* 功能：空状态——无用户数据时显示引导 */}
      {isEmpty && (
        <EmptyState
          image={<UserOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有任何系统用户"
          description="为需要使用系统的人创建登录账号"
          primaryAction={{ label: '新增用户', onClick: handleCreate }}
        />
      )}

      {/* 功能：用户数据表格——分页展示，每行有分配角色按钮 */}
      {!isEmpty && (
        <Card id="user-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={data}
            rowKey="userId"
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
            scroll={{ x: 800 }}
          />
        </Card>
      )}

      {/* 功能：新增用户弹窗——选择员工+填写用户名+初始密码 */}
      <Modal
        title="新增用户"
        open={createModalVisible}
        onOk={handleCreateSubmit}
        onCancel={() => setCreateModalVisible(false)}
        confirmLoading={submitting}
        okText="保存"
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
              placeholder="选择员工"
              showSearch
              filterOption={(input, option) =>
                (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
              options={employees}
            />
          </Form.Item>
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input placeholder="登录用户名" maxLength={50} />
          </Form.Item>
          <Form.Item
            name="password"
            label="初始密码"
            rules={[{ required: true, message: '请输入初始密码' }]}
          >
            <Input.Password placeholder="初始密码" maxLength={100} />
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
