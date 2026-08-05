#!/usr/bin/env bash
# L0 静态代码验证脚本
# 用于验证 docs/06-engineering-value.md 中的结构性声明（无需编译运行 Java）
# 用法: bash scripts/verify-static.sh
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0; SKIP=0
RESULTS=()

check() { # check <id> <desc> <assertion_result(0/1/2)> <detail>
  local id="$1" desc="$2" rc="$3" detail="${4:-}"
  if [ "$rc" -eq 0 ]; then PASS=$((PASS+1)); RESULTS+=("PASS|$id|$desc|$detail");
  elif [ "$rc" -eq 1 ]; then FAIL=$((FAIL+1)); RESULTS+=("FAIL|$id|$desc|$detail");
  else SKIP=$((SKIP+1)); RESULTS+=("SKIP|$id|$desc|$detail"); fi
}

GOLDEN_DIR="$ROOT/supervisor-agent/src/main/resources/data/golden_set"

# ---------- V-01 Golden Set 数量与格式 ----------
c1=$(grep -c '^--- CASE' "$GOLDEN_DIR/consult_golden.txt" 2>/dev/null || echo 0)
c2=$(grep -c '^--- CASE' "$GOLDEN_DIR/order_golden.txt" 2>/dev/null || echo 0)
c3=$(grep -c '^--- CASE' "$GOLDEN_DIR/feedback_golden.txt" 2>/dev/null || echo 0)
[ "$c1" -eq 10 ] && [ "$c2" -eq 10 ] && [ "$c3" -eq 10 ] \
  && check V-01 "Golden Set 共 30 条（三类各 10 条）" 0 "consult=$c1 order=$c2 feedback=$c3" \
  || check V-01 "Golden Set 共 30 条（三类各 10 条）" 1 "consult=$c1 order=$c2 feedback=$c3"

# 字段契约（2026-08-05 按测试报告 V-01b 修正）：
#   order/feedback 场景：每用例含 input/user_id/expected_agent/expected_tools/expected_output_keywords 5 字段
#   consult 场景：政策咨询无用户身份依赖，每用例含 4 字段（不含 user_id）
field_err=0
for f in "$GOLDEN_DIR/order_golden.txt" "$GOLDEN_DIR/feedback_golden.txt"; do
  cases=$(grep -c '^--- CASE' "$f")
  for field in input user_id expected_agent expected_tools expected_output_keywords; do
    cnt=$(grep -c "^${field}:" "$f")
    [ "$cnt" -eq "$cases" ] || field_err=$((field_err+1))
  done
done
# consult 场景（4 字段，不含 user_id）
cases=$(grep -c '^--- CASE' "$GOLDEN_DIR/consult_golden.txt")
for field in input expected_agent expected_tools expected_output_keywords; do
  cnt=$(grep -c "^${field}:" "$GOLDEN_DIR/consult_golden.txt")
  [ "$cnt" -eq "$cases" ] || field_err=$((field_err+1))
done
[ "$field_err" -eq 0 ] \
  && check V-01b "用例字段契约（order/feedback 5 字段、consult 4 字段）" 0 "30 条用例字段完整" \
  || check V-01b "用例字段契约（order/feedback 5 字段、consult 4 字段）" 1 "字段缺失 $field_err 处"

# ---------- V-02 Retry 参数 ----------
EXEC="$ROOT/order-sub-agent/src/main/java/com/alibaba/cloud/ai/demo/node/ExecutorNode.java"
grep -q "MAX_RETRY = 3" "$EXEC" \
  && check V-02a "Retry 最大次数 MAX_RETRY=3" 0 "ExecutorNode.java" \
  || check V-02a "Retry 最大次数 MAX_RETRY=3" 1 "未找到 MAX_RETRY = 3"
grep -q "long delay = 500L \* retry" "$EXEC" \
  && check V-02b "退避公式 delay=500ms×retry" 0 "ExecutorNode.java" \
  || check V-02b "退避公式 delay=500ms x retry" 1 "未找到 delay = 500L * retry"

# ---------- V-03 满意度阈值 ----------
EVA="$ROOT/supervisor-agent/src/main/java/com/alibaba/cloud/ai/demo/config/scheduling/EvaluationAgentConfiguration.java"
grep -q 'avg < 3.0 ? "alert" : "normal"' "$EVA" \
  && check V-03 "满意度阈值 avg<3.0 走告警分支" 0 "EvaluationAgentConfiguration.java" \
  || check V-03 "满意度阈值 avg<3.0 走告警分支" 1 "未找到 avg < 3.0 条件"

# ---------- V-04 两周窗口 ----------
MEM="$ROOT/memory-mcp-server/src/main/java/com/alibaba/cloud/ai/demo/service/MemoryService.java"
grep -q "minusWeeks(2)" "$MEM" \
  && check V-04a "检索窗口起点 = 今天-14天" 0 "MemoryService.java:59" \
  || check V-04a "检索窗口起点 = 今天-14天" 1 "未找到 minusWeeks(2)"
grep -q '"created_at"' "$MEM" && grep -q '"gte"' "$MEM" && grep -q '"lte"' "$MEM" \
  && check V-04b "请求体含 created_at gte/lte 过滤" 0 "MemoryService.java:77-82" \
  || check V-04b "请求体含 created_at gte/lte 过滤" 1 "过滤字段缺失"

