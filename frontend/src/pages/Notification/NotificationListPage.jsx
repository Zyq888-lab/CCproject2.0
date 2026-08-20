{/* 模块用途：NotificationListPage——站内通知列表，点击标记已读并跳转到 targetUrl */}
{/* 依赖组件：PageHeader, EmptyState, client.js, Ant Design Table/Tag/Button/Card */}
{/* 修改注意：未读通知标题加粗；点击后 PUT /notifications/{id}/read 再跳转 */}
import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Table, Tag, Spin, Result, Button, Card,
} from 'antd';
import { BellOutlined } from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

const TYPE_LABEL_MAP = {
  TASK_ASSIGNED: '考核任务',
  RETURNED: '退回',
  CONFIRMED: '确认',
  URGE: '催办',
};

const TYPE_COLOR_MAP = {
  TASK_ASSIGNED: 'blue',
  RETURNED: 'red',
  CONFIRMED: 'green',
  URGE: 'orange',
};

function NotificationListPage() {
  const navigate = useNavigate();
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const mountedRef = useRef(true);

  // 功能：分页获取当前用户站内通知
  const fetchData = async (page = 1, size = 20) => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/notifications', { params: { page, size } });
      if (mountedRef.current) {
        const pageData = res.data || {};
        setData(pageData.list || []);
        setPagination((prev) => ({ ...prev, current: pageData.page || page, total: pageData.total || 0 }));
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

  // 功能：点击通知——标记已读并跳转到 targetUrl（缺省回仪表盘）
  const handleClick = async (record) => {
    if (!record.isRead) {
      try { await client.put(`/notifications/${record.id}/read`); } catch (_) { /* 忽略已读失败 */ }
    }
    navigate(record.targetUrl || '/dashboard');
  };

  const columns = [
    { title: '类型', dataIndex: 'type', key: 'type', width: 110,
      render: (t) => <Tag color={TYPE_COLOR_MAP[t] || 'default'}>{TYPE_LABEL_MAP[t] || t || '-'}</Tag> },
    { title: '标题', dataIndex: 'title', key: 'title', width: 220,
      render: (v, r) => <span style={{ fontWeight: r.isRead ? 'normal' : 600 }}>{v || '-'}</span> },
    { title: '内容', dataIndex: 'content', key: 'content',
      render: (v) => v || '-' },
    { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-' },
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
        extra={<Button type="primary" onClick={() => fetchData(pagination.current, pagination.pageSize)}>重试</Button>}
      />
    );
  }

  return (
    <div id="notification-list-page-area">
      <PageHeader
        title="通知"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
      />

      {error && data.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={() => fetchData(pagination.current, pagination.pageSize)}>重试</Button>
        </div>
      )}

      {isEmpty && (
        <EmptyState
          image={<BellOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="暂无通知"
          description="新的考核任务会在这里提醒你"
        />
      )}

      {!isEmpty && (
        <Card id="notification-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={data}
            rowKey="id"
            loading={loading}
            size="middle"
            rowClassName={(_, index) => index % 2 === 1 ? 'table-row-striped' : ''}
            onRow={(record) => ({
              onClick: () => handleClick(record),
              style: { cursor: 'pointer' },
            })}
            onChange={(pag) => fetchData(pag.current, pag.pageSize)}
            pagination={{
              current: pagination.current,
              pageSize: pagination.pageSize,
              total: pagination.total,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50],
              showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条，共 ${total} 条`,
            }}
            scroll={{ x: 700 }}
          />
        </Card>
      )}
    </div>
  );
}

export default NotificationListPage;
