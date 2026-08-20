{/* 模块用途：MyAssessmentPage——我的指标页，平铺列表展示 KPI 指标(项目/阶段/指标名称/评价标准/权重/评估人/状态) */}
{/* 依赖组件：PageHeader, EmptyState, client.js, Ant Design Table/Tag/Spin/Result/Button */}
{/* 修改注意：仅员工角色可见；无项目时显示"暂无考核项目，请联系PM分配项目角色" */}
import { useState, useEffect, useRef } from 'react';
import {
  Table, Tag, Spin, Result, Button,
} from 'antd';
import { ProfileOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

const STATUS_LABEL_MAP = {
  PENDING: '待评分',
  IN_PROGRESS: '评分中',
  SUBMITTED: '已提交',
  RETURNED: '已退回',
  CONFIRMED: '已确认',
  CANCELED: '已取消',
};

const STATUS_COLOR_MAP = {
  PENDING: 'orange',
  IN_PROGRESS: 'blue',
  SUBMITTED: 'green',
  RETURNED: 'red',
  CONFIRMED: 'cyan',
  CANCELED: 'default',
};

// 功能：权重格式化——DECIMAL(5,4) 小数转百分比，如 0.5 → 50%，0.3333 → 33.33%
const formatWeight = (w) => {
  if (w == null) return '-';
  const pct = Math.round(Number(w) * 10000) / 100;
  return `${pct}%`;
};

function MyAssessmentPage() {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const mountedRef = useRef(true);

  // 功能：获取当前员工的项目考核聚合结果
  const fetchData = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/my-assessment');
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

  // 功能：平铺列表——每个 KPI 一行，合并任务与指标字段；KPI 为空的指标跳过（不显示占位行）
  const rows = [];
  let rowIndex = 0;
  items.forEach((it) => {
    const kpis = it.kpis || [];
    kpis.forEach((kpi) => {
      rows.push({
        key: `row-${rowIndex++}`,
        item: it,
        periodName: it.periodName || it.periodId || '-',
        projectName: it.projectName || it.projectCode || '职能考核',
        projectStage: it.projectStage,
        kpiName: kpi.kpiName,
        evaluationCriteria: kpi.evaluationCriteria,
        weight: kpi.weight,
        assessorName: it.assessorName,
        status: it.status,
      });
    });
  });

  const columns = [
    { title: '考核周期', dataIndex: 'periodName', key: 'periodName', width: 140,
      render: (v) => v || '-' },
    { title: '项目', dataIndex: 'projectName', key: 'projectName',
      render: (v) => v || '-' },
    { title: '阶段', dataIndex: 'projectStage', key: 'projectStage', width: 100,
      render: (v) => v || '-' },
    { title: '指标名称', dataIndex: 'kpiName', key: 'kpiName',
      render: (v) => v || '-' },
    { title: '评价标准', dataIndex: 'evaluationCriteria', key: 'evaluationCriteria',
      render: (v) => v || '-' },
    { title: '权重', dataIndex: 'weight', key: 'weight', width: 100,
      render: (v) => formatWeight(v) },
    { title: '评估人', dataIndex: 'assessorName', key: 'assessorName', width: 120,
      render: (v) => v || '-' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (v) => (
        <Tag color={STATUS_COLOR_MAP[v] || 'default'}>
          {STATUS_LABEL_MAP[v] || v || '-'}
        </Tag>
      ) },
  ];

  const isEmpty = !loading && !error && items.length === 0;

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
    <div id="my-assessment-page-area">
      <PageHeader
        title="我的指标"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
      />

      {error && items.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchData}>重试</Button>
        </div>
      )}

      {isEmpty && (
        <EmptyState
          image={<ProfileOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="暂无考核项目"
          description="请联系PM分配项目角色"
        />
      )}

      {!isEmpty && (
        <Table
          id="my-assessment-list"
          columns={columns}
          dataSource={rows}
          pagination={false}
          size="middle"
          scroll={{ x: 'max-content' }}
          locale={{ emptyText: '暂无考核指标' }}
        />
      )}
    </div>
  );
}

export default MyAssessmentPage;
