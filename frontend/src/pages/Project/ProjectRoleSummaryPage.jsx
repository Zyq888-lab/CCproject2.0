{/* 模块用途：ProjectRoleSummaryPage——项目角色分配汇总视图，跨项目展示所有角色分配 */}
{/* 依赖组件：PageHeader, client.js, Ant Design Table/Select/Button/Checkbox */}
{/* 修改注意：导出为前端CSV生成，不依赖后端导出接口；筛选仅PD负责人用Checkbox */}
import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Table, Button, Input, Select, Space, Checkbox, Tag, message, Card, Spin, Result, Modal, Upload,
} from 'antd';
import {
  SearchOutlined, ReloadOutlined, LinkOutlined, DownloadOutlined, InboxOutlined,
} from '@ant-design/icons';
import * as XLSX from 'xlsx';

const { Dragger } = Upload;
import PageHeader from '../../components/PageHeader';
import client from '../../api/client';

const STAGE_OPTIONS = [
  { label: 'P1', value: 'P1' },
  { label: 'P2', value: 'P2' },
  { label: 'P3', value: 'P3' },
  { label: 'P4', value: 'P4' },
  { label: 'P5', value: 'P5' },
];

const STAGE_COLOR_MAP = { 'P1': 'cyan', 'P2': 'blue', 'P3': 'green', 'P4': 'orange', 'P5': 'red' };
const STATUS_COLOR_MAP = { 'ACTIVE': 'green', 'INACTIVE': 'default' };

