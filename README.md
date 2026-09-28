# CCproject2.0

继峰座椅项目制绩效考核系统

## 文档

- [Phase 1 文档归档](docs/phase1/) — 设计文档 / 工程规划 / 功能清单 / 清理记录 / 修复日志
- [Phase 2 设计文档](docs/designs/)与[工程计划](docs/phase2/) — 校准、角色主标记、总裁确认与账号激活
- [Phase 2.2 UAT 测试清单](UAT测试清单-Phase2.2-总裁确认与激活改密版.md) — 按角色验证激活、校准、总裁确认与结果发布
- [Phase 2.2 开发进度快照](项目进度汇报-Phase2.2完成-20260921.md)与[调查报告](docs/investigation/) — 历史记录，现行功能以版本变更为准
- [版本变更](CHANGELOG.md)与[待办事项](TODOS.md)
- [正确性与安全修复说明](docs/security/phase2-hardening-20260928.md) — 权限边界、分数口径、管理员口令与 UAT 升级要求

## 当前版本（v2.14.0.0）

- 管理员可按员工工号激活账号；新账号首次登录需修改密码。
- PD 可按项目和阶段查看校准矩阵，逐项调整 KPI，并重新提交总裁退回的人员。
- 负责总裁逐人确认或退回评分；全部确认后由管理员发布，员工才能查看结果。
- 评分凭证可上传，并可在校准、监控和结果明细中打开；结果明细显示指标所属项目与阶段。
- 仪表盘显示差异详情，管理员可将已处理的差异销账。

## 快速开始

首次启动新数据库，或数据库中的管理员仍使用旧默认口令时，先在启动终端配置 `ADMIN_BOOTSTRAP_PASSWORD`。口令须为 12–128 个字符，包含大小写字母、数字和特殊字符；首次登录后必须改密。已有非默认管理员口令不会被覆盖。请通过本地环境或秘密管理工具提供口令，勿写入仓库。

```bash
# 后端 (Spring Boot 3 + PostgreSQL)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 前端 (React + Vite)
cd frontend && npm install && npm run dev
```

开发配置使用本地 PostgreSQL 数据库 `jifeng_assessment`；后端监听 8080 端口，前端监听 3000 端口并代理 `/api` 到后端。

UAT 部署还须配置 `DATASOURCE_PASSWORD` 和 `APP_UPLOAD_DIR`；附件目录必须指向已有文件的绝对路径。运行依赖真实管理员登录的 E2E 时，通过 `E2E_ADMIN_PASSWORD` 提供当前管理员口令。详见修复说明。

## 标签

- `v1.0.0-uat` — Phase 1 清理完成，锁定稳定版本
