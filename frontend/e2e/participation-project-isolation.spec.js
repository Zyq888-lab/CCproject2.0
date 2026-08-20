// 模块用途：验证项目参与录入的项目下拉数据隔离——员工仅见被分配项目阶段，未分配提交被拒，管理员仍全量
// 依赖：运行中的后端(8080) + 前端(3000)；zhanggong/123456(E001,员工,仅分配 P001@P2)，admin/admin123
import { test, expect } from '@playwright/test';

async function loginAs(page, username, password) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码').fill(password);
  await page.locator('#login-card button[type="submit"]').click();
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 });
}

async function openProjectDropdown(page) {
  await page.goto('/participation');
  await expect(page.locator('#participation-page-area')).toBeVisible({ timeout: 10000 });
  await page.getByRole('button', { name: /新增参与/ }).first().click();
  await expect(page.locator('.ant-modal:visible')).toBeVisible({ timeout: 10000 });
  const projectSelect = page.locator('.ant-modal:visible .ant-select').filter({ hasText: '选择项目' }).first();
  await projectSelect.click();
  return page.locator('.ant-select-dropdown:visible .ant-select-item-option');
}

test.describe('项目参与录入项目下拉数据隔离', () => {
  test('员工仅看到被分配项目阶段（P001@P2），看不到 P002/P003', async ({ page }) => {
    await loginAs(page, 'zhanggong', '123456');
    const options = await openProjectDropdown(page);
    await expect(options.filter({ hasText: 'P001' })).toHaveCount(1);
    await expect(options.filter({ hasText: 'P002' })).toHaveCount(0);
    await expect(options.filter({ hasText: 'P003' })).toHaveCount(0);
  });

  test('员工直接提交未分配项目阶段被 400 拒绝', async ({ page }) => {
    await loginAs(page, 'zhanggong', '123456');
    await page.goto('/participation');
    await expect(page.locator('#participation-page-area')).toBeVisible({ timeout: 10000 });

    const result = await page.evaluate(async () => {
      const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
      const token = m ? m[1] : '';
      const H = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token };
      // 取一个未关闭周期作为提交目标
      const pr = await fetch('/api/v1/periods', { headers: H });
      const periods = (await pr.json()).data || [];
      const active = periods.find((p) => p.status !== 'COMPLETED');
      if (!active) return { skip: true };
      // P003@P3 未分配给 E001，应被拒
      const r = await fetch('/api/v1/participations', { method: 'POST', headers: H, body: JSON.stringify({
        periodId: active.periodId,
        items: [{ projectCode: 'P003', projectStage: 'P3', participationRate: 100 }],
      })});
      return { status: r.status, body: await r.json() };
    });

    expect(result.skip).toBeUndefined();
    expect(result.status).toBe(400);
    expect(result.body.message).toContain('未分配');
  });

  test('管理员项目下拉仍显示全部项目（非员工不受 scope 影响）', async ({ page }) => {
    await loginAs(page, 'admin', 'admin123');
    const options = await openProjectDropdown(page);
    await expect(options.filter({ hasText: 'P001' })).toHaveCount(1);
    await expect(options.filter({ hasText: 'P002' })).toHaveCount(1);
    await expect(options.filter({ hasText: 'P003' })).toHaveCount(1);
  });
});
