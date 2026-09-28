# TODOS

## Phase 2.1

### 凭证迁移到对象存储

**What:** 将当前本地磁盘保存的评分凭证迁移到对象存储（MinIO/S3），并规划已有文件迁移与备份。

**Why:** 凭证现已真实落盘，PD 校准、管理员监控和结果明细可以打开；但本地磁盘不适合多实例共享，也需要明确的持久化和备份策略。

**Context:** eng-review 决策 E 将对象存储推迟至 Phase 3；本版本先使用本地存储，后续可抽取 `StorageService` 并迁移旧文件。

**Effort:** M
**Priority:** P3
**Depends on:** Phase 2.1 结果页上线

### 逐行 N+1 债务扫除

**What:** 扫除 `MyAssessmentService.resolveName` 等剩余服务的逐行 `selectById` N+1。

**Why:** 校准矩阵已批量预取（eng-review 决策 M），但其他服务仍有逐行 `selectById`，数据量增长后变慢。

**Context:** `MyAssessmentService.resolveName`（238-241 行）逐行 `selectById` 查姓名；本次只修了校准矩阵。批量预取到 Map 是既定解法，其余服务照此清理。

**Effort:** S
**Priority:** P3
**Depends on:** None

### 设计系统 DESIGN.md 缺失

**What:** 补一份 DESIGN.md（设计系统），固化现有 UI 约定。

**Why:** 仓库无 DESIGN.md，现有约定（Card 圆角 8、条纹 Table、Tag 状态色、#1890FF/#FF4D4F）只存在于代码，新页面靠抄现有页、一致性靠自觉。

**Context:** design-review 已把 2.1 三屏的约定写进计划（「UI 设计决策」节），但值得独立成文。用 `/design-consultation` 补，或实现三屏时顺手沉淀。

**Effort:** S
**Priority:** P3
**Depends on:** None

## Phase 2.2

### 总裁已通过人员的 KPI 仍可修改

**What:** 在改分与总裁确认共享的周期锁内拒绝修改该项目已通过人员的 KPI，界面同步只读，并补批准 A 后改 A、再批准 B 及并发回归。

**Why:** 周期还在等待其他人员确认时，PD 可修改已通过人员的覆盖分；最后确认会采用新分，原已通过人员无需重新确认，确认意见与最终分数可能不一致。

**Context:** 2026-09-28 ship 审查确认 `CalibrationService.adjustKpi` 缺少人员确认状态检查，`CalibrationMatrixPage` 无退回时仍显示改分。用户明确选择“接受该风险，继续更新 PR”，本次未修复。后续优先采用锁定已通过评分方案；若业务要允许修改，需另外设计撤销确认与重确认。

**Effort:** S
**Priority:** P1
**Depends on:** None

### averageTaskScores 空分任务按 0 计（P3-3）

**What:** `ResultService.averageTaskScores` 对没有评分行的任务（`scoresByTask.getOrDefault(..., List.of())`）仍 `count++`，且 `weightedSum([],[])` 贡献 0 分，导致 SUBMITTED 但无评分行的任务把均值往下拉。

**Why:** 一条已提交但缺评分的任务不应按 0 分参与均值，否则会静默压低员工 composite；应跳过空评分任务（或对「已提交却无评分」显式告警），而不是当 0 分计。

**Context:** 需先确认「任务已 SUBMITTED 但无 assessment_score 行」是否合法——若合法则跳过，若不合法应在上游拦截，再定修法。

**Effort:** S
**Priority:** P3
**Depends on:** None

### generateNextUserId 并发撞号（I-2）

**What:** `UserService.generateNextUserId` 用 `MAX(userId)+1` 生成新 ID，`activate` 复用；并发激活两个账号时可能撞号，第二个 insert 触发主键冲突返回 409。

**Why:** 并发下两个事务读到相同 `MAX(userId)` 会生成相同 userId，后者 insert 触发 `DuplicateKeyException`→409，用户需重试。低频但真实。

**Context:** 属 `createUser` 既有模式，非 Track A 引入；`sys_user.user_id` 无自增序列。修法三选一待定：DB 序列 / UUID / 冲突重试兜底。

**Effort:** S
**Priority:** P3
**Depends on:** None

### abort 强制关闭后结果对员工可见（次生风险，延后）

**What:** `PeriodService.abortPeriod` 把任意非 COMPLETED 周期强制置为 COMPLETED，但 `PeriodStatusPolicy.isResultVisible` 对 COMPLETED 返回 true，导致被强关的 CALIBRATING/CONFIRMED 周期里未经校准/总裁确认的中间结果对员工「最终可见」。

**Why:** 逃生出口复用了「已归档可见」语义，但强关的周期从未走完 confirm/publish，其结果不应算最终结果。

**Context:** 修法需新增独立终态 `ABORTED`（不可见），同步改 `PeriodStatusPolicy.isResultVisible/approvalNode`、`PeriodConfigPage` 的 `STATUS_CONFIG`、`createPeriod` 唯一约束与 `assertNotCompleted` 把 ABORTED 当终态。改动面大，本次仅在前端补「强制关闭」按钮，此风险延后单独排期。

**Effort:** M
**Priority:** P3
**Depends on:** 前端强制关闭按钮上线

## Completed

### 参与录入审批按项目、阶段主 PM 收敛

**What:** 非 ADMIN 的审批权限和 `scope=approval` 待审批查询均按当前员工主负责的项目、阶段过滤，其他 PM 的申请不进入列表。

**Why:** 全局 PM 角色不能代表所有项目的审批范围；列表与实际审批权限需一致，避免显示后才报无权限。

**Context:** 历史 Phase2.1 计划曾将此路由列为待定；当前 `ParticipationService.approve` 已校验实际阶段主 PM，提交 `c0287a3` 收窄待审批查询，后续代码保留该规则。2026-09-28 一条现有申请的跨 PM 对照通过；两个项目的新申报与审批闭环仍列在 [发布验收清单](docs/phase2/release/ACCEPTANCE.md)，本条“完成”指代码实现，不代表完整业务验收。

**Effort:** M
**Priority:** P2
**Depends on:** Phase2 主角色配置
**Completed:** v2.14.0.0（2026-09-28 文档核对）

### 校准矩阵离群分组键与标签不一致（P3-2）

**What:** `CalibrationService.resolveGroup` 的分组键只用项目 code（`"project:" + code`），但行标签带阶段（`name + "·" + stage`），同一项目不同阶段的员工被混进同一 σ 组做离群判定。

**Why:** 分组键决定离群 σ 的计算边界：现在 code 相同、阶段不同的员工被放在一个组里比均值/σ，而展示却按「项目·阶段」标注，导致「同组比离群」的组边界与用户看到的组标签不一致，离群标记可能误导 PD。

**Context:** 已将分组键改为 `project:<code>|<stage>`，并同步行标签与汇总带口径。

**Effort:** S
**Priority:** P3
**Depends on:** None
**Completed:** v2.14.0.0 (2026-09-24)
