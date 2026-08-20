#!/usr/bin/env bash
# Phase 2.0 全场景 QA 测试脚本（curl + jq + psql）
# 前置：clean-test-data.sql + seed-qa-test-data.sql 已执行，后端:8080 前端:3000 运行中
set -uo pipefail

BASE="http://localhost:8080/api/v1"
PSQL="/c/Program Files/PostgreSQL/16/bin/psql.exe"
export PGPASSWORD=postgres
PSQL_ARGS="-h 127.0.0.1 -U postgres -d jifeng_assessment -tA"

PASS=0
FAIL=0
RESULTS=()

log()   { RESULTS+=("$1"); }
check() { # name ok_reason detail
  if [ "$2" = "ok" ]; then
    echo "✅ PASS | $1 | $3"
    log "PASS | $1 | $3"
    PASS=$((PASS+1))
  else
    echo "❌ FAIL | $1 | $3"
    log "FAIL | $1 | $3"
    FAIL=$((FAIL+1))
  fi
}

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
    # 用文件传 body，避免 Windows 下 curl 命令行参数对中文的转码破坏
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
echo " Phase 2.0 全场景 QA 测试  $(date +%Y-%m-%d_%H:%M:%S)"
echo "=========================================================="

# ============ 0. 登录 + 建用户 ============
ATOKEN=$(login admin admin123 /tmp/qa-admin.jar)
echo "[0] admin token len=${#ATOKEN}"
RESP=$(req /tmp/qa-admin.jar "$ATOKEN" POST /users '{"employeeId":"E001","username":"zhanggong","password":"123456"}')
U_ZHANGGONG=$(echo "$RESP" | jq -r '.data.userId // empty')
echo "[0] create 张工 resp: $RESP"
RESP=$(req /tmp/qa-admin.jar "$ATOKEN" POST /users '{"employeeId":"E002","username":"lizong","password":"123456"}')
U_LIZONG=$(echo "$RESP" | jq -r '.data.userId // empty')
echo "[0] create 李总 resp: $RESP"
echo "[0] U_ZHANGGONG=$U_ZHANGGONG U_LIZONG=$U_LIZONG"

# 分配角色
R1=$(req /tmp/qa-admin.jar "$ATOKEN" PUT "/users/$U_ZHANGGONG/roles" '{"roleTypes":["员工"]}')
R2=$(req /tmp/qa-admin.jar "$ATOKEN" PUT "/users/$U_LIZONG/roles" '{"roleTypes":["PM","评估人","员工"]}')
echo "[0] 张工 roles: $R1"
echo "[0] 李总 roles: $R2"

# 登录两位业务用户
ZTOKEN=$(login zhanggong 123456 /tmp/qa-zhanggong.jar)
LTOKEN=$(login lizong 123456 /tmp/qa-lizong.jar)
echo "[0] 张工 token len=${#ZTOKEN}  李总 token len=${#LTOKEN}"

# ============ 场景1：ADMIN 发起考核 ============
echo ""
echo "===== 场景1：ADMIN 发起考核 ====="
R=$(req /tmp/qa-admin.jar "$ATOKEN" POST /tasks/P2026Q3/launch)
C=$(code "$R")
TC=$(echo "$R" | jq -r '.data.taskCount // -1')
DC=$(echo "$R" | jq -r '.data.discrepancyCount // -1')
PSTATUS=$(dbq "SELECT status FROM assessment_period WHERE period_id='P2026Q3';")
if [ "$C" = "200" ] && [ "$TC" -ge 1 ] 2>/dev/null && [ "$PSTATUS" = "ONGOING" ]; then
  check "S1 发起考核" ok "code=$C taskCount=$TC discrepancyCount=$DC period=$PSTATUS"
else
  check "S1 发起考核" bad "code=$C taskCount=$TC discrepancyCount=$DC period=$PSTATUS resp=$R"
fi

