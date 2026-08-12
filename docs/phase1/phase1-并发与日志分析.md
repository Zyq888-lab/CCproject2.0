# Phase 1 并发控制与日志审计分析

> 生成日期: 2026-08-12 | 基于代码扫描

---

## 一、并发控制

### 1.1 已有的：乐观锁 (Optimistic Locking)

| 覆盖模块 | 实体字段 | 实现 |
|---------|---------|------|
| Project | `@Version Long version` | `BaseService.updateWithOptimisticLock()` |
| PositionConfig | `@Version Long version` | 同上 |
| SystemParam | `@Version Long version` | 同上 |
| Employee | `@Version Long version` | 同上 |
| ProjectRole | `@Version Long version` | 同上 |

**工作原理**:

```
用户A                          数据库                      用户B
  │                              │                          │
  │ SELECT version=3             │                          │
  │─────────────────────────────►│                          │
  │                              │                          │ SELECT version=3
  │                              │◄─────────────────────────│
  │                              │                          │
  │ UPDATE SET ... WHERE         │                          │
  │   version=3                  │                          │
  │─────────────────────────────►│                          │
  │  ✓ rows=1, version→4        │                          │
  │                              │                          │ UPDATE SET ... WHERE
  │                              │◄─────────────────────────│ version=3
  │                              │────── rows=0 ───────────►│
  │                              │                          │
  │                              │                    ✗ 409 Conflict
```

**前端处理** (`ConfirmModal.jsx`):

```js
showConflictWarning('其他用户', '几秒前')
// → 弹窗: "数据已被其他用户在几秒前修改，请刷新后重试"
```

**测试覆盖**:

| 测试类 | 用例数 | 场景 |
|--------|:--:|------|
| `OptimisticLockIntegrationTest` | 8 | Project确认/重置并发 + PositionConfig编辑并发 + SystemParam批量更新并发 |
| `ProjectServiceTest` | 7 | 确认阶段version冲突→409 |
| `PositionConfigServiceTest` | 13 | 编辑version冲突→409 |
| `SystemParamServiceTest` | 6 | 批量更新version冲突→409 |

### 1.2 已有的：唯一约束 (Unique Constraints)

| 约束 | 表 | 错误处理 |
|------|----|---------|
| projectCode + projectStage 联合唯一 | `project` | DuplicateKeyException → 409 |
| employeeId 唯一 | `employee` | DuplicateKeyException → 409 |
| userId → employeeId 唯一 | `sys_user` | 应用层校验 → 409 |
| roleCode 唯一 | `project_role` | DuplicateKeyException → 409 |
| positionConfigId + roleCode 唯一 | `position_assessor_role_config` | DuplicateKeyException → 409 |
| category + position 联合唯一 | `position_assessment_config` | DuplicateKeyException → 409 |
| projectCode + projectStage + roleCode + employeeId | `project_role_assignment` | DuplicateKeyException → 409 |

### 1.3 已有的：活跃周期唯一

`PeriodService.createPeriod()`:

```java
long activeCount = periodMapper.selectCount(
    new LambdaQueryWrapper<AssessmentPeriod>()
        .ne(AssessmentPeriod::getStatus, "COMPLETED"));
if (activeCount > 0) {
    throw new BusinessException(409, "当前已有未关闭的考核周期");
}
```

### 1.4 没有的

| 缺失项 | 说明 | 影响 |
|--------|------|------|
| 悲观锁 (`SELECT ... FOR UPDATE`) | 无场景需要 | Phase 1 OK |
| 分布式锁 (Redis/ZooKeeper) | 单实例部署 | Phase 1 OK |
| 消息队列串行化 | 无异步操作 | Phase 1 OK |
| 限流/熔断 | 无高并发场景 | Phase 1 OK |

**结论**: Phase 1 并发用户 < 10，乐观锁 + 唯一约束 + 409 重试完全够用。

---

## 二、日志审计

