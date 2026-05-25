// 模块用途：E2E 7步配置向导完整走通测试（T32）
// 依赖：运行中的后端(8080) + 前端(3000)，admin/admin123 种子账号
// 修改注意：用时间戳生成唯一编码避免重复运行409冲突；antd 中文按钮空格问题用CSS选择器规避

import { test, expect } from '@playwright/test';

const TS = Date.now();
const ROLE_CODE = `PDL${TS}`;
const ROLE_NAME = `负责${TS}`;
const PROJECT_CODE = `PRJ${TS}`;
const PROJECT_NAME = `项目${TS}`;
const EMPLOYEE_ID = 'ADMIN';  // DataInitializer 种子员工（大写）

// 向导步骤提交按钮选择器（每个步骤都渲染在 #wizard-step-content 内）
const stepSubmitBtn = '#wizard-step-content button[type="submit"]';

test.describe('7步配置向导', () => {
  test.beforeEach(async ({ page }) => {
    // 1. 登录
    await page.goto('/login');
    await page.getByPlaceholder('用户名').fill('admin');
    await page.getByPlaceholder('密码').fill('admin123');
    await page.locator('#login-card button[type="submit"]').click();
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 });

    // 2. 清理：重置向导 + 关闭未完成的考核周期
    await page.evaluate(async () => {
      const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
      const token = match ? match[1] : '';
      const headers = { 'X-XSRF-TOKEN': token };
      // reset 后门已改为原地清空进度（不再软删除），避免 uk_wizard_user UNIQUE(user_id, deleted) 冲突
      const resetRes = await fetch('/api/v1/wizard/reset', { method: 'PUT', headers });
      if (!resetRes.ok) throw new Error(`Wizard reset returned ${resetRes.status}`);
      const resetJson = await resetRes.json();
      if (resetJson.code !== 200) throw new Error(`Wizard reset failed: ${resetJson.code} ${resetJson.message}`);
      // 关闭所有未完成的考核周期
      const periodRes = await fetch('/api/v1/periods', { headers });
      const periodData = await periodRes.json();
      for (const p of (periodData.data || [])) {
        if (p.status !== 'COMPLETED') {
          await fetch(`/api/v1/periods/${p.periodId}/close`, { method: 'PUT', headers });
        }
      }
    });
  });

  test('完整走通7步配置向导，最后显示完成弹窗', async ({ page }) => {
    test.setTimeout(120000); // 7步向导需要更长时间
    // 3. 导航到配置向导
    await page.goto('/setup-wizard');
    await page.waitForSelector('#setup-wizard-area', { timeout: 10000 });

    // 2b. 兜底：如果有恢复提示，关闭并回到步骤1
    const resumeBanner = page.locator('.ant-alert').filter({ hasText: '检测到上次配置进度' });
    if (await resumeBanner.isVisible({ timeout: 2000 }).catch(() => false)) {
      await resumeBanner.getByLabel('Close').first().click().catch(() => {});
      await page.locator('.ant-steps-item').filter({ hasText: '项目角色' }).first().click();
    }

    // ========================================
    // Step 1: 项目角色
    // ========================================
    await expect(page.getByText('步骤1：配置项目角色')).toBeVisible({ timeout: 5000 });
    await page.getByPlaceholder('如 PDL、PQL、Launch').fill(ROLE_CODE);
    await page.getByPlaceholder('如 项目开发负责人').fill(ROLE_NAME);
    await page.locator(stepSubmitBtn).click();

    // ========================================
    // Step 2: 导入员工 → 跳过
    // ========================================
    await expect(page.locator('button').filter({ hasText: '跳过' })).toBeVisible({ timeout: 10000 });
    await page.locator('button').filter({ hasText: '跳过' }).click();

    // ========================================
    // Step 3: 创建项目
    // ========================================
    await expect(page.getByText('步骤3：创建项目')).toBeVisible({ timeout: 10000 });
    await page.getByPlaceholder('如 PRJ2025001').fill(PROJECT_CODE);
    await page.getByPlaceholder('如 某车型座椅总成').fill(PROJECT_NAME);
    await page.locator('#wizard-step-content .ant-select').first().click();
    await page.locator('.ant-select-item-option-content').filter({ hasText: 'P2' }).first().click();
    await page.locator(stepSubmitBtn).click();

    // ========================================
    // Step 4: 分配角色人员
    // ========================================
    await expect(page.getByText('步骤4：分配项目角色')).toBeVisible({ timeout: 10000 });
    await page.getByPlaceholder('步骤3创建的项目编码').fill(PROJECT_CODE);
    await page.getByPlaceholder('步骤1创建的角色编码，如 PDL').fill(ROLE_CODE);
    await page.getByPlaceholder('待分配员工的工号').fill(EMPLOYEE_ID);
    await page.locator(stepSubmitBtn).click();

    // ========================================
    // Step 5: KPI指标
    // ========================================
    await expect(page.getByText('步骤5：配置KPI指标')).toBeVisible({ timeout: 10000 });
    await page.getByPlaceholder('如 PDL').fill(ROLE_CODE);
    await page.locator('#wizard-step-content .ant-select').first().click();
    await page.locator('.ant-select-item-option-content').filter({ hasText: 'P2' }).first().click();
    await page.getByPlaceholder('如 技术方案质量').fill(`KPI${TS}`);
    await page.getByPlaceholder('如 30').fill('100');
    await page.locator(stepSubmitBtn).click();

    // ========================================
    // Step 6: 岗位考核配置
    // ========================================
    await expect(page.getByText('步骤6：配置岗位考核')).toBeVisible({ timeout: 10000 });
    await page.getByPlaceholder('如 研发技术类').fill(`研发${TS}`);
    await page.getByPlaceholder('如 整椅研发岗').fill(`整椅${TS}`);
    await page.locator('#wizard-step-content .ant-select').first().click();
    await page.locator('.ant-select-item-option-content').filter({ hasText: '直接上级评分' }).first().click();
    await page.locator(stepSubmitBtn).click();

    // ========================================
    // Step 7: 考核周期（向导最后一步）
    // ========================================
    await expect(page.getByText('步骤7：创建考核周期')).toBeVisible({ timeout: 10000 });

    // Ant Design RangePicker: 点击后在输入框直接填写日期
    await page.getByPlaceholder('如 2025年Q1考核').fill(`Q1考核${TS}`);
    await page.locator('#wizard-step-content .ant-picker').first().click();
    await page.waitForTimeout(300);
    await page.getByPlaceholder('Start date').fill('2025-01-01');
    await page.getByPlaceholder('End date').fill('2025-06-30');
    // 按 Escape 关闭日期面板（不要按 Enter，会触发表单提交）
    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);

    // 点击"完成"按钮
    await page.locator(stepSubmitBtn).click();

    // ========================================
    // 验证完成弹窗
    // ========================================
    await expect(page.getByText('恭喜！配置已完成！')).toBeVisible({ timeout: 15000 });
    await expect(page.getByText('即将跳转至仪表盘…')).toBeVisible();

    // 2秒后自动跳转 → 验证回到仪表盘
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 10000 });
  });
});
