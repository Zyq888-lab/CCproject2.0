{/* 模块用途：PositionConfigPage——岗位考核配置页，表格+筛选+新增/编辑弹窗+考核人角色子管理 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Select/InputNumber/Switch */}
{/* 修改注意：权重在前端为百分制整数(0-100)，GET返回小数需×100显示，POST/PUT直接发送整数 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Tag, Space, Modal, Form, Input, Select, InputNumber, Switch, message, Card, Spin, Result, Tooltip,
} from 'antd';
import {
  PlusOutlined, SettingOutlined, EditOutlined, DeleteOutlined, UserOutlined, ReloadOutlined, TagsOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showDeleteConfirm, showConflictWarning } from '../../components/ConfirmModal';
import client from '../../api/client';
import useCategories, { refreshCategories } from '../../hooks/useCategories';

const FUNC_MODE_OPTIONS = [
  { label: '直接上级评分', value: 'DIRECT_LEADER' },
  { label: '组织负责人评分', value: 'ORG_LEADER' },
];

const FUNC_MODE_LABEL = { 'DIRECT_LEADER': '直接上级评分', 'ORG_LEADER': '组织负责人评分' };

function PositionConfigPage() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [filters, setFilters] = useState({ category: '', position: '' });
  const [modalVisible, setModalVisible] = useState(false);
  const [editingConfig, setEditingConfig] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [projectRoles, setProjectRoles] = useState([]);
  const [form] = Form.useForm();

  const [assessorModalVisible, setAssessorModalVisible] = useState(false);
  const [assessorConfig, setAssessorConfig] = useState(null);
  const [assessorRoles, setAssessorRoles] = useState([]);
  const [assessorLoading, setAssessorLoading] = useState(false);
  const [assessorSubmitting, setAssessorSubmitting] = useState(false);
  const [assessorForm] = Form.useForm();

  // --- 岗位分类管理 ---
  const [categoryData, setCategoryData] = useState([]);
  const [categoryLoading, setCategoryLoading] = useState(false);
  const [categoryModalVisible, setCategoryModalVisible] = useState(false);
  const [categoryEditing, setCategoryEditing] = useState(null);
  const [categoryFormVisible, setCategoryFormVisible] = useState(false);
  const [categorySubmitting, setCategorySubmitting] = useState(false);
  const [categoryDeletingId, setCategoryDeletingId] = useState(null);
  const [categoryForm] = Form.useForm();

  const mountedRef = useRef(true);
  const [categoryOptions] = useCategories();

  // 功能：分页获取岗位配置——支持 category 精确匹配 + position 模糊搜索
  const fetchConfigs = useCallback(async (page, size, filterParams) => {
    setLoading(true);
    setError(null);
    try {
      const params = { page, size };
      if (filterParams?.category) params.category = filterParams.category;
      if (filterParams?.position) params.position = filterParams.position;
      const res = await client.get('/position-configs', { params });
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
  }, []);

  // 功能：获取启用的项目角色——用于默认项目角色下拉、考核人角色下拉和名称解析
  const fetchProjectRoles = useCallback(async () => {
    try {
      const res = await client.get('/project-roles', { params: { isActive: true, size: 999 } });
      if (mountedRef.current) setProjectRoles(res.data?.list || []);
    } catch (_) { /* 非关键数据，失败不影响主流程 */ }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    fetchConfigs(pagination.current, pagination.pageSize, filters);
    fetchProjectRoles();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = () => fetchConfigs(1, pagination.pageSize, filters);
  const handleReset = () => {
    const empty = { category: '', position: '' };
    setFilters(empty);
    fetchConfigs(1, pagination.pageSize, empty);
  };

  const handleTableChange = (pag) => {
    const newPage = pag.current;
    const newSize = pag.pageSize;
    setPagination((prev) => ({ ...prev, current: newPage, pageSize: newSize }));
    fetchConfigs(newPage, newSize, filters);
  };

  // 功能：打开新增弹窗——表单默认值：isProjectBased=true, projectWeight=70, funcWeight=30
  const handleCreate = () => {
    setEditingConfig(null);
    form.resetFields();
    form.setFieldsValue({ isProjectBased: true, projectWeight: 70, funcWeight: 30 });
    setModalVisible(true);
  };

  // 功能：打开编辑弹窗——回填所有字段，权重从小数转为百分制整数
  const handleEdit = (record) => {
    setEditingConfig(record);
    form.resetFields();
    form.setFieldsValue({
      category: record.category,
      position: record.position,
      isProjectBased: record.isProjectBased,
      defaultProjectRole: record.defaultProjectRole || undefined,
      funcAssessMode: record.funcAssessMode || undefined,
      projectWeight: record.projectWeight != null ? Math.round(record.projectWeight * 100) : 70,
      funcWeight: record.funcWeight != null ? Math.round(record.funcWeight * 100) : 30,
    });
    setAssessorConfig(record);
    fetchAssessorRoles(record.id);
    setModalVisible(true);
  };

  // 功能：提交新增/编辑——权重以整数发送，编辑时携带version用于乐观锁
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const payload = {
        category: values.category,
        position: values.position,
        isProjectBased: values.isProjectBased,
        defaultProjectRole: values.defaultProjectRole || null,
        funcAssessMode: values.funcAssessMode || null,
        projectWeight: values.projectWeight,
        funcWeight: values.funcWeight,
      };
      if (editingConfig) {
        payload.version = editingConfig.version;
        await client.put(`/position-configs/${editingConfig.id}`, payload);
        message.success({ content: '保存成功', duration: 3 });
      } else {
        await client.post('/position-configs', payload);
        message.success({ content: '创建成功', duration: 3 });
      }
      setModalVisible(false);
      fetchConfigs(pagination.current, pagination.pageSize, filters);
    } catch (err) {
      if (err?.code === 409 && err?.message?.includes('已被他人修改')) {
        showConflictWarning('其他用户', '几');
      } else if (err?.message) {
        message.error({ content: err.message });
      }
    } finally {
      setSubmitting(false);
    }
  };

  // 功能：删除确认——后端逻辑删除，引用检查
  const handleDelete = (record) => {
    showDeleteConfirm(async () => {
      try {
        await client.delete(`/position-configs/${record.id}`);
        message.success({ content: '已删除', duration: 3 });
        fetchConfigs(pagination.current, pagination.pageSize, filters);
      } catch (err) {
        message.error({ content: err?.message || '删除失败' });
      }
    }, `${record.category} — ${record.position}`);
  };

  // --- 考核人角色子管理 ---

  // 功能：获取指定配置的考核人角色列表
  const fetchAssessorRoles = async (configId) => {
    setAssessorLoading(true);
    try {
      const res = await client.get(`/position-configs/${configId}/assessor-roles`);
      if (mountedRef.current) setAssessorRoles(Array.isArray(res.data) ? res.data : []);
    } catch (err) {
      message.error({ content: err?.message || '加载考核人失败' });
    } finally {
      if (mountedRef.current) setAssessorLoading(false);
    }
  };

  // 功能：打开考核人管理弹窗
  const handleManageAssessors = (record) => {
    setAssessorConfig(record);
    assessorForm.resetFields();
    fetchAssessorRoles(record.id);
    setAssessorModalVisible(true);
  };

  // 功能：添加考核人角色——POST后刷新列表
  const handleAddAssessor = async () => {
    try {
      const values = await assessorForm.validateFields();
      setAssessorSubmitting(true);
      await client.post(`/position-configs/${assessorConfig.id}/assessor-roles`, { roleCode: values.roleCode });
      message.success({ content: '已添加', duration: 2 });
      assessorForm.resetFields();
      fetchAssessorRoles(assessorConfig.id);
    } catch (err) {
      if (err?.code === 409) {
        message.warning({ content: '该角色已存在' });
      } else if (err?.message) {
        message.error({ content: err.message });
      }
    } finally {
      setAssessorSubmitting(false);
    }
  };

  // 功能：移除考核人角色——DELETE后刷新列表
  const handleRemoveAssessor = async (assessorRoleId) => {
    try {
      await client.delete(`/position-configs/${assessorConfig.id}/assessor-roles/${assessorRoleId}`);
      message.success({ content: '已移除', duration: 2 });
      fetchAssessorRoles(assessorConfig.id);
    } catch (err) {
      message.error({ content: err?.message || '移除失败' });
    }
  };

  const getRoleName = (code) => {
    const r = projectRoles.find((x) => x.roleCode === code);
    return r ? r.roleName : code;
  };

  const excludedCodes = new Set([...assessorRoles.map((r) => r.roleCode), assessorConfig?.defaultProjectRole].filter(Boolean));
  const assessorRoleOptions = projectRoles
    .filter((r) => !excludedCodes.has(r.roleCode))
    .map((r) => ({ label: `${r.roleCode} — ${r.roleName}`, value: r.roleCode }));

  const projectRoleOptions = projectRoles.map((r) => ({ label: `${r.roleCode} — ${r.roleName}`, value: r.roleCode }));

  // --- 岗位分类管理逻辑 ---
  const fetchCategories = async () => {
    setCategoryLoading(true);
    try {
      const res = await client.get('/position-categories/list');
      if (mountedRef.current) setCategoryData(Array.isArray(res.data) ? res.data : []);
    } catch (err) {
      message.error({ content: err?.message || '加载分类失败' });
    } finally {
      if (mountedRef.current) setCategoryLoading(false);
    }
  };

  const handleCategoryCreate = () => {
    setCategoryEditing(null);
    categoryForm.resetFields();
    categoryForm.setFieldsValue({ sortOrder: 0 });
    setCategoryFormVisible(true);
  };

  const handleCategoryEdit = (record) => {
    setCategoryEditing(record);
    categoryForm.resetFields();
    categoryForm.setFieldsValue({ name: record.name, sortOrder: record.sortOrder ?? 0 });
    setCategoryFormVisible(true);
  };

  const handleCategorySubmit = async () => {
    try {
      const values = await categoryForm.validateFields();
      setCategorySubmitting(true);
      if (!categoryEditing) {
        await client.post('/position-categories', values);
        message.success({ content: '创建成功', duration: 3 });
      } else {
        await client.put(`/position-categories/${categoryEditing.id}`, values);
        message.success({ content: '保存成功', duration: 3 });
      }
      refreshCategories();
      fetchCategories();
      setCategoryFormVisible(false);
      setCategoryEditing(null);
    } catch (err) {
      if (err?.message) message.error({ content: err.message });
    } finally {
      setCategorySubmitting(false);
    }
  };

  const handleCategoryDelete = (record) => {
    showDeleteConfirm(async () => {
      setCategoryDeletingId(record.id);
      try {
        await client.delete(`/position-categories/${record.id}`);
        message.success({ content: '已删除', duration: 3 });
        refreshCategories();
        fetchCategories();
      } catch (err) {
        if (err?.code === 409) {
          message.error({ content: err.message || '该分类被引用，无法删除' });
        } else {
          message.error({ content: err?.message || '删除失败' });
        }
      } finally {
        if (mountedRef.current) setCategoryDeletingId(null);
      }
    }, `岗位分类「${record.name}」`);
  };

  const handleOpenCategoryModal = () => {
    setCategoryModalVisible(true);
    fetchCategories();
  };

  const categoryColumns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    { title: '名称', dataIndex: 'name', key: 'name', width: 200 },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 80 },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-' },
    { title: '操作', key: 'action', width: 140,
      render: (_, record) => (
        <Space size="small">
          <Button type="link" size="small" icon={<EditOutlined />}
            onClick={() => handleCategoryEdit(record)}>编辑</Button>
          <Button type="link" size="small" danger icon={<DeleteOutlined />}
            loading={categoryDeletingId === record.id}
            onClick={() => handleCategoryDelete(record)}>删除</Button>
        </Space>
      ),
    },
  ];

  const columns = [
    { title: '岗位分类', dataIndex: 'category', key: 'category', width: 120 },
    { title: '岗位名称', dataIndex: 'position', key: 'position', width: 140 },
    { title: '项目制', dataIndex: 'isProjectBased', key: 'isProjectBased', width: 80,
      render: (v) => v ? <Tag color="blue">是</Tag> : <Tag>否</Tag> },
    { title: '项目权重', dataIndex: 'projectWeight', key: 'projectWeight', width: 90,
      render: (v) => v != null ? `${Math.round(v * 100)}%` : '-' },
    { title: '职能权重', dataIndex: 'funcWeight', key: 'funcWeight', width: 90,
      render: (v) => v != null ? `${Math.round(v * 100)}%` : '-' },
    { title: '职能考核', dataIndex: 'funcAssessMode', key: 'funcAssessMode', width: 130,
      render: (v) => FUNC_MODE_LABEL[v] || v || '-' },
    { title: '默认角色', dataIndex: 'defaultProjectRole', key: 'defaultProjectRole', width: 100,
      render: (v) => v || '-' },
    { title: '考核人角色', dataIndex: 'assessorRoleNames', key: 'assessorRoleNames', width: 200,
      render: (names) => names && names.length > 0
        ? names.map((name) => <Tag key={name} color="blue">{name}</Tag>)
        : <span style={{ color: '#BFBFBF' }}>—</span> },
    { title: '操作', key: 'action', width: 160,
      render: (_, record) => (
        <Space size="small">
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)}>编辑</Button>
          <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(record)}>删除</Button>
        </Space>
      ),
    },
  ];

  const isEmpty = !loading && !error && data.length === 0 && !filters.category && !filters.position;

  if (loading && data.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large"><div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div></Spin>
      </div>
    );
  }

  if (error && data.length === 0) {
    return (
      <Result status="error" title="加载失败" subTitle={error}
        extra={<Button type="primary" onClick={() => fetchConfigs(pagination.current, pagination.pageSize, filters)}>重试</Button>}
      />
    );
  }

  return (
    <div id="position-config-area">
      <PageHeader
        title="岗位配置"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[{ label: '新增配置', icon: <PlusOutlined />, type: 'primary', onClick: handleCreate }]}
      />

      {/* 功能：筛选栏——岗位分类下拉+岗位名称搜索 */}
      <Card id="position-search-bar" style={{ marginBottom: 16, borderRadius: 8 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
          <Space wrap size="middle">
            <Select
              placeholder="岗位分类"
              value={filters.category || undefined}
              onChange={(v) => setFilters((f) => ({ ...f, category: v || '' }))}
              allowClear
              style={{ width: 140 }}
              options={categoryOptions}
            />
            <Input
              placeholder="岗位名称"
              value={filters.position}
              onChange={(e) => setFilters((f) => ({ ...f, position: e.target.value }))}
              onPressEnter={handleSearch}
              style={{ width: 140 }}
              allowClear
            />
            <Button type="primary" onClick={handleSearch}>搜索</Button>
            <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
          </Space>
          <Button icon={<TagsOutlined />} onClick={handleOpenCategoryModal}>管理分类</Button>
        </div>
      </Card>

      {error && data.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={() => fetchConfigs(pagination.current, pagination.pageSize, filters)}>重试</Button>
        </div>
      )}

      {isEmpty && (
        <EmptyState
          image={<SettingOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有岗位考核配置"
          description="为每个岗位设置项目/职能考核权重及考核人角色"
          primaryAction={{ label: '新增配置', onClick: handleCreate }}
        />
      )}

      {!isEmpty && (
        <Card id="position-table-card" style={{ borderRadius: 8 }}>
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
            scroll={{ x: 1160 }}
          />
        </Card>
      )}

      {/* 功能：新增/编辑弹窗——岗位分类/名称/项目制/默认角色/双权重/职能考核模式 */}
      <Modal
        title={editingConfig ? '编辑岗位配置' : '新增岗位配置'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        width={520}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="category" label="岗位分类" rules={[{ required: true, message: '请选择岗位分类' }]}>
            <Select placeholder="选择岗位分类" options={categoryOptions} />
          </Form.Item>
          <Form.Item name="position" label="岗位名称" rules={[{ required: true, message: '请输入岗位名称' }]}>
            <Input placeholder="如 整椅研发岗" maxLength={50} />
          </Form.Item>
          <Form.Item name="isProjectBased" label="是否纳入项目制考核" valuePropName="checked">
            <Switch checkedChildren="是" unCheckedChildren="否" />
          </Form.Item>

          {editingConfig && (
            <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: 12, marginBottom: 16 }}>
              <div style={{ fontWeight: 500, marginBottom: 8 }}>项目考核人角色</div>
              {assessorLoading ? <Spin size="small" /> : (
                assessorRoles.length > 0 ? (
                  <Table columns={[
                    { title: '角色编码', dataIndex: 'roleCode', key: 'roleCode', width: 120 },
                    { title: '角色名称', dataIndex: 'roleName', key: 'roleName', width: 120 },
                    { title: '', key: 'action', width: 60,
                      render: (_, r) => r.roleCode === editingConfig?.defaultProjectRole
                        ? <Tooltip title="默认角色不可移除"><Button type="link" size="small" danger disabled>移除</Button></Tooltip>
                        : <Button type="link" size="small" danger onClick={() => handleRemoveAssessor(r.id)}>移除</Button>
                    },
                  ]} dataSource={assessorRoles} rowKey="id" size="small" pagination={false} />
                ) : <span style={{ color: '#BFBFBF' }}>暂无</span>
              )}
              <Space.Compact style={{ width: '100%', marginTop: 8 }}>
                <Form form={assessorForm} layout="inline">
                  <Form.Item name="roleCode" rules={[{ required: true, message: '请选择' }]} style={{ marginBottom: 0 }}>
                    <Select placeholder="添加考核人角色" style={{ width: 200 }} options={assessorRoleOptions} showSearch optionFilterProp="label" />
                  </Form.Item>
                </Form>
                <Button type="primary" size="small" onClick={handleAddAssessor} loading={assessorSubmitting}>添加</Button>
              </Space.Compact>
            </div>
          )}

          <Form.Item name="defaultProjectRole" label="默认项目角色">
            <Select placeholder="选择默认角色（可选）" options={projectRoleOptions} showSearch optionFilterProp="label" allowClear />
          </Form.Item>
          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item name="projectWeight" label="项目考核权重(%)" rules={[{ required: true, message: '请输入' }]} style={{ flex: 1 }}>
              <InputNumber min={0} max={100} precision={0} style={{ width: '100%' }} placeholder="如 70" />
            </Form.Item>
            <Form.Item name="funcWeight" label="职能考核权重(%)" rules={[{ required: true, message: '请输入' }, ({ getFieldValue }) => ({
              validator(_, value) {
                const pw = getFieldValue('projectWeight');
                if (value != null && pw != null && pw + value !== 100) {
                  return Promise.reject(new Error(`项目权重(${pw}) + 职能权重(${value}) 应等于 100`));
                }
                return Promise.resolve();
              },
            })]} style={{ flex: 1 }}>
              <InputNumber min={0} max={100} precision={0} style={{ width: '100%' }} placeholder="如 30" />
            </Form.Item>
          </div>
          <Form.Item name="funcAssessMode" label="职能考核模式">
            <Select placeholder="选择考核模式" options={FUNC_MODE_OPTIONS} allowClear />
          </Form.Item>
        </Form>
      </Modal>

      {/* 功能：岗位分类管理弹窗——内嵌表格+新增/编辑子弹窗 */}
      <Modal
        title="岗位分类管理"
        open={categoryModalVisible}
        onCancel={() => { setCategoryModalVisible(false); setCategoryEditing(null); setCategoryFormVisible(false); }}
        footer={null}
        width={700}
      >
        <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ color: '#8C8C8C', fontSize: 13 }}>共 {categoryData.length} 条记录</span>
          <Button type="primary" icon={<PlusOutlined />} size="small" onClick={handleCategoryCreate}>新增分类</Button>
        </div>
        <Table
          columns={categoryColumns}
          dataSource={categoryData}
          rowKey="id"
          loading={categoryLoading}
          size="small"
          pagination={categoryData.length > 10 ? { defaultPageSize: 10, showSizeChanger: true, pageSizeOptions: [10, 20, 50] } : false}
        />

        <Modal
          title={categoryEditing ? '编辑岗位分类' : '新增岗位分类'}
          open={categoryFormVisible}
          onOk={handleCategorySubmit}
          onCancel={() => { setCategoryEditing(null); setCategoryFormVisible(false); }}
          confirmLoading={categorySubmitting}
          okText="保存"
          cancelText="取消"
          width={440}
        >
          <Form form={categoryForm} layout="vertical" style={{ marginTop: 16 }}>
            <Form.Item name="name" label="分类名称" rules={[{ required: true, message: '请输入分类名称' }]}>
              <Input placeholder="如 研发技术类" maxLength={64} />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序" extra="数字越小越靠前">
              <InputNumber min={0} max={999} precision={0} style={{ width: '100%' }} placeholder="0" />
            </Form.Item>
          </Form>
        </Modal>
      </Modal>
    </div>
  );
}

export default PositionConfigPage;
