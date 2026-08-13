{/* 模块用途：ParticipationPage——项目参与录入页，表格+周期/状态筛选+新增弹窗(多项目行+比重实时校验) */}
{/* 依赖组件：PageHeader, EmptyState, client.js, Ant Design Table/Modal/Form/Select/InputNumber/Tag/Space/Button */}
{/* 修改注意：投入比重合计 绿=100% / 橙<100% / 红>100%，单项≥1%，总和必须=100%才能保存 */}
import { useState, useEffect, useRef } from 'react';
import {
  Table, Button, Select, Space, Tag, Modal, Form, InputNumber, message, Card, Tooltip,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, FolderOpenOutlined, MinusCircleOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

const STATUS_OPTIONS = [
  { label: '待审批', value: 'PENDING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已拒绝', value: 'REJECTED' },
  { label: '已取消', value: 'CANCELLED' },
];

const STATUS_LABEL_MAP = {
  'PENDING': '待审批',
  'APPROVED': '已通过',
  'REJECTED': '已拒绝',
  'CANCELLED': '已取消',
};

const STATUS_COLOR_MAP = {
  'PENDING': 'orange',
  'APPROVED': 'green',
  'REJECTED': 'red',
  'CANCELLED': 'default',
};

function ParticipationPage() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [filters, setFilters] = useState({ periodId: '', status: '' });
  const [periods, setPeriods] = useState([]);
  const [projects, setProjects] = useState([]);
  const [modalVisible, setModalVisible] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [rows, setRows] = useState([]);
  const [form] = Form.useForm();
  const mountedRef = useRef(true);

  // 功能：分页获取参与记录——支持按周期和状态筛选
  const fetchParticipations = async (page, size, filterParams) => {
    setLoading(true);
    setError(null);
    try {
      const params = { page, size };
      if (filterParams?.periodId) params.periodId = filterParams.periodId;
      if (filterParams?.status) params.status = filterParams.status;
      const res = await client.get('/participations', { params });
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
  };

  // 功能：获取考核周期列表（新增弹窗选周期用）
  const fetchPeriods = async () => {
    try {
      const res = await client.get('/periods');
      if (mountedRef.current) setPeriods(Array.isArray(res.data) ? res.data : []);
    } catch (_) { /* 非关键 */ }
  };

  // 功能：获取项目列表（新增弹窗选项目用）
  const fetchProjects = async () => {
    try {
      const res = await client.get('/projects', { params: { page: 1, size: 999 } });
      if (mountedRef.current) {
        const list = res.data?.list || [];
        setProjects(list.map((p) => ({
          label: `${p.projectCode} — ${p.projectName} (${p.projectStage})`,
          value: p.projectCode,
          stage: p.projectStage,
        })));
      }
    } catch (_) { /* 非关键 */ }
  };

  useEffect(() => {
    mountedRef.current = true;
    fetchParticipations(pagination.current, pagination.pageSize, filters);
    fetchPeriods();
    fetchProjects();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = () => fetchParticipations(1, pagination.pageSize, filters);
  const handleReset = () => {
    const empty = { periodId: '', status: '' };
    setFilters(empty);
    fetchParticipations(1, pagination.pageSize, empty);
  };

  const handleTableChange = (pag) => {
    fetchParticipations(pag.current, pag.pageSize, filters);
  };

  // 功能：打开新增弹窗——初始化一行空项目行
  const handleCreate = () => {
    form.resetFields();
    setRows([{ key: Date.now(), projectCode: null, rate: null }]);
    setModalVisible(true);
  };

  // 功能：添加一行项目——多项目参与录入
  const handleAddRow = () => {
    setRows((prev) => [...prev, { key: Date.now(), projectCode: null, rate: null }]);
  };

  // 功能：删除一行项目
  const handleRemoveRow = (key) => {
    setRows((prev) => prev.filter((r) => r.key !== key));
  };

  // 功能：更新某行的项目或比重
  const handleRowChange = (key, field, value) => {
    setRows((prev) => prev.map((r) => (r.key === key ? { ...r, [field]: value } : r)));
  };

  // 功能：计算投入比重合计
  const totalRate = rows.reduce((sum, r) => sum + (Number(r.rate) || 0), 0);

  // 功能：比重合计状态——绿=100% / 橙<100% / 红>100%
  const totalColor = totalRate === 100 ? '#52C41A' : totalRate < 100 ? '#FA8C16' : '#FF4D4F';
  const totalText = totalRate === 100 ? `${totalRate}% ✓` : totalRate < 100 ? `${totalRate}% (还需 ${100 - totalRate}%)` : `${totalRate}% (超出 ${totalRate - 100}%)`;

  // 功能：提交新增参与——校验周期/项目行/比重合计
  const handleSubmit = async () => {
    if (!form.getFieldValue('periodId')) {
      message.warning({ content: '请选择考核周期' });
      return;
    }
    if (rows.length === 0) {
      message.warning({ content: '至少填写一个项目' });
      return;
    }
    for (const r of rows) {
      if (!r.projectCode) {
        message.warning({ content: '请选择项目' });
        return;
      }
      if (r.rate == null || r.rate < 1) {
        message.warning({ content: '单个项目投入比重不能小于1%' });
        return;
      }
    }
    if (totalRate !== 100) {
      message.warning({ content: '投入比重总和必须等于100%' });
      return;
    }

    setSubmitting(true);
    try {
      const payload = {
        periodId: form.getFieldValue('periodId'),
        items: rows.map((r) => ({
          projectCode: r.projectCode,
          projectStage: projects.find((p) => p.value === r.projectCode)?.stage,
          participationRate: r.rate,
        })),
      };
      await client.post('/participations', payload);
      message.success({ content: '参与记录已提交', duration: 3 });
      setModalVisible(false);
      fetchParticipations(pagination.current, pagination.pageSize, filters);
    } catch (err) {
      message.error({ content: err?.message || '提交失败' });
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    { title: '项目编码', dataIndex: 'projectCode', key: 'projectCode', width: 120 },
    { title: '项目阶段', dataIndex: 'projectStage', key: 'projectStage', width: 90 },
    { title: '投入比重', dataIndex: 'participationRate', key: 'participationRate', width: 100,
      render: (v) => v != null ? `${v}%` : '-' },
    { title: '建议比重', dataIndex: 'suggestedRate', key: 'suggestedRate', width: 100,
      render: (v) => v != null ? `${v}%` : '-' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s) => <Tag color={STATUS_COLOR_MAP[s] || 'default'}>{STATUS_LABEL_MAP[s] || s || '-'}</Tag> },
    { title: '提交时间', dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-' },
  ];

  const isEmpty = !loading && !error && data.length === 0 && !filters.periodId && !filters.status;

  return (
    <div id="participation-page-area">
      <PageHeader
        title="项目参与录入"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[{ label: '新增参与', icon: <PlusOutlined />, type: 'primary', onClick: handleCreate }]}
      />

      {/* 功能：筛选栏——周期下拉 + 状态下拉 */}
      <Card id="participation-search-bar" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Space wrap size="middle">
          <Select
            placeholder="考核周期"
            value={filters.periodId || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, periodId: v || '' }))}
            allowClear
            style={{ width: 200 }}
            options={periods.map((p) => ({ label: p.periodName, value: p.periodId }))}
          />
          <Select
            placeholder="状态"
            value={filters.status || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, status: v || '' }))}
            allowClear
            style={{ width: 140 }}
            options={STATUS_OPTIONS}
          />
          <Button type="primary" onClick={handleSearch}>搜索</Button>
          <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
        </Space>
      </Card>

      {error && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={() => fetchParticipations(pagination.current, pagination.pageSize, filters)}>重试</Button>
        </div>
      )}

      {/* 功能：引导式空状态——无记录且无筛选条件时显示 */}
      {isEmpty && (
        <EmptyState
          image={<FolderOpenOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有项目参与记录"
          description="填写你在各项目中的投入比重，提交后由项目考核人审批"
          primaryAction={{ label: '新增参与', onClick: handleCreate }}
        />
      )}

      {!isEmpty && (
        <Card id="participation-table-card" style={{ borderRadius: 8 }}>
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
            scroll={{ x: 780 }}
          />
        </Card>
      )}

      {/* 功能：新增参与弹窗——多项目行 + 比重实时校验（绿/橙/红） */}
      <Modal
        title="新增项目参与"
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        confirmLoading={submitting}
        okText="提交"
        cancelText="取消"
        width={640}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="periodId" label="考核周期" rules={[{ required: true, message: '请选择考核周期' }]}>
            <Select
              placeholder="选择考核周期"
              options={periods.map((p) => ({ label: p.periodName, value: p.periodId }))}
            />
          </Form.Item>

          <div style={{ marginBottom: 8 }}>
            <span style={{ fontWeight: 500 }}>项目投入比重</span>
            <span style={{ color: '#8C8C8C', marginLeft: 8, fontSize: 12 }}>（单项≥1%，总和须=100%）</span>
          </div>

          {/* 功能：多项目行——每行 项目下拉 + 比重输入 + 删除按钮 */}
          {rows.map((row) => (
            <Space key={row.key} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
              <Select
                placeholder="选择项目"
                style={{ width: 300 }}
                value={row.projectCode || undefined}
                onChange={(v) => handleRowChange(row.key, 'projectCode', v)}
                options={projects}
                showSearch
                optionFilterProp="label"
              />
              <InputNumber
                placeholder="比重%"
                min={1}
                max={100}
                precision={0}
                style={{ width: 120 }}
                value={row.rate}
                onChange={(v) => handleRowChange(row.key, 'rate', v)}
              />
              <Button
                type="text"
                danger
                icon={<MinusCircleOutlined />}
                onClick={() => handleRemoveRow(row.key)}
                disabled={rows.length === 1}
              />
            </Space>
          ))}

          <Button type="dashed" block icon={<PlusOutlined />} onClick={handleAddRow} style={{ marginBottom: 12 }}>
            添加项目行
          </Button>

          {/* 功能：比重合计实时显示——绿=100% / 橙<100% / 红>100% */}
          <Tooltip title={totalRate === 100 ? '已满足100%' : totalRate < 100 ? '未达到100%' : '超出100%'}>
            <div style={{
              padding: '10px 12px',
              borderRadius: 6,
              background: '#FAFAFA',
              border: `1px solid ${totalColor}`,
              color: totalColor,
              fontWeight: 500,
            }}>
              投入比重合计：{totalText}
            </div>
          </Tooltip>
        </Form>
      </Modal>
    </div>
  );
}

export default ParticipationPage;
