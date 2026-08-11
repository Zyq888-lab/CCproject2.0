{/* 模块用途：FuncKpiPage——职能KPI配置页，表格+筛选+新增/编辑弹窗+启停切换 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Select/InputNumber */}
{/* 修改注意：创建时权重发送整数(0-100)，更新时发送小数(0-1)；GET返回小数，显示时×100 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Space, Modal, Form, Input, Select, InputNumber, Switch, message, Card, Spin, Result, Upload, Tag
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, LineChartOutlined, ReloadOutlined, DownloadOutlined, InboxOutlined,
} from '@ant-design/icons';
import * as XLSX from 'xlsx';
const { Dragger } = Upload;
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showDeleteConfirm, showConflictWarning } from '../../components/ConfirmModal';
import client from '../../api/client';
import useCategories from '../../hooks/useCategories';

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
  const [categoryOptions] = useCategories();
  const [allPositionConfigs, setAllPositionConfigs] = useState([]);
  const [positionOptions, setPositionOptions] = useState([]);
  const [formPositionOptions, setFormPositionOptions] = useState([]);
  const [importVisible, setImportVisible] = useState(false);

  const updatePositionOptions = (configs, cat) => {
    const filtered = cat ? configs.filter((p) => p.category === cat) : configs;
    const names = [...new Set(filtered.map((p) => p.position).filter(Boolean))].sort();
    setPositionOptions(names.map((n) => ({ label: n, value: n })));
  };
  const [importData, setImportData] = useState([]);
  const [importing, setImporting] = useState(false);

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
    client.get('/position-configs', { params: { size: 9999 } }).then((res) => {
      const list = res.data?.list || [];
      setAllPositionConfigs(list);
      const allNames = [...new Set(list.map((p) => p.position).filter(Boolean))].sort().map((n) => ({ label: n, value: n }));
      setPositionOptions(allNames);
      setFormPositionOptions(allNames);
    }).catch(() => {});
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
      // 权重总和校验：同岗位下所有权重之和不得超过100%
      const scopeCategory = editingRecord ? editingRecord.category : values.category;
      const scopePosition = editingRecord ? editingRecord.position : values.position;
      const newWeight = values.weight;
      const otherSum = data
        .filter((d) => d.category === scopeCategory
          && d.position === scopePosition
          && d.id !== (editingRecord?.id || ''))
        .reduce((s, d) => s + Math.round((d.weight || 0) * 100), 0);
      if (otherSum + newWeight > 100) {
        message.error(`权重总和超过100%（其他指标合计: ${otherSum}% + 当前: ${newWeight}% = ${otherSum + newWeight}%）`);
        return;
      }
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
      if (err?.code === 409 && err?.message?.includes('已被他人修改')) {
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

  const FMAP = { '岗位分类': 'category', '岗位': 'position', 'KPI指标': 'kpiName', '评价标准': 'evaluationCriteria', '权重': 'weight', '排序': 'sortOrder' };
  const handleFileParse = (file) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const wb = XLSX.read(e.target.result, { type: 'array' });
        setImportData(XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { defval: '' }).map((row, idx) => {
          const mapped = { _key: idx };
          Object.entries(FMAP).forEach(([col, field]) => { mapped[field] = String(row[col] || '').trim(); });
          mapped._valid = !!mapped.category && !!mapped.kpiName && mapped.weight;
          return mapped;
        }));
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
      const res = await client.post('/kpi-configs/func-kpi/import', valid.map(({ _key, _valid, ...rest }) => ({ ...rest, weight: parseFloat(rest.weight) || 0 })));
      const result = res.data || {};
      const fail = (result.errors || []).length;
      if (fail > 0) { message.warning({ content: `成功 ${result.success || 0} 条，失败 ${fail} 条`, duration: 5 }); result.errors.slice(0, 5).forEach(e => message.error({ content: e })); }
      else message.success({ content: `成功导入 ${result.success} 条`, duration: 3 });
      if (result.success > 0 || fail === 0) { setImportVisible(false); setImportData([]); fetchData(filters); }
    } catch (err) { message.error({ content: err?.message || '导入失败' }); }
    finally { setImporting(false); }
  };
  const downloadTemplate = () => {
    const ws = XLSX.utils.json_to_sheet([{ '岗位分类': '研发技术类', '岗位': '整椅研发工程师', 'KPI指标': '指标名称', '评价标准': '1-5分', '权重': '30', '排序': '1' }]);
    const wb = XLSX.utils.book_new(); XLSX.utils.book_append_sheet(wb, ws, '职能KPI导入模板');
    XLSX.writeFile(wb, '职能KPI导入模板.xlsx');
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
        actions={[{ label: '批量导入', icon: <DownloadOutlined />, onClick: () => setImportVisible(true) }, { label: '新增KPI', icon: <PlusOutlined />, type: 'primary', onClick: handleCreate }]}
      />

      <Card id="func-kpi-search-bar" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Space wrap size="middle">
          <Select
            placeholder="岗位分类"
            value={filters.category || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, category: v || '' }))}
            allowClear
            style={{ width: 140 }}
            options={categoryOptions}
          />
          <Select
            placeholder="岗位名称"
            value={filters.position || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, position: v || '' }))}
            allowClear
            showSearch
            optionFilterProp="label"
            style={{ width: 180 }}
            options={positionOptions}
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
        width={520}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="category" label="岗位分类" rules={[{ required: true, message: '请选择岗位分类' }]}>
            <Select placeholder="选择岗位分类" options={categoryOptions} disabled={!!editingRecord}
              onChange={(v) => handleModalCategoryChange(v)} />
          </Form.Item>
          <Form.Item name="position" label="岗位名称" rules={[{ required: true, message: '请选择岗位名称' }]}>
            <Select placeholder="选择岗位" showSearch optionFilterProp="label" options={formPositionOptions} disabled={!!editingRecord} />
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
          <WeightSumHint data={data} editingRecord={editingRecord} form={form} />
        </Form>
      </Modal>

      <Modal title="批量导入职能KPI" open={importVisible} onCancel={() => { setImportVisible(false); setImportData([]); }}
        width={850} footer={<Space>{importData.length > 0 && <Button type="primary" loading={importing} onClick={handleImport}>确认导入 ({importData.filter(r => r._valid).length} 条)</Button>}<Button onClick={() => { setImportVisible(false); setImportData([]); }}>关闭</Button></Space>}>
        {!importData.length ? (
          <div><Dragger accept=".xlsx,.xls" maxCount={1} beforeUpload={handleFileParse} showUploadList={false}><p className="ant-upload-drag-icon"><InboxOutlined /></p><p className="ant-upload-text">点击或拖拽Excel文件上传</p><p className="ant-upload-hint">列：岗位分类、岗位、KPI指标、评价标准、权重、排序</p></Dragger>
            <div style={{ marginTop: 16, textAlign: 'center' }}><Button type="link" icon={<DownloadOutlined />} onClick={downloadTemplate}>下载导入模板</Button></div></div>
        ) : (
          <Table columns={[{ title: '岗位分类', dataIndex: 'category', width: 120 },{ title: '岗位', dataIndex: 'position', width: 130 },{ title: 'KPI指标', dataIndex: 'kpiName', width: 150 },{ title: '权重', dataIndex: 'weight', width: 70 },{ title: '校验', dataIndex: '_valid', width: 60, render: (v) => <Tag color={v ? 'green' : 'red'}>{v ? '有效' : '无效'}</Tag> }]} dataSource={importData} rowKey="_key" size="small" scroll={{ y: 360 }} pagination={false} />
        )}
      </Modal>
    </div>
  );
}

// 新增表单：岗位分类变化时联动岗位名称下拉选项
const handleModalCategoryChange = (cat) => {
  if (!cat) { setFormPositionOptions(positionOptions); return; }
  const filtered = allPositionConfigs.filter((p) => p.category === cat);
  const names = [...new Set(filtered.map((p) => p.position).filter(Boolean))].sort().map((n) => ({ label: n, value: n }));
  setFormPositionOptions(names);
};

function WeightSumHint({ data, editingRecord, form }) {
  const watchedWeight = Form.useWatch('weight', form);
  const watchedCategory = Form.useWatch('category', form);
  const watchedPosition = Form.useWatch('position', form);
  const category = editingRecord ? editingRecord.category : watchedCategory;
  const position = editingRecord ? editingRecord.position : watchedPosition;
  const weight = watchedWeight;
  if (weight == null || !category || !position) return null;
  const otherSum = data
    .filter((d) => d.category === category && d.position === position && d.id !== (editingRecord?.id || ''))
    .reduce((s, d) => s + Math.round(d.weight * 100), 0);
  const total = otherSum + weight;
  const ok = total <= 100;
  return (
    <div style={{ color: ok ? '#52C41A' : '#FF4D4F', fontSize: 13, marginTop: -8, marginBottom: 16 }}>
      {editingRecord
        ? `该岗位下其他指标权重之和 ${otherSum}% + 当前 ${weight}% = ${total}%`
        : `该岗位下已有指标权重之和 ${otherSum}%，加上当前 ${weight}% = ${total}%`}
      {ok ? ' ✓' : ` ⚠ 超出 100%`}
    </div>
  );
}

export default FuncKpiPage;
