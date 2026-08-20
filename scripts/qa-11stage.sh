#!/usr/bin/env bash
# Phase 2.0 十一阶段端到端 QA 测试脚本（curl + jq + psql）
# 前置：seed-qa-11stage.sql 已执行，后端:8080 已重启（V18 已应用）
set -uo pipefail

BASE="http://localhost:8080/api/v1"
PSQL="/c/Program Files/PostgreSQL/16/bin/psql.exe"
export PGPASSWORD=postgres
PSQL_ARGS="-h 127.0.0.1 -U postgres -d jifeng_assessment -tA"

PERIOD="P2026Q4"
PCODE="P004"
PSTAGE="P4"

STAGE_PASS=0
STAGE_FAIL=0
FAILED_STAGES=()

# ---- 报告辅助 ----
note()   { echo "     $*"; }
stage_ok() { echo "✅ 阶段通过：$1"; STAGE_PASS=$((STAGE_PASS+1)); }
stage_bad() { echo "❌ 阶段失败：$1 —— $2"; STAGE_FAIL=$((STAGE_FAIL+1)); FAILED_STAGES+=("$1"); }

# ---- helpers ----
login() { # user pass jar -> echo token
  local user="$1" pass="$2" jar="$3"
  rm -f "$jar"
  curl -s -c "$jar" -H "Content-Type: application/json" \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}" "$BASE/auth/login" >/dev/null
  local token
  token=$(awk '/XSRF-TOKEN/{print $NF}' "$jar")
  curl -s -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $token" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}" "$BASE/auth/login" >/dev/null
  awk '/XSRF-TOKEN/{print $NF}' "$jar" | tail -1
}

req() { # jar token METHOD path [body]
  local jar="$1" token="$2" method="$3" path="$4" body="${5:-}"
  if [ -n "$body" ]; then
    printf '%s' "$body" > /tmp/qa-body.json
    curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -H "Content-Type: application/json" \
      -X "$method" --data-binary @/tmp/qa-body.json "$BASE$path"
  else
    curl -s -b "$jar" -H "X-XSRF-TOKEN: $token" -X "$method" "$BASE$path"
  fi
}

code() { echo "$1" | jq -r '.code // "none"'; }
dbq() { "$PSQL" $PSQL_ARGS -c "$1" 2>/dev/null; }

echo "=========================================================="
echo " Phase 2.0 十一阶段端到端 QA 测试  $(date +%Y-%m-%d_%H:%M:%S)"
echo " 周期=$PERIOD 项目=$PCODE/$PSTAGE"
echo "=========================================================="

# ============ 0. 登录 ============
ATOKEN=$(login admin admin123 /tmp/qa-a.jar)
ZTOKEN=$(login zhu 123456 /tmp/qa-z.jar)      # E004 祝工 PM/评估人
XTOKEN=$(login xiong 123456 /tmp/qa-x.jar)    # E003 熊工 员工
note "admin token=${#ATOKEN}  zhu token=${#ZTOKEN}  xiong token=${#XTOKEN}"

# ============ 阶段一：PM 创建项目与分配角色 ============
echo ""
echo "===== 阶段一：PM创建项目与分配角色 ====="
S1OK=1
R=$(req /tmp/qa-z.jar "$ZTOKEN" POST /projects '{"projectCode":"'$PCODE'","projectName":"AI测试项目","projectStage":"'$PSTAGE'","description":"11阶段QA"}')
C=$(code "$R"); CFG=$(echo "$R" | jq -r '.data.stageConfirmed | tostring')
note "创建项目 code=$C stageConfirmed=$CFG"
[ "$C" = "200" ] && [ "$CFG" = "false" ] || S1OK=0

# PM 立即看到项目
R=$(req /tmp/qa-z.jar "$ZTOKEN" GET "/projects?projectCode=$PCODE&size=50")
C=$(code "$R"); PCT=$(echo "$R" | jq -r '.data.total // -1')
note "PM查项目 code=$C total=$PCT"
[ "$C" = "200" ] && [ "$PCT" -ge 1 ] 2>/dev/null || S1OK=0