# ============ 场景2：员工填写项目参与（张工 P001 100%） ============
echo ""
echo "===== 场景2：员工填写项目参与 ====="
R=$(req /tmp/qa-zhanggong.jar "$ZTOKEN" POST /participations '{"periodId":"P2026Q3","items":[{"projectCode":"P001","projectStage":"P2","participationRate":100}]}')
C=$(code "$R")
PART_STATUS=$(echo "$R" | jq -r '.data[0].status // empty')
PART_ID=$(echo "$R" | jq -r '.data[0].id // empty')
if [ "$C" = "200" ] && [ "$PART_STATUS" = "PENDING" ]; then
  check "S2 填写参与" ok "code=$C status=$PART_STATUS id=$PART_ID"
else
  check "S2 填写参与" bad "code=$C status=$PART_STATUS resp=$R"
fi

# ============ 场景3：PM 审批参与（通过） ============
echo ""
echo "===== 场景3：PM 审批参与（通过） ====="
R=$(req /tmp/qa-lizong.jar "$LTOKEN" PUT "/participations/$PART_ID/approve" '{"approved":true,"suggestedRate":100,"comment":"同意"}')
C=$(code "$R")
APPROVED=$(echo "$R" | jq -r '.data.status // empty')
PROJ_TASK=$(dbq "SELECT count(*) FROM assessment_task WHERE period_id='P2026Q3' AND assessee_id='E001' AND task_type='PROJECT' AND project_code='P001';")
if [ "$C" = "200" ] && [ "$APPROVED" = "APPROVED" ] && [ "$PROJ_TASK" = "1" ]; then
  check "S3 PM审批通过" ok "code=$C status=$APPROVED 生成PROJECT任务=$PROJ_TASK"
else
  check "S3 PM审批通过" bad "code=$C status=$APPROVED PROJECT任务=$PROJ_TASK resp=$R"
fi

# ============ 场景4：被拒后重新提交（张工 P002） ============
echo ""
echo "===== 场景4：被拒后重新提交 ====="
R=$(req /tmp/qa-zhanggong.jar "$ZTOKEN" POST /participations '{"periodId":"P2026Q3","items":[{"projectCode":"P002","projectStage":"P2","participationRate":100}]}')
P2_ID=$(echo "$R" | jq -r '.data[0].id // empty')
C=$(code "$R")
echo "[4] 提交P002: code=$C id=$P2_ID"

R=$(req /tmp/qa-lizong.jar "$LTOKEN" PUT "/participations/$P2_ID/approve" '{"approved":false,"comment":"投入比重需调整"}')
C=$(code "$R")
REJ=$(echo "$R" | jq -r '.data.status // empty')
echo "[4] 拒绝P002: code=$C status=$REJ"

R=$(req /tmp/qa-zhanggong.jar "$ZTOKEN" POST "/participations/$P2_ID/resubmit" '{"participationRate":100}')
C=$(code "$R")
RESUB=$(echo "$R" | jq -r '.data.status // empty')
if [ "$C" = "200" ] && [ "$REJ" = "REJECTED" ] && [ "$RESUB" = "PENDING" ]; then
  check "S4 被拒后重新提交" ok "拒绝=$REJ 重提后=$RESUB"
else
  check "S4 被拒后重新提交" bad "拒绝=$REJ 重提后=$RESUB resp=$R"
fi

# ============ 场景5：PM 打分（张工 PROJECT 任务） ============
echo ""
echo "===== 场景5：PM 打分 ====="
TASK_ID=$(dbq "SELECT id FROM assessment_task WHERE period_id='P2026Q3' AND assessee_id='E001' AND task_type='PROJECT' AND project_code='P001' LIMIT 1;")
echo "[5] PROJECT task_id=$TASK_ID"

R=$(req /tmp/qa-lizong.jar "$LTOKEN" PUT "/tasks/$TASK_ID/start")
C=$(code "$R")
START_STATUS=$(echo "$R" | jq -r '.data.status // empty')
echo "[5] start: code=$C status=$START_STATUS"

