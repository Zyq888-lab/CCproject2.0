{/* 模块用途：DashboardPage——仪表盘页，卡片引导方式展示8个配置模块及完成进度 */}
{/* 依赖组件：PageHeader, Ant Design Card/Progress/Spin, react-router-dom, client.js */}
{/* 修改注意：卡片顺序按推荐配置流程排列；config-progress API返回5项数据，缺失项默认"待配置" */}
import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Progress, Spin, Result, Button, Tag, Badge, List, Empty } from 'antd';
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

// 功能：差异类型→文案/颜色映射——与后端 DiscrepancyLog.type 枚举对齐
const DISCREPANCY_TYPE_MAP = {
  NO_POSITION_CONFIG: { label: '缺岗位配置', color: 'orange' },
  NO_ASSESSOR: { label: '无考核人', color: 'red' },
  NO_LEADER: { label: '无直属上级', color: 'red' },
  NO_PRIMARY_ASSESSOR: { label: '角色未标主', color: 'volcano' },
  NO_PRESIDENT: { label: '缺负责总裁', color: 'red' },
};

// 功能：差异类型→跳转目标映射——ADMIN 点「去处理」跳到对应配置页
const DISCREPANCY_TYPE_LINK = {
  NO_POSITION_CONFIG: { to: '/position-config', label: '去配置岗位' },
  NO_LEADER: { to: '/employee-management', label: '去补上级' },
  NO_ASSESSOR: { to: '/project/assignment-summary', label: '去分配考核人' },
  NO_PRIMARY_ASSESSOR: { to: '/project/assignment-summary', label: '去标主' },
  NO_PRESIDENT: { to: '/project/list', label: '去分配总裁' },
};

// 功能：从后端返回的5项数据中查找对应卡片的count，projectKpi使用kpi聚合值
function resolveCount(backendItems, cardKey) {
  if (cardKey === 'projectKpi') {
    const kpiItem = backendItems.find((i) => i.key === 'kpi');
    return kpiItem ? kpiItem.count : 0;
  }
  const item = backendItems.find((i) => i.key === cardKey);
  return item ? item.count : 0;
}

// 功能：待处理任务跳转目标——按登录角色区分（总裁=总裁确认/PD=考核校准/评估人=考核任务/员工&PM=项目参与/ADMIN=项目列表）
function resolvePendingLink(roles) {
  if (roles.includes('ROLE_总裁')) return '/president-confirm';
  if (roles.includes('ROLE_PD')) return '/period-config';
  if (roles.includes('ROLE_评估人')) return '/tasks';
  if (roles.includes('ROLE_员工')) return '/participation';
  if (roles.includes('ROLE_PM')) return '/participation';
  if (roles.includes('ROLE_ADMIN')) return '/project/list';
  return '/tasks';
}

