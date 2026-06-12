from pathlib import Path

from storage.asset_storage import AssetStorage


def test_local_put_bytes_returns_generated_url(tmp_path):
    storage = AssetStorage(
        provider="local",
        local_root=tmp_path.resolve(),
        public_base_url="/generated",
        oss_endpoint="",
        oss_bucket_name="",
        oss_access_key_id="",
        oss_access_key_secret="",
        oss_key_prefix="",
    )
    url = storage.put_bytes("images/1/image-1.png", b"png-bytes", "image/png")

    assert url == "/generated/images/1/image-1.png"
    assert Path(tmp_path / "images/1/image-1.png").read_bytes() == b"png-bytes"
