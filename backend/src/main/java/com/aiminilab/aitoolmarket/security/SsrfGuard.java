package com.aiminilab.aitoolmarket.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * SSRF (Server-Side Request Forgery) protection utility.
 * Validates outbound URLs to prevent attacks on internal services and cloud metadata endpoints.
 */
public final class SsrfGuard {

    private SsrfGuard() {
    }

    // Blocked IP ranges (private/reserved/metadata)
    private static final String[] BLOCKED_CIDRS = {
            "127.0.0.0/8",      // Loopback
            "10.0.0.0/8",       // Private Class A
            "172.16.0.0/12",    // Private Class B
            "192.168.0.0/16",   // Private Class C
            "169.254.0.0/16",   // Link-local (AWS/GCP metadata)
            "0.0.0.0/32",       // Null route
            "::1/128",          // IPv6 loopback
            "fc00::/7",         // IPv6 unique local
            "fe80::/10"         // IPv6 link-local
    };

    // Cloud metadata endpoints (by hostname)
    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "100.100.100.200",           // Alibaba Cloud IMDS
            "169.254.169.254",           // AWS/GCP/Azure/Huawei Cloud IMDS
            "metadata.tencentyun.com",   // Tencent Cloud IMDS
            "metadata.google.internal"   // GCP IMDS
    );

    /**
     * Validates a URL to prevent SSRF attacks.
     *
     * @param url the URL to validate
     * @throws SsrfException if the URL is unsafe
     */
    public static void validate(String url) throws SsrfException {
        if (url == null || url.isBlank()) {
            throw new SsrfException("URL must be a non-empty string");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new SsrfException("Invalid URL format: " + e.getMessage());
        }

        // 1. Protocol check
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new SsrfException("URL missing scheme");
        }
        String schemeLower = scheme.toLowerCase();
        if (!"http".equals(schemeLower) && !"https".equals(schemeLower)) {
            throw new SsrfException("Protocol '" + scheme + "' is not allowed. Only http/https are permitted.");
        }

        // 2. Host check
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfException("Invalid URL: missing hostname");
        }
        String hostLower = host.toLowerCase();

        // 3. Block known metadata hostnames
        if (BLOCKED_HOSTNAMES.contains(hostLower)) {
            throw new SsrfException("Access to cloud metadata service '" + host + "' is forbidden");
        }

        // 4. IP address check (if hostname is an IP)
        try {
            InetAddress address = InetAddress.getByName(hostLower);
            checkIpBlocked(address);
        } catch (UnknownHostException e) {
            throw new SsrfException("Failed to resolve hostname: " + host);
        }
    }

    /**
     * Validates a URI object.
     *
     * @param uri the URI to validate
     * @throws SsrfException if the URI is unsafe
     */
    public static void validate(URI uri) throws SsrfException {
        if (uri == null) {
            throw new SsrfException("URI must not be null");
        }
        validate(uri.toString());
    }

    private static void checkIpBlocked(InetAddress address) throws SsrfException {
        // Check if address is loopback, link-local, site-local, etc.
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            throw new SsrfException("IP address " + address.getHostAddress() + " is in a blocked range");
        }

        // Additional CIDR check for extra safety
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            // IPv4 checks
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;

            // 10.0.0.0/8
            if (first == 10) {
                throw new SsrfException("IP address " + address.getHostAddress() + " is in blocked range 10.0.0.0/8");
            }
            // 172.16.0.0/12
            if (first == 172 && second >= 16 && second <= 31) {
                throw new SsrfException("IP address " + address.getHostAddress() + " is in blocked range 172.16.0.0/12");
            }
            // 192.168.0.0/16
            if (first == 192 && second == 168) {
                throw new SsrfException("IP address " + address.getHostAddress() + " is in blocked range 192.168.0.0/16");
            }
            // 169.254.0.0/16
            if (first == 169 && second == 254) {
                throw new SsrfException("IP address " + address.getHostAddress() + " is in blocked range 169.254.0.0/16");
            }
            // 127.0.0.0/8
            if (first == 127) {
                throw new SsrfException("IP address " + address.getHostAddress() + " is in blocked range 127.0.0.0/8");
            }
            // 0.0.0.0
            if (first == 0) {
                throw new SsrfException("IP address " + address.getHostAddress() + " is in blocked range 0.0.0.0/8");
            }
        }
    }

    /**
     * Exception thrown when SSRF validation fails.
     */
    public static class SsrfException extends Exception {
        public SsrfException(String message) {
            super(message);
        }
    }
}
