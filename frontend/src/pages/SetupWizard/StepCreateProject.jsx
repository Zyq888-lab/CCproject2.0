{/* 模块用途：StepCreateProject——步骤3，创建第一个项目 */}
{/* 依赖组件：Ant Design Form/Input/Select/Button, client.js */}
{/* 修改注意：projectCode全局唯一，projectStage从枚举中选取 */}
import { Form, Input, Select, Button, Card } from 'antd';
import { FolderOutlined, NumberOutlined } from '@ant-design/icons';
import client from '../../api/client';

const STAGE_OPTIONS = [
  { label: 'P2 概念阶段', value: 'P2' },
  { label: 'P3 设计阶段', value: 'P3' },
  { label: 'P4 样件阶段', value: 'P4' },
  { label: 'P5 量产阶段', value: 'P5' },
];

function StepCreateProject({ onNext, onError, submitting, setSubmitting }) {
  const [form] = Form.useForm();

  const handleSubmit = async (values) => {
    setSubmitting(true);
    try {
      const res = await client.post('/wizard/step/project', {
        projectCode: values.projectCode,
        projectName: values.projectName,
        projectStage: values.projectStage,
        description: values.description || '',
      });
      onNext(res.data);
    } catch (err) {
      onError(err?.message || '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="步骤3：创建项目" style={{ maxWidth: 520, margin: '0 auto' }}>
      <Form form={form} layout="vertical" onFinish={handleSubmit} size="large">
        <Form.Item
          name="projectCode"
          label="项目编码"
          rules={[{ required: true, message: '请输入项目编码' }]}
        >
          <Input prefix={<NumberOutlined />} placeholder="如 PRJ2025001" maxLength={20} />
        </Form.Item>

        <Form.Item
          name="projectName"
          label="项目名称"
          rules={[{ required: true, message: '请输入项目名称' }]}
        >
          <Input prefix={<FolderOutlined />} placeholder="如 某车型座椅总成" maxLength={100} />
        </Form.Item>

        <Form.Item
          name="projectStage"
          label="项目阶段"
          rules={[{ required: true, message: '请选择项目阶段' }]}
        >
          <Select placeholder="选择项目阶段" options={STAGE_OPTIONS} />
        </Form.Item>

        <Form.Item name="description" label="项目说明">
          <Input.TextArea rows={3} placeholder="可选：项目背景、范围等说明" maxLength={500} />
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

export default StepCreateProject;
