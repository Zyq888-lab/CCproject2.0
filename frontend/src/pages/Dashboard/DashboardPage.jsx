{/* 模块用途：DashboardPage——仪表盘页，首次访问显示欢迎引导，已有数据显示配置进度卡片 */}
{/* 依赖组件：EmptyState, PageHeader, Ant Design Card/Progress/Badge/Spin, react-router-dom, client.js */}
{/* 修改注意：阶段2添加差异报告红点Badge；config-progress API返回5项数据 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Progress, Spin, Result, Button, Tag } from 'antd';
import {
  CheckCircleFilled,
  TeamOutlined,
  AimOutlined,
  FolderOutlined,
  SettingOutlined,
  LineChartOutlined,
  RocketOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

// 功能：各配置模块对应的图标映射
const ICON_MAP = {
  employee: <TeamOutlined style={{ fontSize: 28 }} />,
  projectRole: <AimOutlined style={{ fontSize: 28 }} />,
  project: <FolderOutlined style={{ fontSize: 28 }} />,
  positionConfig: <SettingOutlined style={{ fontSize: 28 }} />,
  kpi: <LineChartOutlined style={{ fontSize: 28 }} />,
};

// 功能：各配置模块对应的颜色
const COLOR_MAP = {
  employee: '#1890FF',
  projectRole: '#52C41A',
  project: '#FAAD14',
  positionConfig: '#722ED1',
  kpi: '#EB2F96',
};

// 功能：卡片网格样式——2行×3列
const GRID_STYLE = { display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 };

function DashboardPage() {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
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

  useEffect(() => {
    mountedRef.current = true;
    fetchProgress();
    return () => { mountedRef.current = false; };
  }, [fetchProgress]);

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

  const allEmpty = items.every((item) => item.count === 0);
  const configuredCount = items.filter((item) => item.count > 0).length;
  const progressPercent = items.length > 0
    ? Math.round((configuredCount / items.length) * 100)
    : 0;

  // 功能：首次访问——所有模块count=0时显示欢迎引导布局
  if (allEmpty) {
    return (
      <div id="dashboard-welcome-area">
        <EmptyState
          image={
            <RocketOutlined style={{ fontSize: 72, color: '#1890FF' }} />
          }
          title="欢迎使用绩效考核系统"
          description="完成7步配置，预计需要15-20分钟"
          primaryAction={{
            label: '开始配置',
            onClick: () => navigate('/setup-wizard'),
          }}
        />
        {/* 功能：配置进度预览卡片——2行×3列，显示6个待配置模块 */}
        <div id="dashboard-preview-cards" style={{
          ...GRID_STYLE,
          maxWidth: 720,
          margin: '0 auto',
          marginTop: 32,
        }}>
          {items.map((item) => (
            <Card
              key={item.key}
              size="small"
              hoverable
              onClick={() => navigate(item.link)}
              style={{ borderRadius: 8, textAlign: 'center' }}
            >
              <div style={{ color: COLOR_MAP[item.key] || '#8C8C8C', marginBottom: 8 }}>
                {ICON_MAP[item.key]}
              </div>
              <div style={{ fontSize: 14, fontWeight: 500 }}>{item.label}</div>
              <Tag color="default" style={{ marginTop: 8 }}>{item.status}</Tag>
            </Card>
          ))}
        </div>
      </div>
    );
  }

  // 功能：配置进度布局——顶部进度条+6张配置卡片2行×3列
  return (
    <div id="dashboard-configured-area">
      <PageHeader
        title="仪表盘"
        breadcrumb={[{ title: '首页' }]}
      />

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

      {/* 功能：配置进度卡片——2行×3列CSS Grid，已配置项绿色左边框+绿色对勾 */}
      <div id="dashboard-config-cards" style={GRID_STYLE}>
        {items.map((item) => {
          const isConfigured = item.count > 0;
          return (
            <Card
              key={item.key}
              hoverable
              onClick={() => navigate(item.link)}
              style={{
                borderRadius: 8,
                borderLeft: isConfigured ? '3px solid #52C41A' : '3px solid #E8E8E8',
              }}
            >
              <div id={`card-${item.key}`} style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                {/* 功能：模块图标——已配置时显示对应颜色 */}
                <div style={{
                  width: 48,
                  height: 48,
                  borderRadius: 8,
                  background: isConfigured ? `${COLOR_MAP[item.key]}15` : '#FAFAFA',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  color: isConfigured ? COLOR_MAP[item.key] : '#D9D9D9',
                }}>
                  {ICON_MAP[item.key]}
                </div>
                <div style={{ flex: 1 }}>
                  <div style={{ fontSize: 14, fontWeight: 500, marginBottom: 4 }}>
                    {item.label}
                    {isConfigured && (
                      <CheckCircleFilled style={{ color: '#52C41A', marginLeft: 6, fontSize: 12 }} />
                    )}
                  </div>
                  <div style={{ fontSize: 12, color: '#8C8C8C' }}>
                    {item.count} 条记录
                  </div>
                </div>
                {/* 功能：配置状态标签——已配置绿色/待配置灰色 */}
                <Tag color={isConfigured ? 'success' : 'default'}>
                  {item.status}
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
