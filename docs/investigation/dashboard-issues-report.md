# 仪表盘两个问题 — 调查报告

> 日期：2026-09-18
> 范围：仅调查，未改代码。给出涉及文件、当前逻辑、实际数据状态、修复建议。
> 状态：历史调查；报告列出的差异销账、员工与项目信息、跳转和未读数刷新问题已在 `1d1083e` 修复。下文的“当前逻辑”指调查当日的代码。

---

## 问题 1：差异报告卡片信息不全 + 无跳转

### 涉及文件
- 后端 `DashboardService.java`（`pendingDiscrepancies`）、`DashboardController.java:51-55`
- 后端 `TaskGeneratorService.java:387-397`（`buildDiscrepancy` 写差异）
- 实体 `DiscrepancyLog.java`、迁移 `V14__create_discrepancy_log_table.sql`
- 前端 `DashboardPage.jsx:204-217`（差异渲染）、`App.jsx:40-45`（路由）

### 当前逻辑
- `GET /dashboard/discrepancies` 返回 `List<DiscrepancyLog>` 原始实体，字段是齐的：`id/periodId/employeeId/projectCode/type/detail/resolved/…`（`DashboardService.java:162-166`）。
- `buildDiscrepancy()` 只写 `periodId/employeeId/type/detail/resolved=false/时间`，**从不 `setProjectCode`**（`TaskGeneratorService.java:387-397`）。
- 前端渲染只用了两个字段：`Tag(meta.label)`（类型标签）+ `d.detail`（`DashboardPage.jsx:211-212`），**忽略 `d.employeeId`、`d.projectCode`**，也没有任何跳转。

### 实际数据状态（实测本地库）
```
NO_LEADER           36 条，employee_id 全有值，project_code 0 条有值
NO_POSITION_CONFIG  22 条，employee_id 全有值，project_code 0 条有值
合计 58 条，resolved=true 0 条
```
- **employee_id 已正确写入**（58/58 有值）。
- **project_code 全部 NULL**。当前恰好只有两类「无项目」差异，null 表面看着合理；但代码层 `buildDiscrepancy` 从不写 projectCode，一旦出现 `NO_ASSESSOR`/`NO_PRIMARY_ASSESSOR`（这两类都带项目，`TaskGeneratorService.java:174/182` 里明明有 `p.getProjectCode()`），projectCode 仍会落空。
- 表里也没有 `project_stage` 列（V14 只有 project_code）。

### 根因
1. `project_code` 从不落库（buildDiscrepancy 不写，`Discrepancy` record 只传 type/detail）。
2. API 不做姓名/项目名 enrichment（无 employeeName/projectName）。
3. 前端不渲染 employeeId（响应里其实有）+ 无跳转链接。

### 修复建议
1. **后端透传 projectCode**：把 `Discrepancy(type, detail)` 扩为 `(type, projectCode, projectStage, detail)`；`NO_ASSESSOR`/`NO_PRIMARY_ASSESSOR` 传 `p.getProjectCode()/p.getProjectStage()`，`NO_POSITION_CONFIG`/`NO_LEADER` 传 null。若要展示阶段需在表里加 `project_stage` 列（迁移）。
2. **后端 enrichment**：`/discrepancies` 改返回 DTO，join employee/project 补 `employeeName/projectName`。
3. **前端**：渲染 `employeeId(+name)/projectCode(+name)`，按 type 加跳转：
   - `NO_POSITION_CONFIG`（缺岗位配置）→ `/position-config`
   - `NO_LEADER`（无直属上级）→ `/employee-management`（注意 `DashboardPage.jsx` CARD_CONFIG 里写的是 `/employee/list`，但 `App.jsx:40` 已把 `/employee/list` redirect 到 `/employee-management`，真路由是后者）
   - `NO_ASSESSOR`（无考核人）→ `/project/assignment-summary`（角色分配汇总），不是 `/project-role`（那是「项目角色类型」管理页）
   - `NO_PRIMARY_ASSESSOR`（角色未标主）→ `/project/assignment-summary`（与 `notifyAdminsNoPrimary` 通知的 targetUrl 一致，`TaskGeneratorService.java:370`）

