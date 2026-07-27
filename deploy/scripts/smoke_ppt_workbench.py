#!/usr/bin/env python3
"""Authenticated production PPT smoke test.

Creates a three-slide project, waits for the automatic chain, validates the
downloaded PPTX as OOXML, and removes the project. The bearer token is read only
from the environment and is never printed.
"""
from __future__ import annotations

import io
import json
import os
import time
import urllib.error
import urllib.request
import uuid
import zipfile

BASE_URL = os.environ.get("PPT_SMOKE_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
TOKEN = os.environ.get("PPT_SMOKE_AUTH_TOKEN", "")
TIMEOUT_SECONDS = int(os.environ.get("PPT_SMOKE_TIMEOUT_SECONDS", "1800"))


def request(method: str, path: str, body=None) -> tuple[dict | None, bytes]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as response:
        raw = response.read()
        content_type = response.headers.get("Content-Type", "")
        return (json.loads(raw) if "json" in content_type else None), raw


def api_data(method: str, path: str, body=None):
    payload, _ = request(method, path, body)
    if not isinstance(payload, dict) or "data" not in payload:
        raise RuntimeError(f"invalid API envelope for {method} {path}")
    return payload["data"]


def main() -> None:
    if not TOKEN:
        raise SystemExit("PPT_SMOKE_AUTH_TOKEN is required")
    marker = uuid.uuid4().hex[:12]
    project_id = None
    try:
        project = api_data(
            "POST",
            "/api/v2/ppt/projects",
            {
                "title": f"Production PPT smoke {marker}",
                "topic": "用三页介绍科创点AI的产品价值、工作流和用户收益",
                "creationType": "idea",
                "language": "zh",
                "aspectRatio": "16:9",
                "pageCount": 3,
                "toolCode": "banana_ppt_generator",
            },
        )
        project_id = int(project["projectId"])
        api_data(
            "POST",
            f"/api/v2/ppt/projects/{project_id}/jobs",
            {
                "jobType": "GENERATE_OUTLINE",
                "clientRequestId": f"prod-smoke-{marker}",
                "payload": {"autoContinue": True},
            },
        )

        deadline = time.monotonic() + TIMEOUT_SECONDS
        export_id = None
        while time.monotonic() < deadline:
            detail = api_data("GET", f"/api/v2/ppt/projects/{project_id}")
            jobs = detail.get("recentJobs") or []
            failed = next(
                (job for job in jobs if job.get("status") in {"FAILED", "CANCELLED"}),
                None,
            )
            if failed:
                error = failed.get("error") or {}
                raise RuntimeError(
                    f"PPT stage failed: {failed.get('jobType')} {error.get('code')}"
                )
            exports = detail.get("exports") or []
            ready = next((item for item in exports if item.get("status") == "READY"), None)
            if ready:
                export_id = int(ready["exportId"])
                break
            time.sleep(3)
        if export_id is None:
            raise TimeoutError("PPT smoke generation timed out")

        _, pptx = request(
            "GET",
            f"/api/v2/ppt/projects/{project_id}/exports/{export_id}/download",
        )
        if not pptx.startswith(b"PK"):
            raise RuntimeError("download is not an OOXML ZIP")
        with zipfile.ZipFile(io.BytesIO(pptx)) as archive:
            names = set(archive.namelist())
            if "[Content_Types].xml" not in names or "ppt/presentation.xml" not in names:
                raise RuntimeError("download is not a valid PPTX package")
        print(f"PPT production smoke passed: project={project_id}, bytes={len(pptx)}")
    finally:
        if project_id is not None:
            try:
                request("DELETE", f"/api/v2/ppt/projects/{project_id}")
            except (OSError, urllib.error.HTTPError):
                pass


if __name__ == "__main__":
    main()
