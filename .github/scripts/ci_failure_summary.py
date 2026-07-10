#!/usr/bin/env python3
"""Create a concise GitHub Actions failure summary from CI logs and reports."""

from __future__ import annotations

import argparse
import html
import os
import re
import smtplib
import sys
import xml.etree.ElementTree as ET
from email.message import EmailMessage
from pathlib import Path


ROOT = Path.cwd()

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return ""


def last_lines(text: str, limit: int = 80) -> str:
    return "\n".join(text.splitlines()[-limit:])


def extract_error_hints(log_text: str) -> list[str]:
    hints: list[str] = []
    patterns = [
        r"Error:\s+[^\n]+",
        r"\[ERROR\]\s+[^\n]+",
        r"FAILED\s+[^\n]+",
        r"Failures:\s*(?:\n|\r\n)(?:Error:\s+)?\s*[^\n]+",
        r"\w+(?:Error|Exception):\s+[^\n]+",
        r"Status expected:<[^\n]+",
        r"AssertionError:\s+[^\n]+",
        r"npm ERR!\s+[^\n]+",
        r"Module not found:\s+[^\n]+",
    ]
    for pattern in patterns:
        for match in re.finditer(pattern, log_text, flags=re.MULTILINE):
            line = match.group(0).strip()
            if line not in hints:
                hints.append(line)
            if len(hints) >= 12:
                return hints
    return hints


def parse_surefire_reports(base: Path) -> list[str]:
    reports: list[str] = []
    for xml_path in sorted(base.glob("**/target/surefire-reports/TEST-*.xml")):
        try:
            tree = ET.parse(xml_path)
        except ET.ParseError:
            continue
        suite = tree.getroot()
        for case in suite.findall("testcase"):
            failure = case.find("failure")
            error = case.find("error")
            node = failure if failure is not None else error
            if node is None:
                continue
            classname = case.attrib.get("classname", "unknown")
            name = case.attrib.get("name", "unknown")
            message = (node.attrib.get("message") or (node.text or "")).strip()
            message = " ".join(message.split())[:500]
            reports.append(f"- `{classname}#{name}`: {message}")
    return reports


def detect_likely_cause(log_text: str, surefire: list[str]) -> str:
    lower = log_text.lower()
    if "redisconnection" in lower or "unable to connect to redis" in lower:
        return "疑似 Redis 依赖/连接问题：CI runner 未启动 Redis，或测试环境仍访问 localhost:6379。"
    if "connection refused" in lower and "5672" in lower:
        return "疑似 RabbitMQ 依赖/连接问题：CI runner 未启动 RabbitMQ，或测试环境仍访问 localhost:5672。"
    if surefire or "status expected:<" in lower:
        return "后端测试断言失败：优先查看 Surefire 失败用例和断言信息。"
    if "failed" in lower and "pytest" in lower:
        return "Python pytest 失败：优先查看 FAILED 用例和 traceback。"
    if "npm err!" in lower or "failed to compile" in lower:
        return "前端安装/构建失败：优先查看 npm 错误、TypeScript/Vite 编译错误。"
    return "未能自动判断根因；请查看关键错误和日志尾部。"


def build_summary(job_name: str, log_path: Path, output_path: Path, title: str | None = None) -> str:
    log_text = read_text(log_path)
    surefire = parse_surefire_reports(ROOT)
    hints = extract_error_hints(log_text)
    likely = detect_likely_cause(log_text, surefire)
    run_url = (
        os.getenv("GITHUB_SERVER_URL", "https://github.com")
        + "/"
        + os.getenv("GITHUB_REPOSITORY", "")
        + "/actions/runs/"
        + os.getenv("GITHUB_RUN_ID", "")
    )

    lines = [
        f"## {title or 'CI 失败摘要'}",
        "",
        f"- Job：`{job_name}`",
        f"- Workflow：`{os.getenv('GITHUB_WORKFLOW', '')}`",
        f"- 分支/提交：`{os.getenv('GITHUB_REF_NAME', '')}` / `{os.getenv('GITHUB_SHA', '')[:12]}`",
        f"- 运行链接：{run_url}",
        f"- 自动判断：{likely}",
        "",
    ]

    if surefire:
        lines.append("### Surefire 失败用例")
        lines.extend(surefire[:20])
        lines.append("")

    if hints:
        lines.append("### 关键错误")
        for hint in hints[:12]:
            lines.append(f"- `{hint}`")
        lines.append("")

    lines.extend([
        "### 日志尾部",
        "```text",
        last_lines(log_text, 100),
        "```",
        "",
    ])

    summary = "\n".join(lines)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(summary, encoding="utf-8")
    return summary


def send_email(summary_dir: Path) -> int:
    host = os.getenv("CI_SUMMARY_SMTP_HOST")
    port = int(os.getenv("CI_SUMMARY_SMTP_PORT", "587"))
    username = os.getenv("CI_SUMMARY_SMTP_USERNAME")
    password = os.getenv("CI_SUMMARY_SMTP_PASSWORD")
    mail_from = os.getenv("CI_SUMMARY_MAIL_FROM") or username
    mail_to = os.getenv("CI_SUMMARY_MAIL_TO")
    if not all([host, username, password, mail_from, mail_to]):
        print("CI summary mail secrets are incomplete; skip email notification.")
        return 0

    parts = [read_text(path) for path in sorted(summary_dir.glob("**/*.md"))]
    body = "\n\n---\n\n".join(parts) or "CI failed, but no summary artifact was found."

    msg = EmailMessage()
    msg["Subject"] = f"CI 失败摘要 - {os.getenv('GITHUB_REPOSITORY', '')} #{os.getenv('GITHUB_RUN_NUMBER', '')}"
    msg["From"] = mail_from
    msg["To"] = mail_to
    msg.set_content(body)
    msg.add_alternative("<pre>" + html.escape(body) + "</pre>", subtype="html")

    with smtplib.SMTP(host, port, timeout=30) as smtp:
        smtp.starttls()
        smtp.login(username, password)
        smtp.send_message(msg)
    print("CI failure summary email sent.")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("build")
    build.add_argument("--job", required=True)
    build.add_argument("--log", required=True)
    build.add_argument("--out", required=True)
    build.add_argument("--title")
    mail = sub.add_parser("mail")
    mail.add_argument("--summary-dir", required=True)
    args = parser.parse_args(argv)

    if args.command == "build":
        summary = build_summary(args.job, Path(args.log), Path(args.out), args.title)
        github_step_summary = os.getenv("GITHUB_STEP_SUMMARY")
        if github_step_summary:
            with open(github_step_summary, "a", encoding="utf-8") as fh:
                fh.write(summary + "\n")
        print(summary)
        return 0
    if args.command == "mail":
        return send_email(Path(args.summary_dir))
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