function ProjectRoleSummaryPage({ hideHeader = false }) {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });
  const [filters, setFilters] = useState({
    projectCode: '', projectStage: '', roleCode: '', employeeId: '', isPrimaryPd: false,
  });
  const [roleOptions, setRoleOptions] = useState([]);
  const [importVisible, setImportVisible] = useState(false);
  const [importData, setImportData] = useState([]);
  const [importing, setImporting] = useState(false);
  const mountedRef = useRef(true);

  // 功能：获取角色列表——用于筛选下拉
  const fetchRoles = useCallback(async () => {
    try {
      const res = await client.get('/project-roles', { params: { isActive: true, size: 999 } });
      const list = res.data?.list || [];
      if (mountedRef.current) {
        setRoleOptions(list.map((r) => ({ label: `${r.roleCode} — ${r.roleName}`, value: r.roleCode })));
      }
    } catch (_) { /* 非关键数据 */ }
  }, []);

  // 功能：分页获取汇总数据——GET /api/v1/projects/assignments/summary
  const fetchSummary = useCallback(async (page, size, filterParams) => {
    setLoading(true);
    setError(null);
    try {
      const params = { page, size };
      if (filterParams?.projectCode) params.projectCode = filterParams.projectCode;
      if (filterParams?.projectStage) params.projectStage = filterParams.projectStage;
      if (filterParams?.roleCode) params.roleCode = filterParams.roleCode;
      if (filterParams?.employeeId) params.employeeId = filterParams.employeeId;
      if (filterParams?.isPrimaryPd) params.isPrimaryPd = filterParams.isPrimaryPd;
      const res = await client.get('/projects/assignments/summary', { params });
      if (mountedRef.current) {
        const pageData = res.data || {};
        setData(pageData.list || []);
        setPagination((prev) => ({
          ...prev,
          current: pageData.page || page,
          total: pageData.total || 0,
        }));
      }
    } catch (err) {
      if (mountedRef.current) setError(err?.message || '加载失败');
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    fetchRoles();
    fetchSummary(1, 20, filters);
    return () => { mountedRef.current = false; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = () => fetchSummary(1, pagination.pageSize, filters);
  const handleReset = () => {
    const empty = { projectCode: '', projectStage: '', roleCode: '', employeeId: '', isPrimaryPd: false };
    setFilters(empty);
    fetchSummary(1, pagination.pageSize, empty);
  };

  const handleTableChange = (pag) => {
    const newPage = pag.current;
    const newSize = pag.pageSize;
    setPagination((prev) => ({ ...prev, current: newPage, pageSize: newSize }));
    fetchSummary(newPage, newSize, filters);
  };

  // 功能：导出CSV——前端生成，导出当前筛选条件下的全量数据
  const handleExport = async () => {
    try {
      // 请求不分页的全量数据
      const params = { page: 1, size: 9999 };
      if (filters.projectCode) params.projectCode = filters.projectCode;
      if (filters.projectStage) params.projectStage = filters.projectStage;
      if (filters.roleCode) params.roleCode = filters.roleCode;
      if (filters.employeeId) params.employeeId = filters.employeeId;
      if (filters.isPrimaryPd) params.isPrimaryPd = filters.isPrimaryPd;
      const res = await client.get('/projects/assignments/summary', { params });
      const list = res.data?.list || [];
      if (list.length === 0) {
        message.warning({ content: '没有数据可导出', duration: 2 });
        return;
      }
      const headers = ['项目编码', '项目名称', '阶段', '项目状态', '角色编码', '角色名称',
        '员工工号', '员工姓名', '岗位分类', '岗位', '部门', 'PD负责人', '分配时间'];
      const rows = list.map((r) => [
        r.projectCode, r.projectName, r.projectStage, r.projectStatus,
        r.roleCode, r.roleName, r.employeeId, r.employeeName,
        r.employeeCategory, r.employeePosition, r.orgName,
        r.isPrimaryPd ? '是' : '', r.createdAt ? new Date(r.createdAt).toLocaleString('zh-CN') : '',
      ]);
      const bom = '﻿';
      const csv = bom + [headers, ...rows].map((row) => row.map((c) => {
        const v = String(c ?? '');
        return v.includes(',') || v.includes('"') ? `"${v.replace(/"/g, '""')}"` : v;
      }).join(',')).join('\n');
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `项目角色分配汇总_${new Date().toISOString().slice(0, 10)}.csv`;
      a.click();
      URL.revokeObjectURL(url);
      message.success({ content: `已导出 ${list.length} 条记录`, duration: 3 });
    } catch (err) {
      message.error({ content: err?.message || '导出失败' });
    }
  };

  // 批量导入
  const COL_MAP = { '项目编码': 'projectCode', '项目阶段': 'projectStage', '角色编码': 'roleCode', '员工工号': 'employeeId' };
  const handleFileParse = (file) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      try {
        const wb = XLSX.read(e.target.result, { type: 'array' });
        const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { defval: '' })
          .map((row, idx) => {
            const mapped = { _key: idx };
            Object.entries(COL_MAP).forEach(([col, field]) => { mapped[field] = String(row[col] || '').trim(); });
            mapped._valid = !!mapped.projectCode && !!mapped.projectStage && !!mapped.roleCode && !!mapped.employeeId;
            return mapped;
          });
        setImportData(rows);
        if (rows.length > 0) message.success({ content: `成功解析 ${rows.length} 条记录` });
      } catch (_) { message.error({ content: '文件解析失败' }); }
    };
    reader.readAsArrayBuffer(file);
    return false;
  };
  const handleImport = async () => {
    const valid = importData.filter(r => r._valid);
    if (!valid.length) { message.warning({ content: '无有效数据' }); return; }
    setImporting(true);
    try {
      const payload = valid.map(({ _key, _valid, ...rest }) => rest);
      const res = await client.post('/projects/assignments/import', payload);
      const result = res.data || {};
      message.success({ content: `成功 ${result.success || 0} 条，跳过 ${result.skip || 0} 条`, duration: 4 });
      setImportVisible(false); setImportData([]);
      fetchData(pagination.current, pagination.pageSize, filters);
    } catch (err) { message.error({ content: err?.message || '导入失败' }); }
    finally { setImporting(false); }
  };
  const downloadTemplate = () => {
    const ws = XLSX.utils.json_to_sheet([{ '项目编码': 'P001', '项目阶段': 'P1', '角色编码': 'PDL', '员工工号': 'EMP001' }]);
    const wb = XLSX.utils.book_new(); XLSX.utils.book_append_sheet(wb, ws, '导入模板');
    XLSX.writeFile(wb, '角色分配导入模板.xlsx');
  };

  const columns = [
    { title: '项目编码', dataIndex: 'projectCode', key: 'projectCode', width: 120 },
    { title: '项目名称', dataIndex: 'projectName', key: 'projectName', width: 140, ellipsis: true },
    { title: '阶段', dataIndex: 'projectStage', key: 'projectStage', width: 70,
      render: (v) => <Tag color={STAGE_COLOR_MAP[v] || 'default'}>{v || '-'}</Tag> },
    { title: '项目状态', dataIndex: 'projectStatus', key: 'projectStatus', width: 90,
      render: (v) => <Tag color={STATUS_COLOR_MAP[v] || 'default'}>{v || '-'}</Tag> },
    { title: '角色编码', dataIndex: 'roleCode', key: 'roleCode', width: 100 },
    { title: '角色名称', dataIndex: 'roleName', key: 'roleName', width: 100 },
    { title: '员工工号', dataIndex: 'employeeId', key: 'employeeId', width: 100 },
    { title: '员工姓名', dataIndex: 'employeeName', key: 'employeeName', width: 100 },
    { title: '岗位分类', dataIndex: 'employeeCategory', key: 'employeeCategory', width: 100 },
    { title: '岗位', dataIndex: 'employeePosition', key: 'employeePosition', width: 110 },
    { title: '部门', dataIndex: 'orgName', key: 'orgName', width: 120, ellipsis: true },
    { title: 'PD负责人', dataIndex: 'isPrimaryPd', key: 'isPrimaryPd', width: 90,
      render: (v) => v ? <Tag color="blue">是</Tag> : null },
    { title: '分配时间', dataIndex: 'createdAt', key: 'createdAt', width: 160,
      render: (v) => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-' },
  ];

  if (loading && data.length === 0) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
        <Spin size="large"><div style={{ padding: 50, textAlign: 'center', color: '#8C8C8C' }}>加载中…</div></Spin>
      </div>
    );
  }

  if (error && data.length === 0) {
    return (
      <Result status="error" title="加载失败" subTitle={error}
        extra={<Button type="primary" onClick={() => fetchSummary(pagination.current, pagination.pageSize, filters)}>重试</Button>}
      />
    );
  }

  const isEmpty = !loading && !error && data.length === 0
    && !filters.projectCode && !filters.projectStage && !filters.roleCode && !filters.employeeId && !filters.isPrimaryPd;

  return (
    <div id="project-role-summary-area">
      {!hideHeader && (
        <PageHeader
          title="角色分配汇总"
          breadcrumb={[{ title: '首页', path: '/dashboard' }, { title: '项目管理', path: '/project/list' }]}
          actions={[
            { label: '批量导入', icon: <DownloadOutlined />, onClick: () => setImportVisible(true) },
            { label: '导出CSV', icon: <DownloadOutlined />, onClick: handleExport },
          ]}
        />
      )}

      {hideHeader && (
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16, gap: 8 }}>
          <Button icon={<DownloadOutlined />} onClick={() => setImportVisible(true)}>批量导入</Button>
          <Button icon={<DownloadOutlined />} onClick={handleExport}>导出CSV</Button>
        </div>
      )}

      <Card id="summary-search-bar" style={{ marginBottom: 16, borderRadius: 8 }}>
        <Space wrap size="middle">
          <Select
            placeholder="项目编码"
            value={filters.projectCode || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, projectCode: v || '' }))}
            allowClear
            showSearch
            style={{ width: 160 }}
            options={data.length > 0 ? [...new Map(data.map((d) => [d.projectCode, { label: d.projectCode, value: d.projectCode }])).values()] : []}
          />
          <Select
            placeholder="项目阶段"
            value={filters.projectStage || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, projectStage: v || '' }))}
            allowClear
            style={{ width: 100 }}
            options={STAGE_OPTIONS}
          />
          <Select
            placeholder="角色"
            value={filters.roleCode || undefined}
            onChange={(v) => setFilters((f) => ({ ...f, roleCode: v || '' }))}
            allowClear
            style={{ width: 160 }}
            options={roleOptions}
            showSearch
            optionFilterProp="label"
          />
          <Input
            placeholder="员工工号"
            value={filters.employeeId || ''}
            onChange={(e) => setFilters((f) => ({ ...f, employeeId: e.target.value }))}
            allowClear
            style={{ width: 140 }}
            onPressEnter={handleSearch}
          />
          <Checkbox
            checked={filters.isPrimaryPd}
            onChange={(e) => setFilters((f) => ({ ...f, isPrimaryPd: e.target.checked }))}
          >
            仅PD负责人
          </Checkbox>
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>搜索</Button>
          <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
        </Space>
      </Card>

      {error && data.length > 0 && (
        <div style={{ marginBottom: 16, color: '#FF4D4F', textAlign: 'center' }}>
          {error}
          <Button type="link" onClick={() => fetchSummary(pagination.current, pagination.pageSize, filters)}>重试</Button>
        </div>
      )}

      {isEmpty && (
        <Card id="summary-empty-card" style={{ borderRadius: 8, textAlign: 'center', padding: 60 }}>
          <LinkOutlined style={{ fontSize: 72, color: '#1890FF', marginBottom: 16 }} />
          <div style={{ fontSize: 16, color: '#262626', marginBottom: 8 }}>暂无角色分配记录</div>
          <div style={{ color: '#8C8C8C', marginBottom: 24 }}>请先在项目管理中为项目分配角色人员</div>
        </Card>
      )}

      {!isEmpty && (
        <Card id="summary-table-card" style={{ borderRadius: 8 }}>
          <Table
            columns={columns}
            dataSource={data}
            rowKey="id"
            loading={loading}
            size="middle"
            rowClassName={(_, index) => index % 2 === 1 ? 'table-row-striped' : ''}
            onChange={handleTableChange}
            pagination={{
              current: pagination.current,
              pageSize: pagination.pageSize,
              total: pagination.total,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50],
              showTotal: (total, range) => `第 ${range[0]}-${range[1]} 条，共 ${total} 条`,
            }}
            scroll={{ x: 1400 }}
          />
        </Card>
      )}

      <Modal title="批量导入角色分配" open={importVisible} onCancel={() => { setImportVisible(false); setImportData([]); }}
        width={850} footer={
          <Space>{importData.length > 0 && <Button type="primary" loading={importing} onClick={handleImport}>确认导入 ({importData.filter(r => r._valid).length} 条)</Button>}
            <Button onClick={() => { setImportVisible(false); setImportData([]); }}>关闭</Button></Space>}>
        {!importData.length ? (
          <div>
            <Dragger accept=".xlsx,.xls" maxCount={1} beforeUpload={handleFileParse} showUploadList={false}>
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">点击或拖拽Excel文件上传</p>
              <p className="ant-upload-hint">列：项目编码、项目阶段、角色编码、员工工号</p>
            </Dragger>
            <div style={{ marginTop: 16, textAlign: 'center' }}>
              <Button type="link" icon={<DownloadOutlined />} onClick={downloadTemplate}>下载导入模板</Button></div>
          </div>
        ) : (
          <Table columns={[
            { title: '项目编码', dataIndex: 'projectCode', width: 110 },
            { title: '项目阶段', dataIndex: 'projectStage', width: 90 },
            { title: '角色编码', dataIndex: 'roleCode', width: 110 },
            { title: '员工工号', dataIndex: 'employeeId', width: 110 },
            { title: '校验', dataIndex: '_valid', width: 60, render: (v) => <Tag color={v ? 'green' : 'red'}>{v ? '有效' : '无效'}</Tag> },
          ]} dataSource={importData} rowKey="_key" size="small" scroll={{ y: 360 }} pagination={false} />
        )}
      </Modal>
    </div>
  );
}

export default ProjectRoleSummaryPage;
