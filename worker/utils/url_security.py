"""
URL security validator for Worker-side SSRF prevention.

Prevents:
- SSRF via internal IP access (127.0.0.1, 10.x, 172.16-31.x, 192.168.x, 169.254.x)
- Cloud metadata access (100.100.100.200, 169.254.169.254)
- DNS Rebinding (two DNS resolutions, compare IPs)
- HTTP redirect-based SSRF (disable auto-redirect, re-validate each hop)
- Non-HTTP protocols (file://, gopher://, etc.)
- IPv6 loopback/ULA/link-local bypass
"""

from __future__ import annotations

import ipaddress
import logging
import socket
import time
from urllib.parse import urlparse

import requests

logger = logging.getLogger(__name__)


class UrlSecurityError(ValueError):
    """Raised when a URL fails security validation."""
    pass


# ── Whitelist: domains that skip DNS validation (trusted platforms) ──

ALLOWED_HOSTS: frozenset[str] = frozenset({
    "cdn.wlcloudai.com",
    "oss-cn-beijing.aliyuncs.com",
    "api.sunoapi.org",
    "api.siliconflow.cn",
    "ark.cn-beijing.volces.com",
    "api-beijing.klingai.com",
    "dashscope.aliyuncs.com",
})

# ── Blocked CIDR ranges (IPv4 + IPv6) ──

_BLOCKED_CIDRS: list[ipaddress.IPv4Network | ipaddress.IPv6Network] = [
    # IPv4 private ranges
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    # IPv4 loopback
    ipaddress.ip_network("127.0.0.0/8"),
    # IPv4 link-local (169.254.0.0/16 covers AWS/GCP metadata 169.254.169.254)
    ipaddress.ip_network("169.254.0.0/16"),
    # IPv4 "this network" (0.0.0.0/8)
    ipaddress.ip_network("0.0.0.0/8"),
    # Carrier-grade NAT (100.64.0.0/10)
    ipaddress.ip_network("100.64.0.0/10"),
    # IPv4 multicast
    ipaddress.ip_network("224.0.0.0/4"),
    # IPv6 loopback
    ipaddress.ip_network("::1/128"),
    # IPv6 unspecified
    ipaddress.ip_network("::/128"),
    # IPv6 ULA (fc00::/7)
    ipaddress.ip_network("fc00::/7"),
    # IPv6 link-local (fe80::/10)
    ipaddress.ip_network("fe80::/10"),
    # IPv6 multicast
    ipaddress.ip_network("ff00::/8"),
]

# ── Cloud metadata addresses (explicit, defense-in-depth) ──

_BLOCKED_METADATA_IPS: frozenset[str] = frozenset({
    "100.100.100.200",    # Alibaba Cloud metadata
    "169.254.169.254",    # AWS / GCP / Azure metadata
})

MAX_SAFE_REDIRECTS = 5


def validate_url(url: str, *, check_rebinding: bool = False) -> None:
    """
    Validate that a URL is safe to access.

    Checks:
    1. Protocol is http or https
    2. Host is present
    3. Whitelisted domains skip DNS validation
    4. DNS resolves to non-internal IPs
    5. Optional: DNS rebinding check (two resolutions, 2s apart)

    Raises UrlSecurityError if the URL is unsafe.
    """
    if not url or not url.strip():
        raise UrlSecurityError("URL is empty")

    parsed = urlparse(url.strip())
    scheme = (parsed.scheme or "").lower()
    if scheme not in ("http", "https"):
        raise UrlSecurityError(f"Only http/https protocols allowed, got: {scheme or 'none'}")

    host = (parsed.hostname or "").lower()
    if not host:
        raise UrlSecurityError("URL missing hostname")

    # Whitelist fast path — trusted platforms skip DNS validation
    if _is_allowed_host(host):
        return

    # Resolve and validate IPs
    ips = _resolve_and_validate(host, url)

    # Optional DNS rebinding check
    if check_rebinding:
        time.sleep(2)
        second_ips = _resolve_and_validate(host, url)
        if set(ips) != set(second_ips):
            raise UrlSecurityError(
                f"DNS rebinding detected: {host} resolved to {ips} then {second_ips}"
            )


