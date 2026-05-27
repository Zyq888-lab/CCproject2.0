{/* 模块用途：SetupWizardPage——7步配置向导容器，Steps导航条+步骤路由+恢复提示+完成弹窗 */}
{/* 依赖组件：StepProjectRole~StepCreatePeriod 7个子步骤, client.js, Ant Design Steps/Alert/Modal/Spin */}
{/* 修改注意：步骤顺序和STEP_ENDPOINTS一一对应，新增步骤时同步更新TOTAL_STEPS */}
import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Steps, Alert, Modal, Spin, Result, Button } from 'antd';
import { CheckCircleFilled } from '@ant-design/icons';
import client from '../../api/client';
import StepProjectRole from './StepProjectRole';
import StepImportEmployee from './StepImportEmployee';
import StepCreateProject from './StepCreateProject';
import StepAssignRole from './StepAssignRole';
import StepKpiConfig from './StepKpiConfig';
import StepPositionConfig from './StepPositionConfig';
import StepCreatePeriod from './StepCreatePeriod';

const STEP_LABELS = ['', '项目角色', '导入员工', '创建项目', '分配角色', 'KPI指标', '岗位考核', '考核周期'];

const STEP_COMPONENTS = {
  1: StepProjectRole,
  2: StepImportEmployee,
  3: StepCreateProject,
  4: StepAssignRole,
  5: StepKpiConfig,
  6: StepPositionConfig,
  7: StepCreatePeriod,
};

function SetupWizardPage() {
  const [currentStep, setCurrentStep] = useState(0);
  const [completedSteps, setCompletedSteps] = useState([]);
  const [wizardCompleted, setWizardCompleted] = useState(false);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const [resumeBannerVisible, setResumeBannerVisible] = useState(false);
  const [showCompletionModal, setShowCompletionModal] = useState(false);
  const navigate = useNavigate();
  const mountedRef = useRef(true);

  // 功能：从后端加载向导进度——首次访问currentStep=0，已有进度则恢复到上次位置
  const fetchProgress = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.get('/wizard/progress');
      if (mountedRef.current) {
        const { currentStep: saved, completedSteps: savedCompleted, completed } = res.data || {};
        const completedArr = savedCompleted ? savedCompleted.split(',').filter(Boolean).map(Number) : [];
        setCompletedSteps(completedArr);
        setWizardCompleted(completed || false);

        if (completed) {
          setShowCompletionModal(true);
        } else if (saved > 1 && completedArr.length > 0) {
          setResumeBannerVisible(true);
          setCurrentStep(saved);
        } else {
          setCurrentStep(saved > 0 ? saved : 1);
        }
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

  // 功能：完成弹窗2秒后自动跳转仪表盘
  useEffect(() => {
    if (!showCompletionModal) return;
    const timer = setTimeout(() => {
      navigate('/dashboard');
    }, 2000);
    return () => clearTimeout(timer);
  }, [showCompletionModal, navigate]);

  // 功能：步骤提交成功后的回调——更新已完成步骤、推进到下一步、检查是否全部完成
  const handleNext = useCallback((result) => {
    if (!mountedRef.current) return;
    setError(null);
    setResumeBannerVisible(false);
    const { completedStep, nextStep, wizardCompleted: allDone } = result;
    setCompletedSteps((prev) => {
      const updated = new Set([...prev, completedStep]);
      return [...updated].sort((a, b) => a - b);
    });
    if (allDone) {
      setWizardCompleted(true);
      setShowCompletionModal(true);
    } else {
      setCurrentStep(nextStep);
    }
  }, []);

  // 功能：步骤提交失败的回调——显示网络错误横幅
  const handleError = useCallback((message) => {
    if (mountedRef.current) {
      setError(message);
    }
  }, []);

  // 功能：点击已完成步骤回跳到该步骤——提交中禁止跳转防止导航冲突
  const handleStepClick = (step) => {
    if (submitting) return;
    if (completedSteps.includes(step) || step === currentStep) {
      setCurrentStep(step);
    }
  };

  // 功能：加载中——显示Spin
  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large">
          <div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div>
        </Spin>
      </div>
    );
  }

  // 功能：加载失败——显示错误结果+重试按钮
  if (error && currentStep === 0) {
    return (
      <Result
        status="error"
        title="加载失败"
        subTitle={error}
        extra={<Button type="primary" onClick={fetchProgress}>重试</Button>}
      />
    );
  }

  // 功能：已完成全部步骤——显示完成弹窗
  if (showCompletionModal) {
    return (
      <Modal
        open
        centered
        closable={false}
        footer={null}
      >
        <div style={{ textAlign: 'center', padding: '24px 0' }}>
          <CheckCircleFilled style={{ fontSize: 64, color: '#52C41A', marginBottom: 16 }} />
          <h2 style={{ fontSize: 20, fontWeight: 500, marginBottom: 8 }}>恭喜！配置已完成！</h2>
          <p style={{ color: '#8C8C8C', fontSize: 14 }}>即将跳转至仪表盘…</p>
        </div>
      </Modal>
    );
  }

  const StepComponent = STEP_COMPONENTS[currentStep];

  return (
    <div id="setup-wizard-area" style={{ background: '#FFFFFF', borderRadius: 8, padding: 24 }}>
      {/* 功能：网络错误横幅——可关闭的红色Alert */}
      {error && (
        <Alert
          title={error}
          type="error"
          showIcon
          closable
          onClose={() => setError(null)}
          style={{ marginBottom: 16 }}
        />
      )}

      {/* 功能：断点续配提示——检测到上次进度，显示蓝色Info横幅 */}
      {resumeBannerVisible && (
        <Alert
          title={`检测到上次配置进度，已自动恢复到第${currentStep}步`}
          type="info"
          showIcon
          closable
          onClose={() => setResumeBannerVisible(false)}
          style={{ marginBottom: 16 }}
        />
      )}

      {/* 功能：7步导航条——type="navigation"，已完成步可点击回跳 */}
      <Steps
        type="navigation"
        current={currentStep - 1}
        onChange={(step) => handleStepClick(step + 1)}
        style={{ marginBottom: 32 }}
        items={STEP_LABELS.slice(1).map((label, idx) => ({
          title: label,
          status: completedSteps.includes(idx + 1) ? 'finish' : undefined,
        }))}
      />

      {/* 功能：当前步骤表单——动态渲染对应Step组件 */}
      <div id="wizard-step-content" style={{ minHeight: 300 }}>
        {StepComponent && (
          <StepComponent
            onNext={handleNext}
            onError={handleError}
            submitting={submitting}
            setSubmitting={setSubmitting}
            completedSteps={completedSteps}
          />
        )}
      </div>
    </div>
  );
}

export default SetupWizardPage;
