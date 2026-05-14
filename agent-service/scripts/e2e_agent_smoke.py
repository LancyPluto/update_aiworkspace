"""End-to-end smoke test for the agent runtime.

Run this inside the `ai-supermarket-agent-service` container where it has
network access to `backend:8080`:

    docker exec -i ai-supermarket-agent-service python /app/scripts/e2e_agent_smoke.py

The script:
  1. Registers a fresh user and grants enough credit (via admin endpoints
     are not used; we just rely on the default new-user grant).
  2. Creates an agent session and sends a Xiaohongshu copywriting request.
  3. Polls the run event stream, auto-confirms tool calls, and prints the
     final run status plus the event-type sequence.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from typing import Any


BACKEND_BASE_URL = os.environ.get("BACKEND_BASE_URL", "http://backend:8080")
DEFAULT_PROMPT = "请帮我生成一篇小红书文案，topic: 夏日防晒喷雾"
RUN_TIMEOUT_SECONDS = 180
POLL_INTERVAL_SECONDS = 1.5


def http_request(method: str, path: str, *, token: str | None = None, body: Any = None) -> dict:
    data = None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(BACKEND_BASE_URL + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        body_text = exc.read().decode("utf-8", errors="ignore")
        raise SystemExit(f"HTTP {exc.code} on {method} {path}: {body_text}") from exc
    return json.loads(payload) if payload else {}


def register_user() -> tuple[str, int]:
    username = f"e2e_smoke_{uuid.uuid4().hex[:8]}"
    payload = {"username": username, "password": "Passw0rd!", "nickname": "E2E"}
    response = http_request("POST", "/api/v1/auth/register", body=payload)
    data = response["data"]
    user = data.get("user") or {}
    return data["accessToken"], int(user.get("id") or user.get("userId"))


def create_session(token: str) -> int:
    response = http_request("POST", "/api/v1/agent/sessions", token=token, body={"title": "smoke session"})
    return int(response["data"]["id"])


def start_run(token: str, session_id: int, prompt: str) -> int:
    payload = {"content": prompt}
    response = http_request("POST", f"/api/v1/agent/sessions/{session_id}/messages", token=token, body=payload)
    return int(response["data"]["runId"])


def fetch_events(token: str, run_id: int, last_event_id: int) -> list[dict]:
    path = f"/api/v1/agent/runs/{run_id}/events?afterEventId={last_event_id}&pageSize=100"
    response = http_request("GET", path, token=token)
    data = response["data"]
    return data.get("list") or data.get("events") or []


def fetch_run(token: str, run_id: int) -> dict:
    response = http_request("GET", f"/api/v1/agent/runs/{run_id}", token=token)
    return response["data"]


def confirm_tool(token: str, run_id: int, tool_code: str) -> None:
    http_request(
        "POST",
        f"/api/v1/agent/runs/{run_id}/tool-confirmations",
        token=token,
        body={"toolCode": tool_code},
    )


def parse_event_payload(event: dict) -> dict:
    raw = event.get("eventJson")
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prompt", default=DEFAULT_PROMPT)
    args = parser.parse_args()

    token, user_id = register_user()
    print(f"[smoke] registered user_id={user_id}")
    session_id = create_session(token)
    print(f"[smoke] session_id={session_id}")
    run_id = start_run(token, session_id, args.prompt)
    print(f"[smoke] run_id={run_id} prompt={args.prompt!r}")

    last_event_id = 0
    confirmed_tools: set[str] = set()
    event_types: list[str] = []
    deadline = time.monotonic() + RUN_TIMEOUT_SECONDS
    final_status: str | None = None

    def drain_events() -> None:
        nonlocal last_event_id
        while True:
            events = fetch_events(token, run_id, last_event_id)
            if not events:
                return
            for event in events:
                last_event_id = max(last_event_id, int(event["id"]))
                event_types.append(event["eventType"])
                payload = parse_event_payload(event)
                print(f"[event] {event['id']:>3} {event['eventType']:30s} {event.get('eventText') or ''}")
                if event["eventType"] == "tool.confirmation_required":
                    tool_code = str(payload.get("toolCode") or "")
                    if tool_code and tool_code not in confirmed_tools:
                        confirmed_tools.add(tool_code)
                        print(f"[smoke] auto-confirming tool {tool_code}")
                        confirm_tool(token, run_id, tool_code)
            if len(events) < 100:
                return

    while time.monotonic() <= deadline:
        drain_events()
        run = fetch_run(token, run_id)
        status = run.get("status")
        if status in {"SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"}:
            time.sleep(0.5)
            drain_events()
            final_status = status
            break
        time.sleep(POLL_INTERVAL_SECONDS)

    if final_status is None:
        print("[smoke] run did not complete within timeout", file=sys.stderr)
        return 2

    started_count = sum(1 for t in event_types if t == "tool.started")
    dispatched_count = sum(1 for t in event_types if t == "tool.task_dispatched")
    progress_count = sum(1 for t in event_types if t == "tool.task_progress")
    finished_count = sum(1 for t in event_types if t == "tool.finished")

    summary = {
        "runId": run_id,
        "finalStatus": final_status,
        "eventTypes": event_types,
        "counts": {
            "tool.started": started_count,
            "tool.task_dispatched": dispatched_count,
            "tool.task_progress": progress_count,
            "tool.finished": finished_count,
        },
    }
    print("[smoke] summary:")
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    if final_status != "SUCCESS":
        return 3
    if started_count != 1:
        print(f"[smoke] expected exactly 1 tool.started event, got {started_count}", file=sys.stderr)
        return 4
    if dispatched_count < 1:
        print("[smoke] expected at least 1 tool.task_dispatched event", file=sys.stderr)
        return 5
    if finished_count < 1:
        print("[smoke] expected at least 1 tool.finished event", file=sys.stderr)
        return 6
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