# 分配角色：熊工 AIP（成员）、祝工 AIM（评估人）
R=$(req /tmp/qa-z.jar "$ZTOKEN" POST "/projects/$PCODE/$PSTAGE/assignments" '{"roleCode":"AIP","employeeId":"E003"}')
C1=$(code "$R")
R=$(req /tmp/qa-z.jar "$ZTOKEN" POST "/projects/$PCODE/$PSTAGE/assignments" '{"roleCode":"AIM","employeeId":"E004"}')
C2=$(code "$R")
note "分配角色 AIP/E003=$C1  AIM/E004=$C2"
[ "$C1" = "200" ] && [ "$C2" = "200" ] || S1OK=0

# PM 创建项目后自动成为 PM（primary PD）
PM_ROLE=$(dbq "SELECT count(*) FROM project_role_assignment WHERE project_code='$PCODE' AND project_stage='$PSTAGE' AND project_role_code='PM' AND employee_id='E004' AND is_primary_pd=true AND deleted=0;")
note "PM自动分配(is_primary_pd)=$PM_ROLE"
[ "$PM_ROLE" = "1" ] || S1OK=0

if [ "$S1OK" = "1" ]; then stage_ok "阶段一 PM创建项目与分配角色"; else stage_bad "阶段一 PM创建项目与分配角色" "创建=$C 阶段确认=$CFG PM角色=$PM_ROLE"; fi

# ============ 阶段二：项目成员参与录入 ============
echo ""
echo "===== 阶段二：项目成员参与录入（下拉只显示已分配项目/阶段）====="
S2OK=1
# 提交未分配的 P3 阶段 → 应 400（阶段 P4 不能被当成 P3）
R=$(req /tmp/qa-x.jar "$XTOKEN" POST /participations '{"periodId":"'$PERIOD'","items":[{"projectCode":"'$PCODE'","projectStage":"P3","participationRate":100}]}')
C=$(code "$R"); MSG=$(echo "$R" | jq -r '.message // ""')
note "提交未分配P3阶段 code=$C msg=$MSG"
[ "$C" = "400" ] || S2OK=0

# 提交正确 P4 阶段 → 应 200 PENDING
R=$(req /tmp/qa-x.jar "$XTOKEN" POST /participations '{"periodId":"'$PERIOD'","items":[{"projectCode":"'$PCODE'","projectStage":"'$PSTAGE'","participationRate":100}]}')
C=$(code "$R"); PST=$(echo "$R" | jq -r '.data[0].status // empty'); PART_ID=$(echo "$R" | jq -r '.data[0].id // empty')
note "提交P4阶段 code=$C status=$PST id=$PART_ID"
[ "$C" = "200" ] && [ "$PST" = "PENDING" ] || S2OK=0

# 投入比重≠100% → 400
R=$(req /tmp/qa-x.jar "$XTOKEN" POST /participations '{"periodId":"'$PERIOD'","items":[{"projectCode":"'$PCODE'","projectStage":"'$PSTAGE'","participationRate":50}]}')
C=$(code "$R")
note "比重50%提交 code=$C（应400）"
[ "$C" = "400" ] || S2OK=0

if [ "$S2OK" = "1" ]; then stage_ok "阶段二 项目成员参与录入"; else stage_bad "阶段二 项目成员参与录入" "P3提交code=$C P4提交code=$C"; fi

# ============ 阶段三：PM 审批参与（拒→重提→通过） ============
echo ""
echo "===== 阶段三：PM审批参与（拒→重提→通过，发起前不生成任务）====="
S3OK=1
# 拒绝
R=$(req /tmp/qa-z.jar "$ZTOKEN" PUT "/participations/$PART_ID/approve" '{"approved":false,"suggestedRate":80,"comment":"投入比重需调整"}')
C=$(code "$R"); REJ=$(echo "$R" | jq -r '.data.status // empty'); SR=$(echo "$R" | jq -r '.data.suggestedRate // empty')
note "拒绝 code=$C status=$REJ suggestedRate=$SR"
[ "$C" = "200" ] && [ "$REJ" = "REJECTED" ] && [ "$SR" = "80" ] || S3OK=0

