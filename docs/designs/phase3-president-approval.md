# Phase 3 — 按项目「总裁审批」设计

> 状态：**历史草案，已由 [Phase 2.2 总裁确认设计](phase2.2-president-confirm-and-user-activation.md) 取代。** 下文的 `PRESIDENT_REVIEW` 状态、`president_project_review` 表和端点并未采用；现行流程使用 `project_confirmation`、`CONFIRMED`（待发布）和 `PUBLISHED`（已发布）。

## 背景与目标

当前系统里「总裁」角色是孤儿：`RoleType.PRESIDENT("总裁")` 定义了，但没有任何 `@PreAuthorize` 或数据隔离分支识别它（authority 是 `ROLE_总裁`，而所有权限清单只列 ADMIN/PM/PD/评估人/员工）；「总裁确认」实际是 ADMIN-only 动作；`NEED_PRESIDENT_CONFIRM` 是死参数。

目标业务流（用户已确认）：

> PD 校准后，流程流转到总裁。总裁只看到自己管辖范围（项目+阶段）的审批，可批量确认；个别人有问题可退回到上一节点（PD）。总裁全部确认后流程结束，由 admin 发布结果。

约束：**多个总裁各管一摊项目**，一个总裁不是全量兜底。

## 已敲定的设计

### 1. 状态机

```
INIT → ONGOING → CALIBRATING → PRESIDENT_REVIEW → CONFIRMED → COMPLETED
```

新增 `PRESIDENT_REVIEW` 状态：PD 提交校准（`submit-calibration`）后进入，全部总裁确认后由 admin 发布翻到 `CONFIRMED`。

### 2. 总裁管辖 = 项目角色（维度 1：项目）

复用现有 `project_role_assignment` + `RoleAssignmentPage`，**无需新表**：

- `project_role` 表 seed `总裁`（`role_code='总裁'`）。
- ADMIN 在角色分配页按 `(项目, 阶段)` 分配某个总裁工号，`role_code='总裁'`。
- 数据隔离 / 监控 / 审批页加总裁分支：总裁只看到自己 `role_code='总裁'` 的那些 `(项目, 阶段)`。
- 多个总裁 = 各自项目各分配一条，天然支持。

**口径：项目 + 阶段，不要所有阶段混在一起**（用户确认；按考核周期审批）。

### 3. 审批粒度与记录

- 总裁审批按 `(period, project_code, project_stage)` 记一条确认，新表 `president_project_review`：
  - `period_id, project_code, project_stage, reviewed_by, reviewed_at`，唯一键 `(period, project, stage)`。
- 员工级退回：`assessment_result` 加列 `review_status (PENDING/APPROVED/RETURNED)`、`returned_by / return_reason / returned_at`。

### 4. 退回流转

总裁退回某员工 → 该员工 `review_status=RETURNED` → PD 重新校准 → PD「重新提交校准」→ 该员工单独重入总裁队列（其余已确认的不重来）。

### 5. 发布门

admin 发布（`PUT /periods/{id}/confirm`）前置校验：所有 `(项目, 阶段)` 总裁均已确认 + 无未处理的退回；否则 block。

### 6. 端点

- `POST /periods/{id}/submit-calibration` → 状态 `PRESIDENT_REVIEW`。
- `GET /periods/{id}/president-review`（总裁视角，按自己管辖范围）。
- `POST /periods/{id}/president-review/approve`（批量确认 `(项目, 阶段)`）。
- `POST /periods/{id}/president-review/return`（员工级退回）。
- `PUT /periods/{id}/confirm`（admin 发布，带发布门）。

### 7. 前端

- `ConfirmPage` → 总裁审批页：按项目+阶段分组，支持批量确认 + 员工行内退回。
- `RoleAssignmentPage`：总裁角色可分配（ADMIN only，PD 仍只读）。
- `PeriodConfigPage` `STATUS_CONFIG` 加 `PRESIDENT_REVIEW`（status-map-drift 教训）。

## 待解决（phase 3 开工前必答）

**纯职能员工（只有 FUNCTIONAL 任务、无项目阶段）归属谁？** 他们落不进任何 `(项目, 阶段)`，而总裁又是多个人、不能固定一个兜底（用户原话「总裁有很多个人」）。

候选口径（员工表字段）：

- `orgName`（部门）—— 总裁管某几个部门，纯职能员工按部门归总裁（倾向此）。
- `category`（岗位类别）。
- 或确认实际没有纯职能员工（每人至少挂一个项目），可直接忽略。

定了口径后，对应补一个「总裁-部门」轻量分配（`president_org_assignment` 表或系统参数 JSON）。
