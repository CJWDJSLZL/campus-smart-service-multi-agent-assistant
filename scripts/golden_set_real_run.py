#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Golden Set 真实 Agent 运行评测（consult 场景）。

调用运行中的 consult-sub-agent（/api/consult_sub_agent/debug）逐条执行 consult golden 用例，
测量真实端到端延迟与 expected_output_keywords 命中率。

注意：
- 需先启动 consult-sub-agent（Nacos 禁用 + 本地 MySQL）
- order/feedback 场景因 MCP 服务发现依赖 Nacos，本环境不可执行（沿用 Prompt 基线）

用法: python3 scripts/golden_set_real_run.py [--agent-url http://127.0.0.1:10005]
"""
import argparse
import os
import re
import sys
import time

import requests

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GOLDEN_DIR = os.path.join(ROOT, "supervisor-agent", "src", "main", "resources", "data", "golden_set")


def parse_golden(agent):
    cases = []
    fname = os.path.join(GOLDEN_DIR, "consult_golden.txt")
    case_id, fields = None, {}
    for raw in open(fname, encoding="utf-8"):
        line = raw.strip()
        if line.startswith("--- CASE"):
            if case_id:
                cases.append(build(case_id, fields))
            case_id = line.replace("---", "").strip()
            fields = {}
        elif case_id and ":" in line:
            k, v = line.split(":", 1)
            fields[k.strip()] = v.strip()
        elif case_id and line == "---":
            cases.append(build(case_id, fields))
            case_id = None
    if case_id:
        cases.append(build(case_id, fields))
    return cases


def build(case_id, fields):
    return {
        "id": case_id,
        "input": fields.get("input", ""),
        "expectedAgent": fields.get("expected_agent", ""),
        "keywords": [x.strip() for x in fields.get("expected_output_keywords", "").replace("[", "").replace("]", "").split(",") if x.strip()],
    }


def run_agent(url, user_query, chat_id, mode="react", timeout=120):
    """调用真实 consult-sub-agent，返回 (答案全文, 总耗时ms, 首token耗时ms)"""
    t0 = time.time()
    first_chunk_at = None
    answer_parts = []
    try:
        with requests.get(f"{url}/api/consult_sub_agent/debug",
                          params={"user_query": user_query, "chat_id": chat_id, "mode": mode},
                          stream=True, timeout=timeout) as r:
            for line in r.iter_lines(decode_unicode=True):
                if not line:
                    continue
                if first_chunk_at is None:
                    first_chunk_at = time.time()
                if line.startswith("data:"):
                    answer_parts.append(line[5:])
    except Exception as e:
        answer_parts.append(f"[ERROR] {e}")
    total = (time.time() - t0) * 1000
    first = (first_chunk_at - t0) * 1000 if first_chunk_at else total
    return "".join(answer_parts), total, first


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-url", default="http://127.0.0.1:10005")
    parser.add_argument("--mode", default="react")
    args = parser.parse_args()

    # 连通性检查
    probe, pt, pf = run_agent(args.agent_url, "你好", "probe", args.mode, timeout=30)
    if "[ERROR]" in probe or pt > 20000:
        print(f"Agent 不可达或超时: {pt:.0f}ms -> {probe[:100]}")
        sys.exit(1)
    print(f"Agent 连通性 OK (首token {pf:.0f}ms, 总 {pt:.0f}ms)\n")

    cases = parse_golden("consult")
    print(f"真实 Agent 评测 | mode={args.mode} | consult 用例 {len(cases)} 条\n")

    details = []
    kw_hit, kw_total = 0, 0
    lat_total, lat_first = [], []
    for i, c in enumerate(cases, 1):
        answer, total_ms, first_ms = run_agent(args.agent_url, c["input"], f"golden-{c['id']}", args.mode)
        hit = [k for k in c["keywords"] if k.lower() in answer.lower()]
        kw_hit += len(hit)
        kw_total += len(c["keywords"])
        lat_total.append(total_ms)
        lat_first.append(first_ms)
        ok = len(hit) == len(c["keywords"])
        details.append({"id": c["id"], "input": c["input"], "keywords_hit": hit,
                        "keywords_total": len(c["keywords"]), "kw_ok": ok,
                        "total_ms": round(total_ms), "first_ms": round(first_ms),
                        "answer_len": len(answer)})
        print(f"[{i}/{len(cases)}] {c['id']} kw={len(hit)}/{len(c['keywords'])} {'✓' if ok else '✗'} "
              f"总耗时={total_ms:.0f}ms 首token={first_ms:.0f}ms 答案长度={len(answer)}")

    result = {
        "mode": args.mode,
        "total": len(cases),
        "keyword_accuracy": round(kw_hit / kw_total * 100, 1),
        "avg_total_ms": round(sum(lat_total) / len(lat_total)),
        "avg_first_token_ms": round(sum(lat_first) / len(lat_first)),
        "max_total_ms": max(lat_total),
        "details": details,
    }
    out = os.path.join(ROOT, "scripts", "golden_set_real_result.json")
    import json
    json.dump(result, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    print("\n========== Golden Set 真实 Agent 评测（consult） ==========")
    print(f"关键词命中率   : {result['keyword_accuracy']:.1f}% ({kw_hit}/{kw_total})")
    print(f"平均总耗时     : {result['avg_total_ms']}ms | 平均首token {result['avg_first_token_ms']}ms | 最大 {result['max_total_ms']}ms")
    print(f"结果已写入 {out}")


if __name__ == "__main__":
    main()
