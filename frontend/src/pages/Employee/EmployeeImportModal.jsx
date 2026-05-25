{/* 模块用途：EmployeeImportModal——Excel批量导入员工，上传+预览+校验+确认导入 */}
{/* 依赖组件：xlsx, client.js, importConfig.jsx, Ant Design Modal/Upload/Table/Button/Alert */}
{/* 修改注意：Excel列名映射见 importConfig.jsx，新增列时同步更新 */}
import { useState, useRef } from 'react';
import { Modal, Upload, Table, Button, Alert, Space, message } from 'antd';
import { InboxOutlined, DownloadOutlined } from '@ant-design/icons';
import client from '../../api/client';
import { parseExcelFile, buildPreviewColumns, downloadTemplate } from './importConfig.jsx';

const { Dragger } = Upload;

function EmployeeImportModal({ open, onClose, onSuccess }) {
  const [parsedData, setParsedData] = useState([]);
  const [errors, setErrors] = useState([]);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState(null);
  const [fileName, setFileName] = useState('');
  const mountedRef = useRef(true);

  const resetState = () => {
    setParsedData([]);
    setErrors([]);
    setImportResult(null);
    setFileName('');
    setImporting(false);
  };

  const handleClose = () => {
    resetState();
    onClose();
  };

  // 功能：解析Excel文件——委托给共享parseExcelFile，处理Promise结果
  const handleFileParse = (file) => {
    resetState();
    setFileName(file.name);
    parseExcelFile(file).then(({ rows, errors: parseErrors }) => {
      if (mountedRef.current) {
        setParsedData(rows);
        setErrors(parseErrors);
        if (parseErrors.length === 0 && rows.length > 0) {
          message.success({ content: `成功解析 ${rows.length} 条记录，请确认后点击"确认导入"` });
        }
      }
    }).catch((err) => {
      message.error({ content: err.message });
    });
    return false;
  };

  // 功能：提交导入——将解析后的有效数据发送到后端批量导入接口
  const handleImport = async () => {
    const validRows = parsedData.filter((r) => r._valid !== false);
    if (validRows.length === 0) {
      message.warning({ content: '没有可导入的有效数据' });
      return;
    }

    setImporting(true);
    try {
      const payload = validRows.map(({ _rowNum, _valid, ...emp }) => emp);
      const res = await client.post('/employees/import', payload);
      if (mountedRef.current) {
        setImportResult(res.data);
        if (res.data.successCount > 0) {
          message.success({ content: `成功导入 ${res.data.successCount} 条记录` });
          onSuccess();
        }
        if (res.data.errors && res.data.errors.length > 0) {
          message.warning({ content: `${res.data.errors.length} 条记录导入失败` });
        }
      }
    } catch (err) {
      message.error({ content: err?.message || '导入失败' });
    } finally {
      if (mountedRef.current) setImporting(false);
    }
  };

  // 预览表格列定义（共享配置）
  const previewColumns = buildPreviewColumns();

  // 导入结果列定义
  const resultColumns = [
    { title: '行号', dataIndex: 'row', key: 'row', width: 60 },
    { title: '工号', dataIndex: 'employeeId', key: 'employeeId', width: 100 },
    { title: '错误信息', dataIndex: 'message', key: 'message' },
  ];

  const hasData = parsedData.length > 0;
  const hasErrors = errors.length > 0;
  const hasResult = importResult !== null;

  return (
    <Modal
      title="批量导入员工"
      open={open}
      onCancel={handleClose}
      width={900}
      footer={
        <Space>
          <Button onClick={handleClose}>关闭</Button>
          {hasData && !hasResult && (
            <Button type="primary" loading={importing} disabled={importing} onClick={handleImport}>
              {importing ? '导入中…' : `确认导入 (${parsedData.filter((r) => r._valid !== false).length} 条)`}
            </Button>
          )}
          {hasResult && (
            <Button type="primary" onClick={handleClose}>完成</Button>
          )}
        </Space>
      }
    >
      {/* 步骤1：文件上传 */}
      {!hasData && !hasResult && (
        <div id="employee-import-upload-area">
          <Dragger
            accept=".xlsx,.xls"
            maxCount={1}
            beforeUpload={handleFileParse}
            showUploadList={false}
          >
            <p className="ant-upload-drag-icon"><InboxOutlined /></p>
            <p className="ant-upload-text">点击或拖拽Excel文件到此区域上传</p>
            <p className="ant-upload-hint">支持 .xlsx / .xls 格式，文件需包含表头行（工号、姓名、邮箱、岗位分类、岗位、部门、直属上级工号、状态）</p>
          </Dragger>
          <div style={{ marginTop: 16, textAlign: 'center' }}>
            <Button type="link" icon={<DownloadOutlined />} onClick={downloadTemplate}>
              下载导入模板
            </Button>
          </div>
        </div>
      )}

      {/* 步骤2：预览表格 */}
      {hasData && !hasResult && (
        <div id="employee-import-preview-area">
          {hasErrors && (
            <Alert
              type="warning"
              title={`${errors.length} 条记录存在校验问题，将跳过无效行`}
              style={{ marginBottom: 12 }}
              showIcon
            />
          )}
          {!hasErrors && (
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
            scroll={{ x: 900, y: 360 }}
            pagination={false}
            rowClassName={(record) => record._valid === false ? 'import-row-invalid' : ''}
          />
        </div>
      )}

      {/* 步骤3：导入结果 */}
      {hasResult && (
        <div id="employee-import-result-area">
          <Alert
            type={importResult.errors && importResult.errors.length > 0 ? 'warning' : 'success'}
            title={`导入完成：总计 ${importResult.totalRows} 条，成功 ${importResult.successCount} 条，失败 ${importResult.errors ? importResult.errors.length : 0} 条`}
            style={{ marginBottom: 12 }}
            showIcon
          />
          {importResult.errors && importResult.errors.length > 0 && (
            <Table
              columns={resultColumns}
              dataSource={importResult.errors}
              rowKey="row"
              size="small"
              scroll={{ y: 300 }}
              pagination={false}
            />
          )}
        </div>
      )}
    </Modal>
  );
}

export default EmployeeImportModal;