function DashboardPage() {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pendingCount, setPendingCount] = useState(0);
  const [rolesLoaded, setRolesLoaded] = useState(false);
  const [isConfigRole, setIsConfigRole] = useState(false);
  const [isAdmin, setIsAdmin] = useState(false);
  const [userRoles, setUserRoles] = useState([]);
  const [discrepancies, setDiscrepancies] = useState([]);
  const [resolvingIds, setResolvingIds] = useState(new Set());
  const navigate = useNavigate();

  const mountedRef = useRef(true);

  // 功能：获取当前用户角色——区分「配置角色」(ADMIN/PM) 与普通员工，决定是否加载配置进度
  useEffect(() => {
    client.get('/auth/me').then((res) => {
      const data = res.data || res;
      const roles = data.roles || [];
      setUserRoles(roles);
      setIsConfigRole(roles.includes('ROLE_ADMIN') || roles.includes('ROLE_PM'));
      setIsAdmin(roles.includes('ROLE_ADMIN'));
      setRolesLoaded(true);
    }).catch(() => {
      setUserRoles([]);
      setIsConfigRole(false);
      setIsAdmin(false);
      setRolesLoaded(true);
    });
  }, []);

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

  // 功能：获取未处理差异报告——仅 ADMIN 调用，展示 NO_PRIMARY_ASSESSOR 等差异类型
  const fetchDiscrepancies = useCallback(async () => {
    try {
      const res = await client.get('/dashboard/discrepancies');
      if (mountedRef.current) {
        setDiscrepancies(res.data || []);
      }
    } catch (_) { /* 非关键 */ }
  }, []);

  // 功能：标记差异已处理——调用销账端点后刷新差异列表与待处理数
  const handleResolve = useCallback(async (id) => {
    setResolvingIds((prev) => new Set(prev).add(id));
    try {
      await client.post(`/dashboard/discrepancies/${id}/resolve`);
      await Promise.all([fetchDiscrepancies(), fetchPendingCount()]);
    } catch (_) { /* 非关键 */ } finally {
      setResolvingIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  }, [fetchDiscrepancies, fetchPendingCount]);

  useEffect(() => {
    mountedRef.current = true;
    if (!rolesLoaded) return () => { mountedRef.current = false; };
    fetchPendingCount();
    if (isAdmin) {
      fetchDiscrepancies();
    }
    if (isConfigRole) {
      fetchProgress();
    } else {
      setLoading(false);
    }
    return () => { mountedRef.current = false; };
  }, [rolesLoaded, isConfigRole, isAdmin, fetchProgress, fetchPendingCount, fetchDiscrepancies]);

  // 功能：加载中——显示Spin旋转加载（角色未确认或配置数据加载中）
  if (!rolesLoaded || loading) {
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

  // 功能：加载失败——仅配置角色(ADMIN/PM)配置进度加载失败时显示错误+重试
  if (isConfigRole && error) {
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

      {/* 功能：待处理任务/差异卡——全员可见；ADMIN 显示「待处理差异」且无跳转（明细在下方差异报告卡） */}
      <Card id="dashboard-pending-card" style={{ borderRadius: 8, marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Badge count={pendingCount} size="small">
            <CarryOutOutlined style={{ fontSize: 28, color: '#1890FF' }} />
          </Badge>
          <span style={{ fontSize: 14, fontWeight: 500 }}>{isAdmin ? '待处理差异' : '待处理任务'}</span>
          <span style={{ fontSize: 20, fontWeight: 600, color: pendingCount > 0 ? '#FA8C16' : '#52C41A' }}>
            {pendingCount}
          </span>
          <Button type="link" onClick={() => navigate(resolvePendingLink(userRoles))}>查看 →</Button>
        </div>
      </Card>

      {/* 功能：差异报告卡——仅 ADMIN 可见，列出未处理差异（含「角色未标主」类型） */}
      {isAdmin && (
        <Card id="dashboard-discrepancy-card" style={{ borderRadius: 8, marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span style={{ fontSize: 14, fontWeight: 500 }}>差异报告</span>
            <Badge count={discrepancies.length} size="small" />
          </div>
          {discrepancies.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无差异" />
          ) : (
            <List
              size="small"
              dataSource={discrepancies}
              renderItem={(d) => {
                const meta = DISCREPANCY_TYPE_MAP[d.type] || { label: d.type || '未知类型', color: 'default' };
                const link = DISCREPANCY_TYPE_LINK[d.type];
                const subject = d.employeeName ? `${d.employeeName}（${d.employeeId}）` : (d.employeeId || '未知员工');
                const project = d.projectName || d.projectCode
                  ? `${d.projectName || '未知项目'}${d.projectCode ? `（${d.projectCode}）` : ''}`
                  : '';
                return (
                  <List.Item style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-start', gap: 8 }}>
                    <Tag color={meta.color} style={{ flexShrink: 0 }}>{meta.label}</Tag>
                    <div style={{ flex: '1 1 auto', minWidth: 220 }}>
                      <div>
                        <span style={{ fontWeight: 500 }}>{subject}</span>
                        {project && <span style={{ color: '#595959', marginLeft: 8 }}>{project}</span>}
                      </div>
                      <div style={{ color: '#8C8C8C', marginTop: 2 }}>{d.detail}</div>
                    </div>
                    {link && (
                      <Button type="link" size="small" onClick={() => navigate(link.to)}>
                        {link.label}
                      </Button>
                    )}
                    <Button
                      type="link"
                      size="small"
                      loading={resolvingIds.has(d.id)}
                      onClick={() => handleResolve(d.id)}
                    >
                      已处理
                    </Button>
                  </List.Item>
                );
              }}
            />
          )}
        </Card>
      )}

      {isConfigRole && (
        <>
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
        </>
      )}
    </div>
  );
}

export default DashboardPage;