def safe_get(
    url: str,
    *,
    session: requests.Session | None = None,
    stream: bool = False,
    timeout: tuple[float, float] | float | None = None,
    max_redirects: int = MAX_SAFE_REDIRECTS,
    check_rebinding: bool = False,
    **kwargs: object,
) -> requests.Response:
    """
    Perform requests.get() with SSRF protection.

    - Validates URL before every request (including redirects)
    - Disables auto-redirect; manually follows and re-validates each redirect
    - Blocks internal IPs, cloud metadata, non-HTTP protocols

    Returns the final response (after following safe redirects).
    Raises UrlSecurityError if any URL (initial or redirect) is unsafe.
    """
    http = session or requests.Session()
    current_url = url
    redirect_count = 0

    while True:
        # Validate current URL before making the request
        validate_url(current_url, check_rebinding=check_rebinding and redirect_count == 0)

        # Make request with auto-redirect DISABLED
        response = http.get(
            current_url,
            stream=stream,
            timeout=timeout,
            allow_redirects=False,
            **kwargs,  # type: ignore[arg-type]
        )

        # If not a redirect, return the response
        if not response.is_redirect:
            return response

        # Handle redirect manually
        redirect_count += 1
        if redirect_count > max_redirects:
            raise UrlSecurityError(
                f"Too many redirects ({redirect_count}), max allowed: {max_redirects}"
            )

        location = response.headers.get("Location", "")
        if not location:
            raise UrlSecurityError("Redirect response missing Location header")

        # Resolve relative redirects
        if location.startswith("/"):
            parsed_current = urlparse(current_url)
            location = f"{parsed_current.scheme}://{parsed_current.netloc}{location}"
        elif not location.startswith(("http://", "https://")):
            raise UrlSecurityError(f"Unsupported redirect target: {location}")

        logger.debug("Safe redirect %d/%d: %s -> %s", redirect_count, max_redirects, current_url, location)
        current_url = location

        # Close the redirect response to free the connection
        response.close()


# ── Internal helpers ──

def _is_allowed_host(host: str) -> bool:
    """Check if host is in the whitelist (exact match or subdomain)."""
    for allowed in ALLOWED_HOSTS:
        if host == allowed or host.endswith("." + allowed):
            return True
    return False


def _resolve_and_validate(host: str, original_url: str) -> list[str]:
    """
    Resolve host via DNS and validate all resolved IPs.

    Returns list of resolved IP strings.
    Raises UrlSecurityError if any IP is internal/metadata.
    """
    try:
        infos = socket.getaddrinfo(host, None, proto=socket.IPPROTO_TCP)
    except socket.gaierror:
        raise UrlSecurityError(f"DNS resolution failed for: {host}")

    ips: list[str] = []
    for family, _type, _proto, _canonname, sockaddr in infos:
        ip_str = sockaddr[0]
        if ip_str in ips:
            continue
        ips.append(ip_str)
        _validate_ip(ip_str, host, original_url)

    if not ips:
        raise UrlSecurityError(f"No IPs resolved for: {host}")

    return ips


def _validate_ip(ip_str: str, host: str, original_url: str) -> None:
    """Validate a single IP address against blocklists."""
    # Explicit metadata check (defense-in-depth)
    if ip_str in _BLOCKED_METADATA_IPS:
        raise UrlSecurityError(f"Blocked cloud metadata address: {ip_str} (from {host})")

    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        raise UrlSecurityError(f"Invalid IP address: {ip_str} (from {host})")

    # Check against blocked CIDR ranges
    for cidr in _BLOCKED_CIDRS:
        if ip in cidr:
            raise UrlSecurityError(
                f"Blocked internal/private address: {ip_str} ({cidr}) (from {host})"
            )

    # IPv4-mapped IPv6 (e.g., ::ffff:127.0.0.1) — extract and re-check
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        mapped_str = str(ip.ipv4_mapped)
        if mapped_str in _BLOCKED_METADATA_IPS:
            raise UrlSecurityError(f"Blocked metadata via IPv4-mapped IPv6: {mapped_str}")
        for cidr in _BLOCKED_CIDRS:
            if ip.ipv4_mapped in cidr:  # type: ignore[operator]
                raise UrlSecurityError(
                    f"Blocked internal address via IPv4-mapped IPv6: {mapped_str} ({cidr})"
                )
