from typing import Any


DEFAULT_SYSTEM_PROMPT = (
    "你是一个社交媒体评论洞察分析师，擅长围绕产品、服务或目标人群，从小红书、抖音、微博、B站、知乎等公开社交内容中提炼用户真实反馈、购买顾虑、使用场景和产品机会。"
    "你必须区分已检索到的信息、用户提供的评论样本和推断结论。"
    "如果当前模型环境不能联网或不能访问平台评论，请明确说明限制，并基于用户输入给出可验证的调研框架，不能编造具体评论、热度、账号、链接或数据。"
)


def build_prompt_payload(context: dict[str, Any]) -> dict[str, str]:
    params = context.get("params") or {}
    return {
        "system_prompt": _build_system_prompt(context.get("systemPrompt", "")),
        "user_prompt": _build_user_prompt(params),
    }


def _build_system_prompt(system_prompt: str) -> str:
    normalized = (system_prompt or "").strip()
    return normalized or DEFAULT_SYSTEM_PROMPT


def _build_user_prompt(params: dict[str, Any]) -> str:
    product_name = _value(params, "productName")
    target_audience = _value(params, "targetAudience")
    platforms = _value(params, "platforms") or "小红书、抖音"
    analysis_goal = _value(params, "analysisGoal") or "提炼用户期望和产品改进建议"
    region = _value(params, "region")
    source_urls = _value(params, "sourceUrls")
    comment_samples = _value(params, "commentSamples")
    extra_info = _value(params, "extraInfo")

    lines = [
        "请围绕以下对象做一次社交媒体评论洞察分析：",
        "",
        f"- 产品/服务/品牌名称：{product_name}",
        f"- 目标人群：{target_audience}",
        f"- 重点平台：{platforms}",
        f"- 分析目标：{analysis_goal}",
    ]
    if region:
        lines.append(f"- 关注地区：{region}")
    if source_urls:
        lines.append(f"- 用户提供的公开链接/关键词线索：{source_urls}")
    if comment_samples:
        lines.append(f"- 用户粘贴的评论样本：{comment_samples}")
    if extra_info:
        lines.append(f"- 补充背景：{extra_info}")

    lines.extend(
        [
            "",
            "工作要求：",
            "1. 优先尝试基于可用的联网检索能力查看公开网页、搜索结果、平台外显内容或用户提供的评论样本。",
            "2. 不要绕过平台登录、反爬、隐私限制或抓取非公开数据；如果无法直接访问小红书/抖音评论，要明确写出限制。",
            "3. 每条事实尽量标注来源；没有来源的内容只能作为推断或待验证假设。",
            "4. 重点提炼评论里的需求、痛点、担忧、购买触发点、使用场景、竞品比较和改进建议。",
            "5. 结论要面向产品或运营决策，避免泛泛而谈。",
            "",
            "请严格按照下面 Markdown 结构输出：",
            f"# {product_name or '目标产品'} 社交媒体评论洞察报告",
            "## 1. 检索范围与可用性说明",
            "说明本次覆盖的平台、关键词、链接、评论样本来源，以及是否存在无法联网或无法访问平台评论的限制。",
            "",
            "## 2. 评论信号摘要",
            "用 5-8 条要点总结用户讨论中的核心信号，区分正向反馈、负向反馈和中性问题。",
            "",
            "## 3. 用户期望与购买动机",
            "提炼用户真正期待解决的问题、购买触发点、理想使用场景和决策因素。",
            "",
            "## 4. 高频痛点与顾虑",
            "列出用户常见不满、疑虑、风险感知、价格/质量/效果/服务等方面的阻力。",
            "",
            "## 5. 平台差异洞察",
            "分别说明小红书、抖音等平台上用户表达方式、内容偏好和转化线索的差异；没有可靠数据时写待验证。",
            "",
            "## 6. 产品与运营建议",
            "给出 6-10 条可执行建议，每条包含建议、依据、适用场景和验证方式。",
            "",
            "## 7. 内容选题与话术方向",
            "给出适合社媒运营的选题、评论区回复话术和种草/短视频切入角度。",
            "",
            "## 8. 待验证问题清单",
            "列出下一步需要真实抓取、人工抽样或平台后台验证的问题。",
            "",
            "## 9. 来源与样本记录",
            "列出使用到的链接、平台、搜索关键词、用户提供样本或说明来源待补充。",
        ]
    )
    return "\n".join(lines)


def _value(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if value is None:
        return ""
    return str(value).strip()
