import { test, expect } from '@playwright/test';
import { Buffer } from 'node:buffer';
import * as XLSX from 'xlsx';

const base = { periodName: '2026年第三季度考核', calibratedScores: {}, taskType: 'PROJECT', status: '已提交', nodeLabel: '待确认', assessorId: '00999', assessorName: '测试评估人', projectRoles: ['PDL', 'PQL'], kpiCount: 1, scoredCount: 1 };
const tasks = [
  { ...base, taskId: 1, employeeId: '00101', employeeName: '员工甲', projectCode: 'PROJ_A', projectName: '项目甲', projectStage: 'P2', calibratedScores: { 11: 4.5 }, indicators: [
    { kpiConfigId: 11, indicatorName: '质量指标', evaluationCriteria: '测试标准', weight: 0.6, score: 3 },
    { kpiConfigId: 12, indicatorName: '进度指标', weight: 0.4, score: 0 },
  ] },
  { ...base, taskId: 2, employeeId: '00101', employeeName: '员工甲', projectCode: 'PROJ_A', projectName: '项目甲', projectStage: 'P3', projectRoles: ['AME'], calibratedScores: { 13: 0 }, indicators: [{ kpiConfigId: 13, indicatorName: '阶段指标', weight: 1, score: 4 }] },
  { ...base, taskId: 3, employeeId: '00202', employeeName: '员工乙', projectCode: 'PROJ_B', projectName: '项目乙', projectStage: 'P2', calibratedScores: { 11: 3 }, indicators: [{ kpiConfigId: 11, indicatorName: '质量指标', weight: 0.6, score: 2 }] },
  { ...base, taskId: 4, employeeId: '00202', employeeName: '员工乙', taskType: 'FUNCTIONAL', projectRoles: [], status: '待评分', scoredCount: 0, indicators: [{ kpiConfigId: 21, indicatorName: '职能指标', weight: 1, score: null }] },
  { ...base, taskId: 5, employeeId: '00303', employeeName: '无指标员工', projectCode: 'PROJ_C', projectName: '项目丙', projectStage: 'P1', indicators: [], kpiCount: 0, scoredCount: 0 },
  { ...base, taskId: 6, employeeId: '00404', employeeName: '=1+1', projectCode: 'PROJ_D', projectName: '+项目名称', projectStage: 'P2', indicators: [{ kpiConfigId: 31, indicatorName: '@指标名称', evaluationCriteria: '-评价标准', weight: 1, score: 5 }] },
];

async function openMonitor(page, data = tasks) {
  const writes = [];
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    if (request.method() !== 'GET') {
      writes.push(`${request.method()} ${new URL(request.url()).pathname}`);
      return route.fulfill({ status: 405, json: { code: 405, message: '禁止业务写入' } });
    }
    const path = new URL(request.url()).pathname;
    let response = [];
    if (path.endsWith('/auth/me')) response = { username: 'export-admin', roles: ['ROLE_ADMIN'] };
    else if (path.endsWith('/notifications/unread-count')) response = 0;
    else if (path.endsWith('/periods/EXPORT_TEST/monitor')) response = data;
    return route.fulfill({ json: { code: 200, data: response } });
  });
  await page.goto('/period-monitor/EXPORT_TEST');
  await expect(page.locator('#monitor-table-card tbody tr[data-row-key]')).toHaveCount(data.length);
  return { writes, pageErrors };
}

async function downloadWorkbook(page) {
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出指标明细' }).click();
  const download = await downloadEvent;
  expect(download.suggestedFilename()).toBe('周期指标明细_EXPORT_TEST.xlsx');
  const chunks = [];
  for await (const chunk of await download.createReadStream()) chunks.push(chunk);
  const workbook = XLSX.read(Buffer.concat(chunks), { type: 'buffer', cellNF: true });
  const sheet = workbook.Sheets['指标明细'];
  const [headers, ...rows] = XLSX.utils.sheet_to_json(sheet, { header: 1, defval: '' });
  return { workbook, sheet, headers, rows, records: rows.map((row) => Object.fromEntries(headers.map((header, index) => [header, row[index]]))) };
}

function assertReadOnly(evidence) {
  expect(evidence.writes).toEqual([]);
  expect(evidence.pageErrors).toEqual([]);
}

