{/* 模块用途：RoleAssignmentPage——项目角色分配页，表格+新增分配弹窗+标记PD负责人+移除分配 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Select/Tag */}
{/* 修改注意：角色下拉来自/project-roles?isActive=true，员工搜索来自/employees，标记PD仅对PD角色显示 */}
import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useParams } from 'react-router-dom';
import {
  Table, Button, Tag, Space, Modal, Form, Select, message, Card, Spin, Result,
} from 'antd';
import {
  PlusOutlined, LinkOutlined, StarOutlined, DeleteOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showDeleteConfirm, showConflictWarning } from '../../components/ConfirmModal';
import client from '../../api/client';

function RoleAssignmentPage() {
  const { id: projectCode } = useParams();

  const [assignments, setAssignments] = useState([]);
  const [roles, setRoles] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [modalVisible, setModalVisible] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [employees, setEmployees] = useState([]);
  const [employeeSearching, setEmployeeSearching] = useState(false);
  const [form] = Form.useForm();
  const mountedRef = useRef(true);
  const searchTimerRef = useRef(null);

  // 功能：从角色列表中解析角色名称
  const getRoleName = (roleCode) => {
    const role = roles.find((r) => r.roleCode === roleCode);
    return role ? role.roleName : roleCode;
  };

  // 功能：获取项目的角色分配列表——GET /api/v1/projects/{projectCode}/assignments
  const fetchAssignments = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get(`/projects/${projectCode}/assignments`);
      if (mountedRef.current) {
        setAssignments(Array.isArray(res.data) ? res.data : []);
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
  }, [projectCode]);

  // 功能：获取启用的角色列表——用于下拉选项和角色名称解析
  const fetchRoles = useCallback(async () => {
    try {
      const res = await client.get('/project-roles', { params: { isActive: true } });
      if (mountedRef.current) {
        setRoles(Array.isArray(res.data) ? res.data : []);
      }
    } catch (_err) {
      // 角色列表加载失败不影响主流程，仅按角色编码显示
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    fetchAssignments();
    fetchRoles();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 功能：搜索员工——300ms防抖，keyword为空时清空列表
  const handleEmployeeSearch = useMemo(() => {
    const doSearch = async (keyword) => {
      if (!keyword || keyword.trim().length === 0) {
        setEmployees([]);
        return;
      }
      setEmployeeSearching(true);
      try {
        const res = await client.get('/employees', { params: { keyword: keyword.trim(), size: 20 } });
        if (mountedRef.current) {
          setEmployees(res.data?.list || []);
        }
      } catch (_err) {
        // 搜索失败静默处理
      } finally {
        if (mountedRef.current) {
          setEmployeeSearching(false);
        }
      }
    };
    return (keyword) => {
      if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
      if (!keyword || keyword.trim().length === 0) {
        setEmployees([]);
        return;
      }
      setEmployeeSearching(true);
      searchTimerRef.current = setTimeout(() => doSearch(keyword), 300);
    };
  }, []);

  // 功能：打开新增分配弹窗
  const handleAdd = () => {
    form.resetFields();
    setEmployees([]);
    setModalVisible(true);
  };

  // 功能：提交分配——POST /api/v1/projects/{projectCode}/assignments
  const handleAssignSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await client.post(`/projects/${projectCode}/assignments`, {
        roleCode: values.roleCode,
        employeeId: values.employeeId,
      });
      message.success({ content: '分配成功', duration: 3 });
      setModalVisible(false);
      fetchAssignments();
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

  // 功能：标记为PD负责人——PUT /api/v1/projects/{projectCode}/assignments/{id}/toggle-primary-pd
  const handleMarkPd = async (assignment) => {
    try {
      await client.put(`/projects/${projectCode}/assignments/${assignment.id}/toggle-primary-pd`);
      message.success({ content: `已将 ${assignment.employeeName} 标记为PD负责人`, duration: 3 });
      fetchAssignments();
    } catch (err) {
      if (err?.code === 409) {
        showConflictWarning('其他用户', '几');
      } else if (err?.message) {
        message.error({ content: err.message });
      }
    }
  };

  // 功能：移除分配——DELETE /api/v1/projects/{projectCode}/assignments/{id}，二次确认
  const handleRemove = (assignment) => {
    showDeleteConfirm(async () => {
      try {
        await client.delete(`/projects/${projectCode}/assignments/${assignment.id}`);
        message.success({ content: '已移除', duration: 3 });
        fetchAssignments();
      } catch (err) {
        message.error({ content: err?.message || '移除失败' });
      }
    }, `${assignment.employeeName}（${assignment.projectRoleCode}）`);
  };

  const roleOptions = roles.map((r) => ({
    label: `${r.roleCode} — ${r.roleName}`,
    value: r.roleCode,
  }));

  const employeeOptions = employees.map((e) => ({
    label: `${e.employeeId} — ${e.name}`,
    value: e.employeeId,
  }));

  // 功能：表格列——角色编码/名称/员工工号/姓名/PD标记/操作
  const columns = [
    { title: '角色编码', dataIndex: 'projectRoleCode', key: 'projectRoleCode', width: 120 },
    {
      title: '角色名称', dataIndex: 'projectRoleCode', key: 'roleName', width: 140,
      render: (code) => getRoleName(code),
    },
    { title: '员工工号', dataIndex: 'employeeId', key: 'employeeId', width: 120 },
    { title: '员工姓名', dataIndex: 'employeeName', key: 'employeeName', width: 120 },
    {
      title: 'PD', dataIndex: 'isPrimaryPd', key: 'isPrimaryPd', width: 80,
      render: (v) => v ? <Tag color="blue">主PD</Tag> : null,
    },
    {
      title: '操作', key: 'action', width: 160,
      render: (_, record) => (
        <Space size="small">
          {record.projectRoleCode === 'PD' && !record.isPrimaryPd && (
            <Button type="link" size="small" icon={<StarOutlined />} onClick={() => handleMarkPd(record)}>
              标记PD
            </Button>
          )}
          <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => handleRemove(record)}>
            移除
          </Button>
        </Space>
      ),
    },
  ];

  const isEmpty = !loading && !error && assignments.length === 0;

  // 功能：加载中——居中Spin
  if (loading && assignments.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large">
          <div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div>
        </Spin>
      </div>
    );
  }

  // 功能：加载失败——错误结果+重试
  if (error && assignments.length === 0) {
    return (
      <Result
        status="error"
        title="加载失败"
        subTitle={error}
        extra={<Button type="primary" onClick={fetchAssignments}>重试</Button>}
      />
    );
  }

  return (
    <div id="role-assignment-area">
      <PageHeader
        title={`角色分配 — ${projectCode}`}
        breadcrumb={[
          { title: '首页', path: '/dashboard' },
          { title: '项目管理', path: '/project/list' },
        ]}
        actions={[{ label: '新增分配', icon: <PlusOutlined />, type: 'primary', onClick: handleAdd }]}
      />

      {/* 功能：网络错误横幅——已有数据时显示可关闭 */}
      {error && assignments.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchAssignments}>重试</Button>
        </div>
      )}

      {/* 功能：空状态——无分配时显示引导 */}
      {isEmpty && (
        <EmptyState
          image={<LinkOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="该项目还没有角色分配"
          description="为项目分配角色人员（如PDL、PQL、Launch），PD角色可标记为项目负责人"
          primaryAction={{ label: '新增分配', onClick: handleAdd }}
        />
      )}

      {/* 功能：分配数据表格 */}
      {!isEmpty && (
        <Card id="role-assignment-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={assignments}
            rowKey="id"
            loading={loading}
            size="middle"
            rowClassName={(_, index) => index % 2 === 1 ? 'table-row-striped' : ''}
            pagination={false}
            scroll={{ x: 740 }}
          />
        </Card>
      )}

      {/* 功能：新增分配弹窗——角色下拉+员工搜索Select */}
      <Modal
        title="分配角色"
        open={modalVisible}
        onOk={handleAssignSubmit}
        onCancel={() => setModalVisible(false)}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        destroyOnHidden
        width={480}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="roleCode" label="项目角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select
              placeholder="选择角色"
              options={roleOptions}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="employeeId" label="员工" rules={[{ required: true, message: '请选择员工' }]}>
            <Select
              placeholder="搜索员工姓名或工号"
              showSearch
              filterOption={false}
              onSearch={handleEmployeeSearch}
              options={employeeOptions}
              loading={employeeSearching}
              notFoundContent={employeeSearching ? '搜索中…' : '请输入关键词搜索'}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default RoleAssignmentPage;
