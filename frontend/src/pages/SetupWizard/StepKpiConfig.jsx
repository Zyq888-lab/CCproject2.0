{/* 模块用途：StepKpiConfig——步骤5，配置项目KPI指标 */}
{/* 依赖组件：Ant Design Form/Input/Select/InputNumber/Button, client.js */}
{/* 修改注意：权重范围0.00-100.00，后端校验同一角色+阶段所有权重之和≤100% */}
import { Form, Input, Select, InputNumber, Button, Card } from 'antd';
import { LineChartOutlined, AimOutlined, PercentageOutlined } from '@ant-design/icons';
import client from '../../api/client';

const STAGE_OPTIONS = [
  { label: 'P2 概念阶段', value: 'P2' },
  { label: 'P3 设计阶段', value: 'P3' },
  { label: 'P4 样件阶段', value: 'P4' },
  { label: 'P5 量产阶段', value: 'P5' },
];

function StepKpiConfig({ onNext, onError, submitting, setSubmitting }) {
  const [form] = Form.useForm();

  const handleSubmit = async (values) => {
    setSubmitting(true);
    try {
      const res = await client.post('/wizard/step/kpi', {
        projectRoleCode: values.projectRoleCode,
        projectStage: values.projectStage,
        kpiName: values.kpiName,
        weight: values.weight,
      });
      onNext(res.data);
    } catch (err) {
      onError(err?.message || '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="步骤5：配置KPI指标" style={{ maxWidth: 520, margin: '0 auto' }}>
      <Form form={form} layout="vertical" onFinish={handleSubmit} size="large">
        <Form.Item
          name="projectRoleCode"
          label="项目角色编码"
          rules={[{ required: true, message: '请输入项目角色编码' }]}
        >
          <Input prefix={<AimOutlined />} placeholder="如 PDL" />
        </Form.Item>

        <Form.Item
          name="projectStage"
          label="项目阶段"
          rules={[{ required: true, message: '请选择项目阶段' }]}
        >
          <Select placeholder="选择项目阶段" options={STAGE_OPTIONS} />
        </Form.Item>

        <Form.Item
          name="kpiName"
          label="KPI指标名称"
          rules={[{ required: true, message: '请输入KPI指标名称' }]}
        >
          <Input prefix={<LineChartOutlined />} placeholder="如 技术方案质量" maxLength={50} />
        </Form.Item>

        <Form.Item
          name="weight"
          label="权重(%)"
          rules={[{ required: true, message: '请输入权重' }]}
        >
          <InputNumber
            prefix={<PercentageOutlined />}
            min={0}
            max={100}
            step={1}
            precision={0}
            style={{ width: '100%' }}
            placeholder="如 30"
          />
        </Form.Item>

        <Form.Item>
          <Button type="primary" htmlType="submit" loading={submitting} disabled={submitting} block>
            {submitting ? '保存中…' : '下一步 →'}
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}

export default StepKpiConfig;
