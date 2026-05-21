{/* 模块用途：StepAssignRole——步骤4，为项目分配角色人员 */}
{/* 依赖组件：Ant Design Form/Input/Button, client.js */}
{/* 修改注意：需先完成步骤1（项目角色）和步骤3（项目）才能分配 */}
import { Form, Input, Button, Card } from 'antd';
import { FolderOutlined, AimOutlined, UserOutlined } from '@ant-design/icons';
import client from '../../api/client';

function StepAssignRole({ onNext, onError, submitting, setSubmitting }) {
  const [form] = Form.useForm();

  const handleSubmit = async (values) => {
    setSubmitting(true);
    try {
      const res = await client.post('/wizard/step/role-assignment', {
        projectCode: values.projectCode,
        roleCode: values.roleCode,
        employeeId: values.employeeId,
      });
      onNext(res.data);
    } catch (err) {
      onError(err?.message || '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="步骤4：分配项目角色" style={{ maxWidth: 520, margin: '0 auto' }}>
      <Form form={form} layout="vertical" onFinish={handleSubmit} size="large">
        <Form.Item
          name="projectCode"
          label="项目编码"
          rules={[{ required: true, message: '请输入项目编码' }]}
        >
          <Input prefix={<FolderOutlined />} placeholder="步骤3创建的项目编码" />
        </Form.Item>

        <Form.Item
          name="roleCode"
          label="角色编码"
          rules={[{ required: true, message: '请输入角色编码' }]}
        >
          <Input prefix={<AimOutlined />} placeholder="步骤1创建的角色编码，如 PDL" />
        </Form.Item>

        <Form.Item
          name="employeeId"
          label="员工工号"
          rules={[{ required: true, message: '请输入员工工号' }]}
        >
          <Input prefix={<UserOutlined />} placeholder="待分配员工的工号" />
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

export default StepAssignRole;