# 取任务详情拿 kpiConfigId
R=$(req /tmp/qa-lizong.jar "$LTOKEN" GET "/tasks/$TASK_ID")
KPI_ID=$(echo "$R" | jq -r '.data.indicators[0].kpiConfigId // empty')
KPI_TYPE=$(echo "$R" | jq -r '.data.indicators[0].kpiType // empty')
echo "[5] indicators: kpiConfigId=$KPI_ID kpiType=$KPI_TYPE"

R=$(req /tmp/qa-lizong.jar "$LTOKEN" POST "/tasks/$TASK_ID/scores" "{\"items\":[{\"kpiConfigId\":$KPI_ID,\"kpiType\":\"$KPI_TYPE\",\"score\":4.5}]}")
C=$(code "$R")
SUB_STATUS=$(echo "$R" | jq -r '.data.status // empty')
SCORE_ROW=$(dbq "SELECT score FROM assessment_score WHERE task_id=$TASK_ID AND kpi_config_id=$KPI_ID;")
if [ "$C" = "200" ] && [ "$SUB_STATUS" = "SUBMITTED" ] && [ "$SCORE_ROW" = "4.5" ]; then
  check "S5 PM打分提交" ok "code=$C taskStatus=$SUB_STATUS score=$SCORE_ROW"
else
  check "S5 PM打分提交" bad "code=$C taskStatus=$SUB_STATUS score=$SCORE_ROW resp=$R"
fi

# ============ 场景6：通知红点 ============
echo ""
echo "===== 场景6：通知红点（李总未读数） ====="
R=$(req /tmp/qa-lizong.jar "$LTOKEN" GET /notifications/unread-count)
C=$(code "$R")
UNREAD=$(echo "$R" | jq -r '.data // -1')
if [ "$C" = "200" ] && [ "$UNREAD" -ge 1 ] 2>/dev/null; then
  check "S6 通知红点" ok "code=$C unread=$UNREAD"
else
  check "S6 通知红点" bad "code=$C unread=$UNREAD resp=$R"
fi

# ============ 场景7：我的指标 ============
echo ""
echo "===== 场景7：我的指标（张工） ====="
R=$(req /tmp/qa-zhanggong.jar "$ZTOKEN" GET /my-assessment)
C=$(code "$R")
CNT=$(echo "$R" | jq -r '.data | length')
if [ "$C" = "200" ] && [ "$CNT" -ge 2 ] 2>/dev/null; then
  check "S7 我的指标" ok "code=$C 任务数=$CNT"
else
  check "S7 我的指标" bad "code=$C 任务数=$CNT resp=$R"
fi

# ============ 场景8：周期监控 ============
echo ""
echo "===== 场景8：周期监控（ADMIN） ====="
R=$(req /tmp/qa-admin.jar "$ATOKEN" GET /periods/P2026Q3/monitor)
C=$(code "$R")
MON_CNT=$(echo "$R" | jq -r '.data | length')
if [ "$C" = "200" ] && [ "$MON_CNT" -ge 1 ] 2>/dev/null; then
  check "S8 周期监控" ok "code=$C 监控条数=$MON_CNT"
else
  check "S8 周期监控" bad "code=$C 监控条数=$MON_CNT resp=$R"
fi

# ============ 场景9：数据隔离 ============
echo ""
echo "===== 场景9：数据隔离 ====="
R=$(req /tmp/qa-zhanggong.jar "$ZTOKEN" GET "/participations?size=50")
ZG_CNT=$(echo "$R" | jq -r '.data.total // -1')
ZG_LEAK=$(echo "$R" | jq -r '[.data.list[]? | select(.employeeId!="E001")] | length')
R=$(req /tmp/qa-lizong.jar "$LTOKEN" GET "/participations?size=50")
LZ_CNT=$(echo "$R" | jq -r '.data.total // -1')
LZ_P2_LEAK=$(echo "$R" | jq -r '[.data.list[]? | select(.projectCode=="P002")] | length')
if [ "$ZG_CNT" -ge 1 ] 2>/dev/null && [ "$ZG_LEAK" = "0" ] 2>/dev/null && [ "$LZ_CNT" -ge 1 ] 2>/dev/null && [ "$LZ_P2_LEAK" = "0" ] 2>/dev/null; then
  check "S9 数据隔离" ok "张工total=$ZG_CNT(越权=$ZG_LEAK) 李总total=$LZ_CNT(越权P002=$LZ_P2_LEAK)"