# 重新提交（数据回填：清空审批信息）
R=$(req /tmp/qa-x.jar "$XTOKEN" POST "/participations/$PART_ID/resubmit" '{"participationRate":80}')
C=$(code "$R"); RESUB=$(echo "$R" | jq -r '.data.status // empty'); RBY=$(echo "$R" | jq -r '.data.approvedBy | tostring')
note "重提 code=$C status=$RESUB approvedBy清空=$RBY"
[ "$C" = "200" ] && [ "$RESUB" = "PENDING" ] && [ "$RBY" = "null" ] || S3OK=0

# 通过
R=$(req /tmp/qa-z.jar "$ZTOKEN" PUT "/participations/$PART_ID/approve" '{"approved":true,"suggestedRate":80,"comment":"同意"}')
C=$(code "$R"); APPROVED=$(echo "$R" | jq -r '.data.status // empty')
note "通过 code=$C status=$APPROVED"
[ "$C" = "200" ] && [ "$APPROVED" = "APPROVED" ] || S3OK=0

# 发起前不生成任务（周期 INIT → onParticipationApproved 提前返回）
TCNT=$(dbq "SELECT count(*) FROM assessment_task WHERE period_id='$PERIOD';")
note "发起前任务数=$TCNT（应为0）"
[ "$TCNT" = "0" ] || S3OK=0

if [ "$S3OK" = "1" ]; then stage_ok "阶段三 PM审批参与"; else stage_bad "阶段三 PM审批参与" "拒绝=$REJ 重提=$RESUB 通过=$APPROVED 任务数=$TCNT"; fi

# ============ 阶段四：ADMIN 发起考核 ============
echo ""
echo "===== 阶段四：ADMIN发起考核（不检查stage_confirmed，INIT→ONGOING，生成PROJECT+FUNCTIONAL）====="
S4OK=1
# 发起前：员工待评分列表为空（INIT 任务不可评分/不暴露）
R=$(req /tmp/qa-z.jar "$ZTOKEN" GET "/tasks?scope=pending&size=50")
PRE_PEND=$(echo "$R" | jq -r '.data.total // -1')
note "发起前 待评分总数=$PRE_PEND（应为0）"
[ "$PRE_PEND" = "0" ] || S4OK=0

R=$(req /tmp/qa-a.jar "$ATOKEN" POST "/tasks/$PERIOD/launch")
C=$(code "$R"); TC=$(echo "$R" | jq -r '.data.taskCount // -1'); DC=$(echo "$R" | jq -r '.data.discrepancyCount // -1')
PSTATUS=$(dbq "SELECT status FROM assessment_period WHERE period_id='$PERIOD';")
note "launch code=$C taskCount=$TC discrepancyCount=$DC period=$PSTATUS"
[ "$C" = "200" ] && [ "$TC" -ge 2 ] 2>/dev/null && [ "$PSTATUS" = "ONGOING" ] && [ "$DC" -ge 1 ] 2>/dev/null || S4OK=0

# E003 有 PROJECT + FUNCTIONAL 任务
PROJ_T=$(dbq "SELECT count(*) FROM assessment_task WHERE period_id='$PERIOD' AND assessee_id='E003' AND task_type='PROJECT' AND project_code='$PCODE';")
FUNC_T=$(dbq "SELECT count(*) FROM assessment_task WHERE period_id='$PERIOD' AND assessee_id='E003' AND task_type='FUNCTIONAL';")
note "E003 PROJECT任务=$PROJ_T FUNCTIONAL任务=$FUNC_T"
[ "$PROJ_T" = "1" ] && [ "$FUNC_T" = "1" ] || S4OK=0

# 评估人通知（E004 收到 TASK_ASSIGNED）
UNREAD=$(req /tmp/qa-z.jar "$ZTOKEN" GET /notifications/unread-count | jq -r '.data // -1')
note "E004未读通知=$UNREAD（应≥1）"
[ "$UNREAD" -ge 1 ] 2>/dev/null || S4OK=0

if [ "$S4OK" = "1" ]; then stage_ok "阶段四 ADMIN发起考核"; else stage_bad "阶段四 ADMIN发起考核" "launch=$C taskCount=$TC disc=$DC period=$PSTATUS"; fi

