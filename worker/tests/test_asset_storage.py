from pathlib import Path

from storage.asset_storage import AssetStorage


class FakeBucket:
    def __init__(self):
        self.calls = []

    def put_object(self, key, data, headers=None):
        self.calls.append((key, data, headers))


def test_local_put_bytes_returns_generated_url(tmp_path):
    storage = AssetStorage(
        provider="local",
        local_root=tmp_path.resolve(),
        public_base_url="/generated",
        private_base_url="/generated",
        image_transform_options="",
        public_cache_control="public,max-age=31536000,immutable",
        private_cache_control="private,max-age=3600",
        legacy_cache_control="public,max-age=300,must-revalidate",
        oss_endpoint="",
        oss_bucket_name="",
        oss_public_bucket_name="",
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
        image_transform_options="",
        public_cache_control="public,max-age=31536000,immutable",
        private_cache_control="private,max-age=3600",
        legacy_cache_control="public,max-age=300,must-revalidate",
        oss_endpoint="oss-cn-guangzhou.aliyuncs.com",
        oss_bucket_name="wlcloudai-assets-private",
        oss_public_bucket_name="wlcloudai-assets-public",
        oss_access_key_id="test-ak",
        oss_access_key_secret="test-sk",
        oss_key_prefix="",
    )

    assert storage.public_url("images/71/image-1.png") == "/api/v1/assets/private/images/71/image-1.png"
    assert storage.public_url("audio/71/voice.mp3") == "/api/v1/assets/private/audio/71/voice.mp3"
    assert storage.public_url("video/71/video-1.mp4") == "/api/v1/assets/private/video/71/video-1.mp4"


def test_local_put_bytes_public_uses_hash_filename(tmp_path):
    storage = AssetStorage(
        provider="local",
        local_root=tmp_path.resolve(),
        public_base_url="/generated",
        private_base_url="/generated",
        image_transform_options="",
        public_cache_control="public,max-age=31536000,immutable",
        private_cache_control="private,max-age=3600",
        legacy_cache_control="public,max-age=300,must-revalidate",
        oss_endpoint="",
        oss_bucket_name="",
        oss_public_bucket_name="",
        oss_access_key_id="",
        oss_access_key_secret="",
        oss_key_prefix="",
    )
    url = storage.put_bytes_public("images/1/image-1.png", b"png-bytes", "image/png")

    assert "image-1" not in url
    assert url.startswith("/generated/images/1/")
    assert url.endswith(".png")
    assert len(url.split("/")[-1]) == 44  # 40 hex + .png


def test_cdn_url_adds_image_transform_for_images():
    storage = AssetStorage(
        provider="oss",
        local_root=Path("."),
        public_base_url="https://cdn.wlcloudai.com",
        private_base_url="/api/v1/assets/private",
        image_transform_options="image/format,webp/quality,Q_85",
        public_cache_control="public,max-age=31536000,immutable",
        private_cache_control="private,max-age=3600",
        legacy_cache_control="public,max-age=300,must-revalidate",
        oss_endpoint="oss-cn-guangzhou.aliyuncs.com",
        oss_bucket_name="wlcloudai-assets-private",
        oss_public_bucket_name="wlcloudai-assets-public",
        oss_access_key_id="test-ak",
        oss_access_key_secret="test-sk",
        oss_key_prefix="",
    )
    assert storage.cdn_url("images/1/abc.png") == "https://cdn.wlcloudai.com/images/1/abc.png?x-oss-process=image/format,webp/quality,Q_85"
    assert storage.cdn_url("video/1/v.mp4") == "https://cdn.wlcloudai.com/video/1/v.mp4"


def test_content_hash_key_deterministic():
    key1 = AssetStorage._content_hash_key("images/1/image-1.png", b"hello")
    key2 = AssetStorage._content_hash_key("images/1/image-1.png", b"hello")
    key3 = AssetStorage._content_hash_key("images/1/image-1.png", b"world")
    assert key1 == key2
    assert key1 != key3
    assert key1.startswith("images/1/")
    assert key1.endswith(".png")
    assert len(key1.split("/")[-1]) == 44  # 40 hex + .png


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


def test_oss_cache_control_separates_private_hashed_and_legacy_objects(tmp_path):
    storage = AssetStorage(
        provider="oss",
        local_root=tmp_path.resolve(),
        public_base_url="https://cdn.wlcloudai.com",
        private_base_url="/api/v1/assets/private",
        image_transform_options="",
        public_cache_control="public,max-age=31536000,immutable",
        private_cache_control="private,max-age=3600",
        legacy_cache_control="public,max-age=300,must-revalidate",
        oss_endpoint="oss-cn-guangzhou.aliyuncs.com",
        oss_bucket_name="private",
        oss_public_bucket_name="public",
        oss_access_key_id="test-ak",
        oss_access_key_secret="test-sk",
        oss_key_prefix="",
    )
    private_bucket = FakeBucket()
    public_bucket = FakeBucket()
    storage._bucket_client = private_bucket
    storage._public_bucket_client = public_bucket

    storage._put_oss("uploads/input.png", b"private", "image/png")
    storage._put_oss_public("images/" + "a" * 40 + ".png", b"public", "image/png")
    storage._put_oss_public("images/legacy.png", b"legacy", "image/png")

    assert private_bucket.calls[0][2]["Cache-Control"] == "private,max-age=3600"
    assert public_bucket.calls[0][2]["Cache-Control"] == "public,max-age=31536000,immutable"
    assert public_bucket.calls[1][2]["Cache-Control"] == "public,max-age=300,must-revalidate"