else
  check "S9 数据隔离" bad "张工total=$ZG_CNT 越权=$ZG_LEAK 李总total=$LZ_CNT 越权P002=$LZ_P2_LEAK"
fi

# ============ 场景10：PM审批入口权限 ============
echo ""
echo "===== 场景10：PM审批入口权限 ====="
# 张工(员工) 尝试审批 -> 应 403
R=$(req /tmp/qa-zhanggong.jar "$ZTOKEN" PUT "/participations/$PART_ID/approve" '{"approved":true}')
C=$(code "$R")
if [ "$C" = "403" ]; then
  check "S10 员工不可审批" ok "张工审批被拒 code=$C"
else
  check "S10 员工不可审批" bad "张工审批 code=$C resp=$R"
fi

# ============ 场景11：ADMIN菜单权限 ============
echo ""
echo "===== 场景11：ADMIN菜单权限 ====="
R=$(req /tmp/qa-zhanggong.jar "$ZTOKEN" POST /tasks/P2026Q3/launch)
C1=$(code "$R")
R=$(req /tmp/qa-zhanggong.jar "$ZTOKEN" GET /periods/P2026Q3/monitor)
C2=$(code "$R")
if [ "$C1" = "403" ] && [ "$C2" = "403" ]; then
  check "S11 员工不可发起/监控" ok "launch=$C1 monitor=$C2"
else
  check "S11 员工不可发起/监控" bad "launch=$C1 monitor=$C2 resp=$R"
fi

# ============ 场景12：打分权限隔离 ============
echo ""
echo "===== 场景12：打分权限隔离 ====="
# 张工(被考核人,非考核人) 尝试打分自己的 PROJECT 任务 -> 403
R=$(req /tmp/qa-zhanggong.jar "$ZTOKEN" GET "/tasks/$TASK_ID")
C=$(code "$R")
if [ "$C" = "403" ]; then
  check "S12 非考核人不可打分" ok "张工读任务详情被拒 code=$C"
else
  check "S12 非考核人不可打分" bad "张工读任务详情 code=$C resp=$R"
fi

# ============ 场景13：乐观锁/并发安全 ============
echo ""
echo "===== 场景13：乐观锁/并发安全 ====="
# FUNCTIONAL 任务并发 start：10 并发，应恰 1 成功，其余被状态机(400)/乐观锁(409)安全拒绝
FTASK=$(dbq "SELECT id FROM assessment_task WHERE period_id='P2026Q3' AND assessee_id='E001' AND task_type='FUNCTIONAL' LIMIT 1;")
echo "[13] FUNCTIONAL task_id=$FTASK"
rm -f /tmp/qa-lock-*.json
for i in $(seq 1 10); do
  (req /tmp/qa-lizong.jar "$LTOKEN" PUT "/tasks/$FTASK/start" > "/tmp/qa-lock-$i.json" 2>&1) &
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
FTASK_STATUS=$(dbq "SELECT status FROM assessment_task WHERE id=$FTASK;")
FTASK_VER=$(dbq "SELECT version FROM assessment_task WHERE id=$FTASK;")
if [ "$W200" = "1" ] && [ "$FTASK_STATUS" = "IN_PROGRESS" ] && [ "$FTASK_VER" = "1" ]; then
  check "S13 并发安全" ok "成功=$W200 状态机400=$W400 乐观锁409=$W409 其他=$WOTHER 终态=$FTASK_STATUS ver=$FTASK_VER"
else
  check "S13 并发安全" bad "成功=$W200 400=$W400 409=$W409 其他=$WOTHER 终态=$FTASK_STATUS ver=$FTASK_VER"
fi

# ============ 汇总 ============
echo ""
echo "=========================================================="
echo " 汇总：PASS=$PASS  FAIL=$FAIL"
echo "=========================================================="
for line in "${RESULTS[@]}"; do echo "$line"; done
