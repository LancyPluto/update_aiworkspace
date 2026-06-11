#!/usr/bin/env python3
"""Apply comic drama form fields with valid UTF-8."""

from __future__ import annotations

import json
import subprocess

FIELDS = [
    ("storyTheme", "漫剧主题", "text", "例如：穿越后我靠 AI 开店逆袭", None, 1, 1),
    ("genre", "题材类型", "select", "选择漫剧题材", ["都市逆袭", "甜宠恋爱", "悬疑反转", "科幻脑洞"], 2, 0),
    ("plotOutline", "剧情梗概（可选）", "textarea", "留空则由大模型自动生成剧本与分镜", None, 3, 0),
    ("visualStyle", "画风风格", "select", "选择画面风格", ["电影感写实", "国漫厚涂", "日漫赛璐璐", "Q 版轻喜剧"], 4, 0),
    ("aspectRatio", "画面比例", "select", "选择画幅", ["16:9", "9:16", "1:1"], 5, 0),
    ("episodeLength", "单集时长", "select", "选择目标时长", ["30s", "60s", "90s"], 6, 0),
]


def _sql_literal(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def main() -> None:
    sql = """
DELETE i FROM tool_field_schema_items i
JOIN tool_field_schemas s ON s.id = i.schema_id
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1.0.0';
"""
    for field_key, field_name, field_type, placeholder, options, sort_order, required in FIELDS:
        options_json = "NULL" if not options else _sql_literal(json.dumps(options, ensure_ascii=False))
        sql += f"""
INSERT INTO tool_field_schema_items (
  schema_id, field_key, field_name, field_type, placeholder, options_json, validation_json,
  required, execution_required, user_required, default_value, agent_fill_strategy, risk_level, sort_order, status
)
SELECT s.id, {_sql_literal(field_key)}, {_sql_literal(field_name)}, {_sql_literal(field_type)},
       {_sql_literal(placeholder)}, {options_json}, NULL,
       {required}, 0, 0, NULL, 'default', 'LOW', {sort_order}, 'ACTIVE'
FROM tool_field_schemas s
JOIN ai_tools t ON t.id = s.tool_id
WHERE t.tool_code = 'ai_comic_drama_agent' AND s.schema_version = 'v1.0.0';
"""
    subprocess.run(
        [
            "docker", "exec", "-i", "ai-supermarket-mysql",
            "mysql", "-uroot", "-proot123456", "ai_supermarket_v1",
        ],
        input=sql.encode("utf-8"),
        check=True,
    )
    print(f"applied comic fields: count={len(FIELDS)}")


if __name__ == "__main__":
    main()
