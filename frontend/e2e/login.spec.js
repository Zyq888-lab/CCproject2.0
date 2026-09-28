// 模块用途：E2E 登录流程测试（T32）
// 依赖：运行中的后端(8080) + 前端(3000)，通过 E2E_ADMIN_PASSWORD 配置已完成首次改密的管理员密码

import { test, expect } from '@playwright/test';
import { adminPassword } from './helpers/admin-credentials.js';

test.describe('登录流程', () => {
  test('使用管理员账号登录成功并跳转仪表盘', async ({ page }) => {
    // 1. 访问登录页
    await page.goto('/login');
    await expect(page.locator('#login-card')).toBeVisible();

    // 2. 填写用户名和密码
    await page.getByPlaceholder('用户名').fill('admin');
    await page.getByPlaceholder('密码').fill(adminPassword());

    // 3. 点击登录按钮（antd 在中文按钮文字间插入空格，"登录" → "登 录"）
    await page.locator('#login-card button[type="submit"]').click();

    // 4. 验证跳转到仪表盘（URL 变化 + 页面内容加载）
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 });

    // 5. 验证仪表盘关键元素可见
    await expect(page.locator('#dashboard-welcome-area, #dashboard-configured-area, #dashboard-loading-area').first()).toBeVisible({ timeout: 10000 });
  });

  test('错误密码登录失败显示错误提示', async ({ page }) => {
    await page.goto('/login');
    await page.getByPlaceholder('用户名').fill('admin');
    await page.getByPlaceholder('密码').fill('wrong_password');

    await page.locator('#login-card button[type="submit"]').click();

    // 应停留在 /login 或显示错误提示
    await expect(page.locator('.ant-alert-error').first()).toBeVisible({ timeout: 10000 });
  });

  test('空表单提交显示校验错误', async ({ page }) => {
    await page.goto('/login');
    await page.locator('#login-card button[type="submit"]').click();

    // Ant Design 表单校验会显示错误消息
    await expect(page.getByText('请输入用户名')).toBeVisible();
    await expect(page.getByText('请输入密码')).toBeVisible();
  });
});
