{/* 模块用途：StepCreatePeriod——步骤7，创建考核周期（向导最后一步） */}
{/* 依赖组件：Ant Design Form/Input/DatePicker/Button, client.js */}
{/* 修改注意：startDate必须早于endDate，提交后触发完成弹窗 */}
import { useRef } from 'react';
import { Form, Input, DatePicker, Button, Card } from 'antd';
import { CalendarOutlined } from '@ant-design/icons';
import client from '../../api/client';

function StepCreatePeriod({ onNext, onError, submitting, setSubmitting }) {
  const [form] = Form.useForm();
  const lockRef = useRef(false);

  const handleSubmit = async (values) => {
    if (lockRef.current) return;
    lockRef.current = true;
    setSubmitting(true);
    try {
      const [start, end] = values.dateRange || [];
      if (!start || !end) {
        onError('请选择起止日期');
        return;
      }
      const res = await client.post('/wizard/step/period', {
        periodName: values.periodName,
        startDate: start.format('YYYY-MM-DD'),
        endDate: end.format('YYYY-MM-DD'),
      });
      onNext(res.data);
    } catch (err) {
      onError(err?.message || '保存失败');
    } finally {
      lockRef.current = false;
      setSubmitting(false);
    }
  };

  return (
    <Card title="步骤7：创建考核周期" style={{ maxWidth: 520, margin: '0 auto' }}>
      <Form form={form} layout="vertical" onFinish={handleSubmit} size="large">
        <Form.Item
          name="periodName"
          label="周期名称"
          rules={[{ required: true, message: '请输入周期名称' }]}
        >
          <Input prefix={<CalendarOutlined />} placeholder="如 2025年Q1考核" maxLength={50} />
        </Form.Item>

        <Form.Item
          name="dateRange"
          label="起止日期"
          rules={[{ required: true, message: '请选择起止日期' }]}
        >
          <DatePicker.RangePicker style={{ width: '100%' }} />
        </Form.Item>

        <Form.Item>
          <Button type="primary" htmlType="submit" loading={submitting} disabled={submitting} block>
            {submitting ? '保存中…' : '完成'}
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}

export default StepCreatePeriod;
