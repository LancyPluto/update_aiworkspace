"""SSRF protection for outbound HTTP requests."""
from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlparse

# Blocked IP ranges (private/reserved/metadata)
_BLOCKED_NETWORKS = [
    ipaddress.ip_network("127.0.0.0/8"),      # Loopback
    ipaddress.ip_network("10.0.0.0/8"),       # Private Class A
    ipaddress.ip_network("172.16.0.0/12"),    # Private Class B
    ipaddress.ip_network("192.168.0.0/16"),   # Private Class C
    ipaddress.ip_network("169.254.0.0/16"),   # Link-local (AWS/GCP metadata)
    ipaddress.ip_network("0.0.0.0/32"),       # Null route
    ipaddress.ip_network("::1/128"),          # IPv6 loopback
    ipaddress.ip_network("fc00::/7"),         # IPv6 unique local
    ipaddress.ip_network("fe80::/10"),        # IPv6 link-local
]

# Cloud metadata endpoints (by hostname)
_BLOCKED_HOSTNAMES = {
    "100.100.100.200",           # Alibaba Cloud IMDS
    "169.254.169.254",           # AWS/GCP/Azure/Huawei Cloud IMDS
    "metadata.tencentyun.com",   # Tencent Cloud IMDS
    "metadata.google.internal",  # GCP IMDS
}

# Allowed protocols
_ALLOWED_SCHEMES = {"http", "https"}


class SSRFGuardError(Exception):
    """Raised when URL fails SSRF validation."""
    pass


def validate_outbound_url(url: str, *, allowed_domains: set[str] | None = None) -> None:
    """Validate URL to prevent SSRF attacks.
    
    Args:
        url: The URL to validate
        allowed_domains: Optional whitelist of allowed domains (supports wildcard *.example.com)
        
    Raises:
        SSRFGuardError: If URL is unsafe
    """
    if not url or not isinstance(url, str):
        raise SSRFGuardError("URL must be a non-empty string")
    
    parsed = urlparse(url)
    
    # 1. Protocol check
    scheme = parsed.scheme.lower()
    if scheme not in _ALLOWED_SCHEMES:
        raise SSRFGuardError(f"Protocol '{scheme}' is not allowed. Only http/https are permitted.")
    
    hostname = parsed.hostname
    if not hostname:
        raise SSRFGuardError("Invalid URL: missing hostname")
    
    hostname_lower = hostname.lower().rstrip(".")
    
    # 2. Block known metadata hostnames
    if hostname_lower in _BLOCKED_HOSTNAMES:
        raise SSRFGuardError(
            f"Access to cloud metadata service '{hostname}' is forbidden"
        )
    
    # 3. IP address check (if hostname is an IP)
    try:
        ip = ipaddress.ip_address(hostname_lower)
        _check_ip_blocked(ip)
        return  # It's an IP, skip domain whitelist check
    except ValueError:
        pass  # Not an IP, continue to DNS resolution
    
    # 4. Domain whitelist check (if configured)
    if allowed_domains:
        if not _matches_allowed_domain(hostname_lower, allowed_domains):
            raise SSRFGuardError(
                f"Domain '{hostname}' is not in the allowed whitelist"
            )
        return
    
    # 5. DNS resolution + IP check (only if no whitelist)
    try:
        addresses = socket.getaddrinfo(hostname, None, socket.AF_INET, socket.SOCK_STREAM)
        for addr_info in addresses:
            ip_str = addr_info[4][0]
            ip = ipaddress.ip_address(ip_str)
            _check_ip_blocked(ip)
    except socket.gaierror:
        raise SSRFGuardError(f"Failed to resolve hostname: {hostname}")
    except Exception as exc:
        raise SSRFGuardError(f"DNS resolution error: {exc}")


def _check_ip_blocked(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> None:
    """Check if IP is in blocked ranges."""
    for network in _BLOCKED_NETWORKS:
        if ip in network:
            raise SSRFGuardError(
                f"IP address {ip} is in blocked range {network}"
            )


def _matches_allowed_domain(hostname: str, allowed_domains: set[str]) -> bool:
    """Check if hostname matches allowed domain patterns (supports wildcards)."""
    for pattern in allowed_domains:
        if pattern.startswith("*."):
            # Wildcard match: *.example.com matches sub.example.com
            suffix = pattern[1:]  # Remove *
            if hostname.endswith(suffix):
                return True
        else:
            # Exact match
            if hostname == pattern:
                return True
    return False
