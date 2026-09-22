{/* 模块用途：PeriodMonitorPage——周期监控页，展示某周期下所有考核任务的员工/项目/类型/状态/审批节点，支持筛选与CSV导出 */}
{/* 依赖组件：PageHeader, EmptyState, client.js, xlsx, Ant Design Table/Select/Tag/Button/Modal/Descriptions */}
{/* 修改注意：ADMIN 全见、PM 仅见自己项目由后端过滤；当前审批节点由 task.status 推导 */}
import { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Table, Tag, Space, Select, Button, Spin, Result, Modal, Descriptions, Alert,
} from 'antd';
import {
  ArrowLeftOutlined, DownloadOutlined, FundViewOutlined, EyeOutlined,
} from '@ant-design/icons';
import * as XLSX from 'xlsx';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

// 状态文案与审批节点文案由后端按任务真实状态映射返回（r.status / r.nodeLabel 已是中文），前端只负责颜色与覆盖
const STATUS_COLOR_MAP = {
  待评分: 'orange',
  评分中: 'blue',
  已提交: 'green',
  已确认: 'cyan',
  已取消: 'default',
  待校准: 'warning',
  已校准: 'success',
};

const STATUS_OPTIONS = ['待评分', '评分中', '已提交', '已确认', '已取消', '待校准', '已校准']
  .map((label) => ({ value: label, label }));

const TASK_TYPE_LABEL = { PROJECT: '项目考核', FUNCTIONAL: '职能考核' };

// 功能：权重格式化——DECIMAL 小数转百分比，如 0.5 → 50%
const formatWeight = (w) => (w != null ? `${Math.round(Number(w) * 100)}%` : '-');

// 功能：当前审批节点文案——CALIBRATING 期按项目级总裁确认状态优先（APPROVED=总裁已确认/RETURNED=退回待重提），
//   再回落校准提交状态（该项目未提交=待校准；已提交=总裁确认中）；PUBLISHED 期显示已发布，其余沿用后端映射的 nodeLabel
const resolveNodeLabel = (r) => {
  if (r?.periodStatus === 'CALIBRATING') {
    if (r.confirmationStatus === 'APPROVED') return '总裁已确认';
    if (r.confirmationStatus === 'RETURNED') return '退回待重提';
    return r.nodeLabel || '-';
  }
  if (r?.periodStatus === 'CONFIRMED') {
    return '待发布';
  }
  if (r?.periodStatus === 'PUBLISHED') {
    return '已发布';
  }
  return r.nodeLabel || '-';
};

// 功能：状态列文案——CALIBRATING 期按总裁确认状态覆盖任务级状态（APPROVED=已确认 / RETURNED=已退回）
const resolveStatusLabel = (r) => {
  if (r?.periodStatus === 'CALIBRATING') {
    if (r.confirmationStatus === 'APPROVED') return '已确认';
    if (r.confirmationStatus === 'RETURNED') return '已退回';
  }
  return r.status || '-';
};

// 功能：状态列颜色——与 resolveStatusLabel 同口径
const resolveStatusColor = (r) => {
  if (r?.periodStatus === 'CALIBRATING') {
    if (r.confirmationStatus === 'APPROVED') return 'cyan';
    if (r.confirmationStatus === 'RETURNED') return 'red';
  }
  return STATUS_COLOR_MAP[r.status] || 'default';
};

