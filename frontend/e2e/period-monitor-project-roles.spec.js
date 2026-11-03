import { test, expect } from '@playwright/test';
import { Buffer } from 'node:buffer';
import * as XLSX from 'xlsx';

const tasks = [
  { taskId: 1, employeeId: 'EMP_A', employeeName: '多角色员工', projectCode: 'PROJ_A', projectName: '测试项目', projectStage: 'P2', projectRoles: ['PDL', 'PQL'], taskType: 'PROJECT', status: '已提交', nodeLabel: '待确认', totalScore: 4, kpiCount: 2, scoredCount: 2 },
  { taskId: 2, employeeId: 'EMP_A', employeeName: '多角色员工', projectCode: 'PROJ_A', projectName: '测试项目', projectStage: 'P3', projectRoles: ['AME'], taskType: 'PROJECT', status: '已提交', nodeLabel: '待确认', totalScore: 3, kpiCount: 1, scoredCount: 1 },
  { taskId: 3, employeeId: 'EMP_B', employeeName: '职能员工', projectRoles: [], taskType: 'FUNCTIONAL', status: '待评分', nodeLabel: '待评估人评分', totalScore: 0, kpiCount: 1, scoredCount: 0 },
  { taskId: 4, employeeId: 'EMP_C', employeeName: '无角色员工', projectCode: 'PROJ_B', projectName: '其他项目', projectStage: 'P2', projectRoles: [], taskType: 'PROJECT', status: '待评分', nodeLabel: '待评估人评分', totalScore: 0, kpiCount: 0, scoredCount: 0 },
];

async function openMonitor(page) {
  const writes = [];
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() !== 'GET') {
      writes.push(`${request.method()} ${path}`);
      return route.fulfill({ status: 405, json: { code: 405, message: '本测试禁止业务写入' } });
    }
    let data = [];
    if (path.endsWith('/auth/me')) data = { username: 'monitor-admin', roles: ['ROLE_ADMIN'] };
    else if (path.endsWith('/notifications/unread-count')) data = 0;
    else if (path.endsWith('/periods/ROLE_TEST/monitor')) data = tasks;
    return route.fulfill({ json: { code: 200, data } });
  });
  await page.goto('/period-monitor/ROLE_TEST');
  await expect(page.locator('#monitor-table-card tbody tr[data-row-key]')).toHaveCount(4);
  return { writes, pageErrors };
}

async function exportRows(page) {
  const downloadEvent = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出CSV' }).click();
  const download = await downloadEvent;
  expect(download.suggestedFilename()).toBe('周期监控_ROLE_TEST.csv');
  const chunks = [];
  for await (const chunk of await download.createReadStream()) chunks.push(chunk);
  const csv = Buffer.concat(chunks).toString('utf8');
  expect(csv.startsWith('\uFEFF')).toBe(true);
  const workbook = XLSX.read(csv.slice(1), { type: 'string', raw: true });
  return XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]], { header: 1, raw: true });
}

test('周期监控每个项目角色独立标签，同一任务仍只有一行', async ({ page }, testInfo) => {
  const evidence = await openMonitor(page);
  const card = page.locator('#monitor-table-card');
  const headers = await card.getByRole('columnheader').allTextContents();
  expect(headers.slice(0, 4)).toEqual(['员工', '项目', '项目角色', '任务类型']);
  const firstRoles = card.locator('tr[data-row-key="1"] td').nth(2);
  await expect(firstRoles.locator('.ant-tag')).toHaveText(['PDL', 'PQL']);
  await expect(card.locator('tr[data-row-key="2"] td').nth(2).locator('.ant-tag')).toHaveText(['AME']);
  await expect(card.locator('tr[data-row-key="3"] td').nth(2)).toHaveText('—');
  await expect(card.locator('tr[data-row-key="4"] td').nth(2)).toHaveText('—');
  expect(evidence.writes).toEqual([]);
  expect(evidence.pageErrors).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath('project-role-tags.png'), fullPage: true });
});

test('CSV 在同一单元格换行保存多角色，不增加数据行', async ({ page }) => {
  const evidence = await openMonitor(page);
  const [headers, ...rows] = await exportRows(page);
  expect(headers.slice(0, 4)).toEqual(['员工', '项目', '项目角色', '任务类型']);
  expect(rows).toHaveLength(4);
  expect(rows.map((row) => row[2])).toEqual(['PDL\nPQL', 'AME', '—', '—']);
  expect(evidence.writes).toEqual([]);
  expect(evidence.pageErrors).toEqual([]);
});

test('按员工筛选后的 CSV 只导出对应任务及各自角色', async ({ page }) => {
  const evidence = await openMonitor(page);
  await page.locator('#monitor-filter-bar .ant-select').nth(2).click();
  await page.getByText('多角色员工', { exact: true }).last().click();
  await expect(page.locator('#monitor-table-card tbody tr[data-row-key]')).toHaveCount(2);
  const [, ...rows] = await exportRows(page);
  expect(rows).toHaveLength(2);
  expect(rows.map((row) => row[0])).toEqual(['多角色员工', '多角色员工']);
  expect(rows.map((row) => row[2])).toEqual(['PDL\nPQL', 'AME']);
  expect(evidence.writes).toEqual([]);
  expect(evidence.pageErrors).toEqual([]);
});
