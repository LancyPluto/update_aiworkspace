import re


VARIABLE_PATTERN = re.compile(r"\{\{\s*([a-zA-Z0-9_]+)\s*\}\}")


class PromptRenderError(ValueError):
    pass


def render_prompt(template: str, params: dict) -> str:
    missing_keys = []

    def replace(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in params or params[key] is None:
            missing_keys.append(key)
            return match.group(0)
        return str(params[key])

    rendered = VARIABLE_PATTERN.sub(replace, template)
    if missing_keys:
        missing_text = ", ".join(sorted(set(missing_keys)))
        raise PromptRenderError(f"missing prompt variables: {missing_text}")
    return rendered
