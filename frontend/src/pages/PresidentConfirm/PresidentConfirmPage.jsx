{/* 模块用途：PresidentConfirmPage——总裁确认页：列出当前总裁负责项目的确认清单，逐项目通过/退回 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Card/Table/Tag/Button/Modal/Form/Input/Select/Alert */}
{/* 修改注意：清单由后端按当前登录总裁过滤（GET /president/confirmations，可选 periodId）；presidentConflict 项目显示主总裁冲突异常 */}
import { useState, useEffect, useRef, useMemo } from 'react';
import {
  Card, Table, Tag, Button, Modal, Form, Input, Select, Spin, Result, Alert, message, Space,
} from 'antd';
import {
  CheckOutlined, RollbackOutlined, ExclamationCircleOutlined, AuditOutlined,
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
    () => [...new Map(items.map((i) => [i.periodId, i.periodId])).entries()]
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

  const columns = [
    { title: '周期', dataIndex: 'periodId', key: 'periodId', width: 150, render: (v) => v || '-' },
    { title: '项目编码', dataIndex: 'projectCode', key: 'projectCode', width: 140 },
    { title: '项目名称', dataIndex: 'projectName', key: 'projectName', width: 200, render: (v) => v || '-' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s) => <Tag color={STATUS_COLOR[s] || 'default'}>{STATUS_LABEL[s] || s || '-'}</Tag> },
    { title: '退回原因', dataIndex: 'returnReason', key: 'returnReason', width: 220, render: (v) => v || '-' },
    { title: '操作', key: 'action', width: 280,
      render: (_, record) => {
        if (record.presidentConflict) {
          return <span style={{ color: '#FF4D4F' }}><ExclamationCircleOutlined /> 该项目主总裁配置冲突，请联系管理员</span>;
        }
        if (record.status !== 'PENDING') {
          return <span style={{ color: '#BFBFBF' }}>—</span>;
        }
        return (
          <Space size="small">
            <Button type="link" size="small" icon={<CheckOutlined />} onClick={() => handleApprove(record)}>确认通过</Button>
            <Button type="link" size="small" danger icon={<RollbackOutlined />} onClick={() => openReturn(record)}>退回</Button>
          </Space>
        );
      } },
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
            scroll={{ x: 1080 }}
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
    </div>
  );
}

export default PresidentConfirmPage;
