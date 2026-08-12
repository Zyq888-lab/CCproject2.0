# Phase 1 修复日志

> 基于 `git log --reverse` 生成，覆盖 2026-05-18 至 2026-08-12 全部 113 个 commit。
> 按模块归类，标注关键里程碑。

---

## 一、项目初始化 (5/18 — 5/19)

| Commit | 日期 | 说明 |
|--------|------|------|
| `32cc12b` | 05-18 | Initial commit |
| `077e4ed` | 05-18 | 添加 gstack skill routing rules 到 CLAUDE.md |
| `bc3b5aa` | 05-19 | Phase 1 项目结构初始化 — Spring Boot 3 + MyBatis-Plus |
| `9aa9e41` | 05-19 | 添加 HttpRequestMethodNotSupportedException handler (405) |
| `d2c5ffe` | 05-19 | H2 → PostgreSQL 数据库切换 |

---

## 二、后端核心模块 (5/20)

### Employee (T6)
| Commit | 日期 | 说明 |
|--------|------|------|
| `bdd8527` | 05-20 | 员工 CRUD 模块 (乐观锁 + 校验) |

### User + ProjectRole (T7, T8)
| Commit | 日期 | 说明 |
|--------|------|------|
| `9642a9d` | 05-20 | 用户管理 + 项目角色管理 (RBAC + 引用检查) |

### Project (T9)
| Commit | 日期 | 说明 |
|--------|------|------|
| `e94b623` | 05-20 | 项目管理 (阶段确认 + 乐观锁) |

### RoleAssignment (T10)
| Commit | 日期 | 说明 |
|--------|------|------|
| `8eea91c` | 05-20 | 角色分配 (分配/标记主PD/乐观锁) |
| `256679c` | 05-20 | N+1 查询修复 + TOCTOU 错误处理 + PD 角色校验 |

### Position (T11)
| Commit | 日期 | 说明 |
|--------|------|------|
| `54cbf16` | 05-20 | 岗位配置 (权重校验 + 考核人角色) |
| `b492e7a` | 05-20 | DuplicateKeyException 捕获 + funcAssessMode 枚举校验 |

### KPI (T12)
| Commit | 日期 | 说明 |
|--------|------|------|
| `56cc04b` | 05-20 | 项目/职能 KPI CRUD + 权重校验 + 算分器 |

### SystemParam (T13)
| Commit | 日期 | 说明 |
|--------|------|------|
| `bd302d9` | 05-20 | 系统参数 CRUD (乐观锁) |
| `e98e1b6` | 05-20 | 种子数据断言修复 |

### Period (T14)
| Commit | 日期 | 说明 |
|--------|------|------|
| `ed20bcd` | 05-20 | 考核周期 CRUD + 活跃周期唯一约束 |
| `610fb8f` | 05-20 | startDate <= endDate 校验 + 测试 |

### Wizard (T15) — 已删除
| Commit | 日期 | 说明 |
|--------|------|------|
| `7e16769` | 05-20 | 7步配置向导 (进度/断点续配/重置) |
| `5fba581` | 05-20 | 控制器测试 + 校验注解 |
| `8aa1b6a` | 08-12 | **已删除** — 功能被仪表盘引导卡片替代 |

### Import (T16)
| Commit | 日期 | 说明 |
|--------|------|------|
| `ef8c15d` | 05-20 | Excel 批量导入员工 (预览/校验/逐行报告) |
| `4660f55` | 05-21 | N+1 优化 + 异常安全 + 测试补充 + DRY 重构 |

### Dashboard (T17)
| Commit | 日期 | 说明 |
|--------|------|------|
| `e9aeddc` | 05-21 | 配置进度统计 + 差异报告占位 |
| `3ec18f4` | 05-21 | 状态常量提取 + DashboardControllerTest + Phase 2 TODO |

### Common Components (T18)
| Commit | 日期 | 说明 |
|--------|------|------|
| `1b48f79` | 05-21 | toggle-primary-pd 端点路径修复 |
| `56015e0` | 05-21 | AppLayout + EmptyState + PageHeader + ConfirmModal |
| `c544625` | 05-21 | 代码规范修复 (lang/title/comment style) |
| `39a84f1` | 05-21 | 死代码删除 + 魔法数字 + 双导出 + index keys |

---

## 三、前端页面搭建 (5/21 — 5/25)