### 2.1 已有的：ControllerLogAspect (请求日志)

**文件**: `src/main/java/com/jifeng/assessment/common/ControllerLogAspect.java`

**覆盖范围**: 所有 `*Controller.*` 方法 (AOP 环绕通知)

**日志格式** (JSON):

```json
{
  "timestamp": "2026-08-12T13:45:30.123Z",
  "type": "API_REQUEST",
  "method": "POST",
  "url": "/api/v1/projects",
  "controller": "ProjectController.create",
  "user": "admin",
  "status": 200,
  "elapsed_ms": 45
}
```

**分级输出**:

| HTTP 状态 | 日志级别 | 说明 |
|-----------|:--:|------|
| 2xx | `INFO` | 正常请求 |
| 4xx | `WARN` | 客户端错误 (400/401/403/404/409) |
| 5xx | `ERROR` | 服务端错误 (500) |

**可回答的问题**:
- ✅ 谁 (`admin`) 在什么时间 (`timestamp`) 调了什么接口 (`url`)
- ✅ 耗时多少 (`elapsed_ms`)
- ✅ 成功还是失败 (`status`)

### 2.2 已有的：业务关键日志

| 位置 | 日志内容 |
|------|---------|
| `DataInitializer.java:66` | `log.info("初始ADMIN账号已创建")` |
| `ImportService.java` | `log.warn("Row N: duplicate employeeId")` / `log.error("Row N data access error")` |
| `GlobalExceptionHandler.java` | `log.warn("业务异常: code={}, message={}")` |

### 2.3 没有的：业务审计日志

设计文档规划 (Phase 3):

> A31 | 审计日志 | 关键操作记录（SUBMIT/RETURN/CALIBRATE/CONFIRM等） | P2-04

| 缺失项 | 说明 | 计划 |
|--------|------|:--:|
| `audit_log` 表 | 操作人/时间/类型/目标/变更前后值(JSON) | Phase 3 |
| 数据变更快照 | 修改前后完整字段对比 | Phase 3 |
| 关键操作追踪 | SUBMIT / RETURN / CALIBRATE / CONFIRM | Phase 2-3 |
| 登录日志 | 成功/失败/IP/时间/User-Agent | 未计划 |
| 导出/下载记录 | 谁导出了什么数据 | 未计划 |
| 敏感操作告警 | 异常批量操作/非工作时间操作 | 未计划 |

---

## 三、与设计文档对照

`design-绩效考核系统-20260518.md` 相关决策:

| 决策 | 内容 | 当前状态 |
|------|------|:--:|
| D7 | CSRF token + bcrypt(12) | ✅ Phase 1 已实现 |
| D12 | Controller AOP 拦截记录请求日志 (URL/用户/耗时/状态码) | ✅ Phase 1 已实现 |
| D12 | Service 层关键操作记录业务日志 | ⚠️ 仅 ImportService 有 |
| D12 | SLF4J + Logback JSON 格式 | ✅ ControllerLogAspect 输出 JSON |
| D13 | Flyway 数据库迁移版本管理 | ✅ V1-V9 (V8 缺失, dev 已修复) |
| — | 审计日志表 (`audit_log`) | ❌ Phase 3 |
| — | 登录日志 | ❌ 未计划 |

---

## 四、总结

| 维度 | 状态 | 评价 |
|------|:--:|------|
| 并发 — 乐观锁 | ✅ | 全部写操作覆盖，409 + 前端提示完整 |
| 并发 — 唯一约束 | ✅ | 数据库层面兜底，DuplicateKeyException→409 |
| 并发 — 分布式锁 | ❌ | 不需要，单实例 Phase 1 够用 |
| 日志 — 请求全量 | ✅ | AOP 自动记录，JSON 格式，可按需接入 ELK |
| 日志 — 业务审计 | ❌ | Phase 3 规划中，当前零行代码 |
| 日志 — 登录审计 | ❌ | 未规划，建议 Phase 2 补充 |