function PeriodMonitorPage() {
  const { periodId } = useParams();
  const navigate = useNavigate();
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [filters, setFilters] = useState({ status: '', project: '', employee: '', node: '' });
  const [detail, setDetail] = useState(null);
  const mountedRef = useRef(true);

  // 功能：获取周期监控列表
  const fetchData = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get(`/periods/${periodId}/monitor`);
      if (mountedRef.current) {
        setData(Array.isArray(res.data) ? res.data : []);
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
  }, [periodId]); // eslint-disable-line react-hooks/exhaustive-deps

  // 功能：从数据推导筛选下拉选项（项目/员工）
  const projectOptions = [...new Map(
    data.filter((r) => r.projectCode).map((r) => [r.projectCode, r.projectName || r.projectCode]),
  ).entries()].map(([value, label]) => ({ value, label }));
  const employeeOptions = [...new Map(
    data.map((r) => [r.employeeId, r.employeeName || r.employeeId]),
  ).entries()].map(([value, label]) => ({ value, label }));
  const nodeOptions = [...new Set(data.map((r) => resolveNodeLabel(r)))].map((label) => ({ value: label, label }));

  // 功能：客户端筛选——状态/项目/员工/当前审批节点
  const filteredData = data.filter((r) => {
    if (filters.status && r.status !== filters.status) return false;
    if (filters.project && r.projectCode !== filters.project) return false;
    if (filters.employee && r.employeeId !== filters.employee) return false;
    if (filters.node && resolveNodeLabel(r) !== filters.node) return false;
    return true;
  });

  // 功能：导出CSV——UTF-8 BOM 保证 Excel 中文不乱码
  const handleExport = () => {
    const rows = filteredData.map((r) => ({
      员工: r.employeeName || r.employeeId || '-',
      项目: r.projectName || r.projectCode || '-',
      任务类型: TASK_TYPE_LABEL[r.taskType] || r.taskType || '-',
      状态: resolveStatusLabel(r),
      当前审批节点: resolveNodeLabel(r),
      当前审批人: r.currentApproverName || r.currentApproverId || '-',
      评分进度: r.kpiCount ? `${r.scoredCount ?? 0}/${r.kpiCount}` : '-',
      加权总分: r.totalScore != null ? Number(r.totalScore).toFixed(2) : '-',
      评估人: r.assessorName || r.assessorId || '-',
    }));
    const ws = XLSX.utils.json_to_sheet(rows);
    const csv = XLSX.utils.sheet_to_csv(ws);
    const blob = new Blob([`﻿${csv}`], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = `周期监控_${periodId}.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
  };

  const columns = [
    { title: '员工', dataIndex: 'employeeName', key: 'employeeName', width: 120,
      render: (v, r) => v || r.employeeId || '-' },
    { title: '项目', dataIndex: 'projectName', key: 'projectName', width: 180,
      render: (v, r) => v ? `${v}（${r.projectStage || '-'}）` : '-' },
    { title: '任务类型', dataIndex: 'taskType', key: 'taskType', width: 100,
      render: (t) => TASK_TYPE_LABEL[t] || t || '-' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (_, r) => <Tag color={resolveStatusColor(r)}>{resolveStatusLabel(r)}</Tag> },
    { title: '当前审批节点', dataIndex: 'currentNode', key: 'currentNode', width: 160,
      render: (_, r) => resolveNodeLabel(r) },
    { title: '当前审批人', dataIndex: 'currentApproverName', key: 'currentApproverName', width: 120,
      render: (v, r) => v || r.currentApproverId || '-' },
    { title: '评分进度', dataIndex: 'scoredCount', key: 'scoredCount', width: 100,
      render: (_, r) => (r.kpiCount ? `${r.scoredCount ?? 0}/${r.kpiCount}` : '-') },
    { title: '加权总分', dataIndex: 'totalScore', key: 'totalScore', width: 100,
      render: (v) => (v != null ? Number(v).toFixed(2) : '-') },
    { title: '操作', key: 'action', width: 100,
      render: (_, record) => (
        <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setDetail(record)}>查看</Button>
      ) },
  ];

  const isEmpty = !loading && !error && data.length === 0;

  if (loading && data.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large">
          <div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div>
        </Spin>
      </div>
    );
  }

  if (error && data.length === 0) {
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
    <div id="period-monitor-page-area">
      <PageHeader
        title={`周期监控 — ${periodId}`}
        breadcrumb={[{ title: '首页', path: '/dashboard' }, { title: '考核周期', path: '/period-config' }]}
        actions={[
          { label: '导出CSV', icon: <DownloadOutlined />, onClick: handleExport, disabled: filteredData.length === 0 },
        ]}
      />

      {error && data.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchData}>重试</Button>
        </div>
      )}

      {/* 功能：项目级确认汇总提示——仅 CALIBRATING 阶段展示，按已确认项目数/需确认项目数计算 */}
      {data[0]?.periodStatus === 'CALIBRATING' && (
        <Alert
          type={
            (data[0]?.confirmationProjectCount ?? 0) > 0
            && (data[0]?.confirmedProjectCount ?? 0) >= data[0]?.confirmationProjectCount
              ? 'success'
              : 'warning'
          }
          showIcon
          style={{ marginBottom: 16 }}
          message={`${data[0]?.confirmedProjectCount ?? 0}/${data[0]?.confirmationProjectCount ?? 0} 个项目已确认`}
        />
      )}

      {/* 功能：筛选栏——状态/项目/员工 */}
      <Card id="monitor-filter-bar" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Space wrap size="middle">
          <Select
            placeholder="按状态筛选"
            value={filters.status || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, status: v || '' }))}
            allowClear
            style={{ width: 140 }}
            options={STATUS_OPTIONS}
          />
          <Select
            placeholder="按项目筛选"
            value={filters.project || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, project: v || '' }))}
            allowClear
            showSearch
            optionFilterProp="label"
            style={{ width: 220 }}
            options={projectOptions}
          />
          <Select
            placeholder="按员工筛选"
            value={filters.employee || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, employee: v || '' }))}
            allowClear
            showSearch
            optionFilterProp="label"
            style={{ width: 180 }}
            options={employeeOptions}
          />
          <Select
            placeholder="按当前审批节点筛选"
            value={filters.node || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, node: v || '' }))}
            allowClear
            style={{ width: 180 }}
            options={nodeOptions}
          />
          <Button onClick={() => setFilters({ status: '', project: '', employee: '', node: '' })}>重置</Button>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/period-config')}>返回周期列表</Button>
        </Space>
      </Card>

      {isEmpty && (
        <EmptyState
          image={<FundViewOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="暂无监控数据"
          description="该周期下还没有考核任务，发起考核后将在这里看到各任务的流转进度"
        />
      )}

      {!isEmpty && (
        <Card id="monitor-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={filteredData}
            rowKey="taskId"
            loading={loading}
            size="middle"
            rowClassName={(_, index) => index % 2 === 1 ? 'table-row-striped' : ''}
            pagination={{ pageSize: 20, showSizeChanger: true, pageSizeOptions: [10, 20, 50, 100], showTotal: (t) => `共 ${t} 条` }}
            scroll={{ x: 800 }}
            locale={{ emptyText: '当前筛选条件下无匹配任务' }}
          />
        </Card>
      )}

      {/* 功能：查看详情弹窗——展示单条任务的完整信息 */}
      <Modal
        title="任务详情"
        open={!!detail}
        onCancel={() => setDetail(null)}
        footer={<Button type="primary" onClick={() => setDetail(null)}>关闭</Button>}
        width={520}
      >
        {detail && (
          <>
            <Descriptions column={1} size="small" bordered style={{ marginTop: 16 }}>
              <Descriptions.Item label="员工">{detail.employeeName || detail.employeeId || '-'}</Descriptions.Item>
              <Descriptions.Item label="评估人">{detail.assessorName || detail.assessorId || '-'}</Descriptions.Item>
              <Descriptions.Item label="项目">
                {detail.projectName ? `${detail.projectName}（${detail.projectCode}·${detail.projectStage || '-'}）` : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="任务类型">{TASK_TYPE_LABEL[detail.taskType] || detail.taskType || '-'}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={resolveStatusColor(detail)}>{resolveStatusLabel(detail)}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="当前审批节点">{resolveNodeLabel(detail)}</Descriptions.Item>
              <Descriptions.Item label="当前审批人">{detail.currentApproverName || detail.currentApproverId || '-'}</Descriptions.Item>
              <Descriptions.Item label="评分进度">{detail.kpiCount ? `${detail.scoredCount ?? 0}/${detail.kpiCount}` : '-'}</Descriptions.Item>
              <Descriptions.Item label="加权总分">{detail.totalScore != null ? Number(detail.totalScore).toFixed(2) : '-'}</Descriptions.Item>
            </Descriptions>
            <div style={{ marginTop: 16, fontWeight: 500 }}>指标明细</div>
            <Table
              size="small"
              style={{ marginTop: 8 }}
              pagination={false}
              rowKey="kpiConfigId"
              dataSource={detail.indicators || []}
              locale={{ emptyText: '无 KPI 指标配置' }}
              columns={[
                { title: '指标名称', dataIndex: 'indicatorName', key: 'indicatorName',
                  render: (v) => v || '-' },
                { title: '权重', dataIndex: 'weight', key: 'weight', width: 80,
                  render: (v) => formatWeight(v) },
                { title: '得分', dataIndex: 'score', key: 'score', width: 80,
                  render: (v) => (v != null ? v : '-') },
              ]}
            />
          </>
        )}
      </Modal>
    </div>
  );
}

export default PeriodMonitorPage;
