// 模块用途：验证项目参与页「考核周期」列——表头存在 + 单元格显示周期名称（而非原始 periodId）
// 依赖：运行中的后端(8080) + 前端(3000)，admin/admin123 种子账号，周期 P2026Q3「2026年Q3考核」及参与记录
import { test, expect } from '@playwright/test';

test.describe('项目参与页考核周期列', () => {
  test('参与列表显示考核周期列且单元格展示周期名称', async ({ page }) => {
    // 1. 登录 admin
    await page.goto('/login');
    await page.getByPlaceholder('用户名').fill('admin');
    await page.getByPlaceholder('密码').fill('admin123');
    await page.locator('#login-card button[type="submit"]').click();
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 });

    // 2. 进入项目参与页
    await page.goto('/participation');
    await expect(page.locator('#participation-page-area')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('#participation-table-card')).toBeVisible({ timeout: 10000 });

    // 3. 断言表格存在「考核周期」列头
    const periodHeader = page.locator('#participation-table-card th', { hasText: '考核周期' });
    await expect(periodHeader.first()).toBeVisible({ timeout: 10000 });

    // 4. 断言单元格展示的是周期名称「2026年Q3考核」（render 已把 periodId 映射为 periodName），
    //    而非原始 periodId「P2026Q3」；两条参与记录(E001)应各显示一次该名称
    const periodCells = page.locator('#participation-table-card td', { hasText: '2026年Q3考核' });
    await expect(periodCells.first()).toBeVisible({ timeout: 10000 });
    await expect(periodCells).toHaveCount(2);
  });
});
