import { test, expect } from '@playwright/test';

const record = {
  id: 'HOOKS_KPI', category: '回归测试岗位分类', position: '回归测试岗位',
  kpiName: 'Hook 顺序回归指标', evaluationCriteria: '回归数据', weight: 0.2,
  sortOrder: 0, isActive: true,
};

async function openFunctional(page) {
  const pageErrors = [];
  const writes = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() !== 'GET') {
      writes.push({ method: request.method(), path });
      return route.fulfill({ status: 409, json: { code: 409, message: '回归测试禁止写入' } });
    }
    let data = [];
    if (path.endsWith('/auth/me')) data = { username: '回归ADMIN', roles: ['ROLE_ADMIN'] };
    else if (path.endsWith('/notifications/unread-count')) data = 0;
    else if (path.endsWith('/kpi-configs/functional')) data = [record];
    else if (path.endsWith('/position-configs')) {
      data = { list: [{ id: 'HOOKS_POSITION', category: record.category, position: record.position }] };
    } else if (path.endsWith('/position-categories/list')) {
      data = [{ id: 'HOOKS_CATEGORY', name: record.category }];
    }
    return route.fulfill({ json: { code: 200, data } });
  });
  await page.goto('/kpi-config');
  await page.getByRole('tab', { name: '职能KPI配置', exact: true }).click();
  await expect(page.locator('#func-kpi-area')).toBeVisible();
  await expect(page.getByText(record.kpiName, { exact: true })).toBeVisible();
  return { pageErrors, writes };
}

async function openModal(page, mode) {
  if (mode === 'create') {
    await page.locator('#func-kpi-area').getByRole('button', { name: /新增KPI/ }).click();
  } else {
    await page.locator('#func-kpi-table-card').getByRole('button', { name: /编\s*辑/ }).click();
  }
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(mode === 'create' ? '新增职能KPI' : '编辑职能KPI', { exact: true })).toBeVisible();
  return dialog;
}

async function cancel(page) {
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: /取\s*消/ }).click();
  await expect(dialog).toBeHidden();
}

for (const direction of ['create-edit', 'edit-create', 'create-create', 'edit-edit']) {
  test(`职能KPI Modal Hook 顺序回归：${direction}`, async ({ page }) => {
    const evidence = await openFunctional(page);
    const [first, second] = direction.split('-');
    await openModal(page, first);
    await cancel(page);
    const secondDialog = await openModal(page, second);
    if (second === 'edit') {
      await expect(secondDialog.getByText('该岗位下其他指标权重之和 0% + 当前 20% = 20%', { exact: false })).toBeVisible();
    }
    await expect(page.locator('#root')).toBeVisible();
    expect((await page.locator('#root').innerText()).trim()).not.toBe('');
    await cancel(page);
    await expect(page.locator('#func-kpi-area')).toBeVisible();
    expect(evidence.pageErrors).toHaveLength(0);
    expect(evidence.writes).toHaveLength(0);
  });
}
