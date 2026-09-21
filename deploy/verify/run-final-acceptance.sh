#!/usr/bin/env bash
# 端到端校验编排（一次跑完，后台执行）。
#
# 阶段：
#   1) 等全量排空：monitor-cdc-import.sh，判据 lag=0 **且** pending=0
#   2) verify-cdc-completeness.sh —— 源库行数 vs 索引 num_docs 逐表对账（最终判定）
#   3) verify-cache-invalidation.sh —— 索引化失效（需目标实体 lag=0，脚本自带守卫）
#   4) verify-semantic-cache-index.sh —— 语义读取路径 O(1)（探针实体，与排空解耦）
#   5) Agent 时间问答 —— 「8/30 共有多少笔订单」应答 1217（UTC 日界）
#
# 为什么必须按这个顺序：2) 必须在排空后（否则行数差异里混着"还没投影完"）；
# 3) 必须在排空后（CDC 持续失效会把脚本造的缓存索引删掉，见脚本头注释）；
# 4) 与排空无关，放最后只是为了让失败信息更靠前暴露。
#
# 用法：./deploy/verify/run-final-acceptance.sh [输出日志路径]
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="${IRIS_BASE:-http://localhost:8090}"
API_KEY="${IRIS_API_KEY:-legacy-k1}"
NS="${IRIS_NS:-ecomm}"
RC="${IRIS_REDIS_CONTAINER:-iris-redis}"
PY="${IRIS_PY:-python3}"
SSE=/tmp/t26-timeq.sse
DRAIN_ROUNDS="${IRIS_DRAIN_ROUNDS:-120}"
DRAIN_GAP="${IRIS_DRAIN_GAP:-60}"

stage() { echo; echo "########## $* ##########"; }
redis() { docker exec "$RC" redis-cli "$@"; }

stage "阶段 1/5：等待全量排空（lag=0 且 pending=0；${DRAIN_ROUNDS} 轮 × ${DRAIN_GAP}s 上限）"
"$HERE/monitor-cdc-import.sh" "$DRAIN_ROUNDS" "$DRAIN_GAP"
if [ $? -ne 0 ]; then
  echo "排空未在窗口内完成 —— 后续阶段全部跳过（不要在此状态下下结论）"
  exit 1
fi
echo "排空完成，进入验收"

stage "阶段 2/5：逐表完整性对账"
"$HERE/verify-cdc-completeness.sh"
COMP_RC=$?

stage "阶段 3/5：缓存失效索引化"
"$HERE/verify-cache-invalidation.sh"
PHASE3_RC=$?

stage "阶段 4/5：语义缓存索引路径"
"$HERE/verify-semantic-cache-index.sh"
PHASE4_RC=$?

stage "阶段 5/5：Agent 时间问答实测（8/30 应答 1217）"
# /api/v1/agent/* 属演示链路，在 demo jar（iris-lite-demo）。
# BASE 须指向 demo 进程；对中间件 jar（iris-lite-api）该端点不存在（404）。
# 先清 llm-cache：语义缓存会把上一次的错误答案（2484 那类）直接命中回来，
# 不清就测不出提示词是否真的生效。走应用端点清（不用 docker exec --scan，
# 那会制造残留扫描客户端污染后续 scan 断言）。
CLEARED=$(curl -s --noproxy '*' -m 30 -X DELETE "$BASE/api/v1/llm-cache?namespace=${NS}")
echo "  llm-cache 已清：$CLEARED"

Q="2026年8月30日一共有多少笔订单？请只回答准确数字并简述标准。"
SID="t26-timecheck-$(date +%s)"
echo "  问题：$Q"
curl -sN --noproxy '*' -m 240 -X POST "$BASE/api/v1/agent/chat" \
  -H 'Content-Type: application/json' -H "X-API-Key: ${API_KEY}" \
  -d "$(printf '{"namespace":"%s","sessionId":"%s","message":"%s"}' "$NS" "$SID" "$Q")" \
  > "$SSE" 2>/dev/null
echo "  SSE 原始帧数=$(grep -c '^data:' "$SSE" 2>/dev/null)"

# 拼接 answer_delta（answer_reset 表示模型推翻了上一段，要清空累积）
ANSWER=$("$PY" - "$SSE" <<'PY'
import json, sys
text, ev = [], None
for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
    line = line.rstrip('\n')
    if line.startswith('event:'):
        ev = line[6:].strip()
        # answer_reset：模型推翻上一段文本，累积要清空（否则会把废弃草稿也算进去）
        if ev == 'answer_reset':
            text = []
    elif line.startswith('data:') and ev == 'answer_delta':
        try:
            text.append(json.loads(line[5:].strip()).get('text', ''))
        except Exception:
            pass
sys.stdout.write(''.join(text))
PY
)
echo "  Agent 最终回答："
printf '%s\n' "$ANSWER" | sed 's/^/    | /'

TIME_PASS=0
if printf '%s' "$ANSWER" | grep -q '1217'; then
  echo "  [PASS] 回答包含 1217（UTC 日界，提示词生效）"
  TIME_PASS=1
else
  echo "  [FAIL] 回答未包含 1217"
fi
if printf '%s' "$ANSWER" | grep -q '2484'; then
  echo "  [FAIL] 回答出现 2484（累加式错答，说明时区标准仍错）"
  TIME_PASS=0
fi
if [ -z "$ANSWER" ]; then
  echo "  [FAIL] 回答为空（看 SSE 里的 error 帧：$(grep -m1 '^data:' "$SSE" | head -c 200)）"
  TIME_PASS=0
fi

stage "汇总"
echo "  阶段2 完整性对账   : $([ "$COMP_RC" -eq 0 ] && echo PASS || echo "FAIL(rc=$COMP_RC)")"
echo "  阶段3 失效索引 : $([ "$PHASE3_RC"  -eq 0 ] && echo PASS || echo "FAIL(rc=$PHASE3_RC)")"
echo "  阶段4 语义索引 : $([ "$PHASE4_RC"  -eq 0 ] && echo PASS || echo "FAIL(rc=$PHASE4_RC)")"
echo "  阶段5 时间问答     : $([ "$TIME_PASS" -eq 1 ] && echo PASS || echo FAIL)"
if [ "$COMP_RC" -eq 0 ] && [ "$PHASE3_RC" -eq 0 ] && [ "$PHASE4_RC" -eq 0 ] && [ "$TIME_PASS" -eq 1 ]; then
  echo "最终验收：全部通过"
  exit 0
fi
echo "最终验收：存在未通过项（见上）"
exit 1
