package com.aiminilab.aitoolmarket.common.util;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * URL 安全校验工具，防止 SSRF / DNS Rebinding 攻击。
 * 
 * <p>核心策略：</p>
 * <ul>
 *   <li>只允许 http/https 协议</li>
 *   <li>禁止内网 IP、本地回环、链路本地地址</li>
 *   <li>DNS 解析后二次检查目标 IP</li>
 *   <li>可选：DNS 两次解析防 Rebinding</li>
 * </ul>
 */
public final class UrlSecurityValidator {

    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("http", "https");

    /** 云服务商元数据地址（显式拦截，纵深防御） */
    private static final Set<String> BLOCKED_METADATA_IPS = Set.of(
        "100.100.100.200",    // 阿里云元数据
        "169.254.169.254"     // AWS / GCP / Azure 元数据
    );
    
    /** 允许的域名白名单（可选配置） */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
        // OSS CDN
        "cdn.wlcloudai.com",
        "oss-cn-beijing.aliyuncs.com",
        // 第三方 AI 服务
        "api.sunoapi.org",
        "api.siliconflow.cn",
        "ark.cn-beijing.volces.com",
        "api-beijing.klingai.com",
        "dashscope.aliyuncs.com"
    );

    /**
     * 校验 URL 是否安全可访问。
     * 
     * @param urlString 待校验的 URL
     * @throws BusinessException 如果 URL 不安全或格式错误
     */
    public static void validate(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "URL 不能为空");
        }

        String trimmed = urlString.trim();
        
        // 1. 协议检查
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只支持 http/https 协议");
        }

        try {
            URL url = new URL(trimmed);
            String protocol = url.getProtocol().toLowerCase();
            
            // 2. 协议白名单
            if (!ALLOWED_PROTOCOLS.contains(protocol)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的协议: " + protocol);
            }

            String host = url.getHost();
            if (host == null || host.isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "URL 缺少主机名");
            }

            // 3. 域名白名单快速路径（跳过 DNS 解析）
            if (isAllowedHost(host)) {
                return;
            }

            // 4. IP 地址直接检查（如果是 IP 而非域名）
            if (isIpAddress(host)) {
                validateIpAddress(host);
                return;
            }

            // 5. DNS 解析后检查 IP
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses == null || addresses.length == 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "无法解析域名: " + host);
            }

            for (InetAddress addr : addresses) {
                validateInetAddress(addr);
            }

        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "域名解析失败: " + urlString);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "URL 格式错误: " + urlString);
        }
    }

    /**
     * 防 DNS Rebinding 增强版校验。
     * 
     * <p>对域名进行两次 DNS 解析（间隔 2 秒），确保返回的 IP 一致。</p>
     * 
     * @param urlString 待校验的 URL
     * @throws BusinessException 如果检测到 DNS Rebinding 攻击
     */
    public static void validateWithRebindingCheck(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "URL 不能为空");
        }

        String trimmed = urlString.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只支持 http/https 协议");
        }

        try {
            URL url = new URL(trimmed);
            String host = url.getHost();
            
            if (host == null || host.isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "URL 缺少主机名");
            }

            // 白名单域名跳过 Rebinding 检查
            if (isAllowedHost(host)) {
                validate(urlString);
                return;
            }

            // 第一次 DNS 解析
            InetAddress[] firstResolution = InetAddress.getAllByName(host);
            if (firstResolution == null || firstResolution.length == 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "无法解析域名: " + host);
            }

            // 验证第一次解析的 IP
            for (InetAddress addr : firstResolution) {
                validateInetAddress(addr);
            }

            // 间隔 2 秒后第二次解析（检测 Rebinding）
            Thread.sleep(2000);
            InetAddress[] secondResolution = InetAddress.getAllByName(host);
            
            if (secondResolution == null || secondResolution.length != firstResolution.length) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "DNS 解析结果不一致，疑似 DNS Rebinding 攻击");
            }

            // 对比两次解析的 IP 是否一致
            for (int i = 0; i < firstResolution.length; i++) {
                if (!firstResolution[i].equals(secondResolution[i])) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, 
                        "DNS 解析 IP 变化，疑似 DNS Rebinding 攻击: " + 
                        firstResolution[i].getHostAddress() + " -> " + 
                        secondResolution[i].getHostAddress());
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "URL 校验被中断");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "URL 格式错误或解析失败: " + urlString);
        }
    }

    /**
     * 检查是否为 IP 地址（而非域名）。
     */
    private static boolean isIpAddress(String host) {
        try {
            InetAddress.getByName(host);
            // 如果能成功解析且 host 本身就是 IP 格式
            return host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || 
                   host.contains(":"); // IPv6
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * 校验 IP 地址是否安全。
     */
    private static void validateIpAddress(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            validateInetAddress(addr);
        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "无效的 IP 地址: " + ip);
        }
    }

    /**
     * 校验 InetAddress 是否指向安全的网络地址。
     */
    private static void validateInetAddress(InetAddress addr) {
        String ipStr = addr.getHostAddress();

        // 1. 云服务商元数据地址（显式拦截）
        if (BLOCKED_METADATA_IPS.contains(ipStr)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                "禁止访问云元数据地址: " + ipStr);
        }

        // 2. 运营商级 NAT (100.64.0.0/10) — 包含阿里云元数据 100.100.100.200
        if (isCarrierGradeNat(ipStr)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                "禁止访问运营商级NAT地址: " + ipStr);
        }

        // 3. IPv6 ULA (fc00::/7) — Java isSiteLocalAddress() 不覆盖此范围
        if (isIpv6Ula(addr)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                "禁止访问IPv6唯一本地地址: " + ipStr);
        }

        // 4. IPv4-mapped/compatible IPv6 (::ffff:127.0.0.1 等) — 提取内嵌 IPv4 再校验
        if (isIpv4MappedIpv6(addr) || (addr instanceof Inet6Address inet6 && inet6.isIPv4CompatibleAddress())) {
            byte[] bytes = addr.getAddress();
            String embeddedIpv4 = String.format("%d.%d.%d.%d",
                bytes[12] & 0xFF, bytes[13] & 0xFF, bytes[14] & 0xFF, bytes[15] & 0xFF);
            if (BLOCKED_METADATA_IPS.contains(embeddedIpv4)
                    || isCarrierGradeNat(embeddedIpv4)
                    || isPrivateIpv4(embeddedIpv4)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "禁止访问通过IPv4-mapped IPv6嵌套的内网地址: " + embeddedIpv4);
            }
        }

        // 5. 标准 InetAddress 检查
        if (addr.isLoopbackAddress()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                "禁止访问本地回环地址: " + ipStr);
        }

        if (addr.isSiteLocalAddress()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                "禁止访问内网地址: " + ipStr);
        }

        if (addr.isLinkLocalAddress()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                "禁止访问链路本地地址: " + ipStr);
        }

        if (addr.isAnyLocalAddress()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                "禁止访问任意地址: " + ipStr);
        }

        if (addr.isMulticastAddress()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                "禁止访问多播地址: " + ipStr);
        }
    }

    /**
     * 检查是否为运营商级NAT地址 (100.64.0.0/10)。
     * 第一字节=100, 第二字节在64-127范围内。
     */
    private static boolean isCarrierGradeNat(String ipStr) {
        if (ipStr == null || !ipStr.contains(".")) {
            return false;
        }
        String[] parts = ipStr.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 100 && second >= 64 && second <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 检查是否为IPv4私有地址 (10/8, 172.16/12, 192.168/16)。
     */
    private static boolean isPrivateIpv4(String ipStr) {
        if (ipStr == null || !ipStr.contains(".")) {
            return false;
        }
        String[] parts = ipStr.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 10) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 检查是否为IPv6 ULA地址 (fc00::/7)。
     * 第一字节的最高7位为1111110, 即0xFC或0xFD。
     */
    private static boolean isIpv6Ula(InetAddress addr) {
        if (!(addr instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = addr.getAddress();
        return (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * 检查是否为IPv4-mapped IPv6地址 (::ffff:a.b.c.d)。
     * 前10字节为0, 第11-12字节为0xFF, 后4字节为IPv4地址。
     */
    private static boolean isIpv4MappedIpv6(InetAddress addr) {
        if (!(addr instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = addr.getAddress();
        if (bytes.length != 16) {
            return false;
        }
        // 前 80 位必须为 0
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        // 第 10-11 字节必须为 0xFFFF
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    /**
     * 检查域名是否在白名单中。
     */
    private static boolean isAllowedHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase();
        // 精确匹配或子域名匹配
        for (String allowed : ALLOWED_HOSTS) {
            if (normalized.equals(allowed) || normalized.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private UrlSecurityValidator() {
        // 工具类，禁止实例化
    }
}
