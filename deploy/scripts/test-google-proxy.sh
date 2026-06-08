#!/bin/sh
# ICMP ping 不走 HTTP 代理；用此脚本验证 mihomo 出站是否正常。
curl -s -o /dev/null -w "google_via_proxy:%{http_code}\n" -x http://127.0.0.1:7890 -L --max-time 20 https://www.google.com
