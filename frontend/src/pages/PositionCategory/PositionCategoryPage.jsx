{/* 模块用途：PositionCategoryPage——岗位分类管理页，表格+新增/编辑弹窗+删除确认 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Modal/Form/Input/InputNumber */}
{/* 修改注意：后端返回全部列表，前端做客户端分页；删除时处理后端引用错误(409) */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Space, Modal, Form, Input, InputNumber, message, Card, Spin, Result,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, TagsOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showDeleteConfirm } from '../../components/ConfirmModal';
import client from '../../api/client';

function PositionCategoryPage() {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingRecord, setEditingRecord] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [deletingId, setDeletingId] = useState(null);
  const [form] = Form.useForm();
  const mountedRef = useRef(true);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/position-categories/list');
      if (mountedRef.current) setData(Array.isArray(res.data) ? res.data : []);
    } catch (err) {
      if (mountedRef.current) setError(err?.message || '加载失败');
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    fetchData();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleCreate = () => {
    setEditingRecord(null);
    form.resetFields();
    form.setFieldsValue({ sortOrder: 0 });
    setModalVisible(true);
  };

  const handleEdit = (record) => {
    setEditingRecord(record);
    form.resetFields();
    form.setFieldsValue({
      name: record.name,
      sortOrder: record.sortOrder ?? 0,
    });
    setModalVisible(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (!editingRecord) {
        await client.post('/position-categories', values);
        message.success({ content: '创建成功', duration: 3 });
      } else {
        await client.put(`/position-categories/${editingRecord.id}`, values);
        message.success({ content: '保存成功', duration: 3 });
      }
      setModalVisible(false);
      fetchData();
    } catch (err) {
      if (err?.message) {
        message.error({ content: err.message });
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = (record) => {
    showDeleteConfirm(async () => {
      setDeletingId(record.id);
      try {
        await client.delete(`/position-categories/${record.id}`);
        message.success({ content: '已删除', duration: 3 });
        fetchData();
      } catch (err) {
        if (err?.code === 409) {
          message.error({ content: err.message || '该分类被引用，无法删除' });
        } else {
          message.error({ content: err?.message || '删除失败' });
        }
      } finally {
        if (mountedRef.current) setDeletingId(null);
      }
    }, `岗位分类「${record.name}」`);
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
    { title: '名称', dataIndex: 'name', key: 'name', width: 200 },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder', width: 80 },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-' },
    { title: '操作', key: 'action', width: 140,
      render: (_, record) => (
        <Space size="small">
          <Button type="link" size="small" icon={<EditOutlined />}
            onClick={() => handleEdit(record)}>编辑</Button>
          <Button type="link" size="small" danger icon={<DeleteOutlined />}
            loading={deletingId === record.id}
            onClick={() => handleDelete(record)}>删除</Button>
        </Space>
      ),
    },
  ];

  const isEmpty = !loading && !error && data.length === 0;

  if (loading && data.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large"><div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div></Spin>
      </div>
    );
  }

  if (error && data.length === 0) {
    return (
      <Result status="error" title="加载失败" subTitle={error}
        extra={<Button type="primary" onClick={fetchData}>重试</Button>}
      />
    );
  }

  return (
    <div id="position-category-area">
      <PageHeader
        title="岗位分类管理"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
        actions={[{ label: '新增分类', icon: <PlusOutlined />, type: 'primary', onClick: handleCreate }]}
      />

      {error && data.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={fetchData}>重试</Button>
        </div>
      )}

      {isEmpty && (
        <EmptyState
          image={<TagsOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="还没有岗位分类"
          description="创建岗位分类后，可在员工管理、岗位配置中使用"
          primaryAction={{ label: '新增分类', onClick: handleCreate }}
        />
      )}

      {!isEmpty && (
        <Card id="position-category-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={data}
            rowKey="id"
            loading={loading}
            size="middle"
            rowClassName={(_, index) => index % 2 === 1 ? 'table-row-striped' : ''}
            pagination={{
              defaultPageSize: 20,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50],
              showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条，共 ${total} 条`,
            }}
            scroll={{ x: 680 }}
          />
        </Card>
      )}

      <Modal
        title={editingRecord ? '编辑岗位分类' : '新增岗位分类'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        width={440}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="name" label="分类名称" rules={[{ required: true, message: '请输入分类名称' }]}>
            <Input placeholder="如 Management" maxLength={64} />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序" extra="数字越小越靠前">
            <InputNumber min={0} max={999} precision={0} style={{ width: '100%' }} placeholder="0" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default PositionCategoryPage;
