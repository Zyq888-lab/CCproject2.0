{/* 模块用途：DashboardPage——仪表盘页，卡片引导方式展示8个配置模块及完成进度 */}
{/* 依赖组件：PageHeader, Ant Design Card/Progress/Spin, react-router-dom, client.js */}
{/* 修改注意：卡片顺序按推荐配置流程排列；config-progress API返回5项数据，缺失项默认"待配置" */}
import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Progress, Spin, Result, Button, Tag, Badge } from 'antd';
import {
  CheckCircleFilled,
  TeamOutlined,
  AimOutlined,
  FolderOutlined,
  LinkOutlined,
  SettingOutlined,
  LineChartOutlined,
  CalendarOutlined,
  CarryOutOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import client from '../../api/client';

// 功能：8张配置卡片定义——按推荐配置流程排列
const CARD_CONFIG = [
  { key: 'employee',       label: '员工管理',     Icon: TeamOutlined,      color: '#1890FF', link: '/employee/list' },
  { key: 'projectRole',    label: '项目角色',     Icon: AimOutlined,       color: '#52C41A', link: '/project-role' },
  { key: 'project',        label: '项目管理',     Icon: FolderOutlined,    color: '#FAAD14', link: '/project/list' },
  { key: 'roleAssignment', label: '角色分配',     Icon: LinkOutlined,      color: '#13C2C2', link: '/project/list' },
  { key: 'projectKpi',     label: '项目KPI配置',  Icon: LineChartOutlined, color: '#EB2F96', link: '/kpi-config/project' },
  { key: 'funcKpi',        label: '职能KPI配置',  Icon: LineChartOutlined, color: '#722ED1', link: '/kpi-config/functional' },
  { key: 'positionConfig', label: '岗位配置',     Icon: SettingOutlined,   color: '#FA8C16', link: '/position-config' },
  { key: 'periodConfig',   label: '考核周期',     Icon: CalendarOutlined,  color: '#2F54EB', link: '/period-config' },
];

// 功能：卡片网格样式——4列×2行
const GRID_STYLE = { display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 };

// 功能：从后端返回的5项数据中查找对应卡片的count，projectKpi使用kpi聚合值
function resolveCount(backendItems, cardKey) {
  if (cardKey === 'projectKpi') {
    const kpiItem = backendItems.find((i) => i.key === 'kpi');
    return kpiItem ? kpiItem.count : 0;
  }
  const item = backendItems.find((i) => i.key === cardKey);
  return item ? item.count : 0;
}

function DashboardPage() {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pendingCount, setPendingCount] = useState(0);
  const navigate = useNavigate();

  const mountedRef = useRef(true);

  // 功能：获取配置进度——挂载时调用，出错后可重试
  const fetchProgress = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/dashboard/config-progress');
      if (mountedRef.current) {
        setItems(res.data || []);
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
  }, []);

  // 功能：获取待处理任务数——按角色返回（评估人=待评分/员工=待参与/PM=待审批/ADMIN=差异）
  const fetchPendingCount = useCallback(async () => {
    try {
      const res = await client.get('/dashboard/pending-count');
      if (mountedRef.current) {
        setPendingCount(res.data || 0);
      }
    } catch (_) { /* 非关键 */ }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    fetchProgress();
    fetchPendingCount();
    return () => { mountedRef.current = false; };
  }, [fetchProgress, fetchPendingCount]);

  // 功能：加载中——显示Spin旋转加载
  if (loading) {
    return (
      <div id="dashboard-loading-area" style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: 400,
      }}>
        <Spin size="large">
          <div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div>
        </Spin>
      </div>
    );
  }

  // 功能：加载失败——显示错误结果+重试按钮
  if (error) {
    return (
      <Result
        status="error"
        title="加载失败"
        subTitle={error}
        extra={
          <Button type="primary" onClick={fetchProgress}>
            重试
          </Button>
        }
      />
    );
  }

  const configuredCount = CARD_CONFIG.filter((card) => resolveCount(items, card.key) > 0).length;
  const progressPercent = Math.round((configuredCount / CARD_CONFIG.length) * 100);

  // 功能：仪表盘布局——顶部进度条+8张配置卡片4列×2行
  return (
    <div id="dashboard-configured-area">
      <PageHeader
        title="仪表盘"
        breadcrumb={[{ title: '首页' }]}
      />

      {/* 功能：待处理任务卡——全员可见，显示按角色区分的待处理数 */}
      <Card id="dashboard-pending-card" style={{ borderRadius: 8, marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Badge count={pendingCount} size="small">
            <CarryOutOutlined style={{ fontSize: 28, color: '#1890FF' }} />
          </Badge>
          <span style={{ fontSize: 14, fontWeight: 500 }}>待处理任务</span>
          <span style={{ fontSize: 20, fontWeight: 600, color: pendingCount > 0 ? '#FA8C16' : '#52C41A' }}>
            {pendingCount}
          </span>
          <Button type="link" onClick={() => navigate('/tasks')}>查看任务 →</Button>
        </div>
      </Card>

      {/* 功能：配置完成度进度条——绿色百分比 */}
      <Card id="dashboard-progress-bar" style={{ borderRadius: 8, marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <span style={{ fontSize: 14, fontWeight: 500, whiteSpace: 'nowrap' }}>
            配置完成度
          </span>
          <Progress
            percent={progressPercent}
            strokeColor="#52C41A"
            style={{ flex: 1 }}
          />
        </div>
      </Card>

      {/* 功能：8张配置引导卡片——4列×2列CSS Grid，已配置项绿色左边框+绿色对勾 */}
      <div id="dashboard-config-cards" style={GRID_STYLE}>
        {CARD_CONFIG.map((card) => {
          const count = resolveCount(items, card.key);
          const isConfigured = count > 0;
          const { Icon } = card;
          return (
            <Card
              key={card.key}
              hoverable
              onClick={() => navigate(card.link)}
              style={{
                borderRadius: 8,
                borderLeft: isConfigured ? '3px solid #52C41A' : '3px solid #E8E8E8',
              }}
            >
              <div id={`card-${card.key}`} style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                {/* 功能：模块图标——已配置时显示对应颜色 */}
                <div style={{
                  width: 48,
                  height: 48,
                  borderRadius: 8,
                  background: isConfigured ? `${card.color}15` : '#FAFAFA',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: isConfigured ? card.color : '#D9D9D9',
                }}>
                  <Icon style={{ fontSize: 28 }} />
                </div>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 14, fontWeight: 500, marginBottom: 4 }}>
                    {card.label}
                    {isConfigured && (
                      <CheckCircleFilled style={{ color: '#52C41A', marginLeft: 6, fontSize: 12 }} />
                    )}
                  </div>
                  <div style={{ fontSize: 12, color: '#8C8C8C' }}>
                    {isConfigured ? `${count} 条记录` : '暂无数据'}
                  </div>
                </div>
                {/* 功能：配置状态标签——已配置绿色/待配置灰色 */}
                <Tag color={isConfigured ? 'success' : 'default'}>
                  {isConfigured ? '已配置' : '待配置'}
                </Tag>
              </div>
            </Card>
          );
        })}
      </div>
    </div>
  );
}

export default DashboardPage;
