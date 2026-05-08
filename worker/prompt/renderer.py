def render_prompt(template: str, params: dict) -> str:
    rendered = template
    for key, value in params.items():
        rendered = rendered.replace(f'{{{{{key}}}}}', str(value))
    return rendered
