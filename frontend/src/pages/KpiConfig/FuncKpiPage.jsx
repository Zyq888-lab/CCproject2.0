{/* 模块用途：FuncKpiPage——职能KPI配置页，表格+筛选+新增/编辑弹窗+启停切换 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Select/InputNumber */}
{/* 修改注意：创建时权重发送整数(0-100)，更新时发送小数(0-1)；GET返回小数，显示时×100 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Space, Modal, Form, Input, Select, InputNumber, Switch, message, Card, Spin, Result,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, LineChartOutlined, ReloadOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showDeleteConfirm, showConflictWarning } from '../../components/ConfirmModal';
import client from '../../api/client';

const CATEGORY_OPTIONS = [
  { label: '研发技术类', value: '研发技术类' },
  { label: '生产制造类', value: '生产制造类' },
  { label: '质量管理类', value: '质量管理类' },
  { label: '项目管理类', value: '项目管理类' },
];

function FuncKpiPage() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [filters, setFilters] = useState({ category: '', position: '' });
  const [modalVisible, setModalVisible] = useState(false);
  const [editingRecord, setEditingRecord] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [togglingId, setTogglingId] = useState(null);
  const [form] = Form.useForm();

  const mountedRef = useRef(true);

  const fetchData = useCallback(async (filterParams) => {
    setLoading(true);
    setError(null);
    try {
      const params = {};
      if (filterParams?.category) params.category = filterParams.category;
      if (filterParams?.position) params.position = filterParams.position;
      const res = await client.get('/kpi-configs/functional', { params });
      if (mountedRef.current) setData(Array.isArray(res.data) ? res.data : []);
    } catch (err) {
      if (mountedRef.current) setError(err?.message || '加载失败');
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    fetchData(filters);
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = () => fetchData(filters);
  const handleReset = () => {
    const empty = { category: '', position: '' };
    setFilters(empty);
    fetchData(empty);
  };

  const handleCreate = () => {
    setEditingRecord(null);
    form.resetFields();
    form.setFieldsValue({ sortOrder: 0 });
    setModalVisible(true);
  };

  const handleEdit = (record) => {
    setEditingRecord(record);
    form.resetFields();
    form.setFieldsValue({
      category: record.category,
      position: record.position,
      kpiName: record.kpiName,
      evaluationCriteria: record.evaluationCriteria || '',
      weight: record.weight != null ? Math.round(record.weight * 100) : undefined,
      sortOrder: record.sortOrder ?? 0,
    });
    setModalVisible(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const payload = {
        kpiName: values.kpiName,
        evaluationCriteria: values.evaluationCriteria || null,
        weight: editingRecord ? values.weight / 100 : values.weight,
        sortOrder: values.sortOrder,
      };
      if (!editingRecord) {
        payload.category = values.category;
        payload.position = values.position;
        await client.post('/kpi-configs/functional', payload);
        message.success({ content: '创建成功', duration: 3 });
      } else {
        await client.put(`/kpi-configs/functional/${editingRecord.id}`, payload);
        message.success({ content: '保存成功', duration: 3 });
      }
      setModalVisible(false);
      fetchData(filters);
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

  const handleToggle = async (record) => {
    setTogglingId(record.id);
    try {
      await client.put(`/kpi-configs/functional/${record.id}/toggle`);
      message.success({ content: record.isActive ? '已停用' : '已启用', duration: 2 });
      fetchData(filters);
    } catch (err) {
      message.error({ content: err?.message || '操作失败' });
    } finally {
      if (mountedRef.current) setTogglingId(null);
    }
  };

  const handleDelete = (record) => {
    showDeleteConfirm(async () => {
      try {
        await client.delete(`/kpi-configs/functional/${record.id}`);
        message.success({ content: '已删除', duration: 3 });
        fetchData(filters);
      } catch (err) {
        message.error({ content: err?.message || '删除失败' });
      }
    }, `${record.category} — ${record.kpiName}`);
  };

  const columns = [
    { title: '岗位分类', dataIndex: 'category', key: 'category', width: 110 },
    { title: '岗位名称', dataIndex: 'position', key: 'position', width: 120 },
    { title: 'KPI指标', dataIndex: 'kpiName', key: 'kpiName', width: 160 },
    { title: '评价标准', dataIndex: 'evaluationCriteria', key: 'evaluationCriteria', width: 180,
      render: (v) => v || '-', ellipsis: true },
    { title: '权重', dataIndex: 'weight', key: 'weight', width: 80,
      render: (v) => v != null ? `${Math.round(v * 100)}%` : '-' },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 70 },
    { title: '状态', dataIndex: 'isActive', key: 'isActive', width: 80,
      render: (v, record) => (
        <Switch checked={v} loading={togglingId === record.id} onChange={() => handleToggle(record)}
          checkedChildren="启用" unCheckedChildren="停用" />
      ) },
    { title: '操作', key: 'action', width: 140,
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
        extra={<Button type="primary" onClick={() => fetchData(filters)}>重试</Button>}
      />
    );
  }

  return (
    <div id="func-kpi-area">
      <PageHeader
        title="职能KPI配置"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[{ label: '新增KPI', icon: <PlusOutlined />, type: 'primary', onClick: handleCreate }]}
      />

      <Card id="func-kpi-search-bar" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Space wrap size="middle">
          <Select
            placeholder="岗位分类"
            value={filters.category || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, category: v || '' }))}
            allowClear
            style={{ width: 140 }}
            options={CATEGORY_OPTIONS}
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
      </Card>

      {error && data.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={() => fetchData(filters)}>重试</Button>
        </div>
      )}

      {isEmpty && (
        <EmptyState
          image={<LineChartOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有职能KPI指标"
          description="为每个岗位配置职能考核KPI指标及权重"
          primaryAction={{ label: '新增KPI', onClick: handleCreate }}
        />
      )}

      {!isEmpty && (
        <Card id="func-kpi-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={data}
            rowKey="id"
            loading={loading}
            size="middle"
            rowClassName={(_, index) => index % 2 === 1 ? 'table-row-striped' : ''}
            pagination={false}
            scroll={{ x: 940 }}
          />
        </Card>
      )}

      <Modal
        title={editingRecord ? '编辑职能KPI' : '新增职能KPI'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        destroyOnHidden
        width={520}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="category" label="岗位分类" rules={[{ required: true, message: '请选择岗位分类' }]}>
            <Select placeholder="选择岗位分类" options={CATEGORY_OPTIONS} disabled={!!editingRecord} />
          </Form.Item>
          <Form.Item name="position" label="岗位名称" rules={[{ required: true, message: '请输入岗位名称' }]}>
            <Input placeholder="如 整椅研发岗" maxLength={50} disabled={!!editingRecord} />
          </Form.Item>
          <Form.Item name="kpiName" label="KPI指标名称" rules={[{ required: true, message: '请输入KPI名称' }]}>
            <Input placeholder="如 技术文档完整性" maxLength={50} />
          </Form.Item>
          <Form.Item name="evaluationCriteria" label="评价标准">
            <Input.TextArea placeholder="评价标准（可选）" maxLength={500} rows={3} />
          </Form.Item>
          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item name="weight" label="权重(%)" rules={[{ required: true, message: '请输入' }]} style={{ flex: 1 }}>
              <InputNumber min={0} max={100} precision={0} style={{ width: '100%' }} placeholder="如 40" />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序" style={{ flex: 1 }}>
              <InputNumber min={0} max={999} precision={0} style={{ width: '100%' }} placeholder="0" />
            </Form.Item>
          </div>
        </Form>
      </Modal>
    </div>
  );
}

export default FuncKpiPage;
