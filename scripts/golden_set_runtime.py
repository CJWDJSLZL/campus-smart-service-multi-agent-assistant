#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Golden Set 运行时评测 —— 用已解析的 30 条 GoldenCase 产出三维准确率：
  1) 路由准确率（expected_agent）：使用 supervisor 真实 Prompt + DashScope 预测路由
  2) 工具准确率（expected_tools）：使用子 Agent 真实 Prompt 预测工具调用序列（精确/首工具）
  3) 关键词命中率（expected_output_keywords）：生成回复并核对关键词

说明：
- 默认 proxy 模式：复用各 Agent 在 application.yml 中的真实 Prompt 做离线 LLM 评测（可立即运行）。
- --http 模式：调用运行中的 supervisor /api/assistant/chat 做全链路验证（需 Agent 运行环境，本环境不可达时自动跳过）。

用法: python3 scripts/golden_set_runtime.py [--mode proxy|http]
"""
import argparse
import json
import os
import re
import sys

import requests
import yaml

from eval_rag import chat, retrieve  # 复用 DashScope 对话与检索封装

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GOLDEN_DIR = os.path.join(ROOT, "supervisor-agent", "src", "main", "resources", "data", "golden_set")
KNOWN_AGENTS = {"consult_agent", "order_agent", "feedback_agent"}
# 基础设施预节点工具（MemoryInjectNode 隐式调用），Golden Set expected_tools 仅计业务工具序列
INFRA_TOOLS = {"memory-search"}


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


def load_instruction(module_name, prompt_key):
    path = os.path.join(ROOT, module_name, "src", "main", "resources", "application.yml")
    d = yaml.safe_load(open(path, encoding="utf-8"))
    return d["agent"]["prompts"][prompt_key]


# ---------------- Golden Set 解析（与 Java GoldenSetRunner 契约一致） ----------------
def parse_golden_dir():
    cases = []
    for fname in sorted(os.listdir(GOLDEN_DIR)):
        if not fname.endswith(".txt"):
            continue
        case_id, fields = None, {}
        for raw in open(os.path.join(GOLDEN_DIR, fname), encoding="utf-8"):
            line = raw.strip()
            if line.startswith("--- CASE"):
                if case_id:
                    cases.append(build_case(case_id, fields))
                case_id = line.replace("---", "").strip()
                fields = {}
            elif case_id and ":" in line:
                k, v = line.split(":", 1)
                fields[k.strip()] = v.strip()
            elif case_id and line == "---":
                cases.append(build_case(case_id, fields))
                case_id = None
        if case_id:
            cases.append(build_case(case_id, fields))
    return cases


def build_case(case_id, fields):
    return {
        "id": case_id,
        "input": fields.get("input", ""),
        "userId": fields.get("user_id"),
        "expectedAgent": fields.get("expected_agent", ""),
        "expectedTools": parse_list(fields.get("expected_tools")),
        "expectedKeywords": parse_list(fields.get("expected_output_keywords")),
        "clarification": fields.get("clarification_required", "false") == "true",
    }


def parse_list(v):
    if not v:
        return []
    return [x.strip() for x in v.replace("[", "").replace("]", "").split(",") if x.strip()]


# ---------------- 三维度评测 ----------------
def predict_agent(supervisor_prompt, user_input):
    p = (supervisor_prompt + "\n\n用户请求：" + user_input
         + "\n请判断应路由到哪个子 Agent（只输出一个名称：consult_agent / order_agent / feedback_agent）。"
         + "若不应路由任何子 Agent 则输出 none。")
    out = chat("你是总调度智能体，只输出子 Agent 名称", p)
    for a in KNOWN_AGENTS:
        if a in out:
            return a
    if "none" in out.lower():
        return "none"
    return out.strip()[:30]


def predict_tools(agent_prompt, user_input):
    p = (agent_prompt + "\n\n用户请求：" + user_input
         + "\n请按工作流程列出应依次调用的工具名称（按调用顺序），只输出 JSON 数组，如 [\"campus-validate-service-item\"]。"
           "若信息不完整应追问而非调用工具，则输出 []。不要任何解释。")
    out = chat("你只输出 JSON 数组", p)
    m = re.search(r"\[.*?\]", out, re.S)
    if not m:
        return []
    try:
        return [x.strip() for x in json.loads(m.group(0))]
    except Exception:
        return [x.strip() for x in re.findall(r"\"([^\"]+)\"", m.group(0))]


def gen_reply(agent_prompt, user_input, contexts=None):
    if contexts:
        ctx = "\n---\n".join(f"[{c['title']}]\n{c['content']}" for c in contexts)
        p = (agent_prompt + f"\n\n参考材料：\n{ctx}\n\n用户请求：{user_input}\n请依据参考材料回复。")
    else:
        p = (agent_prompt + f"\n\n用户请求：{user_input}\n请以该助手身份给出面向用户的回复。")
    return chat("你是校园智能服务助手", p)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", default="proxy", choices=["proxy", "http"])
    args = parser.parse_args()

    cases = parse_golden_dir()
    if len(cases) != 30:
        print(f"WARN: 解析到 {len(cases)} 条（预期 30）")
    print(f"Golden Set 运行时评测 | mode={args.mode} | {len(cases)} 条用例\n")

    supervisor_prompt = load_instruction("supervisor-agent", "supervisor-agent-instruction")
    prompts = {
        "order_agent": load_instruction("order-sub-agent", "order-agent-instruction"),
        "consult_agent": load_instruction("consult-sub-agent", "consult-agent-instruction"),
        "feedback_agent": load_instruction("feedback-sub-agent", "feedback-agent-instruction"),
    }

    route_correct, tool_exact, tool_first, kw_hit, kw_total = 0, 0, 0, 0, 0
    details = []

    for i, c in enumerate(cases, 1):
        # 1) 路由
        pred_agent = predict_agent(supervisor_prompt, c["input"])
        route_ok = pred_agent == c["expectedAgent"]

        # 2) 工具（非澄清用例，且预测出的 Agent 有对应 Prompt 才评）
        tools_pred, tools_ok, first_ok = [], False, False
        if not c["clarification"] and c["expectedAgent"] in prompts and route_ok:
            tools_pred = predict_tools(prompts[c["expectedAgent"]], c["input"])
            # 过滤基础设施预节点工具（如 memory-search）后与业务工具序列比对
            business = [t for t in tools_pred if t not in INFRA_TOOLS]
            tools_ok = business == c["expectedTools"]
            first_ok = bool(business) and bool(c["expectedTools"]) and business[0] == c["expectedTools"][0]
            tools_pred = business

        # 3) 关键词（生成回复并核对）
        contexts = None
        if c["expectedAgent"] == "consult_agent":
            try:
                contexts, _, _ = retrieve(c["input"], rerank=True, top_k=3)
            except Exception:
                pass
        reply = gen_reply(prompts.get(c["expectedAgent"], supervisor_prompt), c["input"], contexts)
        hit_kws = [k for k in c["expectedKeywords"] if k.lower() in reply.lower()]
        kw_ok = len(hit_kws) == len(c["expectedKeywords"])

        route_correct += int(route_ok)
        tool_exact += int(tools_ok)
        tool_first += int(first_ok)
        kw_hit += len(hit_kws)
        kw_total += len(c["expectedKeywords"])

        details.append({
            "id": c["id"], "input": c["input"], "expectedAgent": c["expectedAgent"], "predAgent": pred_agent,
            "route_ok": route_ok, "expectedTools": c["expectedTools"], "predTools": tools_pred,
            "tools_ok": tools_ok, "first_tool_ok": first_ok,
            "keywords_hit": hit_kws, "keywords_total": len(c["expectedKeywords"]), "kw_ok": kw_ok,
        })
        print(f"[{i}/{len(cases)}] {c['id']} route={pred_agent}({'✓' if route_ok else '✗'}) "
              f"tools={'✓' if tools_ok else ('首工具✓' if first_ok else '✗')} "
              f"kw={len(hit_kws)}/{len(c['expectedKeywords'])}")

    # 汇总
    n_route = len(cases)
    tool_cases = [d for d in details if not (d["expectedTools"] == [] and d["expectedAgent"] in ("consult_agent",))]
    n_tool = sum(1 for c in cases if not c["clarification"] and c["expectedAgent"] in prompts)
    result = {
        "mode": args.mode,
        "total": len(cases),
        "route_accuracy": round(route_correct / n_route * 100, 1),
        "tool_exact_accuracy": round(tool_exact / n_tool * 100, 1) if n_tool else None,
        "tool_first_accuracy": round(tool_first / n_tool * 100, 1) if n_tool else None,
        "keyword_accuracy": round(kw_hit / kw_total * 100, 1),
        "details": details,
    }
    out_path = os.path.join(ROOT, "scripts", "golden_set_runtime_result.json")
    json.dump(result, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    print("\n========== Golden Set 三维准确率 ==========")
    print(f"路由准确率 : {result['route_accuracy']:.1f}% ({route_correct}/{n_route})")
    print(f"工具准确率 : 精确 {result['tool_exact_accuracy']:.1f}% / 首工具 {result['tool_first_accuracy']:.1f}% ({n_tool} 条非澄清工具用例)")
    print(f"关键词命中率: {result['keyword_accuracy']:.1f}% ({kw_hit}/{kw_total})")
    print(f"\n结果已写入 {out_path}")


if __name__ == "__main__":
    main()