# ============ 阶段五：考核人评分 ============
echo ""
echo "===== 阶段五：考核人评分（待评分列表/开始/详情/凭证/草稿/提交）====="
S5OK=1
# 待评分列表只含 ONGOING 周期 E004 作为考核人的任务
R=$(req /tmp/qa-z.jar "$ZTOKEN" GET "/tasks?scope=pending&size=50")
PEND_TOTAL=$(echo "$R" | jq -r '.data.total // -1')
PEND_LEAK=$(echo "$R" | jq -r '[.data.list[]? | select(.assessorId!="E004")] | length')
note "待评分 total=$PEND_TOTAL 越权(非E004考核人)=$PEND_LEAK"
[ "$PEND_TOTAL" -ge 2 ] 2>/dev/null && [ "$PEND_LEAK" = "0" ] 2>/dev/null || S5OK=0

# 取 PROJECT 任务
PROJ_TASK_ID=$(dbq "SELECT id FROM assessment_task WHERE period_id='$PERIOD' AND assessee_id='E003' AND task_type='PROJECT' AND project_code='$PCODE' LIMIT 1;")
FUNC_TASK_ID=$(dbq "SELECT id FROM assessment_task WHERE period_id='$PERIOD' AND assessee_id='E003' AND task_type='FUNCTIONAL' LIMIT 1;")
note "PROJECT task=$PROJ_TASK_ID FUNCTIONAL task=$FUNC_TASK_ID"

# 开始评分
R=$(req /tmp/qa-z.jar "$ZTOKEN" PUT "/tasks/$PROJ_TASK_ID/start")
C=$(code "$R"); ST=$(echo "$R" | jq -r '.data.status // empty')
note "start PROJECT code=$C status=$ST"
[ "$C" = "200" ] && [ "$ST" = "IN_PROGRESS" ] || S5OK=0

# 详情拿指标
R=$(req /tmp/qa-z.jar "$ZTOKEN" GET "/tasks/$PROJ_TASK_ID")
KPI_ID=$(echo "$R" | jq -r '.data.indicators[0].kpiConfigId // empty')
KPI_TYPE=$(echo "$R" | jq -r '.data.indicators[0].kpiType // empty')
IND_CNT=$(echo "$R" | jq -r '.data.indicators | length')
note "指标数=$IND_CNT kpiConfigId=$KPI_ID kpiType=$KPI_TYPE"
[ "$IND_CNT" -ge 1 ] 2>/dev/null && [ -n "$KPI_ID" ] || S5OK=0

# ensure 拿到 scoreId
R=$(req /tmp/qa-z.jar "$ZTOKEN" POST "/tasks/$PROJ_TASK_ID/scores/ensure?kpiConfigId=$KPI_ID&kpiType=$KPI_TYPE")
C=$(code "$R"); SCORE_ID=$(echo "$R" | jq -r '.data // empty')
note "ensure code=$C scoreId=$SCORE_ID"
[ "$C" = "200" ] && [ -n "$SCORE_ID" ] && [ "$SCORE_ID" != "null" ] || S5OK=0

# 凭证上传
printf 'qa-evidence-11stage' > /tmp/qa-evidence.txt
R=$(curl -s -b /tmp/qa-z.jar -H "X-XSRF-TOKEN: $ZTOKEN" -F "file=@/tmp/qa-evidence.txt" "$BASE/scores/$SCORE_ID/evidence")
C=$(code "$R"); EURL=$(echo "$R" | jq -r '.data // empty')
note "上传凭证 code=$C url=$EURL"
[ "$C" = "200" ] || S5OK=0

# 草稿（不改变 task 状态）
R=$(req /tmp/qa-z.jar "$ZTOKEN" PUT "/tasks/$PROJ_TASK_ID/scores" "{\"items\":[{\"kpiConfigId\":$KPI_ID,\"kpiType\":\"$KPI_TYPE\",\"score\":4.5,\"evidenceUrl\":\"$EURL\"}]}")
C=$(code "$R")
STILL=$(dbq "SELECT status FROM assessment_task WHERE id=$PROJ_TASK_ID;")
note "草稿 code=$C task状态=$STILL（应仍IN_PROGRESS）"
[ "$C" = "200" ] && [ "$STILL" = "IN_PROGRESS" ] || S5OK=0

