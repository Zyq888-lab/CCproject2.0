import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: { code: 200, data: { roles: ['ROLE_ADMIN', 'ROLE_总裁'], username: 'reviewer' } } }));
  await page.route('**/api/v1/notifications/unread-count', (route) => route.fulfill({ json: { code: 200, data: 0 } }));
});

test('管理员监控详情展示评分凭证链接', async ({ page }) => {
  await page.route('**/api/v1/periods/TEST/monitor', (route) => route.fulfill({ json: { code: 200, data: [{
    taskId: 1, employeeId: 'EMP_A', employeeName: '员工甲', projectCode: 'P001', projectName: '项目一',
    projectStage: 'P2', taskType: 'PROJECT', status: 'SUBMITTED', indicators: [{
      kpiConfigId: 1, indicatorName: '质量指标', weight: 1, score: 4,
      evidenceUrl: '/api/v1/evidence/monitor-proof.pdf',
    }],
  }] } }));

  await page.goto('/period-monitor/TEST');
  const table = page.locator('#monitor-table-card');
  await expect(table.getByText('员工甲')).toBeVisible();
  await table.getByRole('button', { name: '查看' }).click();
  const evidenceLink = page.locator('.ant-modal:visible').getByRole('link', { name: '查看凭证' });
  await expect(evidenceLink).toHaveAttribute('href', '/api/v1/evidence/monitor-proof.pdf');
  let evidenceRequested = false;
  await page.context().route('**/api/v1/evidence/monitor-proof.pdf', (route) => {
    evidenceRequested = true;
    return route.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF' });
  });
  const evidenceResponse = page.context().waitForEvent('response', {
    predicate: (response) => response.url().endsWith('/api/v1/evidence/monitor-proof.pdf'),
  });
  const popup = page.waitForEvent('popup');
  await evidenceLink.click();
  const response = await evidenceResponse;
  expect(response.status()).toBe(200);
  expect(evidenceRequested).toBe(true);
  await popup;
});

test('总裁查看评分仅显示所选员工在该项目各阶段的行', async ({ page }) => {
  await page.route('**/api/v1/president/confirmations', (route) => route.fulfill({ json: { code: 200, data: [
    { id: 1, periodId: 'TEST', periodName: '测试周期', projectCode: 'P001', projectName: '项目一', assesseeId: 'EMP_A', employeeName: '员工甲', status: 'PENDING' },
    { id: 2, periodId: 'TEST', periodName: '测试周期', projectCode: 'P001', projectName: '项目一', assesseeId: 'EMP_B', employeeName: '员工乙', status: 'PENDING' },
  ] } }));
  await page.route('**/api/v1/periods/TEST/calibration', (route) => route.fulfill({ json: { code: 200, data: { rows: [
    { groupKey: 'project:P001|P2', assesseeId: 'EMP_A', employeeName: '员工甲', originalScore: 4, adjustedScore: 4, kpis: [] },
    { groupKey: 'project:P001|P3', assesseeId: 'EMP_A', employeeName: '员工甲', originalScore: 3, adjustedScore: 3, kpis: [] },
    { groupKey: 'project:P001|P2', assesseeId: 'EMP_B', employeeName: '员工乙', originalScore: 2, adjustedScore: 2, kpis: [] },
  ] } } }));

  await page.goto('/president-confirm');
  const table = page.locator('#president-confirm-table-card');
  await expect(table.getByText('员工甲')).toBeVisible();
  await table.locator('tr').filter({ hasText: '员工甲' }).getByRole('button', { name: '查看评分' }).click();

  const drawer = page.locator('.ant-drawer:visible');
  await expect(drawer.locator('.ant-table-tbody tr')).toHaveCount(2);
  await expect(drawer.getByText('员工乙')).toHaveCount(0);
});

test('我的结果逐指标显示项目与阶段，职能指标显示空值', async ({ page }) => {
  await page.route('**/api/v1/periods/TEST/result', (route) => route.fulfill({ json: { code: 200, data: {
    periodName: '测试周期', employeeName: '员工甲', originalScore: 3.7, adjustedScore: 3.7,
    projectName: '项目一', kpis: [
      { kpiType: 'PROJECT', projectCode: 'P001', projectName: '项目一', projectStage: 'P2', kpiName: '质量指标', weight: 1, score: 4, assessorName: '评估人' },
      { kpiType: 'FUNCTIONAL', projectCode: null, projectName: null, projectStage: null, kpiName: '协作指标', weight: 1, score: 3, assessorName: '评估人' },
    ],
  } } }));

  await page.goto('/period-result/TEST');
  const rows = page.locator('#result-page-area .ant-table-tbody .ant-table-row');
  await expect(rows).toHaveCount(2);
  await expect(rows.filter({ hasText: '质量指标' })).toContainText('项目一');
  await expect(rows.filter({ hasText: '质量指标' })).toContainText('P2');
  await expect(rows.filter({ hasText: '协作指标' }).locator('td').nth(1)).toHaveText('-');
  await expect(rows.filter({ hasText: '协作指标' }).locator('td').nth(2)).toHaveText('-');
});
