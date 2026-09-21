{/* 模块用途：PresidentConfirmPage——总裁确认页：列出当前总裁负责项目的确认清单，逐项目通过/退回 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Card/Table/Tag/Button/Modal/Form/Input/Select/Alert */}
{/* 修改注意：清单由后端按当前登录总裁过滤（GET /president/confirmations，可选 periodId）；presidentConflict 项目显示主总裁冲突异常 */}
import { useState, useEffect, useRef, useMemo } from 'react';
import {
  Card, Table, Tag, Button, Modal, Form, Input, Select, Spin, Result, Alert, message, Space, Drawer,
} from 'antd';
import {
  CheckOutlined, RollbackOutlined, ExclamationCircleOutlined, AuditOutlined, BarChartOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showConfirm } from '../../components/ConfirmModal';
import client from '../../api/client';

const STATUS_LABEL = {
  PENDING: '待确认',
  APPROVED: '已通过',
  RETURNED: '已退回',
};

const STATUS_COLOR = {
  PENDING: 'orange',
  APPROVED: 'green',
  RETURNED: 'red',
};

function PresidentConfirmPage() {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [returnTarget, setReturnTarget] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [periodFilter, setPeriodFilter] = useState('');
  const [form] = Form.useForm();
  const mountedRef = useRef(true);
  const [detail, setDetail] = useState(null); // { projectCode, projectName, periodId }
  const [matrix, setMatrix] = useState(null);
  const [matrixLoading, setMatrixLoading] = useState(false);

  // 功能：加载确认清单——后端按当前总裁的主总裁分配过滤；可选 periodId 过滤，此处拉全量后客户端按周期筛
  const fetchData = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/president/confirmations');
      if (mountedRef.current) {
        setItems(Array.isArray(res.data) ? res.data : []);
      }
    } catch (err) {
      if (mountedRef.current) setError(err?.message || '加载失败');
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    fetchData();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 功能：从清单推导周期下拉选项
  const periodOptions = useMemo(
    () => [...new Map(items.map((i) => [i.periodId, i.periodName || i.periodId])).entries()]
      .map(([value, label]) => ({ value, label })),
    [items],
  );

  const filteredItems = periodFilter
    ? items.filter((i) => i.periodId === periodFilter)
    : items;
  const conflictCount = filteredItems.filter((i) => i.presidentConflict).length;

  // 功能：确认通过——二次确认后调用 approve 端点
  const handleApprove = (record) => {
    showConfirm({
      title: `确认通过「${record.projectName || record.projectCode}」？`,
      content: '通过后该项目不再需要退回重审。',
      okText: '确认通过',
      onOk: async () => {
        try {
          await client.post(`/president/confirmations/${record.id}/approve`);
          message.success({ content: '已确认通过', duration: 3 });
          fetchData();
        } catch (err) {
          message.error({ content: err?.message || '操作失败' });
        }
      },
    });
  };

  // 功能：整项目一键确认——该项目下所有待确认人员一次性通过（approve-all）
  const handleApproveAll = (record) => {
    showConfirm({
      title: `一键确认「${record.projectName || record.projectCode}」？`,
      content: '将确认通过该项目下所有待确认人员，确认后不可再退回重审。',
      okText: '一键确认',
      onOk: async () => {
        try {
          const res = await client.post('/president/confirmations/approve-all', null, {
            params: { periodId: record.periodId, projectCode: record.projectCode },
          });
          message.success({ content: `已确认通过 ${res.data ?? 0} 人`, duration: 3 });
          fetchData();
        } catch (err) {
          message.error({ content: err?.message || '操作失败' });
        }
      },
    });
  };

  // 功能：整项目一键确认分组——按 (周期, 项目) 聚合，列出存在待确认人员的项目供一键通过
  const approveAllGroups = useMemo(() => {
    const map = new Map();
    filteredItems.forEach((i) => {
      const key = `${i.periodId}::${i.projectCode}`;
      if (!map.has(key)) {
        map.set(key, {
          periodId: i.periodId,
          projectCode: i.projectCode,
          projectName: i.projectName || i.projectCode,
          pendingCount: 0,
          conflict: i.presidentConflict,
        });
      }
      const g = map.get(key);
      if (i.status === 'PENDING') g.pendingCount += 1;
      if (i.presidentConflict) g.conflict = true;
    });
    return [...map.values()].filter((g) => g.pendingCount > 0 && !g.conflict);
  }, [filteredItems]);

  // 功能：打开退回弹窗——退回原因必填
  const openReturn = (record) => {
    form.resetFields();
    setReturnTarget(record);
  };

  const handleReturnSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await client.post(`/president/confirmations/${returnTarget.id}/return`, { reason: values.reason });
      message.success({ content: '已退回', duration: 3 });
      setReturnTarget(null);
      fetchData();
    } catch (err) {
      if (err?.message) message.error({ content: err.message });
    } finally {
      setSubmitting(false);
    }
  };

  // 功能：打开评分明细抽屉——拉取该周期校准矩阵，按项目分组键过滤出本项目员工行
  const openDetail = async (record) => {
    setDetail({ projectCode: record.projectCode, projectName: record.projectName, periodId: record.periodId });
    setMatrix(null);
    setMatrixLoading(true);
    try {
      const res = await client.get(`/periods/${record.periodId}/calibration`);
      setMatrix(res.data && res.data.rows ? res.data : null);
    } catch (err) {
      message.error({ content: err?.message || '加载评分明细失败' });
    } finally {
      setMatrixLoading(false);
    }
  };

  const detailRows = useMemo(() => {
    if (!matrix || !detail) return [];
    return (matrix.rows || []).filter((r) => r.groupKey === 'project:' + detail.projectCode);
  }, [matrix, detail]);

  const detailColumns = [
    { title: '员工', dataIndex: 'employeeName', key: 'employeeName', width: 120, render: (v) => v || '-' },
    { title: '原始分', dataIndex: 'originalScore', key: 'originalScore', width: 100, render: (v) => v ?? '-' },
    { title: '调整后分', dataIndex: 'adjustedScore', key: 'adjustedScore', width: 100, render: (v) => v ?? '-' },
    { title: '离群', dataIndex: 'outlier', key: 'outlier', width: 90,
      render: (o, r) => (o ? <Tag color="red">{r.direction === 'HIGH' ? '偏高' : '偏低'}</Tag> : '-') },
  ];

  const columns = [
    { title: '周期', dataIndex: 'periodName', key: 'periodName', width: 150, render: (v) => v || '-' },
    { title: '项目编码', dataIndex: 'projectCode', key: 'projectCode', width: 140 },
    { title: '项目名称', dataIndex: 'projectName', key: 'projectName', width: 200, render: (v) => v || '-' },
    { title: '员工', dataIndex: 'employeeName', key: 'employeeName', width: 150,
      render: (v, r) => (
        <div>
          <div style={{ fontWeight: 500 }}>{v || '-'}</div>
          <div style={{ color: '#8C8C8C', fontSize: 12 }}>{r.assesseeId}</div>
        </div>
      ) },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s) => <Tag color={STATUS_COLOR[s] || 'default'}>{STATUS_LABEL[s] || s || '-'}</Tag> },
    { title: '退回原因', dataIndex: 'returnReason', key: 'returnReason', width: 220, render: (v) => v || '-' },
    { title: '操作', key: 'action', width: 340,
      render: (_, record) => (
        <Space size="small">
          <Button type="link" size="small" icon={<BarChartOutlined />} onClick={() => openDetail(record)}>查看评分</Button>
          {record.presidentConflict ? (
            <span style={{ color: '#FF4D4F' }}><ExclamationCircleOutlined /> 主总裁配置冲突</span>
          ) : record.status === 'PENDING' ? (
            <>
              <Button type="link" size="small" icon={<CheckOutlined />} onClick={() => handleApprove(record)}>确认通过</Button>
              <Button type="link" size="small" danger icon={<RollbackOutlined />} onClick={() => openReturn(record)}>退回</Button>
            </>
          ) : null}
        </Space>
      ) },
  ];

  const isEmpty = !loading && !error && filteredItems.length === 0;

  if (loading && items.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large">
          <div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div>
        </Spin>
      </div>
    );
  }

  if (error && items.length === 0) {
    return (
      <Result
        status="error"
        title="加载失败"
        subTitle={error}
        extra={<Button type="primary" onClick={fetchData}>重试</Button>}
      />
    );
  }

  return (
    <div id="president-confirm-area">
      <PageHeader
        title="总裁确认"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[]}
      />

      {/* 功能：主总裁冲突异常提示——presidentConflict 项目需管理员修正角色分配后才能确认 */}
      {conflictCount > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={`${conflictCount} 个项目存在主总裁配置冲突，请联系管理员处理后再确认`}
        />
      )}

      {/* 功能：周期筛选——多周期时按周期收窄清单 */}
      <Card id="president-confirm-filter" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Space wrap>
          <Select
            allowClear
            placeholder="按周期筛选"
            style={{ width: 220 }}
            value={periodFilter || undefined}
            onChange={(v) => setPeriodFilter(v || '')}
            options={periodOptions}
          />
        </Space>
      </Card>

      {/* 功能：整项目一键确认——列出有待确认人员的项目，一键通过该项目全部人员 */}
      {approveAllGroups.length > 0 && (
        <Card id="president-approve-all" title="整项目一键确认" style={{ marginBottom: 16, borderRadius: 8 }}>
          <Space wrap>
            {approveAllGroups.map((g) => (
              <Button
                key={`${g.periodId}::${g.projectCode}`}
                type="primary"
                ghost
                icon={<CheckOutlined />}
                onClick={() => handleApproveAll(g)}
              >
                {g.projectName}（待确认 {g.pendingCount} 人）
              </Button>
            ))}
          </Space>
        </Card>
      )}

      {isEmpty && (
        <EmptyState
          image={<AuditOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="暂无待确认项目"
          description="当前没有需要您确认的项目，或所有项目均已处理完毕"
        />
      )}

      {!isEmpty && (
        <Card id="president-confirm-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={filteredItems}
            rowKey="id"
            loading={loading}
            size="middle"
            rowClassName={(_, index) => index % 2 === 1 ? 'table-row-striped' : ''}
            pagination={{ pageSize: 20, showSizeChanger: true, pageSizeOptions: [10, 20, 50], showTotal: (t) => `共 ${t} 条` }}
            scroll={{ x: 1230 }}
            locale={{ emptyText: '当前筛选条件下无确认项目' }}
          />
        </Card>
      )}

      {/* 功能：退回弹窗——原因必填 */}
      <Modal
        title={`退回「${returnTarget?.projectName || returnTarget?.projectCode || ''}」`}
        open={!!returnTarget}
        onOk={handleReturnSubmit}
        onCancel={() => setReturnTarget(null)}
        confirmLoading={submitting}
        okText="确认退回"
        okButtonProps={{ danger: true }}
        cancelText="取消"
        width={480}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="reason" label="退回原因" rules={[{ required: true, message: '请填写退回原因' }]}>
            <Input.TextArea rows={3} maxLength={200} placeholder="请说明退回原因" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 功能：评分明细抽屉——只读展示该项目下员工的校准矩阵明细（原始分/调整后分/离群） */}
      <Drawer
        title={`${detail?.projectName || detail?.projectCode || ''} · 评分明细`}
        open={!!detail}
        onClose={() => { setDetail(null); setMatrix(null); }}
        width={640}
      >
        <Spin spinning={matrixLoading}>
          <Table
            columns={detailColumns}
            dataSource={detailRows}
            rowKey="assesseeId"
            size="small"
            pagination={false}
            locale={{ emptyText: '该项目暂无评分明细' }}
          />
        </Spin>
      </Drawer>
    </div>
  );
}

export default PresidentConfirmPage;