# 指标不完整 → 400（用错误 kpiConfigId 提交）
R=$(req /tmp/qa-z.jar "$ZTOKEN" POST "/tasks/$PROJ_TASK_ID/scores" "{\"items\":[{\"kpiConfigId\":999999,\"kpiType\":\"$KPI_TYPE\",\"score\":4.5}]}")
C=$(code "$R"); IMSG=$(echo "$R" | jq -r '.message // ""')
note "不完整提交 code=$C msg=$IMSG（应400不完整）"
[ "$C" = "400" ] || S5OK=0

# 正式提交 → SUBMITTED
R=$(req /tmp/qa-z.jar "$ZTOKEN" POST "/tasks/$PROJ_TASK_ID/scores" "{\"items\":[{\"kpiConfigId\":$KPI_ID,\"kpiType\":\"$KPI_TYPE\",\"score\":4.5,\"evidenceUrl\":\"$EURL\"}]}")
C=$(code "$R"); SUB=$(echo "$R" | jq -r '.data.status // empty')
SCORE_ROW=$(dbq "SELECT score FROM assessment_score WHERE task_id=$PROJ_TASK_ID AND kpi_config_id=$KPI_ID;")
note "提交 code=$C status=$SUB score=$SCORE_ROW"
[ "$C" = "200" ] && [ "$SUB" = "SUBMITTED" ] && [ "$SCORE_ROW" = "4.5" ] || S5OK=0

if [ "$S5OK" = "1" ]; then stage_ok "阶段五 考核人评分"; else stage_bad "阶段五 考核人评分" "提交=$SUB score=$SCORE_ROW"; fi

# ============ 阶段六：通知与查看 ============
echo ""
echo "===== 阶段六：通知与查看（红点/列表/点击跳转）====="
S6OK=1
R=$(req /tmp/qa-z.jar "$ZTOKEN" GET /notifications/unread-count)
UNREAD=$(echo "$R" | jq -r '.data // -1')
note "未读数=$UNREAD"
[ "$UNREAD" -ge 1 ] 2>/dev/null || S6OK=0

R=$(req /tmp/qa-z.jar "$ZTOKEN" GET "/notifications?size=50")
NTYPES=$(echo "$R" | jq -r '[.data.list[]?.type] | unique | join(",")')
NID=$(echo "$R" | jq -r '.data.list[0].id // empty')
note "通知类型=$NTYPES 首条id=$NID"
echo "$NTYPES" | grep -q "TASK_ASSIGNED" || S6OK=0

# 标记已读
R=$(req /tmp/qa-z.jar "$ZTOKEN" PUT "/notifications/$NID/read")
C=$(code "$R"); ISREAD=$(echo "$R" | jq -r '.data.isRead // empty')
note "标记已读 code=$C isRead=$ISREAD"
[ "$C" = "200" ] && [ "$ISREAD" = "true" ] || S6OK=0

if [ "$S6OK" = "1" ]; then stage_ok "阶段六 通知与查看"; else stage_bad "阶段六 通知与查看" "未读=$UNREAD 类型=$NTYPES"; fi

# ============ 阶段七：我的指标 ============
echo ""
echo "===== 阶段七：我的指标（员工只读，含KPI，无空职能项）====="
S7OK=1
R=$(req /tmp/qa-x.jar "$XTOKEN" GET /my-assessment)
C=$(code "$R")
ITEM_CNT=$(echo "$R" | jq -r '.data | length')
PROJ_KPI=$(echo "$R" | jq -r '[.data[] | select(.taskType=="PROJECT") | .kpis[]?] | length')
FUNC_KPI=$(echo "$R" | jq -r '[.data[] | select(.taskType=="FUNCTIONAL") | .kpis[]?] | length')
PROJ_NAME=$(echo "$R" | jq -r '[.data[] | select(.taskType=="PROJECT") | .projectName] | .[0] // empty')
note "指标项数=$ITEM_CNT 项目KPI数=$PROJ_KPI 职能KPI数=$FUNC_KPI 项目名=$PROJ_NAME"
[ "$C" = "200" ] && [ "$ITEM_CNT" -ge 2 ] 2>/dev/null && [ "$PROJ_KPI" -ge 1 ] 2>/dev/null && [ "$FUNC_KPI" -ge 1 ] 2>/dev/null || S7OK=0

