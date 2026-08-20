// 模块用途：验证参与页周期下拉——新增弹窗只显示未关闭周期，筛选栏仍显示全部周期
// 依赖：运行中的后端(8080) + 前端(3000)，admin/admin123；DB 需同时存在未关闭与已关闭周期
import { test, expect } from '@playwright/test';

test.describe('项目参与页周期下拉过滤', () => {
  test('新增弹窗排除已关闭周期，筛选栏保留全部周期', async ({ page }) => {
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

    // 3. 筛选栏周期下拉：应包含已关闭周期（全部周期）
    const filterSelect = page.locator('#participation-search-bar .ant-select').first();
    await filterSelect.click();
    const filterOptions = page.locator('.ant-select-dropdown:visible .ant-select-item-option');
    await expect(filterOptions.filter({ hasText: '2026年Q3考核' })).toHaveCount(1);
    await expect(filterOptions.filter({ hasText: '测试周期2026001' })).toHaveCount(1);
    // 关闭下拉，避免影响后续定位
    await page.keyboard.press('Escape');

    // 4. 打开新增弹窗
    await page.getByRole('button', { name: /新增参与/ }).first().click();
    await expect(page.locator('.ant-modal:visible')).toBeVisible({ timeout: 10000 });

    // 5. 新增弹窗周期下拉：应只显示未关闭周期，不含已关闭周期
    const modalSelect = page.locator('.ant-modal:visible .ant-select').first();
    await modalSelect.click();
    const modalOptions = page.locator('.ant-select-dropdown:visible .ant-select-item-option');
    await expect(modalOptions.filter({ hasText: '测试周期2026001' })).toHaveCount(1);
    await expect(modalOptions.filter({ hasText: '2026年Q3考核' })).toHaveCount(0);
  });
});
