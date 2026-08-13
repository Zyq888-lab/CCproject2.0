// 模块用途：Phase 2.0 核心流程 E2E 测试
// 依赖：运行中的后端(8080) + 前端(3000)，admin/admin123 种子账号
// 前置：测试数据已由 SQL 准备（E2E_EMP1/E2E_ASSESSOR1/E2E_ROLE/E2E_PROJ/岗位配置/KPI/角色分配）
// 修改注意：CSRF 通过页面内 fetch 读取 XSRF-TOKEN cookie 处理

import { test, expect } from '@playwright/test';

// 辅助：登录 admin
async function loginAsAdmin(page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123');
  await page.locator('#login-card button[type="submit"]').click();
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 });
}

// 辅助：创建 INIT 周期（先关闭所有活跃周期）
async function createInitPeriod(page, name) {
  return page.evaluate(async (name) => {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    const token = m ? m[1] : '';
    const H = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token };
    const pr = await fetch('/api/v1/periods', { headers: H });
    const periods = (await pr.json()).data || [];
    for (const p of periods) {
      if (p.status !== 'COMPLETED') {
        await fetch('/api/v1/periods/' + p.periodId + '/close', { method: 'PUT', headers: H });
      }
    }
    const cr = await fetch('/api/v1/periods', { method: 'POST', headers: H, body: JSON.stringify({
      periodName: name, startDate: '2026-08-13', endDate: '2026-12-31',
    })});
    return (await cr.json()).data.periodId;
  }, name);
}

test.describe('Phase 2.0 核心流程', () => {
  test('ADMIN 发起考核 → 任务生成 → 评估人任务列表 → 打分 → SUBMITTED', async ({ page }) => {
    await loginAsAdmin(page);

    // 1. 创建 INIT 周期并发起考核
    const periodId = await createInitPeriod(page, 'E2E考核流程周期');

    const launchResult = await page.evaluate(async (periodId) => {
      const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
      const token = m ? m[1] : '';
      const r = await fetch('/api/v1/tasks/' + periodId + '/launch', { method: 'POST', headers: { 'X-XSRF-TOKEN': token } });
      return await r.json();
    }, periodId);
    expect(launchResult.code).toBe(200);

    // 2. 参与审批触发任务生成（E2E_EMP1 有已审批参与才生成任务）
    const approveResult = await page.evaluate(async (periodId) => {
      const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
      const token = m ? m[1] : '';
      const H = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token };
      // 为 E2E_EMP1 建参与记录
      const p = await fetch('/api/v1/participations', { method: 'POST', headers: H, body: JSON.stringify({
        periodId, employeeId: 'E2E_EMP1',
        items: [{ projectCode: 'E2E_PROJ', projectStage: 'P2', participationRate: 100 }],
      })});
      const pd = await p.json();
      if (pd.code !== 200) return { code: pd.code, message: pd.message };
      const id = pd.data[0].id;
      const a = await fetch('/api/v1/participations/' + id + '/approve', { method: 'PUT', headers: H, body: JSON.stringify({ approved: true }) });
      return await a.json();
    }, periodId);
    expect(approveResult.code).toBe(200);

    // 3. 验证任务已生成
    const taskCount = await page.evaluate(async () => {
      const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
      const token = m ? m[1] : '';
      const r = await fetch('/api/v1/tasks?page=1&size=50&assesseeId=E2E_EMP1', { headers: { 'X-XSRF-TOKEN': token } });
      return (await r.json()).data?.total || 0;
    });
    expect(taskCount).toBeGreaterThan(0);

    // 4. 打开任务列表页
    await page.goto('/tasks');
    await expect(page.locator('#task-list-page-area')).toBeVisible({ timeout: 10000 });

    // 5. 开始评分 + 提交评分 → 任务状态 SUBMITTED
    const submitResult = await page.evaluate(async () => {
      const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
      const token = m ? m[1] : '';
      const H = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token };
      // 查 E2E_EMP1 的 PROJECT 任务（有 KPI 指标）
      const list = await fetch('/api/v1/tasks?page=1&size=50&assesseeId=E2E_EMP1', { headers: H });
      const tasks = (await list.json()).data?.list || [];
      const task = tasks.find((t) => t.taskType === 'PROJECT' && (t.status === 'PENDING' || t.status === 'IN_PROGRESS'));
      if (!task) return { code: 'NO_TASK' };
      // 开始评分
      await fetch('/api/v1/tasks/' + task.id + '/start', { method: 'PUT', headers: H });
      // 查指标列表
      const detail = await fetch('/api/v1/tasks/' + task.id, { headers: H });
      const indicators = (await detail.json()).data?.indicators || [];
      // 提交所有指标评分
      const items = indicators.map((ind) => ({
        kpiConfigId: ind.kpiConfigId, kpiType: ind.kpiType, score: 4.5,
      }));
      const submit = await fetch('/api/v1/tasks/' + task.id + '/scores', { method: 'POST', headers: H, body: JSON.stringify({ items }) });
      const submitted = await submit.json();
      return { code: submitted.code, status: submitted.data?.status, indicatorCount: indicators.length };
    });
    expect(submitResult.code).toBe(200);
    expect(submitResult.status).toBe('SUBMITTED');
    expect(submitResult.indicatorCount).toBeGreaterThan(0);

    // 6. 验证通知已生成（admin 视角看通知表有数据）
    const notificationCount = await page.evaluate(async () => {
      const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
      const token = m ? m[1] : '';
      const r = await fetch('/api/v1/notifications?page=1&size=50', { headers: { 'X-XSRF-TOKEN': token } });
      return (await r.json()).data?.total || 0;
    });
    expect(notificationCount).toBeGreaterThan(0);
  });

  test('员工填写项目参与 → 审批通过 → 任务自动生成', async ({ page }) => {
    await loginAsAdmin(page);

    const periodId = await createInitPeriod(page, 'E2E参与测试周期');

    // 1. 员工填写项目参与（比重 100%）
    const partResult = await page.evaluate(async (periodId) => {
      const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
      const token = m ? m[1] : '';
      const H = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token };
      const p = await fetch('/api/v1/participations', { method: 'POST', headers: H, body: JSON.stringify({
        periodId, employeeId: 'E2E_EMP1',
        items: [{ projectCode: 'E2E_PROJ', projectStage: 'P2', participationRate: 100 }],
      })});
      return await p.json();
    }, periodId);
    expect(partResult.code).toBe(200);
    expect(partResult.data[0].status).toBe('PENDING');

    // 2. 审批通过
    const approveResult = await page.evaluate(async (participationId) => {
      const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
      const token = m ? m[1] : '';
      const H = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token };
      const r = await fetch('/api/v1/participations/' + participationId + '/approve', { method: 'PUT', headers: H, body: JSON.stringify({ approved: true }) });
      return await r.json();
    }, partResult.data[0].id);
    expect(approveResult.code).toBe(200);
    expect(approveResult.data.status).toBe('APPROVED');

    // 3. 验证任务自动生成
    const taskCount = await page.evaluate(async () => {
      const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
      const token = m ? m[1] : '';
      const r = await fetch('/api/v1/tasks?page=1&size=50&assesseeId=E2E_EMP1', { headers: { 'X-XSRF-TOKEN': token } });
      return (await r.json()).data?.total || 0;
    });
    expect(taskCount).toBeGreaterThan(0);
  });
});