---

## 问题 2：待处理任务数 58 与实际不符 + 红点不消失

### 涉及文件
- 后端 `DashboardService.java:97-121`（`pendingCount`）、`DashboardController.java:44-48`
- 前端 `DashboardPage.jsx:102-135`（fetch + useEffect）、`AppLayout.jsx:73-77/164`（铃铛红点）

### 当前逻辑
`pendingCount()` 按角色分支（`DashboardService.java:97-121`）：
- **ADMIN → `selectCount(discrepancy_log where resolved=false)`** = 未处理差异数（就是 58）
- 评估人 → 自己待评分任务；员工 → 自己 PENDING 参与；PM → 自己项目 PENDING 参与

前端 `fetchPendingCount` 只在挂载的 `useEffect` 里拉一次（`DashboardPage.jsx:122-135`），无轮询、无 refetch。

### 根因（三个，叠加）
1. **语义错位**：ADMIN 的「待处理任务」= 差异数 58，不是「待评分+待审批」。而且下面「差异报告」卡（`DashboardPage.jsx:199`）也显示同一个 58，等于同一数字在页面上出现两次；「查看任务→」还跳 `/tasks`（`App.jsx:55`，staff-only，ADMIN 菜单里根本没这项）。
2. **`resolved` 永不置 true**：全代码库只有 `buildDiscrepancy` 里 `setResolved(false)`（`TaskGeneratorService.java:393`），没有任何「标记已处理」端点或 update。所以 ADMIN 就算补完配置，58 也永远不降。实测 `resolved=true` 0 条佐证。
3. **无 refetch**：两个红点数据源都是 mount 一次——
   - 仪表盘「待处理任务」卡 Badge（`DashboardPage.jsx:183`）：DashboardPage 随路由切换会重挂载，回到 /dashboard 会重拉（但 ADMIN 因根因 2 还是 58）。
   - **顶部铃铛 Badge（`AppLayout.jsx:164`）**：`unreadCount` 来自 `/notifications/unread-count`，AppLayout 常驻、只在 mount 拉一次（`AppLayout.jsx:73-77`），**除非整页刷新否则永远不变**——这才是「处理完红点不消失」的最直接元凶。

### 修复建议
1. **定清 ADMIN 语义**：把 ADMIN 的「待处理任务」卡改成「待处理差异」（与差异报告卡合并，别重复显示同一数字），或让 ADMIN 的 pendingCount 返回真正该 ADMIN 处理的数量（差异数本来就是这个语义，只是文案误导）。「查看任务→」对 ADMIN 应指向差异报告或隐藏。
2. **加「标记已处理」**：新增 `POST /dashboard/discrepancies/{id}/resolve`（或批量），ADMIN 在差异报告里点「已处理」置 `resolved=true`；否则 pendingCount 永不下降。
3. **前端刷新时机**：`AppLayout` 的 `unreadCount` 从「mount 一次」改成「路由变化时重拉 + 点击铃铛后重拉」（最简单用 `useLocation()` 作依赖，或 `visibilitychange`/轮询）。仪表盘卡的 pendingCount 若要在「处理完立刻更新」，同理加回退刷新。

---

## 结论
- **问题 1** 是纯数据/展示缺口：employee_id 有、project_code 空、前端不用；补 projectCode 落库 + 前端渲染 + 跳转即可。
- **问题 2** 是三因叠加：ADMIN 语义错位（58=差异数）+ `resolved` 永不清零 + 红点不 refetch。最痛的是 `resolved` 没有清零机制——没有它，差异数只能涨不能降。

### 修复优先级建议
1. 问题 2 的 `resolve` 端点（否则差异数只涨不降，且 ADMIN 无法销账）
2. 问题 1 的 projectCode 落库 + 前端渲染/跳转
3. 问题 2 的红点 refetch（体验层，最后补）
