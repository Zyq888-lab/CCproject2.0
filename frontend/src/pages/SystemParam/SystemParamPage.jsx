{/* 模块用途：SystemParamPage——系统参数管理页，表格列表+行内编辑+批量保存 */}
{/* 依赖组件：PageHeader, client.js, Ant Design Table/Input/Button/message */}
{/* 修改注意：PUT批量更新携带version做乐观锁，409时提示刷新 */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Input, message, Spin, Result, Space,
} from 'antd';
import {
  SettingOutlined, SaveOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import { showConflictWarning } from '../../components/ConfirmModal';
import client from '../../api/client';

function SystemParamPage() {
  const [params, setParams] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [editingMap, setEditingMap] = useState({}); // { id: newValue }
  const mountedRef = useRef(true);

  const fetchParams = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/system-params');
      if (mountedRef.current) {
        setParams(Array.isArray(res.data) ? res.data : []);
        setEditingMap({});
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
    fetchParams();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleValueChange = (id, value) => {
    setEditingMap((prev) => {
      const next = { ...prev };
      next[id] = value ?? '';
      return next;
    });
  };

  const dirtyCount = Object.keys(editingMap).length;

  const handleSaveAll = async () => {
    const updates = Object.entries(editingMap).map(([id, paramValue]) => {
      const param = params.find((p) => String(p.id) === id);
      return { id: Number(id), paramValue, version: param?.version ?? 0 };
    });

    setSubmitting(true);
    try {
      await client.put('/system-params', updates);
      message.success({ content: '已更新', duration: 3 });
      fetchParams();
    } catch (err) {
      if (err?.code === 409) {
        showConflictWarning('其他用户', Math.floor(Date.now() / 1000));
      } else {
        message.error({ content: err?.message || '保存失败' });
      }
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    {
      title: '参数键',
      dataIndex: 'paramKey',
      key: 'paramKey',
      width: 240,
      render: (text) => <code style={{ fontSize: 13 }}>{text}</code>,
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      render: (text) => text || '-',
    },
    {
      title: '参数值',
      dataIndex: 'paramValue',
      key: 'paramValue',
      render: (text, record) => {
        const edited = editingMap[record.id] !== undefined;
        const value = edited ? editingMap[record.id] : text;
        return (
          <Input
            value={value}
            onChange={(e) => handleValueChange(record.id, e.target.value)}
            style={{ maxWidth: 320, ...(edited ? { borderColor: '#1890FF', boxShadow: '0 0 0 2px rgba(24,144,255,0.2)' } : {}) }}
            placeholder="输入参数值"
          />
        );
      },
    },
  ];

  if (loading && params.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large">
          <div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div>
        </Spin>
      </div>
    );
  }

  if (error && params.length === 0) {
    return (
      <Result
        status="error"
        title="加载失败"
        subTitle={error}
        extra={<Button type="primary" onClick={fetchParams}>重试</Button>}
      />
    );
  }

  return (
    <div id="system-param-area">
      <PageHeader
        title="系统参数"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[
          {
            label: `保存全部${dirtyCount > 0 ? ` (${dirtyCount})` : ''}`,
            icon: <SaveOutlined />,
            type: 'primary',
            onClick: handleSaveAll,
            disabled: dirtyCount === 0 || submitting,
            loading: submitting,
          },
        ]}
      />

      {error && params.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchParams}>重试</Button>
        </div>
      )}

      <div id="system-param-table" style={{
        background: '#FFFFFF',
        borderRadius: 8,
        padding: '16px 24px',
      }}>
        <Table
          columns={columns}
          dataSource={params}
          rowKey="id"
          pagination={false}
          loading={loading && params.length > 0}
          locale={{ emptyText: (
            <div style={{ textAlign: 'center', padding: 40 }}>
              <SettingOutlined style={{ fontSize: 48, color: '#BFBFBF', marginBottom: 16 }} />
              <div style={{ color: '#8C8C8C' }}>暂无系统参数</div>
            </div>
          )}}
        />
      </div>
    </div>
  );
}

export default SystemParamPage;
