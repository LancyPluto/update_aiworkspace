from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from config import settings

try:
    import oss2
except ImportError:  # pragma: no cover
    oss2 = None


class AssetStorageError(RuntimeError):
    pass


@dataclass(slots=True)
class AssetStorage:
    provider: str
    local_root: Path
    public_base_url: str
    oss_endpoint: str
    oss_bucket_name: str
    oss_access_key_id: str
    oss_access_key_secret: str
    oss_key_prefix: str
    _bucket_client: object | None = None

    @classmethod
    def from_settings(cls) -> AssetStorage:
        provider = (os.getenv("ASSET_STORAGE_PROVIDER") or "local").strip().lower()
        public_base_url = (
            os.getenv("ASSET_STORAGE_PUBLIC_BASE_URL")
            or settings.generated_media_public_base_url
            or "/generated"
        ).rstrip("/")
        oss_prefix = (os.getenv("OSS_KEY_PREFIX") or "").strip().replace("\\", "/")
        while oss_prefix.startswith("/"):
            oss_prefix = oss_prefix[1:]
        if oss_prefix and not oss_prefix.endswith("/"):
            oss_prefix += "/"
        storage = cls(
            provider=provider,
            local_root=Path(settings.generated_media_dir).resolve(),
            public_base_url=public_base_url,
            oss_endpoint=(os.getenv("OSS_ENDPOINT") or "").strip(),
            oss_bucket_name=(
                os.getenv("OSS_PRIVATE_BUCKET")
                or os.getenv("OSS_BUCKET")
                or ""
            ).strip(),
            oss_access_key_id=(
                os.getenv("OSS_ACCESS_KEY_ID")
                or os.getenv("ALIYUN_ACCESS_KEY_ID")
                or os.getenv("ALIYUN_CAPTCHA_ACCESS_KEY_ID")
                or ""
            ).strip(),
            oss_access_key_secret=(
                os.getenv("OSS_ACCESS_KEY_SECRET")
                or os.getenv("ALIYUN_ACCESS_KEY_SECRET")
                or os.getenv("ALIYUN_CAPTCHA_ACCESS_KEY_SECRET")
                or ""
            ).strip(),
            oss_key_prefix=oss_prefix,
        )
        storage._ensure_oss_client()
        return storage

    @property
    def is_oss(self) -> bool:
        return self.provider == "oss"

    def put_bytes(self, relative_key: str, data: bytes, content_type: str | None = None) -> str:
        key = self._normalize_relative_key(relative_key)
        if self.is_oss:
            return self._put_oss(key, data, content_type)
        return self._put_local(key, data)

    def public_url(self, relative_key: str) -> str:
        key = self._normalize_relative_key(relative_key)
        if self.public_base_url.startswith("http://") or self.public_base_url.startswith("https://"):
            return f"{self.public_base_url}/{key}"
        return f"{self.public_base_url}/{key}"

    def local_path(self, relative_key: str) -> Path:
        key = self._normalize_relative_key(relative_key)
        target = (self.local_root / key).resolve()
        if not str(target).startswith(str(self.local_root)):
            raise AssetStorageError("invalid asset path")
        return target

    def ensure_local_parent(self, relative_key: str) -> Path:
        target = self.local_path(relative_key)
        target.parent.mkdir(parents=True, exist_ok=True)
        return target

    def _put_local(self, relative_key: str, data: bytes) -> str:
        target = self.ensure_local_parent(relative_key)
        try:
            target.write_bytes(data)
        except OSError as exc:
            raise AssetStorageError(f"write asset failed: {exc}") from exc
        return self.public_url(relative_key)

    def _put_oss(self, relative_key: str, data: bytes, content_type: str | None) -> str:
        self._ensure_oss_client()
        object_key = f"{self.oss_key_prefix}{relative_key}"
        headers = {}
        if content_type:
            headers["Content-Type"] = content_type
        try:
            self._bucket_client.put_object(object_key, data, headers=headers or None)
        except Exception as exc:  # pragma: no cover - network
            raise AssetStorageError(f"oss upload failed: {exc}") from exc
        return self.public_url(relative_key)

    def _ensure_oss_client(self) -> None:
        if not self.is_oss:
            return
        if self._bucket_client is not None:
            return
        if oss2 is None:
            raise AssetStorageError("oss2 package is required when ASSET_STORAGE_PROVIDER=oss")
        if not all([self.oss_endpoint, self.oss_bucket_name, self.oss_access_key_id, self.oss_access_key_secret]):
            raise AssetStorageError(
                "ASSET_STORAGE_PROVIDER=oss requires OSS_ENDPOINT, OSS_BUCKET, OSS_ACCESS_KEY_ID, OSS_ACCESS_KEY_SECRET"
            )
        endpoint = self.oss_endpoint
        if not endpoint.startswith("http://") and not endpoint.startswith("https://"):
            endpoint = f"https://{endpoint}"
        auth = oss2.Auth(self.oss_access_key_id, self.oss_access_key_secret)
        self._bucket_client = oss2.Bucket(auth, endpoint, self.oss_bucket_name)

    @staticmethod
    def _normalize_relative_key(relative_key: str) -> str:
        normalized = (relative_key or "").strip().replace("\\", "/")
        while normalized.startswith("/"):
            normalized = normalized[1:]
        if not normalized or ".." in normalized:
            raise AssetStorageError("invalid asset path")
        return normalized


asset_storage = AssetStorage.from_settings()