test('Excel 每指标一行，区分原始分、校准分与最终采用分并保留空白和零分', async ({ page }) => {
  const evidence = await openMonitor(page);
  const { workbook, sheet, headers, records } = await downloadWorkbook(page);
  expect(workbook.SheetNames).toEqual(['指标明细', '导出说明']);
  expect(records).toHaveLength(6);
  expect(headers).toEqual(['周期编码', '考核周期', '任务编号', '员工工号', '员工姓名', '项目编码', '项目名称', '项目阶段', '项目角色', '考核类型', '指标编号', '指标名称', '评价标准', '权重', '原始评分', 'PD校准分', '最终采用分', '评估人工号', '评估人', '任务状态']);
  const first = records.find((r) => r.任务编号 === '1' && r.指标编号 === '11');
  expect(first).toMatchObject({ 周期编码: 'EXPORT_TEST', 考核周期: '2026年第三季度考核', 员工工号: '00101', 员工姓名: '员工甲', 项目阶段: 'P2', 项目角色: 'PDL\nPQL', 权重: 0.6, 原始评分: 3, PD校准分: 4.5, 最终采用分: 4.5, 评估人工号: '00999' });
  expect(records.find((r) => r.指标编号 === '12')).toMatchObject({ 原始评分: 0, PD校准分: '', 最终采用分: 0 });
  expect(records.find((r) => r.任务编号 === '2')).toMatchObject({ 项目阶段: 'P3', 原始评分: 4, PD校准分: 0, 最终采用分: 0 });
  expect(records.find((r) => r.任务编号 === '3')).toMatchObject({ 员工工号: '00202', 项目编码: 'PROJ_B', 原始评分: 2, PD校准分: 3, 最终采用分: 3 });
  expect(records.find((r) => r.任务编号 === '4')).toMatchObject({ 考核类型: '职能考核', 项目编码: '', 项目阶段: '', 项目角色: '—', 原始评分: '', PD校准分: '', 最终采用分: '' });
  expect(records.some((r) => r.任务编号 === '5')).toBe(false);
  const weight = sheet[XLSX.utils.encode_cell({ r: 1, c: headers.indexOf('权重') })];
  expect(weight).toMatchObject({ t: 'n', v: 0.6, z: '0.00%' });
  const formulaRow = records.findIndex((r) => r.员工姓名 === '=1+1') + 1;
  const nameCell = sheet[XLSX.utils.encode_cell({ r: formulaRow, c: headers.indexOf('员工姓名') })];
  expect(nameCell).toMatchObject({ t: 's', v: '=1+1' });
  expect(nameCell.f).toBeUndefined();
  const notes = XLSX.utils.sheet_to_json(workbook.Sheets['导出说明'], { header: 1 }).flat().join('\n');
  expect(notes).toContain('不是员工综合总分');
  expect(notes).toContain('未评分留空');
  assertReadOnly(evidence);
});

test('按员工筛选导出其跨阶段指标，原有 CSV 汇总仍可导出', async ({ page }) => {
  const evidence = await openMonitor(page);
  await page.locator('#monitor-filter-bar .ant-select').nth(2).click();
  await page.getByText('员工甲', { exact: true }).last().click();
  const { records } = await downloadWorkbook(page);
  expect(records).toHaveLength(3);
  expect(records.every((r) => r.员工工号 === '00101')).toBe(true);
  expect(records.map((r) => r.项目阶段)).toEqual(['P2', 'P2', 'P3']);
  const csvEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: /导出CSV$/ }).click();
  const csv = await csvEvent;
  expect(csv.suggestedFilename()).toBe('周期监控_EXPORT_TEST.csv');
  const chunks = [];
  for await (const chunk of await csv.createReadStream()) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString('utf8');
  expect(text.startsWith('\uFEFF')).toBe(true);
  const book = XLSX.read(text.slice(1), { type: 'string', raw: true });
  expect(XLSX.utils.sheet_to_json(book.Sheets[book.SheetNames[0]])).toHaveLength(2);
  assertReadOnly(evidence);
});

test('按项目和状态筛选只导出匹配的指标', async ({ page }) => {
  const evidence = await openMonitor(page);
  await page.locator('#monitor-filter-bar .ant-select').nth(1).click();
  await page.getByText('项目乙', { exact: true }).last().click();
  await page.locator('#monitor-filter-bar .ant-select').nth(0).click();
  await page.getByText('已提交', { exact: true }).last().click();
  const { records } = await downloadWorkbook(page);
  expect(records).toHaveLength(1);
  expect(records[0]).toMatchObject({ 员工工号: '00202', 项目编码: 'PROJ_B', 项目阶段: 'P2', 最终采用分: 3 });
  assertReadOnly(evidence);
});

test('筛选没有结果时禁止明细导出', async ({ page }) => {
  const evidence = await openMonitor(page);
  await page.locator('#monitor-filter-bar .ant-select').nth(2).click();
  await page.getByText('员工甲', { exact: true }).last().click();
  await page.locator('#monitor-filter-bar .ant-select').nth(1).click();
  await page.getByText('项目乙', { exact: true }).last().click();
  await expect(page.getByRole('button', { name: '导出指标明细' })).toBeDisabled();
  assertReadOnly(evidence);
});

test('接口缺少校准分元数据时不能把原始分误导出为最终采用分', async ({ page }) => {
  const evidence = await openMonitor(page, [{ ...tasks[0], calibratedScores: undefined }]);
  await expect(page.getByRole('button', { name: '导出指标明细' })).toBeDisabled();
  await expect(page.getByRole('button', { name: /导出CSV$/ })).toBeEnabled();
  assertReadOnly(evidence);
});

test('只有无指标任务时保留汇总导出并禁用明细导出', async ({ page }) => {
  const evidence = await openMonitor(page, [tasks[4]]);
  await expect(page.getByRole('button', { name: '导出指标明细' })).toBeDisabled();
  await expect(page.getByRole('button', { name: /导出CSV$/ })).toBeEnabled();
  assertReadOnly(evidence);
});
