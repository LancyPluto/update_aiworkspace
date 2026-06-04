from fastapi.testclient import TestClient

from app.main import create_app


class FakeRuntime:
    pass


def test_parse_file_returns_chunks():
    app = create_app(runtime=FakeRuntime(), execution_mode="sync", verify_signature=False)
    client = TestClient(app)

    response = client.post(
        "/internal/v1/files/parse",
        json={
            "filename": "product.txt",
            "contentType": "text/plain",
            "contentBase64": "UHJpY2luZzogUHJvIHBsYW4gaW5jbHVkZXMgdW5saW1pdGVkIGV4cG9ydHMuCgpTZWN1cml0eTogU1NPIGFuZCBhdWRpdCBsb2dzIGFyZSBpbmNsdWRlZC4=",
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["chunks"][0]["chunkIndex"] == 0
    assert "Pricing" in body["chunks"][0]["content"]
    assert body["chunks"][0]["metadata"]["source"] == "product.txt"


def test_parse_image_returns_context_text():
    app = create_app(runtime=FakeRuntime(), execution_mode="sync", verify_signature=False)
    client = TestClient(app)

    response = client.post(
        "/internal/v1/files/parse",
        json={
            "filename": "frame.png",
            "contentType": "image/png",
            "contentBase64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert "用户已上传图片" in body["text"]
    assert body["chunks"]
