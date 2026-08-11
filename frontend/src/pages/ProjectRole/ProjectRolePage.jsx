{/* 模块用途：ProjectRolePage——项目角色管理页，表格+搜索筛选+分页+新增编辑+批量导入 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Input/Switch/Select */}
{/* 修改注意：roleCode提交后不可修改，编辑时禁用角色编码输入 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Tag, Space, Modal, Form, Input, Switch, Select, message, Card, Upload,
} from 'antd';
import {
  PlusOutlined, AimOutlined, EditOutlined, StopOutlined,
  CheckCircleOutlined, DeleteOutlined, SearchOutlined, ReloadOutlined, DownloadOutlined, InboxOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showDeleteConfirm } from '../../components/ConfirmModal';
import client from '../../api/client';
import * as XLSX from 'xlsx';

const { Dragger } = Upload;

const STATUS_COLOR_MAP = { true: 'green', false: 'default' };
const STATUS_LABEL_MAP = { true: '已启用', false: '已停用' };
const IS_ACTIVE_OPTIONS = [
  { label: '全部', value: undefined },
  { label: '已启用', value: true },
  { label: '已停用', value: false },
];

const COLUMN_MAP = { '角色编码': 'roleCode', '角色名称': 'roleName', '描述': 'description', '是否启用': 'isActive' };

function ProjectRolePage() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [filters, setFilters] = useState({ roleCode: '', roleName: '', isActive: undefined });
  const [modalVisible, setModalVisible] = useState(false);
  const [importVisible, setImportVisible] = useState(false);
  const [editingRole, setEditingRole] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [importData, setImportData] = useState([]);
  const [importing, setImporting] = useState(false);
  const [form] = Form.useForm();
  const mountedRef = useRef(true);

  const fetchRoles = useCallback(async (page, size, filterParams) => {
    setLoading(true);
    setError(null);
    try {
      const params = { page, size };
      if (filterParams?.roleCode) params.roleCode = filterParams.roleCode;
      if (filterParams?.roleName) params.roleName = filterParams.roleName;
      if (filterParams?.isActive !== undefined) params.isActive = filterParams.isActive;
      const res = await client.get('/project-roles', { params });
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

  useEffect(() => {
    mountedRef.current = true;
    fetchRoles(1, 20, filters);
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = () => fetchRoles(1, pagination.pageSize, filters);
  const handleReset = () => {
    const empty = { roleCode: '', roleName: '', isActive: undefined };
    setFilters(empty);
    fetchRoles(1, pagination.pageSize, empty);
  };
  const handleTableChange = (pag) => {
    const np = pag.current, ns = pag.pageSize;
    setPagination((prev) => ({ ...prev, current: np, pageSize: ns }));
    fetchRoles(np, ns, filters);
  };

  const handleCreate = () => { setEditingRole(null); form.resetFields(); form.setFieldsValue({ isActive: true }); setModalVisible(true); };
  const handleEdit = (role) => { setEditingRole(role); form.setFieldsValue({ roleCode: role.roleCode, roleName: role.roleName, description: role.description || '', isActive: role.isActive }); setModalVisible(true); };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields(); setSubmitting(true);
      if (editingRole) {
        await client.put(`/project-roles/${editingRole.roleCode}`, { roleName: values.roleName, description: values.description, isActive: values.isActive });
        message.success({ content: '角色已更新', duration: 3 });
      } else {
        await client.post('/project-roles', values);
        message.success({ content: '角色创建成功', duration: 3 });
      }
      setModalVisible(false);
      fetchRoles(pagination.current, pagination.pageSize, filters);
    } catch (err) { if (err?.message) message.error({ content: err.message }); }
    finally { setSubmitting(false); }
  };

  const handleToggle = async (role) => {
    try {
      await client.put(`/project-roles/${role.roleCode}/toggle`);
      message.success({ content: `角色"${role.roleCode}"已${role.isActive ? '停用' : '启用'}`, duration: 3 });
      fetchRoles(pagination.current, pagination.pageSize, filters);
    } catch (err) { if (err?.message) message.error({ content: err.message }); }
  };

  const handleDelete = (role) => {
    showDeleteConfirm(async () => {
      try { await client.delete(`/project-roles/${role.roleCode}`); message.success({ content: '已删除', duration: 3 }); fetchRoles(pagination.current, pagination.pageSize, filters); }
      catch (err) { message.error({ content: err?.message || '删除失败' }); }
    }, `角色 ${role.roleCode}`);
  };

  // 批量导入
  const handleFileParse = (file) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const wb = XLSX.read(e.target.result, { type: 'array' });
        const sheet = wb.Sheets[wb.SheetNames[0]];
        const rows = XLSX.utils.sheet_to_json(sheet, { defval: '' }).map((row, idx) => {
          const mapped = { _key: idx };
          Object.entries(COLUMN_MAP).forEach(([col, field]) => { mapped[field] = String(row[col] || '').trim(); });
          mapped.isActive = mapped.isActive === '是' || mapped.isActive === 'true' || mapped.isActive === '启用';
          mapped._valid = !!mapped.roleCode && !!mapped.roleName;
          return mapped;
        });
        setImportData(rows);
        message.success({ content: `成功解析 ${rows.length} 条记录` });
      } catch (_) { message.error({ content: '文件解析失败' }); }
    };
    reader.readAsArrayBuffer(file);
    return false;
  };

  const handleImport = async () => {
    const valid = importData.filter(r => r._valid);
    if (!valid.length) { message.warning({ content: '无有效数据' }); return; }
    setImporting(true);
    try {
      const payload = valid.map(({ _key, _valid, isActive, ...rest }) => ({ ...rest, isActive }));
      const res = await client.post('/project-roles/import', payload);
      message.success({ content: `成功导入 ${res.data?.total || 0} 条记录`, duration: 3 });
      setImportVisible(false); setImportData([]);
      fetchRoles(pagination.current, pagination.pageSize, filters);
    } catch (err) { message.error({ content: err?.message || '导入失败' }); }
    finally { setImporting(false); }
  };

  const downloadTemplate = () => {
    const ws = XLSX.utils.json_to_sheet([{ '角色编码': 'PDL', '角色名称': '项目开发负责人', '描述': '说明', '是否启用': '是' }]);
    const wb = XLSX.utils.book_new(); XLSX.utils.book_append_sheet(wb, ws, '导入模板');
    XLSX.writeFile(wb, '项目角色导入模板.xlsx');
  };

  const columns = [
    { title: '角色编码', dataIndex: 'roleCode', key: 'roleCode', width: 140 },
    { title: '角色名称', dataIndex: 'roleName', key: 'roleName', width: 160 },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    { title: '是否启用', dataIndex: 'isActive', key: 'isActive', width: 100,
      render: (v) => <Tag color={STATUS_COLOR_MAP[v]}>{STATUS_LABEL_MAP[v]}</Tag> },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN') : '-' },
    {
      title: '操作', key: 'action', width: 240,
      render: (_, r) => (
        <Space size="small">
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(r)}>编辑</Button>
          <Button type="link" size="small" icon={r.isActive ? <StopOutlined /> : <CheckCircleOutlined />}
            onClick={() => handleToggle(r)}>{r.isActive ? '停用' : '启用'}</Button>
          <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(r)}>删除</Button>
        </Space>
      ),
    },
  ];

  const isEmpty = !loading && !error && data.length === 0 && !filters.roleCode && !filters.roleName && filters.isActive === undefined;

  return (
    <div id="project-role-area">
      <PageHeader title="项目角色" breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[
          { label: '新增角色', icon: <PlusOutlined />, type: 'primary', onClick: handleCreate },
          { label: '批量导入', icon: <DownloadOutlined />, onClick: () => setImportVisible(true) },
        ]} />

      <Card style={{ marginBottom: 16, borderRadius: 8 }}>
        <Space wrap size="middle">
          <Input placeholder="角色编码" value={filters.roleCode}
            onChange={(e) => setFilters((f) => ({ ...f, roleCode: e.target.value }))}
            allowClear style={{ width: 140 }} onPressEnter={handleSearch} />
          <Input placeholder="角色名称" value={filters.roleName}
            onChange={(e) => setFilters((f) => ({ ...f, roleName: e.target.value }))}
            allowClear style={{ width: 160 }} onPressEnter={handleSearch} />
          <Select placeholder="状态" value={filters.isActive}
            onChange={(v) => setFilters((f) => ({ ...f, isActive: v }))}
            allowClear style={{ width: 110 }} options={IS_ACTIVE_OPTIONS} />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>搜索</Button>
          <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
        </Space>
      </Card>

      {error && data.length === 0 && (
        <div style={{ marginBottom: 16, textAlign: 'center', color: '#FF4D4F' }}>{error}
          <Button type="link" onClick={() => fetchRoles(pagination.current, pagination.pageSize, filters)}>重试</Button></div>
      )}

      {isEmpty && (
        <EmptyState image={<AimOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有任何项目角色" description="先创建角色（如PDL/PQL/Launch），后续可随时增删"
          primaryAction={{ label: '新增角色', onClick: handleCreate }}
          secondaryAction={{ label: '批量导入', onClick: () => setImportVisible(true) }} />
      )}

      {!isEmpty && (
        <Card style={{ borderRadius: 8 }}>
          <Table columns={columns} dataSource={data} rowKey="roleCode" loading={loading} size="middle"
            onChange={handleTableChange}
            pagination={{ current: pagination.current, pageSize: pagination.pageSize, total: pagination.total,
              showSizeChanger: true, pageSizeOptions: [10, 20, 50],
              showTotal: (t, r) => `第 ${r[0]}-${r[1]} 条，共 ${t} 条` }} />
        </Card>
      )}

      <Modal title={editingRole ? '编辑角色' : '新增角色'} open={modalVisible} onOk={handleSubmit}
        onCancel={() => setModalVisible(false)} confirmLoading={submitting} okText="保存" cancelText="取消" width={480}>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="roleCode" label="角色编码" rules={[{ required: true, message: '请输入角色编码' }]}>
            <Input disabled={!!editingRole} placeholder="如 PDL、PQL" maxLength={20} /></Form.Item>
          <Form.Item name="roleName" label="角色名称" rules={[{ required: true, message: '请输入角色名称' }]}>
            <Input placeholder="如 项目开发负责人" maxLength={50} /></Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea placeholder="角色职责说明（可选）" maxLength={200} rows={2} /></Form.Item>
          <Form.Item name="isActive" label="启用状态" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" /></Form.Item>
        </Form>
      </Modal>

      <Modal title="批量导入项目角色" open={importVisible} onCancel={() => { setImportVisible(false); setImportData([]); }}
        width={800} footer={
          <Space>{importData.length > 0 && <Button type="primary" loading={importing} onClick={handleImport}>确认导入</Button>}
            <Button onClick={() => { setImportVisible(false); setImportData([]); }}>关闭</Button></Space>}>
        {!importData.length ? (
          <div>
            <Dragger accept=".xlsx,.xls" maxCount={1} beforeUpload={handleFileParse} showUploadList={false}>
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">点击或拖拽Excel文件上传</p>
              <p className="ant-upload-hint">支持 .xlsx / .xls 格式，列：角色编码、角色名称、描述、是否启用</p>
            </Dragger>
            <div style={{ marginTop: 16, textAlign: 'center' }}>
              <Button type="link" icon={<DownloadOutlined />} onClick={downloadTemplate}>下载导入模板</Button></div>
          </div>
        ) : (
          <Table columns={[
            { title: '角色编码', dataIndex: 'roleCode', width: 120 },
            { title: '角色名称', dataIndex: 'roleName', width: 150 },
            { title: '描述', dataIndex: 'description', ellipsis: true },
            { title: '是否启用', dataIndex: 'isActive', width: 80, render: (v) => <Tag color={v ? 'green' : 'default'}>{v ? '是' : '否'}</Tag> },
            { title: '校验', dataIndex: '_valid', width: 70, render: (v) => <Tag color={v ? 'green' : 'red'}>{v ? '有效' : '无效'}</Tag> },
          ]} dataSource={importData} rowKey="_key" size="small" scroll={{ y: 360 }} pagination={false} />
        )}
      </Modal>
    </div>
  );
}

export default ProjectRolePage;