| Commit | 日期 | 说明 |
|--------|------|------|
| `9f3a7e8` | 05-21 | 标记 T18 完成 |
| `e14320b` | 05-21 | T19: Login + Dashboard 页面 |
| `052e79c` | 05-21 | CSRF 拦截器 + 401 守卫 + DRY grid + 重试重构 |
| `b44566b` | 05-21 | T20: SetupWizard 容器 + 7 个子步骤 (已删除) |
| `c97eeee` | 05-21 | T21: 员工管理页面 (表格/搜索/分页/CRUD 弹窗) |
| `b05f61a` | 05-22 | T22-T24: UserRole + ProjectRole + ProjectList 页面 |
| `dd58c06` | 05-22 | T25: 项目角色分配页面 |
| `cb2bf9e` | 05-22 | T26: 岗位配置页面 + 考核人角色子管理 |
| `21fcbde` | 05-22 | T27: 项目/职能 KPI 页面 |
| `81fddbc` | 05-22 | T28: 直属上级配置页面 |
| `8b49628` | 05-25 | SystemParam + PeriodConfig 页面 + E2E 测试 + 后端测试套件 |
| `c30e581` | 05-25 | 批量员工导入 UI |
| `8144063` | 05-25 | 批量员工导入 API (批量预加载 + 并发安全) |

---

## 四、Bug 修复轮次 (5/22, 8/10-8/12)

### 5/22 — 第一轮修复
| Commit | 日期 | 说明 |
|--------|------|------|
| `1cbd107` | 05-22 | 员工创建静默失败(P0) + KPI权重(P1) + 岗位权重量纲(P1) + funcAssessMode枚举(P1) |
| `1b7ea74` | 05-22 | CORS未生效(P2) + Ant Design v6弃用(P2) + message API(P2) |
| `dbf6ed3` | 05-22 | ISSUE-001/002: CSRF 403重试 + Alert message→title |
| `ef12570` | 05-22 | ISSUE-003/004: Modal destroyOnHidden + EmployeeListPage mountedRef |
| `6b32cb9` | 05-22 | ISSUE-006/007: 缺少 /dashboard 端点 + /role-assignment 路由 |

### 5/25-5/29 — 第二轮修复
| Commit | 日期 | 说明 |
|--------|------|------|
| `2cef467` | 05-25 | PageHeader disabled/loading 透传 + WizardServiceTest 断言 |
| `e493f79` | 05-25 | 生产环境数据源改为环境变量 |
| `6551a90` | 05-25 | H2 → PostgreSQL 测试环境 |
| `b8e51c1` | 05-26 | BOM-less UTF8 编码 + UAT 测试文档 |
| `dd19eba` | 05-27 | 项目角色删除检查分配和KPI引用 + 逻辑删除后重建 |
| `75930f7` | 05-27 | 侧边栏新增职能KPI菜单入口 |
| `19cd5d2` | 05-27 | FuncKpiPage 前端权重和校验 |
| `ddf0862` | 05-27 | 考核周期编辑功能 |
| `a4dd507` | 05-27 | 退出登录按钮 |
| `b3bcef4` | 05-27 | 向导步骤2 — 导入后下一步消失 + 回访无数据 |
| `e84ed1c` | 05-27 | StrictMode mountedRef + dataRestored 渲染逻辑 |
| `c2a8393` | 05-27 | 物理删除已软删除员工后重建 |
| `f53f87f` | 05-27 | 7步向导替换为8卡片仪表盘引导 |
| `6b52657` | 05-27 | 考核周期开始 — INIT→ONGOING |
| `9f42042` | 05-28 | 员工模块 — 动态分类/必填校验/错误处理 |
| `2fcaef8` | 05-29 | 岗位分类 CRUD (独立管理页面) |
| `82559a7` | 05-29 | V6 迁移 DISTINCT before ROW_NUMBER + V7 version |
| `5e679b8` | 05-29 | 考核人角色名称 + 角色分配汇总查询 + system_param 允许空 |
| `fed66c7` | 05-29 | useCategories hook + KPI 角色 Select + 员工 Tabs |
| `913d0e1` | 05-29 | 岗位分类管理合并到 PositionConfigPage modal |

### 6/2 — 第三轮修复
| Commit | 日期 | 说明 |
|--------|------|------|
| `a786ec3` | 06-02 | 员工状态中文/英文映射 |
| `8c99803` | 06-02 | 员工导入校验 + 409 错误处理 + 岗位配置改进 |

