{/* 模块用途：ConfirmPage——总裁确认页：就绪一句话 + 关键统计 + 确认并发布(强保护二次确认) + 矩阵 drill-down */}
{/* 依赖组件：PageHeader, client.js, Ant Design Card/Button/Modal/Input/Table/Tag/Alert/Statistic */}
{/* 修改注意：确认动作为强保护二次确认——后果文案 + 输入周期名一致才可提交（D9）；确认后周期 CONFIRMED，结果对员工可见 */}
import { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Button, Table, Tag, Modal, Input, Spin, Result, Alert, message, Row, Col,
} from 'antd';
import {
  ArrowLeftOutlined, CheckCircleOutlined, RiseOutlined, FallOutlined, EditOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import client from '../../api/client';

// 功能：分数格式化——0-5 分制两位小数
const fmt = (v) => (v != null ? Number(v).toFixed(2) : '-');

function ConfirmPage() {
  const { periodId } = useParams();
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [confirmVisible, setConfirmVisible] = useState(false);
  const [confirmName, setConfirmName] = useState('');
  const [confirming, setConfirming] = useState(false);
  const mountedRef = useRef(true);

  // 功能：加载校准矩阵——汇总 + 离群 + 改分统计，供总裁确认页派生就绪度与统计
  const fetchData = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get(`/periods/${periodId}/calibration`);
      if (mountedRef.current) setData(res.data || { summary: [], rows: [], unsubmitted: [] });
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

  const rows = data?.rows || [];
  const submittedCount = rows.length;
  const adjustCount = rows.filter((r) => r.adjusted).length;
  const outlierCount = (data?.summary || []).reduce((s, g) => s + (g.outlierCount || 0), 0);
  const avgScore = submittedCount
    ? rows.reduce((s, r) => s + Number(r.adjustedScore ?? 0), 0) / submittedCount
    : null;

  // 功能：确认发布——PUT confirm；400/409 表示状态已变更，提示并刷新
  const doConfirm = async () => {
    setConfirming(true);
    try {
      await client.put(`/periods/${periodId}/confirm`);
      message.success({ content: '已确认发布，员工现可查看考核结果', duration: 4 });
      navigate('/period-config');
    } catch (err) {
      message.error({ content: err?.message || '确认发布失败' });
      setConfirmVisible(false);
      fetchData();
    } finally {
      setConfirming(false);
    }
  };

  // 功能：离群标记——红↑偏高 / 蓝↓偏低（只读展示，与校准矩阵一致）
  const renderOutlier = (_, row) => {
    if (!row.outlier) return <span style={{ color: '#BFBFBF' }}>—</span>;
    const icon = row.direction === 'HIGH' ? <RiseOutlined /> : <FallOutlined />;
    const dev = row.deviation != null ? `${Math.abs(Number(row.deviation)).toFixed(2)}σ` : '';
    return (
      <Tag color={row.direction === 'HIGH' ? 'red' : 'blue'} style={{ marginInlineEnd: 0 }}>
        {icon} {row.direction === 'HIGH' ? '偏高' : '偏低'} {dev}
      </Tag>
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
    { title: '调整后总分', dataIndex: 'adjustedScore', key: 'adjustedScore', width: 120, align: 'center',
      render: (v, row) => (
        <span>
          <span style={{ fontWeight: 600 }}>{fmt(v)}</span>
          {row.adjusted && (
            <span style={{ color: '#8C8C8C', fontSize: 12, marginLeft: 6, textDecoration: 'line-through' }}>
              原{fmt(row.originalScore)}
            </span>
          )}
        </span>
      ) },
    { title: '离群标记', dataIndex: 'outlier', key: 'outlier', width: 130, align: 'center',
      render: renderOutlier },
  ];

  const stats = [
    { label: '提交人数', value: submittedCount, color: '#1890FF' },
    { label: '未提交', value: data?.unsubmittedCount ?? 0, color: '#FAAD14' },
    { label: '改分次数', value: adjustCount, color: '#722ED1' },
    { label: '平均分', value: avgScore != null ? fmt(avgScore) : '-', color: '#52C41A' },
    { label: '离群数', value: outlierCount, color: '#FF4D4F' },
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

  return (
    <div id="confirm-page-area">
      <PageHeader
        title={`总裁确认 — ${data?.periodName || periodId}`}
        breadcrumb={[{ title: '首页', path: '/dashboard' }, { title: '考核周期', path: '/period-config' }]}
        actions={[
          { label: '返回周期列表', icon: <ArrowLeftOutlined />, onClick: () => navigate('/period-config') },
          {
            label: '确认并发布',
            icon: <CheckCircleOutlined />,
            type: 'primary',
            danger: true,
            onClick: () => { setConfirmName(''); setConfirmVisible(true); },
          },
        ]}
      />

      {error && data && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchData}>重试</Button>
        </div>
      )}

      {/* 功能：就绪一句话 + 未提交告警——未提交员工成绩未纳入校准，确认前完整性软门不阻断 */}
      {data && (
        <Alert
          type={data.unsubmittedCount > 0 ? 'warning' : 'success'}
          showIcon
          style={{ marginBottom: 16 }}
          message={`周期「${data.periodName}」· ${submittedCount} 名员工 · 已校准 · ${adjustCount} 处改分 · ${data.unsubmittedCount} 人未提交`}
          description={data.unsubmittedCount > 0 ? '未提交员工的成绩未纳入本次校准与发布。' : '全部员工均已提交，可放心发布。'}
        />
      )}

      {/* 功能：PD 提交状态提示——calibrationSubmittedAt 有值=PD 已提交待总裁确认 */}
      {data && (
        <Alert
          type={data.calibrationSubmittedAt ? 'success' : 'warning'}
          showIcon
          style={{ marginBottom: 16 }}
          message={data.calibrationSubmittedAt ? 'PD 已提交校准，待总裁确认' : 'PD 尚未提交校准'}
        />
      )}

      {/* 功能：关键统计卡——提交/未提交/改分/平均分/离群，一屏掌握校准全貌 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        {stats.map((s) => (
          <Col key={s.label} xs={12} sm={8} lg={4}>
            <Card style={{ borderRadius: 8, textAlign: 'center' }}>
              <div style={{ fontSize: 13, color: '#595959', marginBottom: 6 }}>{s.label}</div>
              <div style={{ fontSize: 24, fontWeight: 600, color: s.color, lineHeight: 1.2 }}>{s.value}</div>
            </Card>
          </Col>
        ))}
      </Row>

      {/* 功能：矩阵 drill-down——只读员工结果表 + 去校准调整入口 */}
      <Card
        id="confirm-matrix-card"
        title="校准结果明细"
        extra={<Button size="small" icon={<EditOutlined />} onClick={() => navigate(`/period-calibration/${periodId}`)}>去校准调整</Button>}
        style={{ borderRadius: 8 }}
      >
        <Table
          columns={columns}
          dataSource={rows}
          rowKey="assesseeId"
          loading={loading}
          size="middle"
          pagination={false}
          scroll={{ x: 640 }}
          locale={{ emptyText: '暂无校准结果' }}
        />
      </Card>

      {/* 功能：强保护二次确认——后果文案 + 输入周期名一致才可提交（D9） */}
      <Modal
        title="确认发布考核结果"
        open={confirmVisible}
        onOk={doConfirm}
        onCancel={() => setConfirmVisible(false)}
        okText="确认发布"
        okButtonProps={{ danger: true, disabled: confirmName.trim() !== data?.periodName }}
        cancelText="取消"
        confirmLoading={confirming}
        width={480}
        centered
      >
        <div style={{ marginBottom: 16, lineHeight: 1.7 }}>
          <p style={{ fontWeight: 500, marginBottom: 8 }}>发布后：</p>
          <ul style={{ paddingLeft: 20, color: '#595959', marginBottom: 8 }}>
            <li>所有员工将立即看到各自的最终考核结果；</li>
            <li>校准数据将锁定，<b>不可再改分</b>；</li>
            <li>此操作<b>不可撤销</b>，请确认校准已完成。</li>
          </ul>
          <p style={{ color: '#FF4D4F' }}>请输入周期名称「{data?.periodName}」以确认发布：</p>
        </div>
        <Input
          placeholder="请输入周期名称"
          value={confirmName}
          onChange={(e) => setConfirmName(e.target.value)}
          onPressEnter={() => { if (confirmName.trim() === data?.periodName) doConfirm(); }}
        />
      </Modal>
    </div>
  );
}

export default ConfirmPage;
