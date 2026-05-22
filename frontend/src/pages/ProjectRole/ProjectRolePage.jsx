{/* 模块用途：ProjectRolePage——项目角色管理页，卡片列表+新增/编辑弹窗+启用停用+删除 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Card/Modal/Form/Input/Switch/Row/Col */}
{/* 修改注意：roleCode提交后不可修改，编辑时禁用角色编码输入，切换启用/停用调用toggle端点 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Card, Button, Tag, Space, Modal, Form, Input, Switch, message, Row, Col, Spin, Result,
} from 'antd';
import {
  PlusOutlined, AimOutlined, EditOutlined, StopOutlined, CheckCircleOutlined, DeleteOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showDeleteConfirm } from '../../components/ConfirmModal';
import client from '../../api/client';

const STATUS_COLOR_MAP = { true: 'green', false: 'default' };
const STATUS_LABEL_MAP = { true: '已启用', false: '已停用' };

function ProjectRolePage() {
  const [roles, setRoles] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingRole, setEditingRole] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const mountedRef = useRef(true);

  // 功能：获取所有项目角色——GET /api/v1/project-roles，不过滤isActive以查看全部
  const fetchRoles = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/project-roles');
      if (mountedRef.current) {
        setRoles(Array.isArray(res.data) ? res.data : []);
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
    fetchRoles();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 功能：打开新增弹窗——表单初始值为空，isActive默认true
  const handleCreate = () => {
    setEditingRole(null);
    form.resetFields();
    form.setFieldsValue({ isActive: true });
    setModalVisible(true);
  };

  // 功能：打开编辑弹窗——回填角色信息
  const handleEdit = (role) => {
    setEditingRole(role);
    form.resetFields();
    form.setFieldsValue({
      roleCode: role.roleCode,
      roleName: role.roleName,
      description: role.description || '',
      isActive: role.isActive,
    });
    setModalVisible(true);
  };

  // 功能：提交新增/编辑——POST或PUT，编辑时roleCode禁用
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (editingRole) {
        await client.put(`/project-roles/${editingRole.roleCode}`, {
          roleName: values.roleName,
          description: values.description,
          isActive: values.isActive,
        });
        message.success({ content: '角色已更新', duration: 3 });
      } else {
        await client.post('/project-roles', {
          roleCode: values.roleCode,
          roleName: values.roleName,
          description: values.description,
          isActive: values.isActive,
        });
        message.success({ content: '角色创建成功', duration: 3 });
      }
      setModalVisible(false);
      fetchRoles();
    } catch (err) {
      if (err?.message) {
        message.error({ content: err.message });
      }
    } finally {
      setSubmitting(false);
    }
  };

  // 功能：切换启用/停用——PUT /api/v1/project-roles/{roleCode}/toggle
  const handleToggle = async (role) => {
    try {
      await client.put(`/project-roles/${role.roleCode}/toggle`);
      message.success({ content: `角色"${role.roleCode}"已${role.isActive ? '停用' : '启用'}`, duration: 3 });
      fetchRoles();
    } catch (err) {
      if (err?.message) {
        message.error({ content: err.message });
      }
    }
  };

  // 功能：删除角色——DELETE，被引用时后端拒绝
  const handleDelete = (role) => {
    showDeleteConfirm(async () => {
      try {
        await client.delete(`/project-roles/${role.roleCode}`);
        message.success({ content: '已删除', duration: 3 });
        fetchRoles();
      } catch (err) {
        message.error({ content: err?.message || '删除失败' });
      }
    }, `角色 ${role.roleCode}`);
  };

  const isEmpty = !loading && !error && roles.length === 0;

  // 功能：加载中——居中Spin
  if (loading && roles.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large">
          <div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div>
        </Spin>
      </div>
    );
  }

  // 功能：加载失败——错误结果+重试
  if (error && roles.length === 0) {
    return (
      <Result
        status="error"
        title="加载失败"
        subTitle={error}
        extra={<Button type="primary" onClick={fetchRoles}>重试</Button>}
      />
    );
  }

  return (
    <div id="project-role-area">
      <PageHeader
        title="项目角色"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[{ label: '新增角色', icon: <PlusOutlined />, type: 'primary', onClick: handleCreate }]}
      />

      {/* 功能：网络错误横幅——数据已加载时显示可关闭提示 */}
      {error && roles.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchRoles}>重试</Button>
        </div>
      )}

      {/* 功能：空状态——无角色时显示引导 */}
      {isEmpty && (
        <EmptyState
          image={<AimOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有任何项目角色"
          description="先创建角色（如PDL/PQL/Launch），后续可随时增删"
          primaryAction={{ label: '新增角色', onClick: handleCreate }}
        />
      )}

      {/* 功能：角色卡片网格——3列响应式，每张卡片展示一个角色 */}
      {!isEmpty && (
        <div id="project-role-card-grid">
        <Row gutter={[16, 16]}>
          {roles.map((role) => (
            <Col key={role.roleCode} xs={24} sm={12} lg={8}>
              <Card
                hoverable
                style={{ borderRadius: 8, height: '100%' }}
                title={
                  <Space>
                    <AimOutlined style={{ color: role.isActive ? '#1890FF' : '#BFBFBF' }} />
                    <strong style={{ fontSize: 16 }}>{role.roleCode}</strong>
                    <Tag color={STATUS_COLOR_MAP[role.isActive]}>
                      {STATUS_LABEL_MAP[role.isActive]}
                    </Tag>
                  </Space>
                }
                actions={[
                  <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(role)}>
                    编辑
                  </Button>,
                  <Button
                    type="link"
                    size="small"
                    icon={role.isActive ? <StopOutlined /> : <CheckCircleOutlined />}
                    onClick={() => handleToggle(role)}
                  >
                    {role.isActive ? '停用' : '启用'}
                  </Button>,
                  <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(role)}>
                    删除
                  </Button>,
                ]}
              >
                <div style={{ marginBottom: 4 }}>
                  <span style={{ color: '#8C8C8C', fontSize: 12 }}>角色名称：</span>
                  <span>{role.roleName}</span>
                </div>
                {role.description && (
                  <div>
                    <span style={{ color: '#8C8C8C', fontSize: 12 }}>描述：</span>
                    <span style={{ fontSize: 13, color: '#595959' }}>{role.description}</span>
                  </div>
                )}
              </Card>
            </Col>
          ))}
        </Row>
        </div>
      )}

      {/* 功能：新增/编辑弹窗——编辑时roleCode字段禁用 */}
      <Modal
        title={editingRole ? '编辑角色' : '新增角色'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        destroyOnHidden
        width={480}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="roleCode"
            label="角色编码"
            rules={[{ required: true, message: '请输入角色编码' }]}
          >
            <Input
              disabled={!!editingRole}
              placeholder="如 PDL、PQL、Launch"
              maxLength={20}
            />
          </Form.Item>
          <Form.Item
            name="roleName"
            label="角色名称"
            rules={[{ required: true, message: '请输入角色名称' }]}
          >
            <Input placeholder="如 项目开发负责人" maxLength={50} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea placeholder="角色职责说明（可选）" maxLength={200} rows={3} />
          </Form.Item>
          <Form.Item name="isActive" label="启用状态" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default ProjectRolePage;
