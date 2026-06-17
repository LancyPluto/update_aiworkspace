import json
import logging
import math
import mimetypes
import os
import socket
import time
from io import BytesIO
from typing import Any
from urllib.parse import urlparse

import requests

from utils.input_image import InputImageError, decode_reference_image_data_url
from volcengine_model import resolve_volcengine_images_paths
from requests.exceptions import (
    ChunkedEncodingError,
    ConnectionError as RequestsConnectionError,
    ConnectTimeout,
    ProxyError,
    ReadTimeout,
    RequestException,
    SSLError,
    Timeout,
)


LOGGER = logging.getLogger(__name__)


class OpenAIImagesError(RuntimeError):
    pass


class OpenAIImagesTimeoutError(OpenAIImagesError):
    pass


class OpenAIImagesRetryableServerError(OpenAIImagesError):
    """5xx gateway responses that are safe to retry within the same task."""


class OpenAIImagesClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        api_key: str | None = None,
        endpoint_path: str | None = None,
        timeout_seconds: int | None = None,
        extra_auth_json: str | None = None,
    ) -> None:
        self.base_url = (base_url or "").rstrip("/")
        self.api_key = (api_key or "").strip()
        self.extra_auth = self._parse_json(extra_auth_json)
        self.timeout = self._resolve_timeout(timeout_seconds)
        endpoint_value = str(endpoint_path or self.extra_auth.get("endpointPath") or "/images/generations")
        edit_endpoint_value = str(self.extra_auth.get("editEndpointPath") or "/images/edits")
        self.endpoint_path, self.edit_endpoint_path = resolve_volcengine_images_paths(
            self.base_url,
            endpoint_value,
            edit_endpoint_value,
        )
        self.ssl_eof_retries = self._resolve_ssl_eof_retries()
        self.connection_retries = self._resolve_connection_retries()
        self.retry_backoff_seconds = _as_float(self.extra_auth.get("retryBackoffSeconds"), 2.0)
        self.retry_backoff_max_seconds = _as_float(self.extra_auth.get("retryBackoffMaxSeconds"), 30.0)
        self.last_usage: dict[str, int] = {}
        self.session = requests.Session()
        self.session.trust_env = _as_bool(self.extra_auth.get("trustEnv"), False)
        # Do not set Content-Type on the session: multipart edits need requests to
        # inject multipart/form-data; a session-level application/json leaks through.
        self.session.headers.update(
            {
                "Authorization": f"Bearer {self.api_key}",
                "Connection": "close",
            }
        )
        proxy_url = str(self.extra_auth.get("proxyUrl") or "").strip()
        if proxy_url:
            self.session.proxies.update({"http": proxy_url, "https": proxy_url})
        elif "trustEnv" not in self.extra_auth:
            env_http_proxy = (os.environ.get("HTTP_PROXY") or os.environ.get("http_proxy") or "").strip()
            env_https_proxy = (os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or "").strip()
            env_proxy = env_http_proxy or env_https_proxy
            if env_proxy:
                self.session.proxies.update(
                    {
                        "http": env_http_proxy or env_proxy,
                        "https": env_https_proxy or env_proxy,
                    }
                )

    def generate_images(
        self,
        *,
        prompt: str,
        model: str | None = None,
        image_size: str = "1024x1024",
        batch_size: int = 1,
        quality: str | None = None,
        style: str | None = None,
        output_format: str | None = None,
        response_format: str | None = None,
        image: str | list[str] | None = None,
        **_: Any,
    ) -> list[str]:
        if not self.base_url:
            raise OpenAIImagesError("openai images baseUrl is not configured")
        if not self.api_key or self.api_key.startswith("replace-with-"):
            raise OpenAIImagesError("openai images API key is not configured")
        if not model:
            raise OpenAIImagesError("openai images modelName is required")

        reference_images = _normalize_reference_images(image)
        if reference_images:
            if self._uses_json_image_array_input():
                payload = self._build_generation_payload(
                    prompt=prompt,
                    model=model,
                    image_size=image_size,
                    batch_size=batch_size,
                    quality=quality,
                    style=style,
                    output_format=output_format,
                    response_format=response_format,
                )
                payload["image"] = reference_images
                LOGGER.info(
                    "openai images json-image request endpoint=%s model=%s n=%s size=%s referenceImages=%s response_format=%s",
                    self.endpoint_path,
                    payload.get("model"),
                    payload.get("n"),
                    payload.get("size"),
                    len(reference_images),
                    _response_format_for_log(payload),
                )
                response = self._post(self.endpoint_path, payload)
            else:
                form_fields, image_files = self._build_edit_multipart(
                    prompt=prompt,
                    model=model,
                    image_size=image_size,
                    batch_size=batch_size,
                    quality=quality,
                    output_format=output_format,
                    reference_images=reference_images,
                )
                endpoint = self.edit_endpoint_path
                transport = "openai-sdk" if _as_bool(self.extra_auth.get("preferSdkEdit"), False) else "requests-multipart"
                LOGGER.info(
                    "openai images edit request endpoint=%s model=%s candidates=%s n=%s size=%s quality=%s output_format=%s transport=%s referenceImages=%s",
                    endpoint,
                    form_fields.get("model"),
                    ",".join(self._edit_model_candidates(model or "")),
                    form_fields.get("n"),
                    form_fields.get("size"),
                    form_fields.get("quality"),
                    form_fields.get("output_format"),
                    transport,
                    len(image_files),
                )
                response = self._edit_reference_image(
                    endpoint,
                    form_fields,
                    image_files,
                    source_model=model,
                )
                payload = {**form_fields, "images": [f"<{transport}>"]}
                responses = [response]
                urls = self._extract_image_urls(response)
                requested_count = max(1, min(10, int(batch_size or 1)))
                if len(urls) < requested_count:
                    LOGGER.warning(
                        "openai images edit returned fewer images than requested requested=%s received=%s endpoint=%s model=%s responseData=%s",
                        requested_count,
                        len(urls),
                        endpoint,
                        form_fields.get("model"),
                        _image_response_data_summary(response),
                    )
                if len(urls) < requested_count and _as_bool(self.extra_auth.get("topUpEditBatch"), False):
                    LOGGER.warning(
                        "openai images edit top-up enabled; issuing additional single-image edit requests requested=%s received=%s endpoint=%s model=%s",
                        requested_count,
                        len(urls),
                        endpoint,
                        form_fields.get("model"),
                    )
                    single_fields = {**form_fields, "n": "1"}
                    while len(urls) < requested_count:
                        top_up_response = self._edit_reference_image(
                            endpoint,
                            single_fields,
                            image_files,
                            source_model=model,
                        )
                        responses.append(top_up_response)
                        urls.extend(self._extract_image_urls(top_up_response))
                urls = urls[:requested_count]
                response = _combine_image_responses(responses)
        else:
            payload = self._build_generation_payload(
                prompt=prompt,
                model=model,
                image_size=image_size,
                batch_size=batch_size,
                quality=quality,
                style=style,
                output_format=output_format,
                response_format=response_format,
            )
            LOGGER.info(
                "openai images request endpoint=%s model=%s n=%s size=%s quality=%s style=%s output_format=%s response_format=%s",
                self.endpoint_path,
                payload.get("model"),
                payload.get("n"),
                payload.get("size"),
                payload.get("quality"),
                payload.get("style"),
                payload.get("output_format"),
                payload.get("response_format"),
            )
            response = self._post(self.endpoint_path, payload)
        urls = self._extract_image_urls(response)
        self.last_usage = self._resolve_usage(response, payload, len(urls))
        return urls

    def _build_generation_payload(
        self,
        *,
        prompt: str,
        model: str,
        image_size: str,
        batch_size: int,
        quality: str | None,
        style: str | None,
        output_format: str | None,
        response_format: str | None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": model,
            "prompt": prompt,
            "n": max(1, min(10, int(batch_size or 1))),
            "size": _normalize_size(image_size),
        }
        resolved_quality = (quality or self.extra_auth.get("quality") or "").strip()
        if resolved_quality:
            payload["quality"] = resolved_quality
        resolved_style = (style or self.extra_auth.get("style") or "").strip()
        if resolved_style:
            payload["style"] = resolved_style
        resolved_output_format = (output_format or self.extra_auth.get("outputFormat") or "").strip()
        if resolved_output_format:
            payload["output_format"] = resolved_output_format
        resolved_response_format = (response_format or self.extra_auth.get("responseFormat") or "").strip()
        if resolved_response_format:
            self._apply_response_format(payload, resolved_response_format)
        if _is_volcengine_ark_base_url(self.base_url):
            payload.setdefault("response_format", "url")
            payload.setdefault("stream", False)
            if "watermark" in self.extra_auth:
                payload["watermark"] = _as_bool(self.extra_auth.get("watermark"), False)
            else:
                payload.setdefault("watermark", False)
            payload.setdefault("sequential_image_generation", "disabled")
            model_name = str(payload.get("model") or "").lower()
            if "seedream-5" in model_name and "guidance_scale" in payload:
                payload.pop("guidance_scale", None)
        return payload

    def _build_edit_multipart(
        self,
        *,
        prompt: str,
        model: str,
        image_size: str,
        batch_size: int,
        quality: str | None,
        output_format: str | None,
        reference_images: list[str],
    ) -> tuple[dict[str, str], list[tuple[str, bytes, str]]]:
        image_files: list[tuple[str, bytes, str]] = []
        for index, reference_image in enumerate(reference_images, start=1):
            try:
                image_bytes, mime = decode_reference_image_data_url(reference_image)
            except InputImageError as exc:
                raise OpenAIImagesError(str(exc)) from exc
            extension = mimetypes.guess_extension(mime) or ".png"
            filename = f"reference-{index}{extension}"
            image_files.append((filename, image_bytes, mime))
        resolved_quality = (quality or self.extra_auth.get("quality") or "low").strip()
        form_fields: dict[str, str] = {
            "model": self._resolve_edit_model(model),
            "prompt": prompt,
            "n": str(max(1, min(10, int(batch_size or 1)))),
            "size": self._resolve_edit_size(image_size),
            "quality": resolved_quality,
        }
        resolved_output_format = (output_format or self.extra_auth.get("outputFormat") or "").strip()
        if resolved_output_format:
            form_fields["output_format"] = resolved_output_format
        return form_fields, image_files

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        url = f"{self.base_url}{_ensure_leading_slash(path)}"
        ssl_budget = self.ssl_eof_retries
        conn_budget = self.connection_retries
        conn_retry_index = 0
        attempt = 0
        while True:
            attempt += 1
            try:
                return self._post_once(url, payload)
            except SSLError as exc:
                if not _is_ssl_eof_error(exc) or ssl_budget <= 0:
                    raise OpenAIImagesError(
                        "openai images SSL connection failed before an HTTP response was received. "
                        "Check the gateway URL, local requests/urllib3 versions, proxy/VPN, and TLS interception. "
                        f"detail={exc}"
                    ) from exc
                ssl_budget -= 1
                time.sleep(min(2.0, float(attempt)))
            except (RequestsConnectionError, ChunkedEncodingError, OpenAIImagesRetryableServerError) as exc:
                if conn_budget <= 0:
                    raise OpenAIImagesError(f"openai images request failed: {exc}") from exc
                conn_budget -= 1
                conn_retry_index += 1
                time.sleep(self._compute_backoff(conn_retry_index))

    def _resolve_edit_model(self, model: str) -> str:
        override = str(self.extra_auth.get("editModel") or "").strip()
        if override:
            return override
        candidates = self._edit_model_candidates(model)
        return candidates[0]

    def _edit_reference_image(
        self,
        endpoint: str,
        form_fields: dict[str, str],
        image_files: list[tuple[str, bytes, str]],
        *,
        source_model: str,
    ) -> dict[str, Any]:
        """Reference edits default to requests (same TLS stack as text-to-image). SDK is opt-in."""
        if _as_bool(self.extra_auth.get("preferSdkEdit"), False):
            try:
                return self._edit_via_openai_sdk(form_fields, image_files, source_model=source_model)
            except OpenAIImagesError as exc:
                if not _should_fallback_to_requests_edit(str(exc)):
                    raise
                LOGGER.warning(
                    "openai images sdk edit failed, falling back to requests multipart: %s",
                    exc,
                )
        return self._post_multipart(endpoint, form_fields, image_files, source_model=source_model)

    def _create_httpx_client(self) -> Any:
        import httpx

        proxy_url = str(self.extra_auth.get("proxyUrl") or "").strip() or None
        return httpx.Client(
            trust_env=self.session.trust_env,
            timeout=httpx.Timeout(
                connect=self.timeout[0],
                read=self.timeout[1],
                write=self.timeout[1],
                pool=self.timeout[0],
            ),
            proxy=proxy_url,
        )

    def _edit_via_openai_sdk(
        self,
        form_fields: dict[str, str],
        image_files: list[tuple[str, bytes, str]],
        *,
        source_model: str,
    ) -> dict[str, Any]:
        try:
            from openai import APIConnectionError, APIStatusError, APITimeoutError, OpenAI
        except ImportError as exc:
            raise OpenAIImagesError(
                "openai SDK is required for reference-image edits; install worker requirements (openai, httpx)"
            ) from exc

        try:
            return self._edit_via_openai_sdk_once(
                form_fields,
                image_files,
                source_model=source_model,
                APIConnectionError=APIConnectionError,
                APIStatusError=APIStatusError,
                APITimeoutError=APITimeoutError,
                OpenAI=OpenAI,
            )
        except APIConnectionError as exc:
            raise OpenAIImagesError(
                "openai images SSL connection failed before an HTTP response was received. "
                "Check gateway URL, proxy/VPN, and local TLS interception. "
                f"detail={exc}"
            ) from exc

    def _edit_via_openai_sdk_once(
        self,
        form_fields: dict[str, str],
        image_files: list[tuple[str, bytes, str]],
        *,
        source_model: str,
        APIConnectionError: Any,
        APIStatusError: Any,
        APITimeoutError: Any,
        OpenAI: Any,
    ) -> dict[str, Any]:
        candidates = self._edit_model_candidates(source_model)
        last_error: OpenAIImagesError | None = None
        connection_failed = False

        for index, model_name in enumerate(candidates):
            attempt_fields = {**form_fields, "model": model_name}
            edit_kwargs: dict[str, Any] = {
                "model": model_name,
                "prompt": attempt_fields["prompt"],
                "size": attempt_fields.get("size") or "auto",
                "quality": attempt_fields.get("quality") or "low",
            }
            batch_count = int(attempt_fields.get("n") or 1)
            if batch_count > 1:
                edit_kwargs["n"] = batch_count
            output_format = attempt_fields.get("output_format")
            if output_format:
                edit_kwargs["output_format"] = output_format

            ssl_attempts = self.ssl_eof_retries + 1
            for ssl_attempt in range(1, ssl_attempts + 1):
                http_client = self._create_httpx_client()
                try:
                    client = OpenAI(
                        api_key=self.api_key,
                        base_url=self.base_url,
                        http_client=http_client,
                        max_retries=0,
                    )
                    edit_kwargs["image"] = _sdk_image_argument(image_files)
                    LOGGER.info(
                        "openai images sdk edit model=%s size=%s quality=%s images=%s bytes=%s sslAttempt=%s/%s",
                        model_name,
                        edit_kwargs.get("size"),
                        edit_kwargs.get("quality"),
                        len(image_files),
                        sum(len(item[1]) for item in image_files),
                        ssl_attempt,
                        ssl_attempts,
                    )
                    result = client.images.edit(**edit_kwargs)
                    return result.model_dump()
                except APITimeoutError as exc:
                    raise OpenAIImagesTimeoutError(f"openai images edit timed out: {exc}") from exc
                except APIConnectionError as exc:
                    connection_failed = True
                    if _is_ssl_eof_error(exc) and ssl_attempt < ssl_attempts:
                        LOGGER.warning(
                            "openai images sdk SSL handshake failed, retrying edit model=%s attempt=%s/%s",
                            model_name,
                            ssl_attempt,
                            ssl_attempts,
                        )
                        time.sleep(min(2, ssl_attempt))
                        continue
                    last_error = OpenAIImagesError(
                        "openai images SSL connection failed before an HTTP response was received. "
                        "Check gateway URL, proxy/VPN, and local TLS interception. "
                        f"detail={exc}"
                    )
                    break
                except APIStatusError as exc:
                    body = exc.message or str(exc)
                    last_error = OpenAIImagesError(
                        _format_openai_images_http_error(exc.status_code, body, model_name)
                    )
                    if _is_missing_model_error(body) and index < len(candidates) - 1:
                        LOGGER.warning(
                            "openai images sdk edit rejected model=%s, retrying with model=%s",
                            model_name,
                            candidates[index + 1],
                        )
                        break
                    raise last_error from exc
                finally:
                    http_client.close()

            if last_error and not _is_missing_model_error(str(last_error)):
                break

        if connection_failed and not _as_bool(self.extra_auth.get("disableRequestsEditFallback"), False):
            LOGGER.warning(
                "openai images sdk connection failed, falling back to requests multipart transport"
            )
            return self._post_multipart(
                self.edit_endpoint_path,
                form_fields,
                image_files,
                source_model=source_model,
            )

        if last_error:
            raise last_error
        raise OpenAIImagesError("openai images edit failed")

    def _resolve_edit_size(self, image_size: str) -> str:
        override = str(self.extra_auth.get("editSize") or "").strip()
        if override:
            return override
        if "ofox.ai" in self.base_url.lower():
            return "auto"
        return _normalize_size(image_size)

    def _edit_model_candidates(self, model: str) -> list[str]:
        override = str(self.extra_auth.get("editModel") or "").strip()
        if override:
            return [override]

        normalized = (model or "").strip()
        if not normalized:
            return []

        candidates = [normalized]
        if "/" in normalized:
            short_name = normalized.rsplit("/", 1)[-1].strip()
            if short_name and short_name not in candidates:
                candidates.append(short_name)
        return candidates

    def _post_multipart(
        self,
        path: str,
        form_fields: dict[str, str],
        image_files: list[tuple[str, bytes, str]],
        *,
        source_model: str,
    ) -> dict[str, Any]:
        url = f"{self.base_url}{_ensure_leading_slash(path)}"
        candidates = self._edit_model_candidates(source_model)
        last_error: OpenAIImagesError | None = None
        for index, model_name in enumerate(candidates):
            attempt_fields = {**form_fields, "model": model_name}
            try:
                return self._post_multipart_with_ssl_retries(url, attempt_fields, image_files)
            except OpenAIImagesError as exc:
                last_error = exc
                if _is_missing_model_error(str(exc)) and index < len(candidates) - 1:
                    LOGGER.warning(
                        "openai images edit rejected model=%s, retrying with model=%s",
                        model_name,
                        candidates[index + 1],
                    )
                    continue
                raise
        if last_error:
            raise last_error
        raise OpenAIImagesError("openai images request failed")

    def _post_multipart_with_ssl_retries(
        self,
        url: str,
        form_fields: dict[str, str],
        image_files: list[tuple[str, bytes, str]],
    ) -> dict[str, Any]:
        ssl_budget = self.ssl_eof_retries
        conn_budget = self.connection_retries
        conn_retry_index = 0
        attempt = 0
        while True:
            attempt += 1
            try:
                return self._post_multipart_once(url, form_fields, image_files)
            except SSLError as exc:
                if not _is_ssl_eof_error(exc) or ssl_budget <= 0:
                    raise OpenAIImagesError(
                        "openai images SSL connection failed before an HTTP response was received. "
                        "Check the gateway URL, local requests/urllib3 versions, proxy/VPN, and TLS interception. "
                        f"detail={exc}"
                    ) from exc
                ssl_budget -= 1
                time.sleep(min(2.0, float(attempt)))
            except (RequestsConnectionError, ChunkedEncodingError, OpenAIImagesRetryableServerError) as exc:
                if conn_budget <= 0:
                    raise OpenAIImagesError(f"openai images edit request failed: {exc}") from exc
                conn_budget -= 1
                conn_retry_index += 1
                time.sleep(self._compute_backoff(conn_retry_index))

    def _multipart_headers(self) -> dict[str, str]:
        return {
            "Authorization": self.session.headers.get("Authorization", ""),
            "Connection": self.session.headers.get("Connection", "close"),
        }

    def _post_multipart_once(
        self,
        url: str,
        form_fields: dict[str, str],
        image_files: list[tuple[str, bytes, str]],
    ) -> dict[str, Any]:
        started_at = time.perf_counter()
        diagnostics = self._connection_diagnostics(url)
        LOGGER.info(
            "openai images transport start url=%s timeout=%s transport=multipart diagnostics=%s",
            _redact_url(url),
            self.timeout,
            diagnostics,
        )
        multipart_body: list[tuple[str, Any]] = []
        for key in ("model", "prompt", "n", "size", "quality", "output_format"):
            value = form_fields.get(key)
            if value:
                multipart_body.append((key, (None, value)))
        for filename, image_bytes, mime in image_files:
            multipart_body.append(("image", (filename, image_bytes, mime)))
        try:
            response = self.session.post(
                url,
                files=multipart_body,
                headers=self._multipart_headers(),
                timeout=self.timeout,
            )
            LOGGER.info(
                "openai images transport completed url=%s status=%s elapsed=%.3fs transport=multipart diagnostics=%s",
                _redact_url(url),
                response.status_code,
                time.perf_counter() - started_at,
                diagnostics,
            )
        except Timeout as exc:
            elapsed = time.perf_counter() - started_at
            timeout_kind = _timeout_kind(exc)
            LOGGER.warning(
                "openai images transport timeout url=%s kind=%s elapsed=%.3fs timeout=%s transport=multipart diagnostics=%s error=%s",
                _redact_url(url),
                timeout_kind,
                elapsed,
                self.timeout,
                diagnostics,
                exc,
            )
            raise OpenAIImagesTimeoutError(
                "openai images request timed out: "
                f"kind={timeout_kind}; elapsed={elapsed:.3f}s; "
                f"connectTimeout={self.timeout[0]}s; readTimeout={self.timeout[1]}s; "
                f"diagnostics={diagnostics}"
            ) from exc
        except SSLError:
            raise
        except ProxyError as exc:
            raise OpenAIImagesError(
                "openai images proxy connection failed. "
                "The request is using a configured proxy or extraAuthJson trustEnv=true; "
                "disable trustEnv or configure proxyUrl explicitly. "
                f"detail={exc}"
            ) from exc
        except (RequestsConnectionError, ChunkedEncodingError):
            raise
        except RequestException as exc:
            raise OpenAIImagesError(f"openai images request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            if 500 <= response.status_code < 600:
                raise OpenAIImagesRetryableServerError(
                    f"openai images request failed: status={response.status_code}, body={response.text}"
                ) from exc
            raise OpenAIImagesError(
                _format_openai_images_http_error(response.status_code, response.text, form_fields.get("model"))
            ) from exc

        try:
            data = response.json()
        except ValueError as exc:
            raise OpenAIImagesError("openai images returned non-json response") from exc
        if not isinstance(data, dict):
            raise OpenAIImagesError("openai images returned invalid response")
        return data

    def _post_once(self, url: str, payload: dict[str, Any]) -> dict[str, Any]:
        started_at = time.perf_counter()
        diagnostics = self._connection_diagnostics(url)
        LOGGER.info(
            "openai images transport start url=%s timeout=%s diagnostics=%s",
            _redact_url(url),
            self.timeout,
            diagnostics,
        )
        try:
            response = self.session.post(
                url,
                json=payload,
                timeout=self.timeout,
            )
            LOGGER.info(
                "openai images transport completed url=%s status=%s elapsed=%.3fs diagnostics=%s",
                _redact_url(url),
                response.status_code,
                time.perf_counter() - started_at,
                diagnostics,
            )
        except Timeout as exc:
            elapsed = time.perf_counter() - started_at
            timeout_kind = _timeout_kind(exc)
            LOGGER.warning(
                "openai images transport timeout url=%s kind=%s elapsed=%.3fs timeout=%s diagnostics=%s error=%s",
                _redact_url(url),
                timeout_kind,
                elapsed,
                self.timeout,
                diagnostics,
                exc,
            )
            raise OpenAIImagesTimeoutError(
                "openai images request timed out: "
                f"kind={timeout_kind}; elapsed={elapsed:.3f}s; "
                f"connectTimeout={self.timeout[0]}s; readTimeout={self.timeout[1]}s; "
                f"diagnostics={diagnostics}"
            ) from exc
        except SSLError:
            raise
        except ProxyError as exc:
            raise OpenAIImagesError(
                "openai images proxy connection failed. "
                "The request is using a configured proxy or extraAuthJson trustEnv=true; "
                "disable trustEnv or configure proxyUrl explicitly. "
                f"detail={exc}"
            ) from exc
        except (RequestsConnectionError, ChunkedEncodingError):
            raise
        except RequestException as exc:
            raise OpenAIImagesError(f"openai images request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            if 500 <= response.status_code < 600:
                raise OpenAIImagesRetryableServerError(
                    f"openai images request failed: status={response.status_code}, body={response.text}"
                ) from exc
            raise OpenAIImagesError(
                f"openai images request failed: status={response.status_code}, body={response.text}"
            ) from exc

        try:
            data = response.json()
        except ValueError as exc:
            raise OpenAIImagesError("openai images returned non-json response") from exc
        if not isinstance(data, dict):
            raise OpenAIImagesError("openai images returned invalid response")
        return data

    def _connection_diagnostics(self, url: str) -> str:
        parsed = urlparse(url)
        host = parsed.hostname or ""
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        proxy_enabled = bool(self.session.proxies) or self.session.trust_env
        addresses = _resolve_host_addresses(host, port)
        return (
            f"host={host or '-'}; port={port}; trustEnv={self.session.trust_env}; "
            f"configuredProxy={bool(self.session.proxies)}; proxyEnabled={proxy_enabled}; resolved={addresses}"
        )

    def _extract_image_urls(self, payload: dict[str, Any]) -> list[str]:
        data = payload.get("data")
        if not isinstance(data, list):
            raise OpenAIImagesError("openai images response missing data")
        urls: list[str] = []
        for item in data:
            if not isinstance(item, dict):
                continue
            url = item.get("url")
            if isinstance(url, str) and url.strip():
                urls.append(url.strip())
                continue
            b64_json = item.get("b64_json")
            if isinstance(b64_json, str) and b64_json.strip():
                urls.append(f"data:image/png;base64,{b64_json.strip()}")
        if not urls:
            raise OpenAIImagesError("openai images response missing image url")
        return urls

    def _resolve_usage(self, response: dict[str, Any], payload: dict[str, Any], image_count: int) -> dict[str, int]:
        usage = response.get("usage")
        if isinstance(usage, dict):
            input_tokens = _as_int(usage.get("input_tokens") or usage.get("prompt_tokens"))
            output_tokens = _as_int(usage.get("output_tokens") or usage.get("completion_tokens"))
            total_tokens = _as_int(usage.get("total_tokens"))
            if total_tokens <= 0:
                total_tokens = input_tokens + output_tokens
            return {
                "promptTokens": input_tokens,
                "completionTokens": output_tokens,
                "totalTokens": total_tokens,
            }

        prompt_tokens = max(1, math.ceil(len(str(payload.get("prompt") or "")) / 4))
        output_tokens = self._estimate_output_tokens(
            size=str(payload.get("size") or "1024x1024"),
            quality=str(payload.get("quality") or "medium"),
            image_count=image_count,
        )
        return {
            "promptTokens": prompt_tokens,
            "completionTokens": output_tokens,
            "totalTokens": prompt_tokens + output_tokens,
        }

    def _estimate_output_tokens(self, *, size: str, quality: str, image_count: int) -> int:
        configured = self.extra_auth.get("imageTokenEstimate")
        if isinstance(configured, dict):
            size_config = configured.get(size)
            if isinstance(size_config, dict):
                value = _as_int(size_config.get(quality) or size_config.get("default"))
                if value > 0:
                    return value * max(1, image_count)
            value = _as_int(configured.get("defaultOutputTokens"))
            if value > 0:
                return value * max(1, image_count)

        long_edge = max(_parse_size(size))
        base = 1296 if long_edge >= 1536 else 1056
        multiplier = {"low": 0.5, "medium": 1.0, "standard": 1.0, "high": 4.0, "auto": 1.0}.get(
            quality.lower(),
            1.0,
        )
        return max(1, int(base * multiplier)) * max(1, image_count)

    def _resolve_timeout(self, timeout_seconds: int | None) -> tuple[float, float]:
        connect_timeout = _as_float(self.extra_auth.get("connectTimeoutSeconds"), 10)
        read_timeout = _as_float(
            self.extra_auth.get("readTimeoutSeconds") or self.extra_auth.get("timeoutSeconds") or timeout_seconds,
            600,
        )
        return (max(1.0, connect_timeout), max(600.0, read_timeout))

    def _resolve_ssl_eof_retries(self) -> int:
        value = self.extra_auth.get("sslEofRetries")
        if isinstance(value, bool):
            return 1 if value else 0
        return min(2, _as_int(value))

    def _resolve_connection_retries(self) -> int:
        value = self.extra_auth.get("connectionRetries")
        if isinstance(value, bool):
            return 1 if value else 0
        if value is None:
            return 2
        return min(5, _as_int(value))

    def _compute_backoff(self, retry_index: int) -> float:
        base = max(0.0, self.retry_backoff_seconds)
        cap = max(base, self.retry_backoff_max_seconds)
        backoff = base * (2 ** max(0, retry_index - 1))
        return min(cap, backoff)

    def _apply_response_format(self, payload: dict[str, Any], response_format: str) -> None:
        location = str(self.extra_auth.get("responseFormatLocation") or "").strip()
        if _normalized_option(location) == "extrabody":
            extra_body = payload.setdefault("extra_body", {})
            if isinstance(extra_body, dict):
                extra_body["response_format"] = response_format
                return
        payload["response_format"] = response_format

    def _uses_json_image_array_input(self) -> bool:
        mode = _normalized_option(self.extra_auth.get("imageInputMode"))
        return mode in {"jsonarray", "jsonimagearray"}

    @staticmethod
    def _parse_json(value: str | None) -> dict[str, Any]:
        if not value or not value.strip():
            return {}
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}


def _format_openai_images_http_error(status_code: int, body: str, model: str | None) -> str:
    message = f"openai images request failed: status={status_code}, body={body}"
    if _is_missing_model_error(body):
        message += (
            f"; editModel={model or '-'}. "
            "Image edits require multipart/form-data with text fields in `data` and the "
            "reference file in `image`. For oFox use model 'openai/gpt-image-2' and size 'auto' "
            "(see https://ofox.ai/zh/docs/api/openai/images)."
        )
    return message


def _combine_image_responses(responses: list[dict[str, Any]]) -> dict[str, Any]:
    if not responses:
        return {"data": []}
    combined = dict(responses[0])
    data: list[Any] = []
    usage: dict[str, int] = {}
    for response in responses:
        response_data = response.get("data")
        if isinstance(response_data, list):
            data.extend(response_data)
        response_usage = response.get("usage")
        if isinstance(response_usage, dict):
            for key, value in response_usage.items():
                numeric = _as_int(value)
                if numeric > 0:
                    usage[key] = usage.get(key, 0) + numeric
    combined["data"] = data
    if usage:
        combined["usage"] = usage
    return combined


def _image_response_data_summary(response: dict[str, Any]) -> dict[str, Any]:
    data = response.get("data")
    if not isinstance(data, list):
        return {"type": type(data).__name__, "count": 0}
    items: list[dict[str, Any]] = []
    for index, item in enumerate(data[:10]):
        if not isinstance(item, dict):
            items.append({"index": index, "type": type(item).__name__})
            continue
        items.append(
            {
                "index": item.get("index", index),
                "hasUrl": bool(str(item.get("url") or "").strip()),
                "hasB64": bool(str(item.get("b64_json") or "").strip()),
                "keys": sorted(str(key) for key in item.keys())[:12],
            }
        )
    return {"type": "list", "count": len(data), "items": items}


def _normalize_reference_images(image: str | list[str] | None) -> list[str]:
    if isinstance(image, str):
        text = image.strip()
        return [text] if text else []
    if not isinstance(image, list):
        return []
    images: list[str] = []
    seen: set[str] = set()
    for item in image:
        if not isinstance(item, str):
            continue
        text = item.strip()
        if text and text not in seen:
            seen.add(text)
            images.append(text)
    return images


def _sdk_image_argument(image_files: list[tuple[str, bytes, str]]) -> Any:
    images: list[tuple[str, BytesIO, str]] = []
    for filename, image_bytes, mime in image_files:
        image_io = BytesIO(image_bytes)
        image_io.name = filename
        images.append((filename, image_io, mime))
    return images[0] if len(images) == 1 else images


def _is_missing_model_error(message: str) -> bool:
    lowered = message.lower()
    return "model parameter" in lowered or (
        "invalid_request_error" in lowered and "model" in lowered and "provide" in lowered
    )


def _should_fallback_to_requests_edit(message: str) -> bool:
    lowered = message.lower()
    return (
        "ssl connection failed" in lowered
        or "connection error" in lowered
        or "eof occurred in violation of protocol" in lowered
        or _is_missing_model_error(lowered)
    )


def _normalize_size(value: str) -> str:
    size = (value or "1024x1024").strip()
    if ":" in size:
        return {
            "1:1": "1024x1024",
            "16:9": "1536x864",
            "9:16": "864x1536",
            "4:3": "1024x768",
            "3:4": "768x1024",
        }.get(size, "1024x1024")
    return size


def _parse_size(value: str) -> tuple[int, int]:
    parts = value.lower().split("x", 1)
    if len(parts) != 2:
        return (1024, 1024)
    try:
        return (max(1, int(parts[0])), max(1, int(parts[1])))
    except ValueError:
        return (1024, 1024)


def _ensure_leading_slash(value: str) -> str:
    return value if value.startswith("/") else f"/{value}"


def _as_int(value: Any) -> int:
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0


def _as_float(value: Any, fallback: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def _as_bool(value: Any, fallback: bool) -> bool:
    if value is None:
        return fallback
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if text in {"1", "true", "yes", "on"}:
        return True
    if text in {"0", "false", "no", "off"}:
        return False
    return fallback


def _normalized_option(value: Any) -> str:
    return str(value or "").strip().lower().replace("_", "").replace("-", "")


def _response_format_for_log(payload: dict[str, Any]) -> str:
    value = payload.get("response_format")
    if isinstance(value, str) and value:
        return value
    extra_body = payload.get("extra_body")
    if isinstance(extra_body, dict):
        value = extra_body.get("response_format")
        if isinstance(value, str):
            return value
    return ""


def _is_ssl_eof_error(exc: BaseException) -> bool:
    message = str(exc).lower()
    return "eof occurred in violation of protocol" in message or "ssleoferror" in message


def _timeout_kind(exc: Timeout) -> str:
    if isinstance(exc, ConnectTimeout):
        return "connect"
    if isinstance(exc, ReadTimeout):
        return "read"
    return "timeout"


def _resolve_host_addresses(host: str, port: int) -> str:
    if not host:
        return "-"
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError as exc:
        return f"dns_error:{exc}"
    addresses: list[str] = []
    for info in infos:
        address = info[4][0]
        if address not in addresses:
            addresses.append(address)
    return ",".join(addresses[:8]) or "-"


def _redact_url(url: str) -> str:
    parsed = urlparse(url)
    if not parsed.hostname:
        return url
    port = f":{parsed.port}" if parsed.port else ""
    path = parsed.path or "/"
    return f"{parsed.scheme}://{parsed.hostname}{port}{path}"


def _is_volcengine_ark_base_url(base_url: str) -> bool:
    host = (urlparse(base_url).hostname or "").lower()
    return host.endswith("volces.com") or host.endswith("volcengine.com")
