# Phase 1 核心功能清单 — 配置中心（MVP）

> 基于 `design-绩效考核系统-20260518.md` + `eng-plan-绩效考核系统-phase1-20260518.md`
> 输出日期: 2026-08-12

---

## 一、基础数据模块（11 个）

### A1 — 员工管理
- **路由**: `/employee-management`
- **权限**: ADMIN
- **功能**: 分页列表 + CRUD + 按岗位分类/工号/姓名搜索
- **批量导入**: Excel 上传 → 客户端解析 → 后端逐行校验(工号/姓名/邮箱必填、分类存在、上级存在、去重) → 成功/失败明细

### A2 — 用户角色分配
- **路由**: `/user-role`
- **权限**: ADMIN (class-level)
- **功能**: 分页列表 + 创建用户(选员工→设用户名→设密码) + 分配角色(6角色多选,覆盖式保存)
- **角色**: ADMIN(红) / 总裁(金) / PD(蓝) / PM(绿) / 评估人(紫) / 员工(灰)

### A3 — 项目角色管理
- **路由**: `/project-role`
- **权限**: ADMIN (class-level)
- **功能**: 动态增删改查(编码/名称/描述/启用); 被引用时不可删除

### A4 — 项目管理
- **路由**: `/project/list`
- **权限**: ADMIN/PM/PD(查看); ADMIN(创建/重置); ADMIN/PM(确认阶段/归档)
- **功能**: 分页列表 + 创建 + 按阶段/状态/编码筛选 + 显示归档 + PM确认阶段(乐观锁) + ADMIN强制重置 + 归档
- **批量导入**: Excel → 校验(编码/名称必填,阶段P1-P5,状态ACTIVE/COMPLETED/INACTIVE)

### A5 — 项目角色分配
- **路由**: `/project/:code/:stage/roles`
- **权限**: ADMIN/PM
- **功能**: 按角色分组展示 + 添加/移除人员 + 差异报告(检查未分配角色的项目)

### A6 — 岗位考核配置
- **路由**: `/position-config`
- **权限**: ADMIN(CRUD); ADMIN/PM(查看列表/考核人角色)
- **功能**: 分页列表 + 新增/编辑(项目制开关/默认角色/双权重/职能考核模式) + 考核人角色子管理 + 岗位分类管理
- **筛选**: 岗位分类 + 岗位名称 + **默认角色**
- **批量导入**: Excel → 校验(分类存在/角色编码存在/权重) + 成功/失败明细

### A7 — 项目KPI指标库
- **路由**: `/kpi-config/project`
- **权限**: ADMIN/PM(查看); ADMIN(CRUD)
- **功能**: 角色×阶段×指标; 权重之和=100%; 启停/排序; 批量导入

### A8 — 职能KPI指标库
- **路由**: `/kpi-config/functional`
- **权限**: ADMIN
- **功能**: 按岗位配置职能KPI; 权重之和=100%

### A9 — 职能关系配置
- **路由**: `/leader-config`
- **权限**: ADMIN
- **功能**: 直属上级关系维护; 批量导入(≤10MB, ≤1000行)

### A10 — 系统参数配置
- **路由**: `/system-param`
- **权限**: ADMIN/PM(查看); ADMIN(修改)
- **参数**: `SCORE_EVIDENCE_REQUIRED` / `MAX_RETURN_TIMES` / `DEFAULT_PERIOD_DURATION_DAYS`

### A11 — 考核周期管理
- **路由**: `/period-config`
- **权限**: ADMIN/PM(查看); ADMIN(创建/编辑/开始/关闭)
- **功能**: 卡片列表 + 手动创建/编辑(仅INIT) + 开始(→ONGOING) + 关闭(→COMPLETED)
- **约束**: 同一时间只能有一个非COMPLETED周期

---

## 二、支撑功能（4 个）

| # | 功能 | 说明 |
|---|------|------|
| — | **登录/认证** | Session + Spring Security + CSRF(XSRF-TOKEN Cookie) + bcrypt(12) |
| — | **仪表盘** | `/dashboard` — ADMIN专用: 配置进度 + 差异报告提醒 |
| — | **7步配置向导** | `/setup-wizard` — 角色→员工→项目→分配→KPI→岗位→周期, 引导首次配置 |
| — | **角色权限控制** | 后端 `@PreAuthorize` 细粒度; 前端菜单按角色过滤(`/auth/me` + `AppLayout`) |

---

## 三、数据基础设施（12 张表）

| # | 表名 | 用途 | 关键字段 |
|----|------|------|---------|
| 1 | `employee` | 员工 | employeeId(PK), name, email, category, position, orgName, directLeaderId, status |
| 2 | `sys_user` | 系统用户 | userId(PK), username, passwordHash, employeeId(FK), enabled |
| 3 | `user_role` | 用户角色 | userId(FK), roleType (ADMIN/PM/PD/总裁/评估人/员工) |
| 4 | `project_role` | 项目角色 | roleCode(PK), roleName, description, isActive |
| 5 | `project` | 项目 | projectCode(PK), projectName, projectStage(P1-P5), status, stageConfirmed, version |
| 6 | `project_role_assignment` | 角色分配 | projectCode+projectStage+roleCode+employeeId 联合唯一 |
| 7 | `position_assessment_config` | 岗位配置 | category+position+isProjectBased+defaultProjectRole+projectWeight+funcWeight |
| 8 | `position_assessor_role_config` | 考核人角色 | positionConfigId(FK), roleCode(FK) |
| 9 | `position_category` | 岗位分类 | name(UQ), sortOrder, deleted(@TableLogic) |
| 10 | `project_kpi_config` | 项目KPI | roleCode+projectStage+indicatorName(UQ), weight, isActive |
| 11 | `func_kpi_config` | 职能KPI | category+position+indicatorName(UQ), weight, isActive |
| 12 | `assessment_period` | 考核周期 | periodId(PK), periodName, startDate, endDate, status(INIT/ONGOING/CALIBRATING/COMPLETED) |
| 13 | `system_param` | 系统参数 | paramKey(PK), paramValue, description |

---

## 四、考核周期状态机

```
INIT ──start──▶ ONGOING ──???──▶ CALIBRATING ──???──▶ COMPLETED
  ▲                  │                                        │
  │    (Phase 1 已实现)         (Phase 2 PD校准流程)            │
  └────────────────close──────────────────────────────────────┘
                        (Phase 1 已实现)
```

Phase 1 仅实现 `INIT → ONGOING` (开始) 和 `ONGOING → COMPLETED` (关闭)。
`CALIBRATING` 状态为 Phase 2 预留，当前无后端逻辑。

---

## 五、Phase 2+ 不做的功能

任何考核流程操作：申报、打分、**PD校准**、总裁确认、申诉、催办、通知、考核结果查询、历史记录、报表导出、数据归档。

Phase 1 已预留数据模型（`CALIBRATING` 状态、`总裁`/`PD`/`评估人` 角色），功能代码零行。

---

## 六、本会话修复记录

| Commit | 内容 | 模块 |
|--------|------|------|
| `a048778` | 角色菜单过滤 + `/auth/me` 端点 | security + AppLayout |
| `ab8e608` | 项目批量导入 | project |
| `3285daa` | 导入权重修复 + 分类/角色校验 + 默认角色筛选 | position |
| `fed860a` | 导入模板优化 + 新增表单考核人角色 + 角色名列显示 | position |
