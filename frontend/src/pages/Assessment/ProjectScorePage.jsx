{/* 模块用途：ProjectScorePage——项目KPI打分页，指标Table+Slider(1-5,step=0.5)+凭证拖拽上传+二次确认+乐观锁冲突 */}
{/* 依赖组件：PageHeader, EmptyState, ConfirmModal, client.js, Ant Design Table/Slider/Upload/Button/Space */}
{/* 修改注意：提交前二次确认"提交后不可修改"；409冲突调用showConflictWarning；指标数据来自 GET /tasks/{taskId} */}
import { useState, useEffect, useRef } from 'react';
import {
  Table, Button, Space, message, Card, Spin, Result, Slider, Upload,
} from 'antd';
import {
  ArrowLeftOutlined, InboxOutlined, SaveOutlined, SendOutlined, FileTextOutlined,
} from '@ant-design/icons';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { showConfirm, showConflictWarning } from '../../components/ConfirmModal';
import client from '../../api/client';

const { Dragger } = Upload;

function ProjectScorePage({ kpiType = 'PROJECT' }) {
  const [task, setTask] = useState(null);
  const [indicators, setIndicators] = useState([]);
  const [scores, setScores] = useState({});
  const [evidences, setEvidences] = useState({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [accessDenied, setAccessDenied] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const mountedRef = useRef(true);

  // 功能：从 URL query 参数解析 taskId
  const taskId = new URLSearchParams(window.location.search).get('taskId');

  // 功能：加载任务详情 + KPI 指标列表，并校验当前用户是否为该任务考核人（越权拦截）
  const fetchTask = async () => {
    setLoading(true);
    setError(null);
    setAccessDenied(false);
    try {
      const [taskRes, meRes] = await Promise.all([
        client.get(`/tasks/${taskId}`),
        client.get('/auth/me').catch(() => null),
      ]);
      const data = taskRes.data || {};
      const myEmployeeId = meRes ? (meRes.data || {}).employeeId : null;
      if (mountedRef.current) {
        // 越权拦截：当前登录用户必须为该任务的考核人，否则显示无权访问
        if (myEmployeeId && data.assessorId && data.assessorId !== myEmployeeId) {
          setAccessDenied(true);
          return;
        }
        setTask(data);
        const list = data.indicators || [];
        setIndicators(list);
        // 初始化评分状态
        const initScores = {};
        const initEvidences = {};
        list.forEach((ind) => {
          initScores[ind.kpiConfigId] = ind.score != null ? ind.score : null;
          initEvidences[ind.kpiConfigId] = ind.evidenceUrl || null;
        });
        setScores(initScores);
        setEvidences(initEvidences);
      }
    } catch (err) {
      if (mountedRef.current) {
        if (err?.code === 403) setAccessDenied(true);
        else setError(err?.message || '加载失败');
      }
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  };

  useEffect(() => {
    mountedRef.current = true;
    if (taskId) fetchTask();
    else setLoading(false);
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // 功能：更新某指标的评分
  const handleScoreChange = (kpiConfigId, value) => {
    setScores((prev) => ({ ...prev, [kpiConfigId]: value }));
  };

  // 功能：凭证拖拽上传——先确保评分草稿行存在拿到 scoreId，再调 /scores/{scoreId}/evidence 上传，返回 URL 存入 evidenceUrl
  const handleEvidenceChange = async (kpiConfigId, file) => {
    try {
      const ind = indicators.find((i) => i.kpiConfigId === kpiConfigId);
      const kpiTypeVal = ind?.kpiType || kpiType;
      const ensureRes = await client.post(`/tasks/${taskId}/scores/ensure`, null, {
        params: { kpiConfigId, kpiType: kpiTypeVal },
      });
      const scoreId = ensureRes.data;
      const fd = new FormData();
      fd.append('file', file);
      const upRes = await client.post(`/scores/${scoreId}/evidence`, fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      setEvidences((prev) => ({ ...prev, [kpiConfigId]: upRes.data }));
      message.success({ content: '凭证已上传', duration: 2 });
    } catch (err) {
      message.error({ content: err?.message || '凭证上传失败' });
    }
    return false;
  };

  // 功能：检查所有指标是否已打分
  const allScored = indicators.every((ind) => scores[ind.kpiConfigId] != null);

  // 功能：构建提交 payload
  const buildItems = () => indicators.map((ind) => ({
    kpiConfigId: ind.kpiConfigId,
    kpiType: ind.kpiType || kpiType,
    score: scores[ind.kpiConfigId],
    evidenceUrl: evidences[ind.kpiConfigId] || null,
  }));

  // 功能：实际提交评分——POST /tasks/{taskId}/scores，409 冲突调用 showConflictWarning
  const doSubmit = async () => {
    setSubmitting(true);
    try {
      await client.post(`/tasks/${taskId}/scores`, { items: buildItems() });
      message.success({ content: '评分已提交', duration: 3 });
      window.history.back();
    } catch (err) {
      if (err?.code === 409) {
        showConflictWarning('其他用户', '几');
      } else {
        message.error({ content: err?.message || '提交失败' });
      }
    } finally {
      setSubmitting(false);
    }
  };

  // 功能：提交前二次确认——"提交后不可修改"
  const handleSubmit = () => {
    if (!allScored) {
      message.warning({ content: '存在未评分的指标' });
      return;
    }
    showConfirm({
      title: '确认提交评分',
      content: '提交后不可修改，确认提交吗？',
      okText: '确认提交',
      okType: 'primary',
      onOk: doSubmit,
    });
  };

  // 功能：暂存草稿——不改变任务状态，可只填部分指标
  const handleSaveDraft = async () => {
    setSubmitting(true);
    try {
      await client.put(`/tasks/${taskId}/scores`, { items: buildItems() });
      message.success({ content: '草稿已保存', duration: 2 });
    } catch (err) {
      if (err?.code === 409) {
        showConflictWarning('其他用户', '几');
      } else {
        message.error({ content: err?.message || '保存失败' });
      }
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    { title: '指标名称', dataIndex: 'indicatorName', key: 'indicatorName', width: 200 },
    { title: '权重', dataIndex: 'weight', key: 'weight', width: 80,
      render: (v) => v != null ? `${Math.round(v * 100)}%` : '-' },
    { title: '得分(1-5)', dataIndex: 'score', key: 'score', width: 240,
      render: (_, ind) => (
        <Slider
          min={1}
          max={5}
          step={0.5}
          marks={{ 1: '1', 2: '2', 3: '3', 4: '4', 5: '5' }}
          value={scores[ind.kpiConfigId]}
          onChange={(v) => handleScoreChange(ind.kpiConfigId, v)}
        />
      ) },
    { title: '凭证', dataIndex: 'evidence', key: 'evidence', width: 200,
      render: (_, ind) => (
        <Dragger
          maxCount={1}
          showUploadList={false}
          beforeUpload={(file) => handleEvidenceChange(ind.kpiConfigId, file)}
          style={{ padding: '4px 8px' }}
        >
          <p className="ant-upload-text" style={{ fontSize: 12, margin: 0 }}>
            {evidences[ind.kpiConfigId] ? '已上传凭证' : '点击或拖拽上传凭证'}
          </p>
        </Dragger>
      ) },
  ];

  const goBack = () => window.history.back();

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large"><div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div></Spin>
      </div>
    );
  }

  if (accessDenied) {
    return (
      <Result status="403" title="无权访问" subTitle="您不是该任务的考核人，无权访问此评分页"
        extra={<Button type="primary" onClick={goBack}>返回</Button>} />
    );
  }

  if (error) {
    return (
      <Result status="error" title="加载失败" subTitle={error}
        extra={<Button type="primary" onClick={fetchTask}>重试</Button>} />
    );
  }

  if (!taskId) {
    return (
      <Result status="warning" title="缺少任务参数" subTitle="请从任务列表进入打分页"
        extra={<Button type="primary" onClick={goBack}>返回</Button>} />
    );
  }

  return (
    <div id="project-score-page-area">
      <PageHeader
        title={`${kpiType === 'FUNCTIONAL' ? '职能考核打分' : '项目考核打分'} — ${task?.projectCode || ''} ${task?.assesseeId || ''}`}
        breadcrumb={[{ title: '首页', path: '/dashboard' }, { title: '考核任务', path: '/tasks' }]}
      />

      {/* 功能：任务信息卡 */}
      <Card id="score-task-info" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Space size={24} wrap>
          <span>被考核人：<strong>{task?.assesseeId || '-'}</strong></span>
          <span>考核人：<strong>{task?.assessorId || '-'}</strong></span>
          <span>退回次数：{task?.returnCount ?? 0}/{task?.maxReturns ?? 3}</span>
        </Space>
      </Card>

      {/* 功能：引导式空状态——无 KPI 指标 */}
      {indicators.length === 0 ? (
        <EmptyState
          image={<FileTextOutlined style={{ fontSize: 72, color: '#1890FF' }} />}
          title="该任务无 KPI 指标配置"
          description={kpiType === 'FUNCTIONAL' ? '请联系管理员配置职能 KPI 指标后重试' : '请联系管理员配置项目 KPI 指标后重试'}
          secondaryAction={{ label: '返回', onClick: goBack }}
        />
      ) : (
        <Card id="score-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={indicators}
            rowKey="kpiConfigId"
            size="middle"
            pagination={false}
          />
        </Card>
      )}

      {/* 功能：操作区——暂存草稿 + 提交评分 */}
      {indicators.length > 0 && (
        <div id="score-actions" style={{ marginTop: 16, textAlign: 'right' }}>
          <Space size={12}>
            <Button icon={<SaveOutlined />} onClick={handleSaveDraft} loading={submitting}>暂存草稿</Button>
            <Button type="primary" icon={<SendOutlined />} onClick={handleSubmit} disabled={!allScored} loading={submitting}>提交评分</Button>
          </Space>
          <div style={{ color: '#8C8C8C', fontSize: 12, marginTop: 8 }}>注：提交后不可修改</div>
        </div>
      )}
    </div>
  );
}

export default ProjectScorePage;