if [ "$S7OK" = "1" ]; then stage_ok "阶段七 我的指标"; else stage_bad "阶段七 我的指标" "项目KPI=$PROJ_KPI 职能KPI=$FUNC_KPI"; fi

# ============ 阶段八：周期监控 ============
echo ""
echo "===== 阶段八：周期监控（ADMIN全见 / PM仅见自己项目）====="
S8OK=1
R=$(req /tmp/qa-a.jar "$ATOKEN" GET "/periods/$PERIOD/monitor")
C=$(code "$R"); MON_CNT=$(echo "$R" | jq -r '.data | length')
MON_E003=$(echo "$R" | jq -r '[.data[] | select(.employeeId=="E003")] | length')
note "ADMIN监控 code=$C 条数=$MON_CNT E003条数=$MON_E003"
[ "$C" = "200" ] && [ "$MON_CNT" -ge 3 ] 2>/dev/null && [ "$MON_E003" -ge 2 ] 2>/dev/null || S8OK=0

# PM 仅见自己项目（FUNCTIONAL project_code 为空 → 排除）
R=$(req /tmp/qa-z.jar "$ZTOKEN" GET "/periods/$PERIOD/monitor")
C=$(code "$R")
PM_CNT=$(echo "$R" | jq -r '.data | length')
PM_FUNC=$(echo "$R" | jq -r '[.data[] | select(.taskType=="FUNCTIONAL")] | length')
note "PM监控 code=$C 条数=$PM_CNT FUNCTIONAL条数=$PM_FUNC（应0）"
[ "$C" = "200" ] && [ "$PM_CNT" -ge 1 ] 2>/dev/null && [ "$PM_FUNC" = "0" ] 2>/dev/null || S8OK=0

if [ "$S8OK" = "1" ]; then stage_ok "阶段八 周期监控"; else stage_bad "阶段八 周期监控" "ADMIN条数=$MON_CNT PM条数=$PM_CNT PM_FUNC=$PM_FUNC"; fi

# ============ 阶段十一：乐观锁冲突（需 ONGOING 周期，故在关闭前执行） ============
echo ""
echo "===== 阶段十一：乐观锁冲突（并发 start FUNCTIONAL 任务）====="
S11OK=1
rm -f /tmp/qa-lock-*.json
for i in $(seq 1 10); do
  (req /tmp/qa-z.jar "$ZTOKEN" PUT "/tasks/$FUNC_TASK_ID/start" > "/tmp/qa-lock-$i.json" 2>&1) &
done
wait
W200=0; W400=0; W409=0; WOTHER=0
for i in $(seq 1 10); do
  c=$(code "$(cat "/tmp/qa-lock-$i.json" 2>/dev/null)")
  case "$c" in
    200) W200=$((W200+1));;
    400) W400=$((W400+1));;
    409) W409=$((W409+1));;
    *)   WOTHER=$((WOTHER+1));;
  esac
done
FTASK_STATUS=$(dbq "SELECT status FROM assessment_task WHERE id=$FUNC_TASK_ID;")
FTASK_VER=$(dbq "SELECT version FROM assessment_task WHERE id=$FUNC_TASK_ID;")
note "并发start：200=$W200 400=$W400 409=$W409 其他=$WOTHER 终态=$FTASK_STATUS ver=$FTASK_VER"
if [ "$W200" = "1" ] && [ "$FTASK_STATUS" = "IN_PROGRESS" ] && [ "$FTASK_VER" = "1" ]; then
  stage_ok "阶段十一 乐观锁冲突"
else
  S11OK=0; stage_bad "阶段十一 乐观锁冲突" "200=$W200 400=$W400 409=$W409 终态=$FTASK_STATUS ver=$FTASK_VER"
fi

