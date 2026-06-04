from io import BytesIO

import httpx
import requests
from requests import Request, Session


def inspect_openai_sdk() -> None:
    captured: dict[str, object] = {}

    class CaptureTransport(httpx.BaseTransport):
        def handle_request(self, request: httpx.Request) -> httpx.Response:
            captured["content_type"] = request.headers.get("content-type", "")
            captured["body"] = request.read()
            return httpx.Response(200, json={"data": [{"b64_json": "x"}]})

    from openai import OpenAI

    client = OpenAI(
        api_key="sk-test",
        base_url="https://api.ofox.ai/v1",
        http_client=httpx.Client(transport=CaptureTransport()),
    )
    client.images.edit(
        model="openai/gpt-image-2",
        image=("t.png", BytesIO(b"fakepng"), "image/png"),
        prompt="hello",
        size="auto",
        quality="low",
    )
    body = captured["body"]
    assert isinstance(body, bytes)
    print("=== openai sdk ===")
    print("content-type:", str(captured["content_type"])[:120])
    print("has model name field:", b'name="model"' in body)
    print("has model value:", b"openai/gpt-image-2" in body)
    for line in body.split(b"\r\n"):
        if b"model" in line.lower() and len(line) < 120:
            print("line:", line)


def inspect_requests_session() -> None:
    session = Session()
    session.headers.update(
        {
            "Authorization": "Bearer x",
            "Connection": "close",
            "Content-Type": "application/json",
        }
    )
    multipart = [
        ("model", (None, "openai/gpt-image-2")),
        ("prompt", (None, "hello")),
        ("size", (None, "auto")),
        ("quality", (None, "low")),
        ("image", ("t.png", b"fake", "image/png")),
    ]
    req = Request("POST", "https://api.ofox.ai/v1/images/edits", files=multipart)
    prepped = session.prepare_request(req)
    body = prepped.body
    assert isinstance(body, bytes)
    print("=== requests session files=list ===")
    print("content-type:", prepped.headers.get("Content-Type", "")[:120])
    print("has model name field:", b'name="model"' in body)
    print("has model value:", b"openai/gpt-image-2" in body)
    for line in body.split(b"\r\n"):
        if b"model" in line.lower() and len(line) < 120:
            print("line:", line)


def inspect_requests_data_and_files() -> None:
    session = Session()
    session.headers.update(
        {
            "Authorization": "Bearer x",
            "Connection": "close",
            "Content-Type": "application/json",
        }
    )
    req = Request(
        "POST",
        "https://api.ofox.ai/v1/images/edits",
        data={
            "model": "openai/gpt-image-2",
            "prompt": "hello",
            "size": "auto",
            "quality": "low",
        },
        files={"image": ("t.png", b"fake", "image/png")},
    )
    prepped = session.prepare_request(req)
    body = prepped.body
    assert isinstance(body, bytes)
    print("=== requests data+files ===")
    print("content-type:", prepped.headers.get("Content-Type", "")[:120])
    print("has model name field:", b'name="model"' in body)
    print("has model value:", b"openai/gpt-image-2" in body)


if __name__ == "__main__":
    inspect_openai_sdk()
    inspect_requests_session()
    inspect_requests_data_and_files()
