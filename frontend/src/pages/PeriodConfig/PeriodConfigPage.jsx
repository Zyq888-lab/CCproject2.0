{/* 模块用途：PeriodConfigPage——考核周期管理页，卡片列表+新增/编辑弹窗+关闭周期 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Card/Modal/Form/Input/DatePicker/Tag/Row/Col */}
{/* 修改注意：仅INIT状态可编辑；存在活跃周期时新增按钮禁用 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Button, Tag, Space, Modal, Form, Input, DatePicker, message, Row, Col, Spin, Result, Select,
} from 'antd';
import {
  PlusOutlined, EditOutlined, CalendarOutlined, LockOutlined, PlayCircleOutlined, BarChartOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showConfirm } from '../../components/ConfirmModal';
import client from '../../api/client';

const STATUS_CONFIG = {
  INIT:        { color: 'default', label: '未开始' },
  ONGOING:     { color: 'processing', label: '进行中' },
  CALIBRATING: { color: 'warning', label: '校准中' },
  COMPLETED:   { color: 'success', label: '已完成' },
};

function PeriodConfigPage() {
  const navigate = useNavigate();
  const [periods, setPeriods] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingPeriod, setEditingPeriod] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [statusFilter, setStatusFilter] = useState('');
  const [form] = Form.useForm();
  const mountedRef = useRef(true);

  const hasActivePeriod = periods.some((p) => p.status !== 'COMPLETED');

  const fetchPeriods = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const query = statusFilter ? `?status=${statusFilter}` : '';
      const res = await client.get(`/periods${query}`);
      if (mountedRef.current) {
        setPeriods(Array.isArray(res.data) ? res.data : []);
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
  }, [statusFilter]);

  useEffect(() => {
    mountedRef.current = true;
    fetchPeriods();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    fetchPeriods();
  }, [fetchPeriods]);

  const handleCreate = () => {
    setEditingPeriod(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (period) => {
    setEditingPeriod(period);
    form.resetFields();
    form.setFieldsValue({
      periodName: period.periodName,
      dateRange: [dayjs(period.startDate), dayjs(period.endDate)],
    });
    setModalVisible(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const payload = {
        periodName: values.periodName,
        startDate: values.dateRange[0].format('YYYY-MM-DD'),
        endDate: values.dateRange[1].format('YYYY-MM-DD'),
      };
      if (editingPeriod) {
        await client.put(`/periods/${editingPeriod.periodId}`, payload);
        message.success({ content: '考核周期保存成功', duration: 3 });
      } else {
        await client.post('/periods', payload);
        message.success({ content: '考核周期创建成功', duration: 3 });
      }
      setModalVisible(false);
      setEditingPeriod(null);
      fetchPeriods();
    } catch (err) {
      if (err?.message) {
        message.error({ content: err.message });
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleLaunch = (period) => {
    showConfirm({
      title: `确定要发起考核"${period.periodName}"吗？`,
      content: '发起后将自动为所有员工生成考核任务，周期状态变为"进行中"。',
      okText: '确认发起',
      onOk: async () => {
        try {
          const res = await client.post(`/tasks/${period.periodId}/launch`);
          const result = res.data || {};
          const taskCount = result.taskCount ?? 0;
          const discrepancyCount = result.discrepancyCount ?? 0;
          const extra = discrepancyCount > 0 ? `，${discrepancyCount} 条差异待处理` : '';
          message.success({ content: `考核已发起，共生成 ${taskCount} 个考核任务${extra}`, duration: 4 });
          fetchPeriods();
        } catch (err) {
          message.error({ content: err?.message || '发起失败' });
        }
      },
    });
  };

  const handleClose = (period) => {
    showConfirm({
      title: `确定要关闭考核周期"${period.periodName}"吗？`,
      content: '关闭后状态变为"已完成"，不可重新开启。',
      okText: '确认关闭',
      okType: 'danger',
      onOk: async () => {
        try {
          await client.put(`/periods/${period.periodId}/close`);
          message.success({ content: '考核周期已关闭', duration: 3 });
          fetchPeriods();
        } catch (err) {
          message.error({ content: err?.message || '关闭失败' });
        }
      },
    });
  };

  const formatDate = (d) => {
    if (!d) return '-';
    return d.length > 10 ? d.substring(0, 10) : d;
  };

  const isEmpty = !loading && !error && periods.length === 0;

  if (loading && periods.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large">
          <div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div>
        </Spin>
      </div>
    );
  }

  if (error && periods.length === 0) {
    return (
      <Result
        status="error"
        title="加载失败"
        subTitle={error}
        extra={<Button type="primary" onClick={fetchPeriods}>重试</Button>}
      />
    );
  }

  return (
    <div id="period-config-area">
      <PageHeader
        title="考核周期"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[{
          label: hasActivePeriod ? '请先关闭当前活跃周期' : '创建周期',
          icon: <PlusOutlined />,
          type: 'primary',
          onClick: handleCreate,
          disabled: hasActivePeriod,
        }]}
      />

      {error && periods.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchPeriods}>重试</Button>
        </div>
      )}

      <div style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder="按状态筛选"
          style={{ width: 160 }}
          value={statusFilter || undefined}
          onChange={(val) => setStatusFilter(val || '')}
          options={Object.entries(STATUS_CONFIG).map(([value, { label }]) => ({ value, label }))}
        />
      </div>

      {isEmpty && (
        <EmptyState
          image={<CalendarOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有考核周期"
          description="创建考核周期后，即可启动绩效考核流程"
          primaryAction={{ label: '创建周期', onClick: handleCreate }}
        />
      )}

      {!isEmpty && (
        <div id="period-card-grid">
          <Row gutter={[16, 16]}>
            {periods.map((period) => {
              const cfg = STATUS_CONFIG[period.status] || { color: 'default', label: period.status };
              return (
                <Col key={period.periodId} xs={24} sm={12} lg={8}>
                  <Card
                    hoverable
                    style={{ borderRadius: 8, height: '100%' }}
                    title={
                      <Space>
                        <CalendarOutlined style={{ color: period.status === 'COMPLETED' ? '#BFBFBF' : '#1890FF' }} />
                        <strong style={{ fontSize: 16 }}>{period.periodName}</strong>
                        <Tag color={cfg.color}>{cfg.label}</Tag>
                      </Space>
                    }
                    actions={[
                      period.status === 'INIT' && (
                        <Button
                          type="link"
                          size="small"
                          icon={<PlayCircleOutlined />}
                          onClick={() => handleLaunch(period)}
                        >
                          发起考核
                        </Button>
                      ),
                      period.status === 'INIT' && (
                        <Button
                          type="link"
                          size="small"
                          icon={<EditOutlined />}
                          onClick={() => handleEdit(period)}
                        >
                          编辑
                        </Button>
                      ),
                      (
                        <Button
                          type="link"
                          size="small"
                          icon={<BarChartOutlined />}
                          onClick={() => navigate(`/period-monitor/${period.periodId}`)}
                        >
                          监控
                        </Button>
                      ),
                      period.status !== 'COMPLETED' && (
                        <Button
                          type="link"
                          size="small"
                          danger
                          icon={<LockOutlined />}
                          onClick={() => handleClose(period)}
                        >
                          关闭
                        </Button>
                      ),
                    ].filter(Boolean)}
                  >
                    <div style={{ marginBottom: 4 }}>
                      <span style={{ color: '#8C8C8C', fontSize: 12 }}>开始日期：</span>
                      <span>{formatDate(period.startDate)}</span>
                    </div>
                    <div style={{ marginBottom: 4 }}>
                      <span style={{ color: '#8C8C8C', fontSize: 12 }}>结束日期：</span>
                      <span>{formatDate(period.endDate)}</span>
                    </div>
                    <div>
                      <span style={{ color: '#8C8C8C', fontSize: 12 }}>编号：</span>
                      <code style={{ fontSize: 12 }}>{period.periodId}</code>
                    </div>
                  </Card>
                </Col>
              );
            })}
          </Row>
        </div>
      )}

      <Modal
        title={editingPeriod ? '编辑考核周期' : '创建考核周期'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => { setModalVisible(false); setEditingPeriod(null); }}
        confirmLoading={submitting}
        okText={editingPeriod ? '保存' : '创建'}
        cancelText="取消"
        width={480}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="periodName"
            label="周期名称"
            rules={[{ required: true, message: '请输入周期名称' }]}
          >
            <Input placeholder="如 2025年Q1考核" maxLength={50} />
          </Form.Item>
          <Form.Item
            name="dateRange"
            label="起止日期"
            rules={[{ required: true, message: '请选择起止日期' }]}
          >
            <DatePicker.RangePicker
              style={{ width: '100%' }}
              placeholder={['开始日期', '结束日期']}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default PeriodConfigPage;
