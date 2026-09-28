import { test, expect } from '@playwright/test';

const matrix = {
  periodName: '校准测试周期',
  submitted: false,
  hasReturned: true,
  summary: [
    { key: 'project:P001|P2', label: '项目一·P2', count: 1, avg: 4, sigma: 0, outlierCount: 0 },
    { key: 'project:P002|P3', label: '项目二·P3', count: 1, avg: 2, sigma: 0, outlierCount: 1 },
  ],
  rows: [
    { groupKey: 'project:P001|P2', groupLabel: '项目一·P2', assesseeId: 'EMP_A', employeeName: '员工甲', originalScore: 4, adjustedScore: 4, outlier: false, confirmationStatus: 'APPROVED', kpis: [] },
    { groupKey: 'project:P002|P3', groupLabel: '项目二·P3', assesseeId: 'EMP_B', employeeName: '员工乙', originalScore: 2, adjustedScore: 2, outlier: true, confirmationStatus: 'RETURNED', kpis: [
      { kpiConfigId: 1, indicatorName: '质量指标', weight: 1, originalScore: 2, score: 2, assessorName: '评估人', evidenceUrl: '/api/v1/evidence/proof.pdf' },
    ] },
  ],
  unsubmitted: [{ assesseeId: 'EMP_U', employeeName: '未提交员工' }],
  unsubmittedCount: 1,
};

test('PD 可按项目和阶段筛选校准行，并与离群筛选叠加', async ({ page }) => {
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: { code: 200, data: { roles: ['ROLE_PD'], username: 'pd' } } }));
  await page.route('**/api/v1/notifications/unread-count', (route) => route.fulfill({ json: { code: 200, data: 0 } }));
  await page.route('**/api/v1/periods/TEST/calibration', (route) => route.fulfill({ json: { code: 200, data: matrix } }));

  await page.goto('/period-calibration/TEST');
  const table = page.locator('#calibration-table-card');
  await expect(table.getByText('员工甲')).toBeVisible();
  await expect(table.getByText('员工乙')).toBeVisible();
  await expect(table.getByText('未提交员工')).toBeVisible();

  await page.getByRole('combobox', { name: '项目筛选' }).click();
  await page.getByText('项目二·P3', { exact: true }).last().click();
  await expect(table.getByText('员工乙')).toBeVisible();
  await expect(table.getByText('员工甲')).toHaveCount(0);
  await expect(table.getByText('未提交员工')).toHaveCount(0);

  await page.getByRole('switch', { name: '只看离群' }).check();
  await expect(table.getByText('员工乙')).toBeVisible();

  await table.getByRole('button', { name: '改分' }).click();
  const evidenceLink = page.locator('.ant-drawer:visible').getByRole('link', { name: '查看凭证' });
  await expect(evidenceLink).toHaveAttribute('href', '/api/v1/evidence/proof.pdf');
  let evidenceRequested = false;
  await page.context().route('**/api/v1/evidence/proof.pdf', (route) => {
    evidenceRequested = true;
    return route.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF' });
  });
  const evidenceResponse = page.context().waitForEvent('response', {
    predicate: (response) => response.url().endsWith('/api/v1/evidence/proof.pdf'),
  });
  const popup = page.waitForEvent('popup');
  await evidenceLink.click();
  const response = await evidenceResponse;
  expect(response.status()).toBe(200);
  expect(evidenceRequested).toBe(true);
  await popup;
});
