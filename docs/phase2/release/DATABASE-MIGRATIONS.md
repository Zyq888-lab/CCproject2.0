# Phase2 数据库迁移清单

核对日期：2026-09-28。当前迁移目录为 [src/main/resources/db/migration](../../../src/main/resources/db/migration/)，最新文件 V30。本次只核对文件，未连接或迁移服务器数据库。

## 升级路径

| 目标库当前状态 | 待处理范围 |
| --- | --- |
| Phase1、最后成功迁移为 V9 | 顺序执行待应用的 V10–V30 |
| Phase2.0、最后成功迁移为 V19 | 待应用的 V20–V30 |
| Phase2.1、最后成功迁移为 V20 | 待应用的 V21–V30 |
| Phase2.2、最后成功迁移为 V24 | 待应用的 V25–V30 |
| 最后成功迁移已为 V30 | 不应重放旧迁移；仍需启动校验和业务核对 |
| 新建空数据库或未知历史 | 先确认真实库与 Flyway 状态；空库由完整迁移初始化，不手动假定基线 |

表中的版本仅用于说明路径。实际环境以 `flyway_schema_history` 的已成功记录为准，不能仅凭服务器目录名、阶段名或部署脚本提示判断。

## V10–V30 逐项清单

| 版本 | 迁移文件 | 主要作用 |
| --- | --- | --- |
| V10 | [create_participation_table](../../../src/main/resources/db/migration/V10__create_participation_table.sql) | 员工项目参与申报表 |
| V11 | [create_assessment_task_table](../../../src/main/resources/db/migration/V11__create_assessment_task_table.sql) | 考核任务表和配对约束 |
| V12 | [create_assessment_score_table](../../../src/main/resources/db/migration/V12__create_assessment_score_table.sql) | 指标评分、草稿和凭证引用 |
| V13 | [create_notification_table](../../../src/main/resources/db/migration/V13__create_notification_table.sql) | 站内通知 |
| V14 | [create_discrepancy_log_table](../../../src/main/resources/db/migration/V14__create_discrepancy_log_table.sql) | 任务生成差异报告 |
| V15 | [add_president_confirm_param](../../../src/main/resources/db/migration/V15__add_president_confirm_param.sql) | 预置总裁确认参数 |
| V16 | [add_participation_approval_comment](../../../src/main/resources/db/migration/V16__add_participation_approval_comment.sql) | 参与审批意见 |
| V17 | [fix_role_assignment_unique_constraint](../../../src/main/resources/db/migration/V17__fix_role_assignment_unique_constraint.sql) | 角色分配唯一键补阶段维度 |
| V18 | [fix_task_and_participation_unique_keys](../../../src/main/resources/db/migration/V18__fix_task_and_participation_unique_keys.sql) | 任务和参与唯一键补阶段维度 |
| V19 | [make_score_nullable](../../../src/main/resources/db/migration/V19__make_score_nullable.sql) | 草稿评分允许空值 |
| V20 | [rename_is_primary_pd_to_is_primary](../../../src/main/resources/db/migration/V20__rename_is_primary_pd_to_is_primary.sql) | 主角色字段改名及部分唯一索引 |
| V21 | [fix_president_confirm_param](../../../src/main/resources/db/migration/V21__fix_president_confirm_param.sql) | 修正总裁确认参数的历史种子值 |
| V22 | [create_assessment_result_and_adjustment](../../../src/main/resources/db/migration/V22__create_assessment_result_and_adjustment.sql) | 周期个人结果与总分调整审计 |
| V23 | [drop_task_return_columns](../../../src/main/resources/db/migration/V23__drop_task_return_columns.sql) | 删除旧任务退回计数列 |
| V24 | [add_calibration_submitted_at](../../../src/main/resources/db/migration/V24__add_calibration_submitted_at.sql) | 周期校准提交时间 |
| V25 | [add_discrepancy_project_stage](../../../src/main/resources/db/migration/V25__add_discrepancy_project_stage.sql) | 差异报告补项目阶段 |
| V26 | [add_activation_and_must_change_password](../../../src/main/resources/db/migration/V26__add_activation_and_must_change_password.sql) | 激活口令参数、首次改密标记 |
| V27 | [add_project_confirmation](../../../src/main/resources/db/migration/V27__add_project_confirmation.sql) | 项目确认表、总裁角色与退回参数 |
| V28 | [add_assessee_id_to_project_confirmation](../../../src/main/resources/db/migration/V28__add_assessee_id_to_project_confirmation.sql) | 总裁确认细化到人员，更新唯一键 |
| V29 | [add_calibration_submission](../../../src/main/resources/db/migration/V29__add_calibration_submission.sql) | 项目、阶段级 PD 校准提交记录 |
| V30 | [add_calibration_kpi_override](../../../src/main/resources/db/migration/V30__add_calibration_kpi_override.sql) | 逐项校准覆盖分、项目小计及单项审计 |

## 对历史数据的影响

- V20 将 `is_primary_pd` 改为 `is_primary`。旧程序继续访问旧列名可能失败，因此必须协调程序与数据库版本。
- V23 删除 `assessment_task.return_count/max_returns`，不能依靠换回旧 JAR 恢复删除的列和原值。
- V28 为旧项目级确认行以项目编码回填 `assessee_id` 哨兵，并改为周期、项目、人员唯一键。该回填不是对历史确认人逐人补齐；存在进行中旧周期时需要核对，不能据此认定所有历史确认正确。
- V29 新建提交表，没有把所有旧周期的单个校准时间自动展开成逐项目、阶段提交记录。升级前应记录活动周期，升级后核对确认门槛。
- V30 增加覆盖分、小计表和审计表；不会自动把所有已发布结果按新口径重算。
- 2026-09-28 的正确性与权限修复没有新增迁移；实际数据回算仍须单独处理。

## 迁移前后核对

在服务器本机，用当前有效凭证执行只读查询；不要把口令写进 SQL 或报告。

```sql
SELECT current_database(), current_user;

SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank DESC;

SELECT period_id, period_name, status
FROM assessment_period
WHERE deleted = 0 AND status <> 'COMPLETED';
```

升级前保存以上结果及数据库备份；升级后确认期望数据库、待应用迁移全部成功、最新版本为 30，并在日志中检查迁移校验或启动失败。不存在历史表时先核对是否空库，不能用 `repair`、删除历史表或修改已应用 SQL 掩盖问题。

Flyway 默认由后端启动执行迁移。不要在启动程序的同时人工重放 V10–V30；新旧后端也不要同时写同一个正在升级的数据库。

## 回滚边界

仓库没有配套的自动逆向迁移。升级失败或业务需要回退时，应根据已应用迁移和兼容性决定是否恢复数据库快照，同时恢复匹配的程序、附件和配置。恢复快照会丢失快照之后的新业务写入，需先停止写入并由环境负责人确认处理方案。

具体备份和恢复记录见 [部署与回滚手册](DEPLOYMENT.md)。
