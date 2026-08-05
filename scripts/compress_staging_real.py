#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
增量压缩 staging 实测：真实 Agent 上下文下的压缩延迟与成本。

流程：
1. 用运行中的 consult-sub-agent（mode=react，同一 chat_id）生成一段 16 轮真实对话，
   收集真实 user/assistant 消息（作为"真实 Agent 上下文"）。
2. 对累积的真实消息分别运行两种压缩策略（滑动窗口 vs 增量·差异），
   用真实 LLM（qwen-plus）执行摘要，测量每次压缩的延迟(ms)与 token。

用法: python3 scripts/compress_staging_real.py [--agent-url http://127.0.0.1:10005]
"""
import argparse
import json
import os
import re
import time

import requests

from eval_rag import chat

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL = "qwen-plus"
MAX_MESSAGES = 20
KEEP_RECENT = 6
BATCH = 10
SUMMARY_MAX_CHARS = 600
CAMPUS_RE = re.compile(r"CAMPUS_\d+")


def load_env(path):
    env = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k] = v.strip().strip('"').strip("'")
    return env


ENV = load_env(os.path.join(ROOT, ".env"))
KEY = ENV.get("DASHSCOPE_API_KEY", "")


def run_agent(url, user_query, chat_id, timeout=120):
    parts = []
    with requests.get(f"{url}/api/consult_sub_agent/debug",
                      params={"user_query": user_query, "chat_id": chat_id, "mode": "react"},
                      stream=True, timeout=timeout) as r:
        for line in r.iter_lines(decode_unicode=True):
            if line and line.startswith("data:"):
                parts.append(line[5:])
    return "".join(parts)


def summarize(system_prompt, text):
    """调用 qwen-plus 生成摘要，返回 (内容, 耗时ms, 输入token, 输出token)"""
    t0 = time.time()
    r = requests.post("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                      headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
                      json={"model": MODEL, "temperature": 0,
                            "messages": [{"role": "system", "content": system_prompt},
                                         {"role": "user", "content": text}]}, timeout=90)
    r.raise_for_status()
    d = r.json()
    usage = d.get("usage", {})
    return (d["choices"][0]["message"]["content"].strip(),
            (time.time() - t0) * 1000,
            usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0))


def fmt(msgs):
    return "\n".join(f"[{role}]: {text}" for role, text in msgs)


# 与 Java 节点一致的 Prompt
SLIDING_SYS = ("请用 3-5 句话提炼以下对话历史的关键信息，"
               "保留用户身份、已办理/查询的服务名称、关键数据（记录编号、时间偏好等）：")
DIFF_SYS = ("你已有一段历史对话摘要。请从新对话片段中提取【新增】的关键事实"
            "（记录编号、服务名称、时间偏好、办理状态等），每行一条。"
            "不要重复已有摘要中已出现的内容，不要输出完整摘要，只输出新增事实。")


def run_sliding(messages):
    """滑动窗口：>20 条时一次性重摘要全部早期消息"""
    stats = {"calls": 0, "latencies": [], "in": 0, "out": 0}
    msgs = list(messages)
    summary = ""
    while len(msgs) > MAX_MESSAGES:
        early = msgs[:len(msgs) - KEEP_RECENT]
        recent = msgs[len(msgs) - KEEP_RECENT:]
        text = (f"[system]: {summary}\n" if summary else "") + fmt(early)
        content, lat, pi, po = summarize(SLIDING_SYS, text)
        stats["calls"] += 1
        stats["latencies"].append(lat)
        stats["in"] += pi
        stats["out"] += po
        summary = content
        msgs = [("system", "【历史对话摘要】" + summary)] + recent
    return stats, msgs


def run_incremental_diff(messages):
    """增量·差异：>20 条时折叠 batch 条新消息，差异追加进摘要"""
    stats = {"calls": 0, "latencies": [], "in": 0, "out": 0}
    msgs = list(messages)
    summary = ""
    while len(msgs) > MAX_MESSAGES:
        compress_end = len(msgs) - KEEP_RECENT
        start = 1 if msgs and msgs[0][0] == "system" else 0
        batch_end = min(compress_end, start + BATCH)
        if batch_end <= start:
            break
        batch = msgs[start:batch_end]
        text = (f"【已有摘要】\n{summary}\n\n" if summary else "") + fmt(batch)
        content, lat, pi, po = summarize(DIFF_SYS, text)
        stats["calls"] += 1
        stats["latencies"].append(lat)
        stats["in"] += pi
        stats["out"] += po
        summary = (summary + "\n" + content).strip()
        if len(summary) > SUMMARY_MAX_CHARS:
            summary = summary[:SUMMARY_MAX_CHARS] + "…"
        msgs = [("system", "【历史对话摘要】" + summary)] + msgs[batch_end:]
    return stats, msgs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-url", default="http://127.0.0.1:10005")
    args = parser.parse_args()

    if not KEY:
        raise SystemExit(".env 缺少 DASHSCOPE_API_KEY")

    # 1) 用真实 Agent 生成 16 轮对话
    questions = [
        "奖学金怎么申请", "转专业需要什么条件", "图书馆研讨间怎么预约",
        "助学贷款怎么申请", "心理咨询中心怎么预约", "宿舍空调坏了怎么报修",
        "优秀学生奖学金能拿多少钱", "图书馆预约有什么规则", "申请贷款需要困难认定吗",
        "在读证明怎么开", "校园卡丢了怎么补办", "体育馆怎么预约",
        "社团活动场地怎么申请", "课程退补选怎么办理", "成绩单怎么打印",
        "毕业证学位证怎么领取",
    ]
    print("用真实 consult-sub-agent 生成 16 轮对话（mode=react）...")
    messages = []
    ever_seen = set()
    for i, q in enumerate(questions, 1):
        answer = run_agent(args.agent_url, q, "staging-compress-session")
        cid = f"CAMPUS_{1000 + i}"
        messages.append(("user", q))
        messages.append(("assistant", answer[:120] + f"（记录编号 {cid}）"))
        ever_seen.add(cid)
        print(f"  第{i}轮: {q} -> 回答 {len(answer)} 字符")
        time.sleep(0.3)

    print(f"\n真实对话消息累计 {len(messages)} 条\n")

    # 2) 两种压缩策略（真实 LLM）
    print("[1/2] 滑动窗口压缩运行中...")
    sw_stats, sw_msgs = run_sliding(messages)
    print("[2/2] 增量·差异压缩运行中...")
    diff_stats, diff_msgs = run_incremental_diff(messages)

    def pct(b, a):
        return "N/A" if b == 0 else f"{(a - b) / b * 100:+.1f}%"

    print("\n========== 压缩 staging 实测（真实 Agent 上下文 + qwen-plus） ==========")
    print(f"| 指标                     | 滑动窗口 | 增量·差异 | 变化 |")
    print(f"| 压缩调用次数              | {sw_stats['calls']:>6} | {diff_stats['calls']:>8} | {pct(sw_stats['calls'], diff_stats['calls'])} |")
    avg_s = sum(sw_stats["latencies"]) / len(sw_stats["latencies"])
    avg_d = sum(diff_stats["latencies"]) / len(diff_stats["latencies"])
    print(f"| 平均单次压缩延迟(ms)      | {avg_s:>6.0f} | {avg_d:>8.0f} | {pct(avg_s, avg_d)} |")
    print(f"| 最大单次压缩延迟(ms)      | {max(sw_stats['latencies']):>6.0f} | {max(diff_stats['latencies']):>8.0f} | {pct(max(sw_stats['latencies']), max(diff_stats['latencies']))} |")
    print(f"| 累计输入 token            | {sw_stats['in']:>6} | {diff_stats['in']:>8} | {pct(sw_stats['in'], diff_stats['in'])} |")
    print(f"| 累计输出 token            | {sw_stats['out']:>6} | {diff_stats['out']:>8} | {pct(sw_stats['out'], diff_stats['out'])} |")
    print(f"| 总 token                  | {sw_stats['in'] + sw_stats['out']:>6} | {diff_stats['in'] + diff_stats['out']:>8} | {pct(sw_stats['in'] + sw_stats['out'], diff_stats['in'] + diff_stats['out'])} |")
    print(f"| 压缩后消息缓冲条数         | {len(sw_msgs):>6} | {len(diff_msgs):>8} | {pct(len(sw_msgs), len(diff_msgs))} |")

    result = {
        "turns": len(questions),
        "sliding_window": {**sw_stats, "final_msgs": len(sw_msgs)},
        "incremental_diff": {**diff_stats, "final_msgs": len(diff_msgs)},
    }
    out = os.path.join(ROOT, "scripts", "compress_staging_real_result.json")
    json.dump(result, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"\n结果已写入 {out}")


if __name__ == "__main__":
    main()
