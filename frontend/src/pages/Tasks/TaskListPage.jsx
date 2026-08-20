{/* 模块用途：TaskListPage——考核任务列表页，三Tab(待评分/我的进度/待审批[仅PM·ADMIN])+周期/状态/项目编码筛选+状态Tag */}
{/* 依赖组件：PageHeader, EmptyState, client.js, Ant Design Tabs/Table/Select/Input/Tag/Space/Card/Modal/Form/Radio/InputNumber */}
{/* 修改注意：状态Tag颜色 PENDING=orange/IN_PROGRESS=blue/SUBMITTED=green/RETURNED=red/CONFIRMED=cyan/CANCELED=default */}
{/* 修改注意：待审批Tab调 GET /participations?status=PENDING，审批提交 PUT /participations/{id}/approve */}
import { useState, useEffect, useRef, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Table, Button, Select, Input, Space, Tag, Card, Tabs, Modal, Form, Radio, InputNumber, message, Spin,
} from 'antd';
import {
  ReloadOutlined, CarryOutOutlined, AuditOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import client from '../../api/client';

const STATUS_OPTIONS = [
  { label: '待评分', value: 'PENDING' },
  { label: '评分中', value: 'IN_PROGRESS' },
  { label: '已提交', value: 'SUBMITTED' },
  { label: '已退回', value: 'RETURNED' },
  { label: '已确认', value: 'CONFIRMED' },
  { label: '已取消', value: 'CANCELED' },
];

const STATUS_LABEL_MAP = {
  'PENDING': '待评分',
  'IN_PROGRESS': '评分中',
  'SUBMITTED': '已提交',
  'RETURNED': '已退回',
  'CONFIRMED': '已确认',
  'CANCELED': '已取消',
};

const STATUS_COLOR_MAP = {
  'PENDING': 'orange',
  'IN_PROGRESS': 'blue',
  'SUBMITTED': 'green',
  'RETURNED': 'red',
  'CONFIRMED': 'cyan',
  'CANCELED': 'default',
};

const TASK_TYPE_LABEL = { 'PROJECT': '项目考核', 'FUNCTIONAL': '职能考核' };

// 功能：状态合并优先级——数字越小越「未完成」，合并行取优先级最高（最小）的状态
const STATUS_PRIORITY = {
  'PENDING': 0,
  'RETURNED': 1,
  'IN_PROGRESS': 2,
  'SUBMITTED': 3,
  'CONFIRMED': 4,
  'CANCELED': 5,
};

// 功能：将扁平任务列表按「被考核人+项目」合并——同一人同一项目的多条不同 taskType 任务合并为一行
// 说明：无项目编码（FUNCTIONAL）归入 projectCode 为空的独立组，展示为「职能考核」
function groupTasks(list) {
  const groups = new Map();
  for (const t of list) {
    const key = `${t.assesseeId}::${t.projectCode || ''}::${t.projectStage || ''}`;
    if (!groups.has(key)) {
      groups.set(key, { key, assesseeId: t.assesseeId, projectCode: t.projectCode || null, projectStage: t.projectStage || null, tasks: [] });
    }
    groups.get(key).tasks.push(t);
  }
  return Array.from(groups.values()).map((g) => {
    let status = 'CANCELED';
    let minPriority = 99;
    for (const t of g.tasks) {
      const p = STATUS_PRIORITY[t.status] != null ? STATUS_PRIORITY[t.status] : 99;
      if (p < minPriority) { minPriority = p; status = t.status; }
    }
    return { ...g, status, taskCount: g.tasks.length };
  });
}

// 功能：合并行展开详情——懒加载该组内每条任务的 KPI 指标列表，只读预览 + 去打分入口
function TaskGroupDetail({ tasks, employeeNameMap, onGoScore, currentEmployeeId }) {
  const [details, setDetails] = useState({});

  useEffect(() => {
    let cancelled = false;
    const fetchAll = async () => {
      const results = {};
      await Promise.all(tasks.map(async (t) => {
        try {
          const res = await client.get(`/tasks/${t.id}`);
          if (!cancelled) results[t.id] = { indicators: (res.data || {}).indicators || [], error: null };
        } catch (err) {
          if (!cancelled) results[t.id] = { indicators: [], error: err?.message || '加载失败' };
        }
      }));
      if (!cancelled) setDetails(results);
    };
    fetchAll();
    return () => { cancelled = true; };
  }, [tasks]);

  return (
    <div style={{ padding: '8px 16px 12px 32px' }}>
      {tasks.map((t) => {
        const d = details[t.id];
        const actionable = ['PENDING', 'IN_PROGRESS', 'RETURNED'].includes(t.status)
          && currentEmployeeId && t.assessorId === currentEmployeeId
          && d?.indicators?.length > 0;
        const label = t.status === 'PENDING' ? '开始评分' : '继续评分';
        return (
          <Card
            key={t.id}
            size="small"
            style={{ marginBottom: 8, borderRadius: 6 }}
            title={(
              <Space size={8}>
                <span>{TASK_TYPE_LABEL[t.taskType] || t.taskType || '-'}</span>
                <span style={{ color: '#8C8C8C', fontSize: 12 }}>考核人：{employeeNameMap[t.assessorId] || t.assessorId}</span>
                <Tag color={STATUS_COLOR_MAP[t.status] || 'default'}>{STATUS_LABEL_MAP[t.status] || t.status || '-'}</Tag>
              </Space>
            )}
            extra={actionable ? <Button type="link" size="small" onClick={() => onGoScore(t)}>{label}</Button> : null}
          >
            {!d ? (
              <div style={{ textAlign: 'center', padding: '12px 0' }}><Spin size="small" /></div>
            ) : d.error ? (
              <span style={{ color: '#FF4D4F' }}>{d.error}</span>
            ) : d.indicators.length === 0 ? (
              <span style={{ color: '#8C8C8C' }}>该任务无 KPI 指标配置</span>
            ) : (
              <Table
                size="small"
                pagination={false}
                rowKey="kpiConfigId"
                dataSource={d.indicators}
                columns={[
                  { title: '指标名称', dataIndex: 'indicatorName', key: 'indicatorName' },
                  { title: '权重', dataIndex: 'weight', key: 'weight', width: 90,
                    render: (v) => (v != null ? `${Math.round(v * 100)}%` : '-') },
                  { title: '得分', dataIndex: 'score', key: 'score', width: 90,
                    render: (v) => (v != null ? v : '-') },
                ]}
              />
            )}
          </Card>
        );
      })}
    </div>
  );
}

function TaskListPage() {
  const navigate = useNavigate();
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [filters, setFilters] = useState({ periodId: '', status: '', projectCode: '' });
  const [activeTab, setActiveTab] = useState('pending');
  const [periods, setPeriods] = useState([]);
  const mountedRef = useRef(true);

  // 待审批 Tab 状态
  const [userRoles, setUserRoles] = useState([]);
  const [currentEmployeeId, setCurrentEmployeeId] = useState(null);
  const [approvalData, setApprovalData] = useState([]);
  const [approvalLoading, setApprovalLoading] = useState(false);
  const [approvalError, setApprovalError] = useState(null);
  const [approvalPagination, setApprovalPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [employeeNameMap, setEmployeeNameMap] = useState({});
  const [projectNameMap, setProjectNameMap] = useState({});
  const [approveModalVisible, setApproveModalVisible] = useState(false);
  const [approving, setApproving] = useState(false);
  const [approveRecord, setApproveRecord] = useState(null);
  const [approveForm] = Form.useForm();

  // 功能：分页获取考核任务列表——支持周期/状态/项目编码筛选；scope 区分待评分(pending)/我的进度(progress)
  const fetchTasks = async (page, size, filterParams, scope = 'pending') => {
    setLoading(true);
    setError(null);
    try {
      const params = { page, size, scope };
      if (filterParams?.periodId) params.periodId = filterParams.periodId;
      if (filterParams?.status) params.status = filterParams.status;
      if (filterParams?.projectCode) params.projectCode = filterParams.projectCode;
      const res = await client.get('/tasks', { params });
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

  // 功能：获取考核周期列表（筛选用）
  const fetchPeriods = async () => {
    try {
      const res = await client.get('/periods');
      if (mountedRef.current) setPeriods(Array.isArray(res.data) ? res.data : []);
    } catch (_) { /* 非关键 */ }
  };

  // 功能：获取当前登录用户角色与工号——角色控制「待审批」Tab 可见性，工号用于打分按钮条件渲染
  const fetchRoles = async () => {
    try {
      const res = await client.get('/auth/me');
      if (mountedRef.current) {
        setUserRoles(res.data?.roles || []);
        setCurrentEmployeeId(res.data?.employeeId || null);
      }
    } catch (_) { /* 非关键 */ }
  };

  // 功能：获取员工/项目查表——用于把 employeeId/projectCode 映射为姓名/项目名称
  const fetchNameMaps = async () => {
    try {
      const [empRes, projRes] = await Promise.all([
        client.get('/employees/names'),
        client.get('/projects', { params: { page: 1, size: 999 } }),
      ]);
      if (mountedRef.current) {
        setEmployeeNameMap(empRes.data || {});
        const projMap = {};
        ((projRes.data || {}).list || []).forEach((p) => { projMap[p.projectCode] = p.projectName; });
        setProjectNameMap(projMap);
      }
    } catch (_) { /* 非关键数据 */ }
  };

  // 功能：分页获取待审批参与记录——status=PENDING
  const fetchApprovals = async (page, size) => {
    setApprovalLoading(true);
    setApprovalError(null);
    try {
      const res = await client.get('/participations', { params: { page, size, status: 'PENDING' } });
      if (mountedRef.current) {
        const pageData = res.data || {};
        setApprovalData(pageData.list || []);
        setApprovalPagination((prev) => ({ ...prev, current: pageData.page || page, total: pageData.total || 0 }));
      }
    } catch (err) {
      if (mountedRef.current) setApprovalError(err?.message || '加载失败');
    } finally {
      if (mountedRef.current) setApprovalLoading(false);
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    fetchTasks(pagination.current, pagination.pageSize, filters, 'pending');
    fetchPeriods();
    fetchRoles();
    fetchNameMaps();
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = () => fetchTasks(1, pagination.pageSize, filters, activeTab);
  const handleReset = () => {
    const empty = { periodId: '', status: '', projectCode: '' };
    setFilters(empty);
    fetchTasks(1, pagination.pageSize, empty, activeTab);
  };

  const handleTableChange = (pag) => {
    fetchTasks(pag.current, pag.pageSize, filters, activeTab);
  };

  const handleApprovalTableChange = (pag) => {
    fetchApprovals(pag.current, pag.pageSize);
  };

  // 功能：Tab 切换——待审批Tab切到即拉取 PENDING 参与记录；其余切回任务列表
  const handleTabChange = (key) => {
    setActiveTab(key);
    if (key === 'approval') {
      fetchApprovals(1, approvalPagination.pageSize);
    } else {
      const f = { ...filters, status: '' };
      setFilters(f);
      fetchTasks(1, pagination.pageSize, f, key);
    }
  };

  // 功能：去打分——PENDING 先开始评分再跳转，IN_PROGRESS/RETURNED 直接跳转打分页（按 taskType 区分项目/职能）
  const handleGoScore = async (task) => {
    try {
      if (task.status === 'PENDING') {
        await client.put(`/tasks/${task.id}/start`);
      }
      const path = task.taskType === 'FUNCTIONAL'
        ? `/assessment/score/functional?taskId=${task.id}`
        : `/assessment/score/project?taskId=${task.id}`;
      navigate(path);
    } catch (err) {
      message.error({ content: err?.message || '操作失败' });
    }
  };

  // 功能：打开审批弹窗——重置表单并记录当前参与记录
  const handleOpenApprove = (record) => {
    approveForm.resetFields();
    setApproveRecord(record);
    setApproveModalVisible(true);
  };

  // 功能：提交审批——PUT /participations/{id}/approve，通过则后端触发任务增量生成
  const handleApproveSubmit = async () => {
    let values;
    try {
      values = await approveForm.validateFields();
    } catch (_) {
      return; // 校验失败，antd 内联提示
    }
    setApproving(true);
    try {
      await client.put(`/participations/${approveRecord.id}/approve`, {
        approved: values.approved,
        suggestedRate: values.suggestedRate ?? null,
        comment: values.comment || null,
      });
      message.success({ content: values.approved ? '审批通过，考核任务已生成' : '审批不通过，已标记为拒绝', duration: 3 });
      setApproveModalVisible(false);
      fetchApprovals(1, approvalPagination.pageSize);
    } catch (err) {
      message.error({ content: err?.message || '审批失败' });
    } finally {
      setApproving(false);
    }
  };

  const columns = [
    { title: '人员名字', dataIndex: 'assesseeId', key: 'assesseeId', width: 110,
      render: (v) => employeeNameMap[v] || v || '-' },
    { title: '工号', dataIndex: 'assesseeId', key: 'employeeId', width: 100,
      render: (v) => v || '-' },
    { title: '项目编码', dataIndex: 'projectCode', key: 'projectCode', width: 110,
      render: (v) => v || '-' },
    { title: '项目名称', dataIndex: 'projectCode', key: 'projectName', width: 180,
      render: (v) => (v ? (projectNameMap[v] || v) : '职能考核') },
    { title: '项目阶段', dataIndex: 'projectStage', key: 'projectStage', width: 100,
      render: (v) => v || '-' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 130,
      render: (s, r) => (
        <Space size={4}>
          <Tag color={STATUS_COLOR_MAP[s] || 'default'}>{STATUS_LABEL_MAP[s] || s || '-'}</Tag>
          {r.taskCount > 1 ? <span style={{ color: '#8C8C8C', fontSize: 12 }}>共{r.taskCount}项</span> : null}
        </Space>
      ) },
  ];

  // 功能：待审批表——员工姓名/项目名称/申请投入比重/申请时间/操作
  const approvalColumns = [
    { title: '员工姓名', dataIndex: 'employeeId', key: 'employeeId', width: 120,
      render: (v) => employeeNameMap[v] || v || '-' },
    { title: '项目名称', dataIndex: 'projectCode', key: 'projectCode', width: 200,
      render: (v, r) => {
        const name = projectNameMap[v];
        return name ? `${name}（${r.projectStage || '-'}）` : `${v}（${r.projectStage || '-'}）`;
      } },
    { title: '申请投入比重', dataIndex: 'participationRate', key: 'participationRate', width: 120,
      render: (v) => v != null ? `${v}%` : '-' },
    { title: '申请时间', dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-' },
    { title: '操作', key: 'action', width: 100,
      render: (_, record) => (
        <Button type="link" size="small" onClick={() => handleOpenApprove(record)}>审批</Button>
      ) },
  ];

  const canApprove = userRoles.includes('ROLE_PM');
  const tabItems = [
    { key: 'pending', label: '待评分' },
    { key: 'progress', label: '我的进度' },
    ...(canApprove ? [{ key: 'approval', label: '待审批' }] : []),
  ];

  // 功能：任务列表按「人+项目」合并（前端聚合，分页内合并）
  const groupedData = useMemo(() => groupTasks(data), [data]);

  const isEmpty = !loading && !error && data.length === 0 && !filters.periodId && !filters.status && !filters.projectCode;
  const approvalEmpty = !approvalLoading && !approvalError && approvalData.length === 0;

  return (
    <div id="task-list-page-area">
      <PageHeader
        title="考核任务"
        breadcrumb={[{ title: '首页', path: '/dashboard' }]}
      />

      {/* 功能：三 Tab——待评分 / 我的进度 / 待审批(仅 PM·ADMIN 可见) */}
      <Card id="task-tabs-card" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Tabs
          activeKey={activeTab}
          onChange={handleTabChange}
          items={tabItems}
        />

        {/* 功能：筛选栏——周期 + 状态 + 项目编码（仅任务 Tab 显示） */}
        {activeTab !== 'approval' && (
          <Space wrap size="middle" style={{ marginTop: 8 }}>
            <Select
              placeholder="考核周期"
              value={filters.periodId || undefined}
              onChange={(v) => setFilters((f) => ({ ...f, periodId: v || '' }))}
              allowClear
              style={{ width: 200 }}
              options={periods.map((p) => ({ label: p.periodName, value: p.periodId }))}
            />
            <Select
              placeholder="状态"
              value={filters.status || undefined}
              onChange={(v) => setFilters((f) => ({ ...f, status: v || '' }))}
              allowClear
              style={{ width: 140 }}
              options={STATUS_OPTIONS}
            />
            <Input
              placeholder="项目编码"
              value={filters.projectCode}
              onChange={(e) => setFilters((f) => ({ ...f, projectCode: e.target.value }))}
              allowClear
              style={{ width: 160 }}
            />
            <Button type="primary" onClick={handleSearch}>搜索</Button>
            <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
          </Space>
        )}
      </Card>

      {activeTab === 'approval' ? (
        <>
          {approvalError && (
            <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
              {approvalError}
              <Button type="link" onClick={() => fetchApprovals(1, approvalPagination.pageSize)}>重试</Button>
            </div>
          )}

          {approvalEmpty && (
            <EmptyState
              image={<AuditOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
              title="暂无待审批的参与记录"
              description="员工提交项目参与申请后，会在这里显示待你审批的记录"
            />
          )}

          {!approvalEmpty && (
            <Card id="approval-table-card" style={{ borderRadius: 8 }}>
              <Table
                columns={approvalColumns}
                dataSource={approvalData}
                rowKey="id"
                loading={approvalLoading}
                size="middle"
                rowClassName={(_, index) => index % 2 === 1 ? 'table-row-striped' : ''}
                onChange={handleApprovalTableChange}
                pagination={{
                  current: approvalPagination.current,
                  pageSize: approvalPagination.pageSize,
                  total: approvalPagination.total,
                  showSizeChanger: true,
                  pageSizeOptions: [10, 20, 50],
                  showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条，共 ${total} 条`,
                }}
                scroll={{ x: 710 }}
              />
            </Card>
          )}
        </>
      ) : (
        <>
          {error && (
            <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
              {error}
              <Button type="link" onClick={() => fetchTasks(pagination.current, pagination.pageSize, filters, activeTab)}>重试</Button>
            </div>
          )}

          {/* 功能：引导式空状态——无任务且无筛选条件时显示 */}
          {isEmpty && (
            <EmptyState
              image={<CarryOutOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
              title={activeTab === 'pending' ? '所有考核任务已完成 ✓' : '暂无考核任务'}
              description={activeTab === 'pending' ? '没有待评分的任务，等待管理员发起新考核' : '暂无被分配的考核任务'}
            />
          )}

          {!isEmpty && (
            <Card id="task-table-card" style={{ borderRadius: 8 }}>
              <Table
                columns={columns}
                dataSource={groupedData}
                rowKey="key"
                loading={loading}
                size="middle"
                expandable={{
                  expandedRowRender: (record) => (
                    <TaskGroupDetail tasks={record.tasks} employeeNameMap={employeeNameMap} onGoScore={handleGoScore} currentEmployeeId={currentEmployeeId} />
                  ),
                  rowExpandable: () => true,
                }}
                rowClassName={(_, index) => index % 2 === 1 ? 'table-row-striped' : ''}
                onChange={handleTableChange}
                pagination={{
                  current: pagination.current,
                  pageSize: pagination.pageSize,
                  total: pagination.total,
                  showSizeChanger: true,
                  pageSizeOptions: [10, 20, 50],
                  showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条，共 ${total} 条`,
                }}
                scroll={{ x: 850 }}
              />
            </Card>
          )}
        </>
      )}

      {/* 功能：审批弹窗——通过/不通过 + 建议投入比重(选填) + 审批意见(选填) */}
      <Modal
        title="审批项目参与"
        open={approveModalVisible}
        onOk={handleApproveSubmit}
        onCancel={() => setApproveModalVisible(false)}
        confirmLoading={approving}
        okText="提交审批"
        cancelText="取消"
        width={520}
      >
        <Form form={approveForm} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="approved"
            label="审批结果"
            rules={[{ required: true, message: '请选择审批结果' }]}
          >
            <Radio.Group>
              <Radio value={true}>通过</Radio>
              <Radio value={false}>不通过</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item name="suggestedRate" label="建议投入比重（选填，1-100%）">
            <InputNumber min={1} max={100} precision={0} style={{ width: '100%' }} placeholder="不通过时可填建议值，供员工重新提交参考" />
          </Form.Item>
          <Form.Item name="comment" label="审批意见（选填）">
            <Input.TextArea rows={3} maxLength={500} placeholder="填写审批意见" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default TaskListPage;
