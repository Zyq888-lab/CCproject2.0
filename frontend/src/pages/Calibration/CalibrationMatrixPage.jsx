{/* 模块用途：CalibrationMatrixPage——PD 校准矩阵页，分布汇总带 + 离群优先排序 + 行内改分（D11） */}
{/* 依赖组件：PageHeader, EmptyState, client.js, Ant Design Table/InputNumber/Select/Switch/Tag/Alert */}
{/* 修改注意：离群按原始分偏离组均值 ±1σ；改分对象为 composite 总分（0-5，两位小数），
    每次行内保存写 adjusted_score + 审计；409 冲突 toast 后刷新 */}
import { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Table, Tag, Switch, Space, Button, InputNumber, Select, Spin, Result, Alert, message, Modal,
} from 'antd';
import {
  ArrowLeftOutlined, EditOutlined, FundViewOutlined, RiseOutlined, FallOutlined, SendOutlined, CheckCircleOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

// 功能：改分原因预设——下拉必填，降低改分随意性
const REASON_OPTIONS = [
  '评估人评分尺度偏差',
  '项目难度/复杂度差异',
  '职能岗位特殊性',
  '数据录入错误',
  '其他',
];

// 功能：分数格式化——0-5 分制两位小数
const fmt = (v) => (v != null ? Number(v).toFixed(2) : '-');

function CalibrationMatrixPage() {
  const { periodId } = useParams();
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [onlyOutliers, setOnlyOutliers] = useState(false);
  const [editingKey, setEditingKey] = useState(null);
  const [editScore, setEditScore] = useState(null);
  const [editReason, setEditReason] = useState(null);
  const [saving, setSaving] = useState(false);
  const [userRoles, setUserRoles] = useState([]);
  const [submitVisible, setSubmitVisible] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const mountedRef = useRef(true);

  const isPd = userRoles.includes('ROLE_PD');
  // 是否已提交校准——由周期 calibrationSubmittedAt 推导（NULL=尚未提交）
  const submitted = !!data?.calibrationSubmittedAt;
  // 总裁是否退回过（存在 RETURNED 确认行）——退回后需 PD 重新提交，按钮据此放开（问题2）
  const hasReturned = !!data?.hasReturned;
  // 提交按钮可见：尚未提交，或已提交但总裁退回需重新提交
  const canSubmit = !submitted || hasReturned;

  // 功能：加载校准矩阵——汇总带 + 离群优先排序行
  const fetchData = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get(`/periods/${periodId}/calibration`);
      if (mountedRef.current) setData(res.data || { summary: [], rows: [] });
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

  // 功能：获取当前用户角色——「提交校准」按钮仅 PD 可见
  useEffect(() => {
    client.get('/auth/me').then((res) => {
      const d = res.data || res;
      setUserRoles(d.roles || []);
    }).catch(() => { /* 非关键 */ });
  }, []);

  // 功能：提交校准——POST submit-calibration，成功后刷新以切换为「已提交待总裁确认」状态
  const doSubmit = async () => {
    setSubmitting(true);
    try {
      await client.post(`/periods/${periodId}/submit-calibration`);
      message.success({ content: '已提交校准，等待总裁确认', duration: 3 });
      setSubmitVisible(false);
      fetchData();
    } catch (err) {
      message.error({ content: err?.message || '提交失败' });
    } finally {
      setSubmitting(false);
    }
  };

  // 功能：进入行内编辑——预填当前调整分
  const startEdit = (row) => {
    setEditingKey(row.assesseeId);
    setEditScore(row.adjustedScore != null ? Number(row.adjustedScore) : null);
    setEditReason(null);
  };

  // 功能：保存改分——PUT adjust，409 冲突 toast 后刷新
  const saveEdit = async (row) => {
    if (editScore == null || editScore < 0 || editScore > 5) {
      message.warning({ content: '请输入 0-5 之间的分数' });
      return;
    }
    if (!editReason) {
      message.warning({ content: '请选择改分原因' });
      return;
    }
    setSaving(true);
    try {
      await client.put(`/periods/${periodId}/calibration/adjust`, {
        assesseeId: row.assesseeId,
        newScore: editScore,
        reason: editReason,
      });
      message.success({ content: '改分已保存', duration: 2 });
      setEditingKey(null);
      fetchData();
    } catch (err) {
      if (err?.code === 409) {
        message.error({ content: '该行已被他人修改，已刷新', duration: 3 });
        setEditingKey(null);
        fetchData();
      } else {
        message.error({ content: err?.message || '保存失败' });
      }
    } finally {
      setSaving(false);
    }
  };

  const rows = data?.rows || [];
  const filteredRows = onlyOutliers ? rows.filter((r) => r.outlier) : rows;
  const outlierCount = rows.filter((r) => r.outlier).length;
  // 未提交员工 → 暗行（沉底，无成绩无改分入口）
  const unsubmittedRows = (data?.unsubmitted || []).map((u) => ({
    assesseeId: u.assesseeId,
    employeeName: u.employeeName,
    unsubmitted: true,
  }));
  const tableRows = [...filteredRows, ...unsubmittedRows];

  // 功能：离群标记——红↑偏高 / 蓝↓偏低，附 σ 偏离度
  const renderOutlier = (_, row) => {
    if (!row.outlier) {
      return <span style={{ color: '#BFBFBF' }}>—</span>;
    }
    const sign = row.direction === 'HIGH' ? '+' : '-';
    const icon = row.direction === 'HIGH' ? <RiseOutlined /> : <FallOutlined />;
    const dev = row.deviation != null ? `${sign}${Math.abs(Number(row.deviation)).toFixed(2)}σ` : '';
    return (
      <Tag color={row.direction === 'HIGH' ? 'red' : 'blue'} style={{ marginInlineEnd: 0 }}>
        {icon} {row.direction === 'HIGH' ? '偏高' : '偏低'} {dev}
      </Tag>
    );
  };

  // 功能：调整后总分单元格——编辑态显示 InputNumber + 原因下拉，否则显示分值 + 原分 ghost 差额
  const renderAdjusted = (_, row) => {
    if (editingKey === row.assesseeId) {
      return (
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          <InputNumber
            min={0}
            max={5}
            step={0.01}
            precision={2}
            value={editScore}
            onChange={setEditScore}
            style={{ width: 100 }}
            placeholder="0-5"
          />
          <Select
            placeholder="选择改分原因"
            value={editReason || undefined}
            onChange={setEditReason}
            style={{ width: 200 }}
            options={REASON_OPTIONS.map((r) => ({ value: r, label: r }))}
          />
        </Space>
      );
    }
    const delta = row.adjusted
      ? Number(row.adjustedScore) - Number(row.originalScore)
      : 0;
    return (
      <div>
        <span style={{ fontWeight: 600, fontSize: 15 }}>{fmt(row.adjustedScore)}</span>
        {row.adjusted && (
          <div style={{ color: '#8C8C8C', fontSize: 12, textDecoration: 'line-through' }}>
            原 {fmt(row.originalScore)}
          </div>
        )}
        {row.adjusted && (
          <span style={{ color: delta > 0 ? '#52C41A' : '#FF4D4F', fontSize: 12 }}>
            {delta > 0 ? '↑' : '↓'}{Math.abs(delta).toFixed(2)}
          </span>
        )}
      </div>
    );
  };

  const columns = [
    { title: '员工', dataIndex: 'employeeName', key: 'employeeName', width: 130, fixed: 'left',
      render: (v, row) => (
        <div>
          <div style={{ fontWeight: 500 }}>{v || row.assesseeId}</div>
          <div style={{ color: '#8C8C8C', fontSize: 12 }}>{row.assesseeId}</div>
        </div>
      ) },
    { title: '项目', dataIndex: 'groupLabel', key: 'groupLabel', width: 160,
      render: (v) => v || '-' },
    { title: '原始总分', dataIndex: 'originalScore', key: 'originalScore', width: 100, align: 'center',
      render: (v) => fmt(v) },
    { title: '调整后总分', dataIndex: 'adjustedScore', key: 'adjustedScore', width: 250,
      render: renderAdjusted },
    { title: '离群标记', dataIndex: 'outlier', key: 'outlier', width: 130, align: 'center',
      render: renderOutlier },
    { title: '总裁确认', dataIndex: 'confirmationStatus', key: 'confirmationStatus', width: 200,
      render: (status, row) => {
        if (status === 'RETURNED') {
          return (
            <div>
              <Tag color="red">已退回</Tag>
              {row.returnReason && (
                <div style={{ color: '#FF4D4F', fontSize: 12, marginTop: 2 }}>{row.returnReason}</div>
              )}
            </div>
          );
        }
        if (status === 'APPROVED') return <Tag color="green">已通过</Tag>;
        if (status === 'PENDING') return <Tag color="orange">待确认</Tag>;
        return <span style={{ color: '#BFBFBF' }}>-</span>;
      } },
    { title: '操作', key: 'action', width: 120, fixed: 'right', align: 'center',
      render: (_, row) => {
        if (row.unsubmitted) {
          return <Tag color="default">未提交</Tag>;
        }
        if (editingKey === row.assesseeId) {
          return (
            <Space size={4}>
              <Button type="primary" size="small" loading={saving} onClick={() => saveEdit(row)}>保存</Button>
              <Button size="small" onClick={() => setEditingKey(null)}>取消</Button>
            </Space>
          );
        }
        // 总裁退回后仅被退回人员可重新改分，其余只读；未退回（初始校准/待确认）保持全员可编辑（问题2）
        const canEdit = !hasReturned || row.confirmationStatus === 'RETURNED';
        if (!canEdit) {
          return <span style={{ color: '#BFBFBF', fontSize: 13 }}>只读</span>;
        }
        return <Button type="link" size="small" icon={<EditOutlined />} onClick={() => startEdit(row)}>改分</Button>;
      } },
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

  const isEmpty = !loading && !error && rows.length === 0 && unsubmittedRows.length === 0;

  return (
    <div id="calibration-matrix-area">
      <PageHeader
        title={`考核校准 — ${data?.periodName || periodId}`}
        breadcrumb={[{ title: '首页', path: '/dashboard' }, { title: '考核周期', path: '/period-config' }]}
        actions={[
          { label: '返回周期列表', icon: <ArrowLeftOutlined />, onClick: () => navigate('/period-config') },
          ...(isPd && data && canSubmit
            ? [{ label: hasReturned ? '重新提交校准' : '提交校准', icon: <SendOutlined />, type: 'primary', onClick: () => setSubmitVisible(true) }]
            : []),
          ...(isPd && submitted && !hasReturned
            ? [{ label: '已提交待总裁确认', icon: <CheckCircleOutlined />, disabled: true }]
            : []),
        ]}
      />

      {error && data && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchData}>重试</Button>
        </div>
      )}

      {/* 功能：未提交人数告警——完整性软门，不阻断校准 */}
      {data?.unsubmittedCount > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={`${data.unsubmittedCount} 人尚未提交考核结果，其成绩未纳入本次校准`}
        />
      )}

      {/* 功能：分布汇总带——按项目/职能展示人数、均分、σ、离群数，离群优先定位 */}
      {!isEmpty && data?.summary?.length > 0 && (
        <Card id="calibration-summary-band" title="分布汇总" style={{ marginBottom: 16, borderRadius: 8 }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
            {data.summary.map((g) => (
              <div
                key={g.key}
                style={{
                  minWidth: 160,
                  padding: '12px 16px',
                  border: g.outlierCount > 0 ? '1px solid #FFA39E' : '1px solid #F0F0F0',
                  borderRadius: 8,
                  background: g.outlierCount > 0 ? '#FFF1F0' : '#FAFAFA',
                }}
              >
                <div style={{ fontSize: 13, color: '#595959', marginBottom: 4 }}>{g.label}</div>
                <div style={{ fontSize: 20, fontWeight: 600, lineHeight: 1.2 }}>{fmt(g.avg)}</div>
                <div style={{ fontSize: 12, color: '#8C8C8C', marginTop: 4 }}>
                  {g.count} 人 · σ {fmt(g.sigma)} · 离群 {g.outlierCount}
                </div>
              </div>
            ))}
          </div>
        </Card>
      )}

      {isEmpty && (
        <EmptyState
          image={<FundViewOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="暂无校准数据"
          description="进入校准前，尚未生成员工考核结果，请先由管理员将周期推进到「校准中」"
        />
      )}

      {!isEmpty && (
        <Card id="calibration-table-card" style={{ borderRadius: 8 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
            <span style={{ color: '#595959' }}>
              共 {rows.length} 人 · 离群 {outlierCount} 人 · 未提交 {unsubmittedRows.length} 人
            </span>
            <Space>
              <span style={{ color: '#595959' }}>只看离群</span>
              <Switch checked={onlyOutliers} onChange={setOnlyOutliers} />
            </Space>
          </div>
          <Table
            columns={columns}
            dataSource={tableRows}
            rowKey={(r) => (r.unsubmitted ? `unsub-${r.assesseeId}` : r.assesseeId)}
            loading={loading}
            size="middle"
            pagination={false}
            scroll={{ x: 1090, y: 520 }}
            rowClassName={(row) => (row.unsubmitted ? 'calibration-row-unsubmitted' : (row.outlier ? 'calibration-row-outlier' : ''))}
            locale={{ emptyText: onlyOutliers ? '无离群员工' : '暂无数据' }}
          />
        </Card>
      )}

      {/* 功能：提交校准二次确认——确认后进入「待总裁确认」，确认前仍可继续改分 */}
      <Modal
        title="提交校准"
        open={submitVisible}
        onOk={doSubmit}
        onCancel={() => setSubmitVisible(false)}
        okText="确认提交"
        cancelText="取消"
        confirmLoading={submitting}
        width={440}
        centered
      >
        <div style={{ lineHeight: 1.7 }}>
          <p>提交后，本轮校准将进入「待总裁确认」状态。</p>
          <p style={{ color: '#595959' }}>总裁确认前仍可继续改分，是否确认已完成校准并提交？</p>
        </div>
      </Modal>
    </div>
  );
}

export default CalibrationMatrixPage;
