{/* 模块用途：ResultPage——员工结果页：大字结果分=adjusted_score + 原始分/差额/原因 + KPI 明细 + 凭证 fallback */}
{/* 依赖组件：PageHeader, EmptyState, client.js, Ant Design Card/Table/Tag/Spin/Result/Button */}
{/* 修改注意：结果分=adjusted_score（D3）；改分时并排「原始分→调整分 + 差额 + 原因」；凭证列 null 回退「凭证暂不可用」 */}
import { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Table, Tag, Spin, Result, Button, Descriptions, Space,
} from 'antd';
import { ArrowLeftOutlined, TrophyOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

// 功能：分数格式化——0-5 分制两位小数
const fmt = (v) => (v != null ? Number(v).toFixed(2) : '-');

// 功能：权重格式化——DECIMAL(5,4) 小数转百分比
const formatWeight = (w) => {
  if (w == null) return '-';
  const pct = Math.round(Number(w) * 10000) / 100;
  return `${pct}%`;
};

const KPI_TYPE_LABEL = { PROJECT: '项目', FUNCTIONAL: '职能' };
const KPI_TYPE_COLOR = { PROJECT: 'geekblue', FUNCTIONAL: 'purple' };

function ResultPage() {
  const { periodId } = useParams();
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const mountedRef = useRef(true);

  // 功能：加载本人考核结果——结果分 + KPI 明细
  const fetchData = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get(`/periods/${periodId}/result`);
      if (mountedRef.current) setData(res.data || null);
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

  const kpis = data?.kpis || [];
  const delta = data?.delta != null ? Number(data.delta) : 0;

  const columns = [
    { title: '类型', dataIndex: 'kpiType', key: 'kpiType', width: 80,
      render: (v) => (
        <Tag color={KPI_TYPE_COLOR[v] || 'default'}>{KPI_TYPE_LABEL[v] || v || '-'}</Tag>
      ) },
    { title: '项目', dataIndex: 'projectName', key: 'projectName', width: 140,
      render: (v) => v || '-' },
    { title: '阶段', dataIndex: 'projectStage', key: 'projectStage', width: 90,
      render: (v) => v || '-' },
    { title: '指标名称', dataIndex: 'kpiName', key: 'kpiName',
      render: (v) => v || '-' },
    { title: '权重', dataIndex: 'weight', key: 'weight', width: 90, align: 'center',
      render: (v) => formatWeight(v) },
    { title: '得分', dataIndex: 'score', key: 'score', width: 90, align: 'center',
      render: (v) => <span style={{ fontWeight: 600 }}>{fmt(v)}</span> },
    { title: '评估人', dataIndex: 'assessorName', key: 'assessorName', width: 120,
      render: (v) => v || '-' },
    { title: '凭证', dataIndex: 'evidenceUrl', key: 'evidenceUrl', width: 140,
      render: (v) => (
        v
          ? <a href={v} target="_blank" rel="noreferrer">查看凭证</a>
          : <span style={{ color: '#BFBFBF' }}>凭证暂不可用</span>
      ) },
  ];

  if (loading && !data) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large"><div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div></Spin>
      </div>
    );
  }

  if (error && !data) {
    return (
      <Result status="error" title="加载失败" subTitle={error}
        extra={<Button type="primary" onClick={fetchData}>重试</Button>} />
    );
  }

  if (!loading && !error && !data) {
    return (
      <div id="result-page-area">
        <PageHeader
          title="我的考核结果"
          breadcrumb={[{ title: '首页', path: '/dashboard' }, { title: '我的结果', path: '/my-result' }]}
        />
        <EmptyState
          image={<TrophyOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="暂无考核结果"
          description="考核结果尚未发布，或您不在本周期考核范围内"
        />
      </div>
    );
  }

  return (
    <div id="result-page-area">
      <PageHeader
        title={`考核结果 — ${data?.periodName || periodId}`}
        breadcrumb={[{ title: '首页', path: '/dashboard' }, { title: '我的结果', path: '/my-result' }]}
        actions={[
          { label: '返回我的结果', icon: <ArrowLeftOutlined />, onClick: () => navigate('/my-result') },
        ]}
      />

      {error && data && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchData}>重试</Button>
        </div>
      )}

      {/* 功能：大字结果分——结果分=adjusted_score（D3），改分时并排原始分→调整分 + 差额 + 原因 */}
      <Card style={{ borderRadius: 8, marginBottom: 16, textAlign: 'center' }}>
        <div style={{ color: '#8C8C8C', fontSize: 14, marginBottom: 4 }}>{data.employeeName} · 最终得分</div>
        <div style={{ fontSize: 56, fontWeight: 700, color: '#1890FF', lineHeight: 1.1 }}>
          {fmt(data.adjustedScore)}
        </div>
        <div style={{ color: '#595959', fontSize: 13, marginTop: 4 }}>
          项目 {data.projectName || '—'} · 考核周期 {data.periodName || periodId} · 校准人 {data.adjustedBy || '—'}
        </div>
        {data.adjusted ? (
          <Space size={8} wrap style={{ justifyContent: 'center', marginTop: 12 }}>
            <span style={{ color: '#8C8C8C', textDecoration: 'line-through' }}>原始分 {fmt(data.originalScore)}</span>
            <span style={{ color: '#BFBFBF' }}>→</span>
            <span style={{ fontWeight: 600 }}>{fmt(data.adjustedScore)}</span>
            <Tag color={delta > 0 ? 'green' : 'red'}>
              差额 {delta > 0 ? '+' : ''}{delta.toFixed(2)}
            </Tag>
            {data.adjustReason && (
              <span style={{ color: '#595959', fontSize: 13 }}>
                原因：<b>{data.adjustReason}</b>
              </span>
            )}
          </Space>
        ) : (
          <div style={{ color: '#8C8C8C', fontSize: 13, marginTop: 8 }}>本周期结果未经调整</div>
        )}
      </Card>

      {/* 功能：KPI 明细表——指标名/权重/得分/评估人 + 凭证 fallback */}
      <Card title="KPI 明细" style={{ borderRadius: 8 }}>
        <Table
          columns={columns}
          dataSource={kpis}
          rowKey={(row, idx) => `${row.kpiType}-${idx}`}
          loading={loading}
          size="middle"
          pagination={false}
          scroll={{ x: 920 }}
          locale={{ emptyText: '暂无 KPI 明细' }}
        />
      </Card>
    </div>
  );
}

export default ResultPage;
