from pathlib import Path

from storage.asset_storage import AssetStorage


def test_local_put_bytes_returns_generated_url(tmp_path):
    storage = AssetStorage(
        provider="local",
        local_root=tmp_path.resolve(),
        public_base_url="/generated",
        private_base_url="/generated",
        oss_endpoint="",
        oss_bucket_name="",
        oss_access_key_id="",
        oss_access_key_secret="",
        oss_key_prefix="",
    )
    url = storage.put_bytes("images/1/image-1.png", b"png-bytes", "image/png")

    assert url == "/generated/images/1/image-1.png"
    assert Path(tmp_path / "images/1/image-1.png").read_bytes() == b"png-bytes"


def test_oss_worker_assets_always_use_private_proxy_url(tmp_path):
    storage = AssetStorage(
        provider="oss",
        local_root=tmp_path.resolve(),
        public_base_url="https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com",
        private_base_url="/api/v1/assets/private",
        oss_endpoint="oss-cn-guangzhou.aliyuncs.com",
        oss_bucket_name="wlcloudai-assets-private",
        oss_access_key_id="test-ak",
        oss_access_key_secret="test-sk",
        oss_key_prefix="",
    )

    assert storage.public_url("images/71/image-1.png") == "/api/v1/assets/private/images/71/image-1.png"
    assert storage.public_url("audio/71/voice.mp3") == "/api/v1/assets/private/audio/71/voice.mp3"
    assert storage.public_url("video/71/video-1.mp4") == "/api/v1/assets/private/video/71/video-1.mp4"


def test_oss_settings_default_private_base_to_backend_proxy(monkeypatch):
    monkeypatch.setenv("ASSET_STORAGE_PROVIDER", "oss")
    monkeypatch.setenv(
        "ASSET_STORAGE_PUBLIC_BASE_URL",
        "https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com",
    )
    monkeypatch.delenv("ASSET_STORAGE_PRIVATE_BASE_URL", raising=False)
    monkeypatch.setenv("OSS_ENDPOINT", "oss-cn-guangzhou.aliyuncs.com")
    monkeypatch.setenv("OSS_PRIVATE_BUCKET", "wlcloudai-assets-private")
    monkeypatch.setenv("OSS_ACCESS_KEY_ID", "test-ak")
    monkeypatch.setenv("OSS_ACCESS_KEY_SECRET", "test-sk")
    monkeypatch.setattr(AssetStorage, "_ensure_oss_client", lambda self: None)

    storage = AssetStorage.from_settings()

    assert storage.private_base_url == "/api/v1/assets/private"
    assert storage.public_url("video/71/video-1.mp4") == "/api/v1/assets/private/video/71/video-1.mp4"