### 8/10-8/12 — 第四轮修复 (本会话)
| Commit | 日期 | 说明 |
|--------|------|------|
| `8d030b3` | 08-10 | 岗位分类存在性校验 (position_category 表) |
| `bbe424c` | 08-10 | 批量导入中非空分类检查 |
| `9cbc01b` | 08-10 | 批量编辑合并到员工列表 + 直属上级姓名显示 |
| `b39e2dc` | 08-10 | 空批量编辑提交警告 |
| `44c47e3` | 08-10 | DuplicateKeyException HTTP 状态 + 批量编辑 AutoComplete |
| `3e7a381` | 08-10 | 批量导入 UnsupportedOperationException (emptySet) |
| `faebb2f` | 08-10 | directLeaderId 和 orgName 筛选 |
| `84d544d` | 08-11 | 侧边栏菜单排序 — 项目管理在项目角色管理之上 |
| `afcfc8e` | 08-11 | 移除项目管理页角色分配汇总按钮 |
| `c5f84e1` | 08-11 | P1 阶段选项 |
| `7490be0` | 08-11 | 复合主键 (projectCode, projectStage) |
| `ae3da20` | 08-11 | 项目确认/重置使用复合键安全更新 |
| `5476982` | 08-11 | 阶段确认时状态设为 COMPLETED |
| `7ae2741` | 08-11 | 归档已完成项目阶段 |
| `c8dd0e2` | 08-11 | 项目编码筛选 |
| `f9e5f1a` | 08-11 | 恢复 projectName→projectCode (列和表单) |
| `560380f` | 08-11 | 大小写不敏感项目编码筛选 |
| `c400a65` | 08-11 | 项目角色表格布局 + 筛选 + 分页 + 批量导入 |
| `08bc08d` | 08-11 | 角色分配关联到 (projectCode, projectStage) |
| `4f46c5e` | 08-11 | 汇总 SQL JOIN 添加 project_stage |
| `2a7ce4b` | 08-11 | 角色下拉 — 适配分页 API |
| `140ed6b` | 08-11 | 汇总角色筛选 — 适配分页 API |
| `2bdf7cb` | 08-11 | 角色分配批量导入 |
| `0afd7b5` | 08-11 | hideHeader 模式导入按钮 |
| `84f7342` | 08-11 | 批量角色分配导入错误显示 |
| `cb0c58f` | 08-11 | 重复导入警告而非静默 |
| `9a70e93` | 08-11 | KPI 批量导入 + 岗位下拉 + 分类联动 |
| `fae2f36` | 08-11 | 表单级分类→岗位联动, 筛选栏独立 |
| `990b787` | 08-11 | Form.useWatch 分类→岗位联动 |
| `fe79598` | 08-12 | 角色下拉修复 + 考核人角色合并到编辑表单 |
| `4046517` | 08-12 | 重命名 考核人角色→项目考核人角色 |
| `00b95ab` | 08-12 | 岗位配置批量导入 |
| `1924604` | 08-12 | downloadTemplate 语法修复 |
| `fed860a` | 08-12 | 导入模板优化 + 新增表单考核人角色 + 角色名列显示 |
| `3285daa` | 08-12 | 导入权重修复 + 分类/角色校验 + 默认角色筛选 |
| `ab8e608` | 08-12 | 项目批量导入 |
| `a048778` | 08-12 | 角色菜单过滤 + /auth/me 端点 |
| `c74cbe6` | 08-12 | Phase 1 核心功能清单文档 |
| `f490a3f` | 08-12 | 项目删除按钮 |
| `8aa1b6a` | 08-12 | 清理孤儿代码 (SetupWizard/LeaderConfig/wizard) |

---

## 五、测试与文档

| Commit | 日期 | 说明 |
|--------|------|------|
| `a98c84f` | 05-27 | 向导移除变更记录 |
| `10c1dae` | 05-29 | UAT 测试清单 — 137 用例覆盖 16 模块 |
| `c74cbe6` | 08-12 | Phase 1 核心功能清单 |

---

## 六、统计

| 指标 | 数值 |
|------|:--:|
| 总 commit 数 | 113 |
| 开发周期 | 2026-05-18 → 2026-08-12 (87 天) |
| 后端模块 | 12 (employee/user/projectrole/project/roleassignment/position/kpi/system/period/wizard/import/dashboard) |
| 前端页面 | 14 |
| 测试 | 189 (覆盖 19 个测试类) |
| 清理删除 | 18 文件 / 2,090 行 (SetupWizard + LeaderConfig + wizard) |
