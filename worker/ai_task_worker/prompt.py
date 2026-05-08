import re
from typing import Any, Dict, List, Tuple

_VAR_PATTERN = re.compile(r"\{\{\s*([a-zA-Z0-9_]+)\s*\}\}")


def extract_variables(template: str) -> List[str]:
    return _VAR_PATTERN.findall(template)


def render_user_prompt(template: str, params: Dict[str, Any]) -> Tuple[str, List[str]]:
    """
    将 userPromptTemplate 中的 {{key}} 替换为 params[key]。
    返回 (渲染结果, 缺失的变量名列表)。
    """
    missing: List[str] = []

    def repl(match: re.Match) -> str:
        key = match.group(1)
        if key not in params:
            missing.append(key)
            return match.group(0)
        val = params[key]
        if val is None:
            missing.append(key)
            return match.group(0)
        return str(val)

    rendered = _VAR_PATTERN.sub(repl, template)
    return rendered, missing
