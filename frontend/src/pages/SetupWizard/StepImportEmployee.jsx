{/* 模块用途：StepImportEmployee——步骤2，Excel批量导入员工，支持上传+预览+导入 */}
{/* 依赖组件：xlsx, importConfig.jsx, Ant Design Upload/Table/Button/Alert, client.js */}
{/* 修改注意：Excel列名映射见 importConfig.jsx，新增列时同步更新 */}
import { useState, useRef, useEffect } from 'react';
import { Button, Upload, Table, Alert, Space, message, Result } from 'antd';
import { UploadOutlined, InboxOutlined, DownloadOutlined } from '@ant-design/icons';
import client from '../../api/client';
import { parseExcelFile, buildPreviewColumns, downloadTemplate } from '../Employee/importConfig.jsx';

const { Dragger } = Upload;

function StepImportEmployee({ onNext, onError, submitting, setSubmitting }) {
  const [parsedData, setParsedData] = useState([]);
  const [errors, setErrors] = useState([]);
  const [imported, setImported] = useState(false);
  const mountedRef = useRef(true);

  useEffect(() => {
    return () => { mountedRef.current = false; };
  }, []);

  const handleFileParse = (file) => {
    setParsedData([]);
    setErrors([]);
    setImported(false);
    parseExcelFile(file).then(({ rows, errors: parseErrors }) => {
      if (mountedRef.current) {
        setParsedData(rows);
        setErrors(parseErrors);
      }
    }).catch((err) => {
      message.error({ content: err.message });
    });
    return false;
  };

  const handleImport = async () => {
    const validRows = parsedData.filter((r) => r._valid !== false);
    if (validRows.length === 0) {
      message.warning({ content: '没有可导入的有效数据' });
      return;
    }
    setSubmitting(true);
    try {
      const payload = validRows.map(({ _rowNum, _valid, ...emp }) => emp);
      const res = await client.post('/employees/import', payload);
      if (mountedRef.current) {
        setImported(true);
        if (res.data.successCount > 0) {
          message.success({ content: `成功导入 ${res.data.successCount} 条记录` });
          onNext(res.data);
        }
        if (res.data.errors && res.data.errors.length > 0) {
          setErrors((prev) => [...prev, ...res.data.errors.map((e) => ({
            row: e.row, employeeId: e.employeeId, message: e.message
          }))]);
        }
      }
    } catch (err) {
      onError(err?.message || '导入失败');
    } finally {
      if (mountedRef.current) setSubmitting(false);
    }
  };

  const handleSkip = async () => {
    if (submitting) return;
    setSubmitting(true);
    try {
      const res = await client.post('/wizard/step/import-employee');
      onNext(res.data);
    } catch (err) {
      onError(err?.message || '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  const previewColumns = buildPreviewColumns();

  const hasData = parsedData.length > 0;

  if (imported) {
    return (
      <Result
        status="success"
        title="员工导入完成"
        subTitle="导入结果已提交，点击下方按钮继续下一步。"
        extra={
          <Button type="primary" size="large" onClick={() => onNext({})}>
            继续下一步 →
          </Button>
        }
      />
    );
  }

  return (
    <div id="step-import-employee-area">
      {!hasData ? (
        <>
          <div style={{ marginBottom: 16 }}>
            <Dragger
              accept=".xlsx,.xls"
              maxCount={1}
              beforeUpload={handleFileParse}
              showUploadList={false}
            >
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">点击或拖拽Excel文件上传员工数据</p>
              <p className="ant-upload-hint">支持 .xlsx / .xls，表头需包含：工号、姓名、邮箱、岗位分类、岗位、部门、直属上级工号、状态</p>
            </Dragger>
          </div>
          <Space style={{ justifyContent: 'center', width: '100%' }}>
            <Button type="link" icon={<DownloadOutlined />} onClick={downloadTemplate}>
              下载导入模板
            </Button>
            <Button type="default" onClick={handleSkip} disabled={submitting}>
              跳过，稍后导入
            </Button>
          </Space>
        </>
      ) : (
        <>
          {errors.length > 0 && (
            <Alert
              type="warning"
              title={`${errors.length} 条记录存在校验问题，无效行将被跳过`}
              style={{ marginBottom: 12 }}
              showIcon
            />
          )}
          {errors.length === 0 && (
            <Alert
              type="success"
              title={`共 ${parsedData.length} 条记录，全部校验通过`}
              style={{ marginBottom: 12 }}
              showIcon
            />
          )}
          <Table
            columns={previewColumns}
            dataSource={parsedData}
            rowKey="_rowNum"
            size="small"
            scroll={{ x: 800, y: 300 }}
            pagination={false}
            rowClassName={(record) => record._valid === false ? 'import-row-invalid' : ''}
            style={{ marginBottom: 16 }}
          />
          <Space style={{ justifyContent: 'center', width: '100%' }}>
            <Button type="primary" size="large" loading={submitting} disabled={submitting}
              onClick={handleImport}>
              {submitting ? '导入中…' : `确认导入 (${parsedData.filter((r) => r._valid !== false).length} 条) →`}
            </Button>
            <Button onClick={handleSkip} disabled={submitting}>跳过，稍后导入</Button>
          </Space>
        </>
      )}
    </div>
  );
}

export default StepImportEmployee;
