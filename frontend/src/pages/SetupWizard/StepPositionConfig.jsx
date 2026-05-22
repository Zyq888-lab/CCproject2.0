{/* 模块用途：StepPositionConfig——步骤6，配置岗位考核规则 */}
{/* 依赖组件：Ant Design Form/Input/Switch/InputNumber/Select/Button, client.js */}
{/* 修改注意：projectWeight+funcWeight应等于100，后端校验 */}
import { Form, Input, Switch, InputNumber, Select, Button, Card } from 'antd';
import { SettingOutlined, TagsOutlined, PercentageOutlined } from '@ant-design/icons';
import client from '../../api/client';

const FUNC_MODE_OPTIONS = [
  { label: '直接上级评分', value: 'DIRECT_LEADER' },
  { label: '组织负责人评分', value: 'ORG_LEADER' },
];

function StepPositionConfig({ onNext, onError, submitting, setSubmitting }) {
  const [form] = Form.useForm();

  const handleSubmit = async (values) => {
    setSubmitting(true);
    try {
      const res = await client.post('/wizard/step/position', {
        category: values.category,
        position: values.position,
        isProjectBased: values.isProjectBased,
        projectWeight: values.projectWeight,
        funcWeight: values.funcWeight,
        funcAssessMode: values.funcAssessMode || '',
      });
      onNext(res.data);
    } catch (err) {
      onError(err?.message || '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="步骤6：配置岗位考核" style={{ maxWidth: 520, margin: '0 auto' }}>
      <Form form={form} layout="vertical" onFinish={handleSubmit} size="large"
        initialValues={{ isProjectBased: true, projectWeight: 70, funcWeight: 30 }}>
        <Form.Item
          name="category"
          label="岗位分类"
          rules={[{ required: true, message: '请输入岗位分类' }]}
        >
          <Input prefix={<TagsOutlined />} placeholder="如 研发技术类" maxLength={50} />
        </Form.Item>

        <Form.Item
          name="position"
          label="岗位名称"
          rules={[{ required: true, message: '请输入岗位名称' }]}
        >
          <Input prefix={<SettingOutlined />} placeholder="如 整椅研发岗" maxLength={50} />
        </Form.Item>

        <Form.Item name="isProjectBased" label="是否纳入项目制考核" valuePropName="checked">
          <Switch checkedChildren="是" unCheckedChildren="否" />
        </Form.Item>

        <Form.Item
          name="projectWeight"
          label="项目考核权重(%)"
          rules={[{ required: true, message: '请输入项目考核权重' }]}
        >
          <InputNumber
            prefix={<PercentageOutlined />}
            min={0} max={100} step={1} precision={0}
            style={{ width: '100%' }} placeholder="如 70"
          />
        </Form.Item>

        <Form.Item
          name="funcWeight"
          label="职能考核权重(%)"
          rules={[{ required: true, message: '请输入职能考核权重' }]}
        >
          <InputNumber
            prefix={<PercentageOutlined />}
            min={0} max={100} step={1} precision={0}
            style={{ width: '100%' }} placeholder="如 30"
          />
        </Form.Item>

        <Form.Item name="funcAssessMode" label="职能考核模式">
          <Select placeholder="选择考核模式" options={FUNC_MODE_OPTIONS} allowClear />
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

export default StepPositionConfig;