# ============ 阶段九：周期关闭后锁定 ============
echo ""
echo "===== 阶段九：周期关闭后锁定（close→COMPLETED，评分→400）====="
S9OK=1
R=$(req /tmp/qa-a.jar "$ATOKEN" PUT "/periods/$PERIOD/close")
C=$(code "$R"); PSTATUS=$(echo "$R" | jq -r '.data.status // empty')
note "close code=$C status=$PSTATUS"
[ "$C" = "200" ] && [ "$PSTATUS" = "COMPLETED" ] || S9OK=0

# 关闭后评分 → 400（对 FUNCTIONAL 任务提交评分）
R=$(req /tmp/qa-z.jar "$ZTOKEN" POST "/tasks/$FUNC_TASK_ID/scores" '{"items":[{"kpiConfigId":1,"kpiType":"FUNCTIONAL","score":4.0}]}')
C=$(code "$R"); CMSG=$(echo "$R" | jq -r '.message // ""')
note "关闭后评分 code=$C msg=$CMSG（应400已关闭）"
[ "$C" = "400" ] || S9OK=0

if [ "$S9OK" = "1" ]; then stage_ok "阶段九 周期关闭后锁定"; else stage_bad "阶段九 周期关闭后锁定" "close=$C 评分=$C msg=$CMSG"; fi

# ============ 阶段十：数据隔离 ============
echo ""
echo "===== 阶段十：数据隔离（各角色只见自己数据）====="
S10OK=1
# 员工 xiong 看参与 → 仅本人 E003
R=$(req /tmp/qa-x.jar "$XTOKEN" GET "/participations?size=50")
X_TOTAL=$(echo "$R" | jq -r '.data.total // -1')
X_LEAK=$(echo "$R" | jq -r '[.data.list[]? | select(.employeeId!="E003")] | length')
note "熊工参与 total=$X_TOTAL 越权=$X_LEAK"
[ "$X_TOTAL" -ge 1 ] 2>/dev/null && [ "$X_LEAK" = "0" ] 2>/dev/null || S10OK=0

# 员工 xiong 看任务进度 → 仅本人被考核
R=$(req /tmp/qa-x.jar "$XTOKEN" GET "/tasks?scope=progress&size=50")
X_T_TOTAL=$(echo "$R" | jq -r '.data.total // -1')
X_T_LEAK=$(echo "$R" | jq -r '[.data.list[]? | select(.assesseeId!="E003")] | length')
note "熊工任务进度 total=$X_T_TOTAL 越权=$X_T_LEAK"
[ "$X_T_TOTAL" -ge 2 ] 2>/dev/null && [ "$X_T_LEAK" = "0" ] 2>/dev/null || S10OK=0

# PM zhu 看参与 → 仅自己负责项目（P004），不含 P001
R=$(req /tmp/qa-z.jar "$ZTOKEN" GET "/participations?size=50")
Z_TOTAL=$(echo "$R" | jq -r '.data.total // -1')
Z_LEAK=$(echo "$R" | jq -r '[.data.list[]? | select(.projectCode=="P001")] | length')
note "祝工参与 total=$Z_TOTAL 越权P001=$Z_LEAK"
[ "$Z_TOTAL" -ge 1 ] 2>/dev/null && [ "$Z_LEAK" = "0" ] 2>/dev/null || S10OK=0

# ADMIN 看全部
R=$(req /tmp/qa-a.jar "$ATOKEN" GET "/participations?size=50")
A_TOTAL=$(echo "$R" | jq -r '.data.total // -1')
note "ADMIN参与 total=$A_TOTAL"
[ "$A_TOTAL" -ge 1 ] 2>/dev/null || S10OK=0

if [ "$S10OK" = "1" ]; then stage_ok "阶段十 数据隔离"; else stage_bad "阶段十 数据隔离" "熊工越权=$X_LEAK 祝工越权=$Z_LEAK"; fi

# ============ 汇总 ============
echo ""
echo "=========================================================="
echo " 汇总：通过 $STAGE_PASS / 11  失败 $STAGE_FAIL / 11"
echo "=========================================================="
if [ "$STAGE_FAIL" -gt 0 ]; then
  echo "失败阶段："
  for s in "${FAILED_STAGES[@]}"; do echo "  ❌ $s"; done
fi
