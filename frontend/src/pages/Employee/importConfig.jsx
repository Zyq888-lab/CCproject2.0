{/* 模块用途：importConfig——批量导入共享配置，COLUMN_MAP/校验/解析/预览列/模板下载 */}
{/* 使用方：EmployeeImportModal, StepImportEmployee */}
import { Tag } from 'antd';
import * as XLSX from 'xlsx';

export const COLUMN_MAP = {
  '工号': 'employeeId',
  '姓名': 'name',
  '邮箱': 'email',
  '岗位分类': 'category',
  '岗位': 'position',
  '部门': 'orgName',
  '直属上级工号': 'directLeaderId',
  '状态': 'status',
};

export const REQUIRED_FIELDS = ['employeeId', 'name', 'email', 'status'];

const STATUS_LABEL_MAP = { 'ACTIVE': '在职', 'INACTIVE': '离职', '在职': '在职', '离职': '离职' };
const STATUS_COLOR_MAP = { 'ACTIVE': 'green', 'INACTIVE': 'red', '在职': 'green', '离职': 'red' };

export function parseExcelFile(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const wb = XLSX.read(e.target.result, { type: 'array' });
        const sheet = wb.Sheets[wb.SheetNames[0]];
        const rawData = XLSX.utils.sheet_to_json(sheet, { defval: '' });

        if (rawData.length === 0) {
          reject(new Error('Excel文件为空，请检查后重新上传'));
          return;
        }

        const parseErrors = [];
        const rows = rawData.map((row, idx) => {
          const mapped = { _rowNum: idx + 2 };
          Object.entries(COLUMN_MAP).forEach(([colName, fieldName]) => {
            const value = row[colName];
            mapped[fieldName] = value !== undefined && value !== '' ? String(value).trim() : '';
          });
          const missingFields = REQUIRED_FIELDS.filter((f) => !mapped[f]);
          if (missingFields.length > 0) {
            const missingNames = missingFields.map((f) =>
              Object.keys(COLUMN_MAP).find((k) => COLUMN_MAP[k] === f)
            ).join('、');
            parseErrors.push({ row: idx + 2, employeeId: mapped.employeeId || '(空)', message: `缺少必填字段: ${missingNames}` });
            mapped._valid = false;
          } else {
            mapped._valid = true;
          }
          return mapped;
        });

        resolve({ rows, errors: parseErrors });
      } catch (err) {
        reject(new Error('Excel解析失败，请确认文件格式正确'));
      }
    };
    reader.onerror = () => reject(new Error('文件读取失败'));
    reader.readAsArrayBuffer(file);
  });
}

export function buildPreviewColumns() {
  return [
    { title: '行号', dataIndex: '_rowNum', key: '_rowNum', width: 60 },
    { title: '工号', dataIndex: 'employeeId', key: 'employeeId', width: 100 },
    { title: '姓名', dataIndex: 'name', key: 'name', width: 100 },
    { title: '邮箱', dataIndex: 'email', key: 'email', width: 180, ellipsis: true },
    { title: '岗位分类', dataIndex: 'category', key: 'category', width: 100 },
    { title: '岗位', dataIndex: 'position', key: 'position', width: 120 },
    { title: '部门', dataIndex: 'orgName', key: 'orgName', width: 120, ellipsis: true },
    { title: '直属上级工号', dataIndex: 'directLeaderId', key: 'directLeaderId', width: 110 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 70,
      render: (v) => <Tag color={STATUS_COLOR_MAP[v] || 'default'}>{STATUS_LABEL_MAP[v] || v || '-'}</Tag> },
    { title: '校验', dataIndex: '_valid', key: '_valid', width: 70,
      render: (v) => v !== false ? <Tag color="green">有效</Tag> : <Tag color="red">无效</Tag> },
  ];
}

export function downloadTemplate() {
  const templateData = [
    { '工号': 'EMP001', '姓名': '张三', '邮箱': 'zhangsan@jifeng.com', '岗位分类': '研发技术类', '岗位': '整椅研发工程师', '部门': '研发中心', '直属上级工号': '', '状态': '在职' },
  ];
  const ws = XLSX.utils.json_to_sheet(templateData);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, '员工导入模板');
  XLSX.writeFile(wb, '员工导入模板.xlsx');
}
