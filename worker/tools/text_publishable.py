"""把小节 Markdown（## xxx）转成便于复制阅读的纯文本，避免给用户暴露 ## 结构标题。"""

from __future__ import annotations


def strip_blank_join(parts: list[str]) -> str:
    return "\n\n".join(p.strip() for p in parts if p and str(p).strip())


def bracket_section(title: str, body: str) -> str:
    body = str(body or "").strip()
    if not body:
        return ""
    return f"【{title}】\n{body}"


def numbered_lines(items: list[str]) -> str:
    rows: list[str] = []
    i = 1
    for raw in items:
        line = str(raw).strip()
        if line:
            rows.append(f"{i}. {line}")
            i += 1
    return "\n".join(rows)
