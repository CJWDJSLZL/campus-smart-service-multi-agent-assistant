#!/usr/bin/env python3
"""
DashScope Bailian 知识库文档上传脚本
上传 knowledge/ 目录下所有 .md 文件到指定知识库
"""

import os
import json
import time
import requests
from pathlib import Path

API_KEY   = "sk-d3fe5b9a7b864187b68468817f3ff73e"
INDEX_ID  = "cate_a24fa70f192d43aea9ac5140f468574c_10593895"
KB_DIR    = Path("/data/smartServices/consult-sub-agent/src/main/resources/knowledge")

HEADERS = {
    "Authorization": f"Bearer {API_KEY}",
}

BASE_URL = "https://dashscope.aliyuncs.com/api/v1"


def upload_file(filepath: Path) -> str | None:
    """Step 1: 上传文件，获取 file_id"""
    url = f"{BASE_URL}/files"
    with open(filepath, "rb") as f:
        resp = requests.post(
            url,
            headers=HEADERS,
            files={"file": (filepath.name, f, "text/markdown")},
            data={"purpose": "file-extract"},
        )
    if resp.status_code in (200, 201):
        data = resp.json()
        file_id = data.get("id") or data.get("file_id")
        print(f"  ✅ 上传成功: {filepath.name}  →  file_id={file_id}")
        return file_id
    else:
        print(f"  ❌ 上传失败: {filepath.name}  status={resp.status_code}  body={resp.text[:200]}")
        return None


def add_to_index(file_id: str, filename: str) -> bool:
    """Step 2: 将 file_id 添加到知识库 index"""
    url = f"{BASE_URL}/indices/{INDEX_ID}/documents"
    payload = {
        "documents": [
            {
                "id": file_id,
                "title": filename,
            }
        ]
    }
    resp = requests.post(
        url,
        headers={**HEADERS, "Content-Type": "application/json"},
        data=json.dumps(payload),
    )
    if resp.status_code in (200, 201):
        print(f"  ✅ 已加入知识库: {filename}")
        return True
    else:
        print(f"  ❌ 加入知识库失败: {filename}  status={resp.status_code}  body={resp.text[:300]}")
        return False


def add_text_to_index(filepath: Path) -> bool:
    """备用方案：直接以文本内容 + 标题方式添加到知识库（部分 API 版本支持）"""
    url = f"{BASE_URL}/indices/{INDEX_ID}/documents"
    content = filepath.read_text(encoding="utf-8")
    payload = {
        "documents": [
            {
                "title": filepath.stem,
                "content": content,
            }
        ]
    }
    resp = requests.post(
        url,
        headers={**HEADERS, "Content-Type": "application/json"},
        data=json.dumps(payload),
    )
    if resp.status_code in (200, 201):
        print(f"  ✅ 直传成功: {filepath.name}")
        return True
    else:
        print(f"  ❌ 直传失败: {filepath.name}  status={resp.status_code}  body={resp.text[:300]}")
        return False


def main():
    md_files = sorted(KB_DIR.glob("*.md"))
    print(f"找到 {len(md_files)} 个 Markdown 文件，开始上传...\n")

    success, fail = 0, 0

    for md in md_files:
        print(f"[{md.name}]")

        # 先尝试两步法（上传文件 → 加入知识库）
        file_id = upload_file(md)
        if file_id:
            ok = add_to_index(file_id, md.stem)
            if ok:
                success += 1
            else:
                fail += 1
        else:
            # 备用：直接传文本内容
            print(f"  ↩ 尝试直传文本内容...")
            ok = add_text_to_index(md)
            if ok:
                success += 1
            else:
                fail += 1

        time.sleep(0.5)   # 避免限速

    print(f"\n完成：成功 {success} 个，失败 {fail} 个")


if __name__ == "__main__":
    main()
