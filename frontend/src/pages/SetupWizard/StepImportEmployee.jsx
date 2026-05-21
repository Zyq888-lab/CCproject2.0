{/* 模块用途：StepImportEmployee——步骤2，标记员工导入完成（实际导入在import模块处理） */}
{/* 依赖组件：Ant Design Button/Result, client.js */}
{/* 修改注意：此步骤无需表单，仅标记完成；后续可扩展为Excel上传预览 */}
import { useRef } from 'react';
import { Button, Result } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import client from '../../api/client';

function StepImportEmployee({ onNext, onError, submitting, setSubmitting }) {
  const lockRef = useRef(false);

  const handleMarkComplete = async () => {
    if (lockRef.current) return;
    lockRef.current = true;
    setSubmitting(true);
    try {
      const res = await client.post('/wizard/step/import-employee');
      onNext(res.data);
    } catch (err) {
      onError(err?.message || '保存失败');
    } finally {
      lockRef.current = false;
      setSubmitting(false);
    }
  };

  return (
    <Result
      icon={<UploadOutlined style={{ fontSize: 64, color: '#1890FF' }} />}
      title="步骤2：导入员工"
      subTitle="批量导入员工数据请前往「员工管理」页面使用导入功能。此处点击标记完成即可继续下一步。"
      extra={
        <Button type="primary" size="large" loading={submitting} disabled={submitting} onClick={handleMarkComplete}>
          {submitting ? '保存中…' : '标记完成，下一步 →'}
        </Button>
      }
    />
  );
}

export default StepImportEmployee;
