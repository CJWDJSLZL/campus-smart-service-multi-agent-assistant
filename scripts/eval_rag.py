#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAG 离线性能评估脚本
直接调用 DashScope 检索与对话接口（无需启动应用），对 docs/06 RAG 声明做量化验证：
  R-1 查询改写对语义召回的影响（rewrite on/off A/B）
  R-2 Rerank 对相关性/召回的提升（rerank on/off A/B）
  生成质量四维（LLM-as-judge）：Faithfulness / Answer Relevancy / Context Precision / Answer Correctness

用法: python3 scripts/eval_rag.py
依赖: requests（pip install requests）
"""
import os
import re
import sys
import time
import json
import statistics

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
INDEX = ENV.get("DASHSCOPE_INDEX_ID", "")
MODEL = ENV.get("DASHSCOPE_MODEL", "qwen-plus")
BASE = "https://dashscope.aliyuncs.com"
RETRIEVE_URI = "/api/v1/indices/pipeline/{pid}/retrieve"
CHAT_URI = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

# 对话接口是否可用（账户欠费时检索接口仍可用，但 chat 接口返回 Arrearage）
CHAT_AVAILABLE = True

# 校园场景评测数据集（query / 召回关键词 / 参考答案关键事实）
# 前 8 条为标准问题；后 4 条为模糊/口语/变体问题，用于检验改写与检索鲁棒性
DATASET = [
    {"query": "奖学金怎么申请", "keywords": ["奖学金", "绩点", "评定"],
     "facts": ["奖学金评定与绩点排名相关", "奖学金分为不同等级", "具体金额以学校当年通知为准"]},
    {"query": "图书馆研讨间怎么预约", "keywords": ["研讨间", "预约"],
     "facts": ["图书馆研讨间需要预约", "预约需要提供人数与时间"]},
    {"query": "转专业需要什么条件", "keywords": ["转专业", "条件"],
     "facts": ["转专业有基本申请条件", "转专业有申请流程"]},
    {"query": "助学贷款怎么申请", "keywords": ["助学贷款", "困难认定"],
     "facts": ["助学贷款需要困难认定", "申请需要相关证明材料"]},
    {"query": "心理咨询中心怎么预约", "keywords": ["心理", "咨询", "预约"],
     "facts": ["学校提供心理咨询服务", "心理咨询需要预约"]},
    {"query": "宿舍空调坏了怎么报修", "keywords": ["报修", "宿舍", "反馈"],
     "facts": ["宿舍问题可以反馈投诉", "报修需要提交反馈记录"]},
    {"query": "奖学金申请需要哪些材料", "keywords": ["奖学金", "材料", "申请"],
     "facts": ["奖学金申请有材料要求", "材料预审是办理流程的一部分"]},
    {"query": "优秀学生奖学金能拿多少钱", "keywords": ["奖学金", "金额"],
     "facts": ["优秀学生奖学金分等级", "金额有参考区间"]},
    # ---- 模糊/变体问题 ----
    {"query": "绩点排名前多少能拿奖学金", "keywords": ["绩点", "奖学金", "排名"],
     "facts": ["奖学金评定与绩点排名相关", "奖学金分为不同等级"]},
    {"query": "约个地方讨论作业", "keywords": ["研讨间", "讨论", "预约"],
     "facts": ["图书馆研讨间可预约用于小组讨论"]},
    {"query": "自习室怎么预约", "keywords": ["自习", "预约"],
     "facts": ["自习/研讨空间预约以图书馆规则为准"]},
    {"query": "怎么申请补助", "keywords": ["补助", "资助"],
     "facts": ["补助类申请需要查看具体申请条件与材料"]},
]


def _auth():
    return {"Authorization": "Bearer " + KEY, "Content-Type": "application/json"}


def retrieve(query, rerank=True, top_k=3):
    """调用 DashScope pipeline retrieve 接口，返回 (top_k 列表, 耗时ms)"""
    body = {
        "query": query,
        "denseSimilarityTopK": top_k * 2,
        "sparseSimilarityTopK": top_k * 2,
        "enableRewrite": False,
        "rewrite": [],
        "enableReranking": rerank,
        # rerank 配置仅在开启时下发，否则 API 端不会执行重排
        "rerank": [{"modelType": "DashScopeTextRerank", "modelName": "gte-rerank-v2"}] if rerank else [],
        "rerankMinScore": 0.0,
        "rerankTopN": top_k,
        "searchFilters": [],
    }
    t0 = time.time()
    r = requests.post(BASE + RETRIEVE_URI.format(pid=INDEX), headers=_auth(), json=body, timeout=30)
    dt = (time.time() - t0) * 1000
    r.raise_for_status()
    resp = r.json()
    nodes = resp.get("nodes", [])
    out = []
    for n in nodes:
        node = n.get("node", {})
        md = node.get("metadata", {})
        # 注意：pipeline retrieve 返回的分块文本字段是 text（部分版本为 content）
        chunk_text = node.get("text") or node.get("content") or ""
        out.append({"content": chunk_text, "title": md.get("title", ""),
                    "score": md.get("_score", 0.0)})
    return out[:top_k], dt, resp.get("rerank", "?")


def parse_number(text):
    """从模型输出中鲁棒地解析一个 0-1 数字"""
    if not text:
        return 0.0
    m = re.search(r"(?:0\.\d+|\b1(?:\.0+)?|\b0\b)", text)
    if m:
        v = float(m.group(0))
        return min(max(v, 0.0), 1.0)
    return 0.0


def chat(system, user, model=None, retries=3):
    """调用 DashScope OpenAI 兼容对话接口（含失败重试）"""
    m = model or MODEL
    last_err = None
    for attempt in range(retries):
        try:
            r = requests.post(CHAT_URI, headers=_auth(),
                              json={"model": m, "temperature": 0,
                                    "messages": [{"role": "system", "content": system},
                                                 {"role": "user", "content": user}]}, timeout=60)
            if r.status_code == 200:
                return r.json()["choices"][0]["message"]["content"].strip()
            last_err = f"HTTP {r.status_code}: {r.text[:300]}"
        except Exception as e:  # noqa: BLE001
            last_err = repr(e)
        time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"chat 调用失败: {last_err}")


def rewrite_query(q):
    """复刻 ConsultService.rewriteQuery 的改写 Prompt"""
    p = ("你是校园服务检索优化助手。将用户查询改写为更适合知识库检索的形式：\n"
         "1. 展开缩写词（如“奖学金”→“奖学金申请政策和评定条件”）\n"
         "2. 添加同义词和领域关键词（如“办理”→“申请办理流程步骤”）\n"
         "3. 补充“申请流程”、“办理步骤”、“所需材料”等场景词\n"
         "4. 只输出改写后的查询语句，不要任何解释\n"
         "原始查询：" + q)
    return chat("你是一个查询改写助手", p)


def keyword_hit(items, keywords):
    """Top-K 上下文是否命中任一关键词（召回质量代理指标）"""
    text = " ".join(it["title"] + it["content"] for it in items).lower()
    return any(k.lower() in text for k in keywords)


def extract_json(text):
    """从模型输出中提取 JSON 对象"""
    m = re.search(r"\{.*\}", text, re.S)
    if m:
        try:
            return json.loads(m.group(0))
        except Exception:
            pass
    return {}


# ---------------- LLM-as-judge 指标（均要求输出裸数字，鲁棒解析） ----------------
def judge_faithfulness(answer, contexts):
    ctx = "\n---\n".join(c["content"][:500] for c in contexts)
    p = ("你是客观评估助手。判断回答中的每一句是否都被给定的参考材料支持。\n"
         f"参考材料：\n{ctx}\n\n回答：\n{answer}\n\n"
         "请只输出一个0到1之间的数字（被材料支持的句子比例），不要任何其他内容。")
    return parse_number(chat("你只输出一个数字", p))


def judge_answer_relevancy(q, answer):
    p = (f"问题：{q}\n回答：{answer}\n\n"
         "请从0到1给回答的切题程度打分（1=完全切题并解答，0=完全离题）。只输出一个数字。")
    return parse_number(chat("你只输出一个数字", p))


def judge_context_precision(q, contexts):
    ctx = "\n---\n".join(c["content"][:400] for c in contexts)
    p = (f"问题：{q}\n\n提供给回答的参考材料：\n{ctx}\n\n"
         "请评估材料中对回答该问题有价值内容的占比（0到1）。只输出一个数字。")
    return parse_number(chat("你只输出一个数字", p))


def judge_answer_correctness(answer, facts):
    fl = "\n".join(f"- {f}" for f in facts)
    p = (f"标准事实清单：\n{fl}\n\n模型回答：\n{answer}\n\n"
         "请评估模型回答覆盖了标准事实的比例（0到1）。只输出一个数字。")
    return parse_number(chat("你只输出一个数字", p))


def gen_answer(q, contexts):
    ctx = "\n---\n".join(f"[{c['title']}]\n{c['content']}" for c in contexts)
    sysp = ("你是校园智能服务助手。请只依据给定的参考材料回答用户问题，若材料不足则说明“材料未覆盖”。"
            "回答简洁，不要编造。")
    return chat(sysp, f"参考材料：\n{ctx}\n\n用户问题：{q}")


# ---------------- 主流程 ----------------
def main():
    if not KEY or not INDEX:
        print("ERROR: 请在 .env 配置 DASHSCOPE_API_KEY / DASHSCOPE_INDEX_ID")
        sys.exit(1)
    print(f"RAG 离线评估开始 | index={INDEX} | model={MODEL} | 数据集 {len(DATASET)} 条\n")

    # 1) 检索 A/B：rerank on/off（改写依赖 chat，若账户欠费则自动降级为检索-only）
    global CHAT_AVAILABLE
    CHAT_AVAILABLE = True
    rows = []
    rerank_reorder_count = 0
    for i, item in enumerate(DATASET, 1):
        q = item["query"]
        raw, t_raw, rerank_used = retrieve(q, rerank=True)
        rw = ""
        if CHAT_AVAILABLE:
            try:
                rw = rewrite_query(q)
            except Exception as e:  # noqa: BLE001
                print(f"    [warn] chat 不可用({e})，改写 A/B 与生成质量评测跳过，仅保留检索指标")
                CHAT_AVAILABLE = False
        if CHAT_AVAILABLE:
            rew, t_rew, _ = retrieve(rw, rerank=True)
        else:
            rew, t_rew, _ = None, 0.0, None
        no_rerank, t_nor, _ = retrieve(q, rerank=False)
        # 检测 rerank 是否改变排序（raw vs rerank_off 的 title 顺序）
        raw_titles = [x["title"] for x in raw]
        nor_titles = [x["title"] for x in no_rerank]
        if raw_titles != nor_titles:
            rerank_reorder_count += 1
        row = {
            "query": q, "rewritten": rw,
            "raw": {"hit": keyword_hit(raw, item["keywords"]), "top1_score": raw[0]["score"] if raw else 0,
                    "ms": round(t_raw, 0), "titles": raw_titles},
            "rerank_off": {"hit": keyword_hit(no_rerank, item["keywords"]), "top1_score": no_rerank[0]["score"] if no_rerank else 0,
                           "ms": round(t_nor, 0), "titles": nor_titles},
        }
        if CHAT_AVAILABLE:
            row["rewrite"] = {"hit": keyword_hit(rew, item["keywords"]), "top1_score": rew[0]["score"] if rew else 0,
                              "ms": round(t_rew, 0)}
        rows.append(row)
        print(f"[{i}/{len(DATASET)}] {q}"
              + (f"\n    rewritten={rw}" if CHAT_AVAILABLE else "")
              + f"\n    raw hit={row['raw']['hit']} top1={row['raw']['top1_score']:.3f} | "
              + (f"rewrite hit={row['rewrite']['hit']} top1={row['rewrite']['top1_score']:.3f} | " if CHAT_AVAILABLE else "")
              + f"rerank_off hit={row['rerank_off']['hit']} top1={row['rerank_off']['top1_score']:.3f} | "
              + f"rerank_reorder={raw_titles != nor_titles} | server_rerank={rerank_used}")

    def agg(key):
        hits = [r[key]["hit"] for r in rows]
        return {"recall@3": statistics.mean(hits), "hit_count": sum(hits),
                "avg_top1_score": statistics.mean(r[key]["top1_score"] for r in rows),
                "avg_ms": statistics.mean(r[key]["ms"] for r in rows)}

    summary_retrieval = {"raw": agg("raw"), "rerank_off": agg("rerank_off")}
    if CHAT_AVAILABLE:
        summary_retrieval["rewrite"] = agg("rewrite")

    # 2) 生成质量（LLM-as-judge）——依赖 chat，欠费时跳过
    gen_rows = []
    summary_gen = {}
    if CHAT_AVAILABLE:
        for i, item in enumerate(DATASET, 1):
            ctxs, _, _ = retrieve(item["query"], rerank=True, top_k=3)
            ans = gen_answer(item["query"], ctxs)
            gen_rows.append({
                "query": item["query"],
                "faithfulness": judge_faithfulness(ans, ctxs),
                "answer_relevancy": judge_answer_relevancy(item["query"], ans),
                "context_precision": judge_context_precision(item["query"], ctxs),
                "answer_correctness": judge_answer_correctness(ans, item["facts"]),
                "answer": ans[:200],
            })
            print(f"[{i}/{len(DATASET)}] gen: faith={gen_rows[-1]['faithfulness']:.2f} "
                  f"rel={gen_rows[-1]['answer_relevancy']:.2f} ctxP={gen_rows[-1]['context_precision']:.2f} "
                  f"corr={gen_rows[-1]['answer_correctness']:.2f}")
        for k in ("faithfulness", "answer_relevancy", "context_precision", "answer_correctness"):
            summary_gen[k] = round(statistics.mean(g[k] for g in gen_rows), 3)

    # 3) R-2/R-1 提升幅度量化
    rerank_gain = (summary_retrieval["raw"]["avg_top1_score"] - summary_retrieval["rerank_off"]["avg_top1_score"])
    rerank_gain_pct = (rerank_gain / summary_retrieval["rerank_off"]["avg_top1_score"] * 100) if summary_retrieval["rerank_off"]["avg_top1_score"] else 0
    rewrite_recall_gain = (summary_retrieval["rewrite"]["recall@3"] - summary_retrieval["raw"]["recall@3"]) if CHAT_AVAILABLE else None

    result = {
        "meta": {"index": INDEX, "model": MODEL, "dataset_size": len(DATASET),
                 "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                 "chat_available": CHAT_AVAILABLE},
        "retrieval_ab": {"raw": summary_retrieval["raw"],
                         "rewrite": summary_retrieval.get("rewrite"),
                         "rerank_off": summary_retrieval["rerank_off"],
                         "rerank_avg_top1_gain_pct": round(rerank_gain_pct, 1),
                         "rewrite_recall_gain": rewrite_recall_gain,
                         "rerank_changed_order_count": rerank_reorder_count},
        "generation_quality": summary_gen,
        "rows": rows,
        "generation_rows": gen_rows,
    }
    out_path = os.path.join(ROOT, "scripts", "eval_rag_result.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    # 4) 汇总输出
    print("\n" + "=" * 60)
    print("检索 A/B 汇总（Recall@3 / Top1 得分 / 耗时）")
    for k in ("raw", "rewrite", "rerank_off"):
        if k not in summary_retrieval:
            continue
        s = summary_retrieval[k]
        print(f"  {k:10s}: recall@3={s['recall@3']:.2f} ({s['hit_count']}/{len(DATASET)})  avg_top1={s['avg_top1_score']:.3f}  avg_ms={s['avg_ms']:.0f}")
    print(f"\n  R-2 Rerank 改变排序的查询数: {rerank_reorder_count}/{len(DATASET)}；Top1 得分差: {rerank_gain:+.3f}（文档声称 20-30%）")
    if CHAT_AVAILABLE:
        print(f"  R-1 查询改写对 Recall@3 的提升: {rewrite_recall_gain:+.2f}")
    else:
        print("  R-1 查询改写 A/B：跳过（DashScope 对话接口欠费不可用）")
    if CHAT_AVAILABLE:
        print("\n生成质量（LLM-as-judge，0-1）")
        for k, v in summary_gen.items():
            print(f"  {k:20s}: {v:.3f}")
    else:
        print("\n生成质量（LLM-as-judge）：跳过（DashScope 对话接口欠费不可用）")
    print(f"\n结果已写入 {out_path}")


if __name__ == "__main__":
    main()
