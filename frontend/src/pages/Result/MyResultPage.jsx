{/* 模块用途：MyResultPage——我的结果入口页，列出各周期，仅 CONFIRMED/COMPLETED 可查看结果 */}
{/* 依赖组件：PageHeader, EmptyState, client.js, Ant Design Table/Tag/Button/Spin/Result */}
{/* 修改注意：复用 GET /periods（全员只读），前端按状态过滤可查看性，无需新增后端列表接口 */}
import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Table, Tag, Button, Spin, Result } from 'antd';
import { TrophyOutlined, EyeOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

const STATUS_CONFIG = {
  INIT:        { color: 'default', label: '未开始' },
  ONGOING:     { color: 'processing', label: '进行中' },
  CALIBRATING: { color: 'warning', label: '校准中' },
  CONFIRMED:   { color: 'cyan', label: '已确认' },
  COMPLETED:   { color: 'success', label: '已完成' },
};

const VISIBLE_STATUS = ['CONFIRMED', 'COMPLETED'];

// 功能：日期格式化——截取到日
const formatDate = (d) => {
  if (!d) return '-';
  return d.length > 10 ? d.substring(0, 10) : d;
};

function MyResultPage() {
  const navigate = useNavigate();
  const [periods, setPeriods] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const mountedRef = useRef(true);

  // 功能：加载全部周期，前端按状态过滤结果可见性
  const fetchData = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/periods');
      if (mountedRef.current) setPeriods(Array.isArray(res.data) ? res.data : []);
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

  const columns = [
    { title: '考核周期', dataIndex: 'periodName', key: 'periodName',
      render: (v) => v || '-' },
    { title: '起止日期', key: 'date', width: 220,
      render: (_, r) => `${formatDate(r.startDate)} ~ ${formatDate(r.endDate)}` },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (v) => {
        const cfg = STATUS_CONFIG[v] || { color: 'default', label: v };
        return <Tag color={cfg.color}>{cfg.label}</Tag>;
      } },
    { title: '操作', key: 'action', width: 160, align: 'center',
      render: (_, r) => (
        VISIBLE_STATUS.includes(r.status) ? (
          <Button type="link" size="small" icon={<EyeOutlined />}
            onClick={() => navigate(`/period-result/${r.periodId}`)}>
            查看结果
          </Button>
        ) : (
          <span style={{ color: '#BFBFBF', fontSize: 13 }}>结果尚未发布</span>
        )
      ) },
  ];

  const isEmpty = !loading && !error && periods.length === 0;

  if (loading && periods.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large"><div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div></Spin>
      </div>
    );
  }

  if (error && periods.length === 0) {
    return (
      <Result status="error" title="加载失败" subTitle={error}
        extra={<Button type="primary" onClick={fetchData}>重试</Button>} />
    );
  }

  return (
    <div id="my-result-page-area">
      <PageHeader title="我的结果" breadcrumb={[{ title: '首页', path: '/dashboard' }]} />

      {error && periods.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchData}>重试</Button>
        </div>
      )}

      {isEmpty && (
        <EmptyState
          image={<TrophyOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="暂无考核周期"
          description="暂无已创建的考核周期，结果发布后可在此查看"
        />
      )}

      {!isEmpty && (
        <Table
          id="my-result-list"
          columns={columns}
          dataSource={periods}
          rowKey="periodId"
          loading={loading}
          size="middle"
          pagination={false}
          locale={{ emptyText: '暂无数据' }}
        />
      )}
    </div>
  );
}

export default MyResultPage;
