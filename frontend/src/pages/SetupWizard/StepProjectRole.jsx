{/* 模块用途：StepProjectRole——步骤1，配置项目角色编码和名称 */}
{/* 依赖组件：Ant Design Form/Input/Button, client.js */}
{/* 修改注意：roleCode提交后不可修改，角色被引用时禁止删除 */}
import { Form, Input, Button, Card } from 'antd';
import { IdcardOutlined, TagOutlined } from '@ant-design/icons';
import client from '../../api/client';

function StepProjectRole({ onNext, onError, submitting, setSubmitting }) {
  const [form] = Form.useForm();

  const handleSubmit = async (values) => {
    setSubmitting(true);
    try {
      const res = await client.post('/wizard/step/project-role', {
        roleCode: values.roleCode,
        roleName: values.roleName,
      });
      onNext(res.data);
    } catch (err) {
      onError(err?.message || '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="步骤1：配置项目角色" style={{ maxWidth: 520, margin: '0 auto' }}>
      <Form form={form} layout="vertical" onFinish={handleSubmit} size="large">
        <Form.Item
          name="roleCode"
          label="角色编码"
          rules={[{ required: true, message: '请输入角色编码' }]}
        >
          <Input prefix={<IdcardOutlined />} placeholder="如 PDL、PQL、Launch" maxLength={20} />
        </Form.Item>

        <Form.Item
          name="roleName"
          label="角色名称"
          rules={[{ required: true, message: '请输入角色名称' }]}
        >
          <Input prefix={<TagOutlined />} placeholder="如 项目开发负责人" maxLength={50} />
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

export default StepProjectRole;
