#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
增量滚动摘要 vs 滑动窗口 —— 真实 LLM 前后对比实验。

使用 DashScope qwen-plus 真实调用生成摘要，从 API usage 采集真实 token 消耗，
在同一模拟对话轨迹（40 轮，含突发工具返回）下对比两种压缩策略：
  滑动窗口（一次性重摘要全部早期消息） vs 增量滚动摘要（批量=10，合并进运行摘要）

指标：摘要调用次数、单次最大 prompt token、累计 token（输入+输出）、
     最终消息缓冲条数、最终摘要实体（CAMPUS 编号）保留率。

用法: python3 scripts/compare_compression_real.py
"""
import os
import re
import sys
import json
import time

import requests

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


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
MODEL = ENV.get("DASHSCOPE_MODEL", "qwen-plus")
CHAT_URI = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

MAX_MESSAGES = 20
KEEP_RECENT = 6
BATCH = 10
TURNS = 40
# 与 Java IncrementalContextCompressionNode 的 maxSummaryChars 对齐
SUMMARY_MAX_CHARS = 600
CAMPUS_RE = re.compile(r"CAMPUS_\d+")


def chat(system, user):
    r = requests.post(CHAT_URI,
                      headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
                      json={"model": MODEL, "temperature": 0,
                            "messages": [{"role": "system", "content": system},
                                         {"role": "user", "content": user}]}, timeout=90)
    r.raise_for_status()
    d = r.json()
    usage = d.get("usage", {})
    return d["choices"][0]["message"]["content"].strip(), usage


def build_trace():
    """生成 40 轮对话轨迹，返回 [(role, text)] 与全程去重 CAMPUS id 集合"""
    trace = []
    ever_seen = set()
    for t in range(TURNS):
        cid = "CAMPUS_" + str(1000 + t)
        trace.append(("user", f"第{t}轮：请问奖学金申请条件和办理流程步骤是什么？"))
        trace.append(("assistant", f"第{t}轮回答：需GPA达标并提交材料，已为您办理，{cid}，时间偏好晚上。"))
        ever_seen.add(cid)
        if t % 5 == 4:
            long_data = "办理记录详情：" + ("预约编号、服务名称、办理状态、经办人、时间" * 60) + cid
            trace.append(("tool", long_data))
    return trace, ever_seen


def fmt_messages(msgs):
    return "\n".join(f"[{role}]: {text}" for role, text in msgs)


def summarize_sliding(old_summary, early_msgs):
    """滑动窗口：一次性重摘要全部早期消息"""
    text = ""
    if old_summary:
        text += f"[system]: {old_summary}\n"
    text += fmt_messages(early_msgs)
    sys_p = ("请用 3-5 句话提炼以下对话历史的关键信息，"
             "保留用户身份、已办理/查询的服务名称、关键数据（记录编号、时间偏好等）：")
    return chat(sys_p, text)


def summarize_incremental(old_summary, batch_msgs):
    """增量滚动摘要：把新片段合并进已有摘要"""
    text = f"【已有摘要】\n{old_summary}\n\n" + fmt_messages(batch_msgs)
    sys_p = ("你已有一段历史对话摘要。请将以下新对话片段合并进已有摘要，生成更新后的摘要。"
             "必须保留已有摘要中的所有关键事实，并纳入新片段中的关键信息。"
             "不要删除或弱化已有摘要中的任何关键实体。")
    return chat(sys_p, text)


def summarize_incremental_diff(old_summary, batch_msgs):
    """增量差异摘要：仅输出新片段中的【新增】关键事实，不重写完整摘要"""
    text = f"【已有摘要】\n{old_summary}\n\n新对话片段：\n" + fmt_messages(batch_msgs)
    sys_p = ("你已有一段历史对话摘要。请从新对话片段中提取【新增】的关键事实"
             "（记录编号、服务名称、时间偏好、办理状态等），每行一条。"
             "不要重复已有摘要中已出现的内容，不要输出完整摘要，只输出新增事实。")
    return chat(sys_p, text)


def run_sliding_window(trace):
    """滑动窗口策略：超 20 条时一次性重摘要全部早期消息，保留最近 6 条"""
    messages = []
    summary = ""
    calls, max_prompt, total_in, total_out = 0, 0, 0, 0
    for role, text in trace:
        messages.append((role, text))
        if len(messages) > MAX_MESSAGES:
            early = messages[:len(messages) - KEEP_RECENT]
            recent = messages[len(messages) - KEEP_RECENT:]
            content, usage = summarize_sliding(summary, early)
            calls += 1
            p, o = usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0)
            max_prompt = max(max_prompt, p)
            total_in += p
            total_out += o
            summary = content
            messages = [("system", "【历史对话摘要】" + summary)] + recent
    return {"calls": calls, "max_prompt": max_prompt, "total_in": total_in,
            "total_out": total_out, "final_msgs": len(messages), "summary": summary}


def run_incremental(trace):
    """增量滚动摘要策略：超 20 条时折叠至多 BATCH 条早期消息进运行摘要"""
    messages = []
    summary = ""
    calls, max_prompt, total_in, total_out = 0, 0, 0, 0
    for role, text in trace:
        messages.append((role, text))
        if len(messages) > MAX_MESSAGES:
            compress_end = len(messages) - KEEP_RECENT
            start = 1 if messages and messages[0][0] == "system" else 0
            batch_end = min(compress_end, start + BATCH)
            if batch_end > start:
                batch = messages[start:batch_end]
                content, usage = summarize_incremental(summary, batch)
                calls += 1
                p, o = usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0)
                max_prompt = max(max_prompt, p)
                total_in += p
                total_out += o
                # 与 Java 实现一致：限制运行摘要长度，防止无限膨胀
                summary = content if len(content) <= SUMMARY_MAX_CHARS else content[:SUMMARY_MAX_CHARS] + "…"
                messages = [("system", "【历史对话摘要】" + summary)] + messages[batch_end:]
    return {"calls": calls, "max_prompt": max_prompt, "total_in": total_in,
            "total_out": total_out, "final_msgs": len(messages), "summary": summary}


def run_incremental_diff(trace):
    """增量差异策略：仅把新片段的新增事实追加进运行摘要（输出 token 最小化）"""
    messages = []
    summary = ""
    calls, max_prompt, total_in, total_out = 0, 0, 0, 0
    for role, text in trace:
        messages.append((role, text))
        if len(messages) > MAX_MESSAGES:
            compress_end = len(messages) - KEEP_RECENT
            start = 1 if messages and messages[0][0] == "system" else 0
            batch_end = min(compress_end, start + BATCH)
            if batch_end > start:
                batch = messages[start:batch_end]
                content, usage = summarize_incremental_diff(summary, batch)
                calls += 1
                p, o = usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0)
                max_prompt = max(max_prompt, p)
                total_in += p
                total_out += o
                # 追加新增事实，同样按 600 字符上限
                summary = (summary + "\n" + content).strip()
                if len(summary) > SUMMARY_MAX_CHARS:
                    summary = summary[:SUMMARY_MAX_CHARS] + "…"
                messages = [("system", "【历史对话摘要】" + summary)] + messages[batch_end:]
    return {"calls": calls, "max_prompt": max_prompt, "total_in": total_in,
            "total_out": total_out, "final_msgs": len(messages), "summary": summary}


def entity_recall(summary, ever_seen):
    ids = set(CAMPUS_RE.findall(summary))
    return round(len(ids) / len(ever_seen) * 100, 1)


def main():
    if not KEY:
        print("ERROR: .env 缺少 DASHSCOPE_API_KEY")
        sys.exit(1)
    trace, ever_seen = build_trace()
    print(f"真实 LLM 对比开始 | model={MODEL} | 40 轮对话，去重实体 {len(ever_seen)} 个\n")

    print("[1/3] 滑动窗口策略运行中...")
    sw = run_sliding_window(trace)
    print("[2/3] 增量滚动摘要（合并）策略运行中...")
    inc = run_incremental(trace)
    print("[3/3] 增量滚动摘要（差异追加）策略运行中...")
    diff_run = run_incremental_diff(trace)

    sw_recall = entity_recall(sw["summary"], ever_seen)
    inc_recall = entity_recall(inc["summary"], ever_seen)
    diff_recall = entity_recall(diff_run["summary"], ever_seen)

    def dv(b, a):
        return "N/A" if b == 0 else f"{((a - b) / b * 100):+.1f}%"

    print("\n========== 压缩策略真实 LLM 对比（40 轮，qwen-plus） ==========")
    print("| 指标                         | 滑动窗口 | 增量·合并 | 增量·差异 | vs滑动窗口 | vs合并 |")
    rows = [
        ("摘要调用次数", lambda r: r["calls"], lambda r: r["calls"]),
        ("单次最大 prompt token", lambda r: r["max_prompt"], lambda r: r["max_prompt"]),
        ("累计输入 token", lambda r: r["total_in"], lambda r: r["total_in"]),
        ("累计输出 token", lambda r: r["total_out"], lambda r: r["total_out"]),
        ("总 token(输入+输出)", lambda r: r["total_in"] + r["total_out"], lambda r: r["total_in"] + r["total_out"]),
        ("最终消息条数", lambda r: r["final_msgs"], lambda r: r["final_msgs"]),
    ]
    for name, fa, fb in rows:
        b, a, c = fa(sw), fb(inc), fb(diff_run)
        print(f"| {name:<26} | {b:>7} | {a:>7} | {c:>7} | {dv(b, c):>7} | {dv(a, c):>7} |")

    print(f"| 摘要实体保留率(%)            | {sw_recall:>7.1f} | {inc_recall:>7.1f} | {diff_recall:>7.1f} | {dv(sw_recall, diff_recall):>7} | {dv(inc_recall, diff_recall):>7} |")

    result = {
        "model": MODEL, "turns": TURNS, "batch": BATCH,
        "sliding_window": {**sw, "entity_recall_pct": sw_recall},
        "incremental_merge": {**inc, "entity_recall_pct": inc_recall},
        "incremental_diff": {**diff_run, "entity_recall_pct": diff_recall},
    }
    out = os.path.join(ROOT, "scripts", "compare_compression_real_result.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"\n结果已写入 {out}")


if __name__ == "__main__":
    main()
