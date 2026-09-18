{/* 模块用途：ChangePasswordPage——强制改密页，旧密码+新密码+确认新密码，成功后提示重新登录 */}
{/* 依赖组件：Ant Design Form/Input/Button/Card, react-router-dom, client.js */}
{/* 修改注意：改密API路径为 /auth/change-password，成功后后端已注销会话，跳 /login */}
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Form, Input, Button, Card, Typography, message } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import client from '../../api/client';

const { Title, Text } = Typography;

function ChangePasswordPage() {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  // 功能：提交改密——POST /api/v1/auth/change-password，成功后提示并跳 /login
  const handleSubmit = async (values) => {
    setLoading(true);
    try {
      await client.post('/auth/change-password', {
        oldPassword: values.oldPassword,
        newPassword: values.newPassword,
      });
      message.success({ content: '密码已更新，请重新登录', duration: 3 });
      navigate('/login');
    } catch (err) {
      message.error({ content: err?.message || '修改失败' });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div id="change-password-area" style={{
      minHeight: '100vh',
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      background: '#F5F5F5',
    }}>
      {/* 功能：改密卡片——400px宽，8px圆角，白色背景 */}
      <Card id="change-password-card" style={{
        width: 400,
        borderRadius: 8,
        boxShadow: '0 4px 12px rgba(0,0,0,0.12)',
      }}>
        <div id="change-password-header" style={{ textAlign: 'center', marginBottom: 32 }}>
          <Title level={3} style={{ marginBottom: 4, fontSize: 20, fontWeight: 500 }}>
            修改初始密码
          </Title>
          <Text type="secondary" style={{ fontSize: 14 }}>首次登录须先设置新密码</Text>
        </div>

        {/* 功能：改密表单——旧密码+新密码+确认新密码 */}
        <Form id="change-password-form" onFinish={handleSubmit} size="large">
          <Form.Item
            name="oldPassword"
            rules={[{ required: true, message: '请输入旧密码' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="旧密码"
              autoComplete="current-password"
            />
          </Form.Item>

          <Form.Item
            name="newPassword"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, message: '新密码长度至少8位' },
              {
                pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/,
                message: '新密码必须同时包含字母和数字',
              },
            ]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="新密码（至少8位，含字母和数字）"
              autoComplete="new-password"
            />
          </Form.Item>

          <Form.Item
            name="confirmPassword"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的新密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="确认新密码"
              autoComplete="new-password"
            />
          </Form.Item>

          <Form.Item>
            {/* 功能：提交按钮——全宽40px高，loading时显示"提交中…" */}
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              block
              style={{ height: 40, background: '#1890FF', borderRadius: 6 }}
            >
              {loading ? '提交中…' : '修改密码'}
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}

export default ChangePasswordPage;
