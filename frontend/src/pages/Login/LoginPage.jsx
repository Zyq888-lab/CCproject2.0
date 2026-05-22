{/* 模块用途：LoginPage——登录页，居中卡片+用户名密码表单+登录按钮 */}
{/* 依赖组件：Ant Design Form/Input/Button/Alert, react-router-dom, client.js */}
{/* 修改注意：登录API路径为 /auth/login，使用JSESSIONID Cookie认证 */}
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Form, Input, Button, Alert, Card, Typography } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import client from '../../api/client';

const { Title, Text } = Typography;

function LoginPage() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const navigate = useNavigate();

  // 功能：提交登录表单——调用POST /api/v1/auth/login，403时自动重试（CSRF token首次加载场景）
  const handleSubmit = async (values) => {
    setLoading(true);
    setError('');
    const doLogin = async () => {
      await client.post('/auth/login', {
        username: values.username,
        password: values.password,
      });
      navigate('/dashboard');
    };
    try {
      await doLogin();
    } catch (err) {
      if (err?.code === 403) {
        try {
          await doLogin();
        } catch (retryErr) {
          setError(retryErr?.message || '用户名或密码错误');
        }
      } else {
        setError(err?.message || '用户名或密码错误');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div id="login-page-area" style={{
      minHeight: '100vh',
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      background: '#F5F5F5',
    }}>
      {/* 功能：登录卡片——400px宽，8px圆角，白色背景 */}
      <Card id="login-card" style={{
        width: 400,
        borderRadius: 8,
        boxShadow: '0 4px 12px rgba(0,0,0,0.12)',
      }}>
        <div id="login-header" style={{ textAlign: 'center', marginBottom: 32 }}>
          <Title level={3} style={{ marginBottom: 4, fontSize: 20, fontWeight: 500 }}>
            继峰座椅绩效考核系统
          </Title>
          <Text type="secondary" style={{ fontSize: 14 }}>请使用系统账号登录</Text>
        </div>

        {/* 功能：登录错误提示——红色Alert，type=error，按钮上方 */}
        {error && (
          <Alert
            title={error}
            type="error"
            showIcon
            closable
            onClose={() => setError('')}
            style={{ marginBottom: 16 }}
          />
        )}

        {/* 功能：登录表单——用户名+密码，密码框带眼睛切换明文 */}
        <Form id="login-form" onFinish={handleSubmit} size="large">
          <Form.Item
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input
              prefix={<UserOutlined />}
              placeholder="用户名"
              autoComplete="username"
            />
          </Form.Item>

          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="密码"
              autoComplete="current-password"
            />
          </Form.Item>

          <Form.Item>
            {/* 功能：登录按钮——全宽40px高，loading时显示"登录中…" */}
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              block
              style={{ height: 40, background: '#1890FF', borderRadius: 6 }}
            >
              {loading ? '登录中…' : '登录'}
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}

export default LoginPage;