# ---------- V-05 滑动窗口参数 ----------
COMP="$ROOT/common/src/main/java/com/alibaba/cloud/ai/common/node/ContextCompressionNode.java"
grep -q "DEFAULT_MAX_MESSAGES = 20" "$COMP" \
  && check V-05a "滑动窗口阈值 maxMessages=20" 0 "ContextCompressionNode.java:53" \
  || check V-05a "滑动窗口阈值 maxMessages=20" 1 "未找到 DEFAULT_MAX_MESSAGES = 20"
grep -q "DEFAULT_KEEP_RECENT = 6" "$COMP" \
  && check V-05b "保留最近 6 条" 0 "ContextCompressionNode.java:54" \
  || check V-05b "保留最近 6 条" 1 "未找到 DEFAULT_KEEP_RECENT = 6"

# ---------- V-06 结构性声明集合 ----------
MIJ="$ROOT/common/src/main/java/com/alibaba/cloud/ai/common/node/MemoryInjectNode.java"
grep -q 'indexOf("<userId>")' "$MIJ" \
  && check V-06a "C-3: <userId> 标签解析" 0 "MemoryInjectNode.java:96" \
  || check V-06a "C-3: <userId> 标签解析" 1 "未找到 <userId> 解析逻辑"

OAG="$ROOT/order-sub-agent/src/main/java/com/alibaba/cloud/ai/demo/config/OrderAgent.java"
(grep -q "new MemorySaver()" "$OAG" || grep -q "MemorySaver" "$OAG") \
  && check V-06b "C-4: MemorySaver Checkpoint" 0 "OrderAgent.java" \
  || check V-06b "C-4: MemorySaver Checkpoint" 1 "未找到 MemorySaver"
grep -q "threadId" "$ROOT/order-sub-agent/src/main/java/com/alibaba/cloud/ai/demo/controller/OrderAgentDebugController.java" \
  && check V-06c "C-4: threadId 恢复" 0 "OrderAgentDebugController.java:79" \
  || check V-06c "C-4: threadId 恢复" 1 "未找到 threadId"

EVAL="$ROOT/supervisor-agent/src/main/java/com/alibaba/cloud/ai/demo/config/scheduling/EvaluationClassifierNode.java"
grep -q "BeanOutputConverter<EvaluationResult>" "$EVAL" \
  && check V-06d "H-2: BeanOutputConverter<EvaluationResult>" 0 "EvaluationClassifierNode.java" \
  || check V-06d "H-2: BeanOutputConverter<EvaluationResult>" 1 "未找到 BeanOutputConverter"

LS="$ROOT/supervisor-agent/src/main/java/com/alibaba/cloud/ai/demo/config/scheduling/LocalScheduledTrigger.java"
grep -Fq 'cron = "0 0 9 * * ?"' "$LS" && grep -Fq 'cron = "0 0 10 ? * MON"' "$LS" \
  && check V-06e "L-2: 定时 cron（每日9点/周一10点）" 0 "LocalScheduledTrigger.java:64,83" \
  || check V-06e "L-2: 定时 cron（每日9点/周一10点）" 1 "cron 表达式缺失"

grep -q 'interruptBefore("executor")' "$OAG" \
  && check V-06f "L-3: HITL interruptBefore(executor)" 0 "OrderAgent.java:273" \
  || check V-06f "L-3: HITL interruptBefore(executor)" 1 "未找到 interruptBefore"

grep -q "IterationNode.converter()" "$EVA" \
  && check V-06g "L-5: IterationNode 批量迭代" 0 "EvaluationAgentConfiguration.java" \
  || check V-06g "L-5: IterationNode 批量迭代" 1 "未找到 IterationNode"

grep -q '@Async("memoryTaskExecutor")' "$MEM" \
  && check V-06h "M-1: 记忆写入 @Async" 0 "MemoryService.java:145" \
  || check V-06h "M-1: 记忆写入 @Async" 1 "未找到 @Async"

grep -q 'new SystemMessage("【用户历史偏好记忆】' "$MIJ" \
  && check V-06i "M-3: SystemMessage 注入" 0 "MemoryInjectNode.java:71" \
  || check V-06i "M-3: SystemMessage 注入" 1 "未找到 SystemMessage 注入"

# ---------- V-07 配置一致性（rerank-top-n） ----------
YML="$ROOT/consult-sub-agent/src/main/resources/application.yml"
YML_V=$(grep -oP 'rerank-top-n:\s*\$\{DASHSCOPE_RERANK_TOP_N:\K[0-9]+' "$YML" 2>/dev/null || echo "?")
PS1_V=$(grep -oP 'DASHSCOPE_RERANK_TOP_N \} else \{ "\K[0-9]+' "$ROOT/start-backend.ps1" 2>/dev/null || echo "?")
ENV_V=$(grep -oP 'DASHSCOPE_RERANK_TOP_N=\K[0-9]+' "$ROOT/env.template" 2>/dev/null || echo "?")
[ "$YML_V" = "$PS1_V" ] && [ "$YML_V" = "$ENV_V" ] && [ -n "$YML_V" ] && [ "$YML_V" != "?" ] \
  && check V-07 "rerank-top-n 三处配置一致" 0 "application.yml=$YML_V ps1=$PS1_V env.template=$ENV_V" \
  || check V-07 "rerank-top-n 三处配置一致" 1 "application.yml=$YML_V ps1=$PS1_V env.template=$ENV_V"

# ---------- 汇总 ----------
echo "======== L0 静态验证结果 ========"
for r in "${RESULTS[@]}"; do echo "$r"; done
echo "=================================="
echo "PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
[ "$FAIL" -eq 0 ]
