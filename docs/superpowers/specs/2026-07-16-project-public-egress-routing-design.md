# Project-scoped public egress routing

## Decision

Phase 1 uses explicit application-layer routing. Mihomo is attached only to this project's Compose default network. Project code can address it as `http://mihomo:7890`; the host publishes only a loopback diagnostic port. No host route, Docker daemon default, or unrelated container network is changed.

Business traffic defaults to `DIRECT`. A request enters Mihomo only when its target hostname matches an enabled `PROXY` or `AUTO` rule. `AUTO` means the Worker sends the matching request to Mihomo, then a Mihomo `url-test` group chooses `DIRECT` or `PROXY`. Diagnostics use independent `HEAD` probes and never duplicate a business POST.

## Configuration boundaries

- Upstream node configuration: subscription URL or manual HTTP/HTTPS/SOCKS5 host, port and credentials. Only `MihomoConfigRenderer` consumes these values.
- Business routing configuration: domain pattern, DIRECT/PROXY/AUTO strategy, priority and enabled state. Task and Worker payloads contain no upstream SOCKS URL or credential.
- Project proxy locator: the only executable proxy URL in a new task policy is `http://mihomo:7890`.
- Business fallback: `DIRECT`. The API calls this `businessFallback`; it is distinct from implementation details inside Mihomo.

Legacy `outbound.proxy.url` and `outbound.proxy.enabledByDefault` are cleared when node or routing configuration is saved. Historical model-level `proxyUrl` values remain in audit data but are ignored at runtime. Existing `model_snapshot_json` is never rewritten. Each execution, including an admin retry, keeps historical model and credential fields but resolves a fresh runtime `proxyPolicy` from the snapshot `baseUrl` and current routing rules.

## Rule semantics

- `EXACT api.example.com`: only that hostname.
- `SUFFIX example.com`: the root hostname and all subdomains.
- `WILDCARD *.example.com`: subdomains only, not the root hostname.

Ordering is deterministic: pattern specificity, longer domain, descending priority, pattern, then rule ID. The renderer, Backend resolver and Worker request matcher use the same contract. Unmatched requests stay DIRECT and do not enter Mihomo.

## Diagnostics and AUTO

The domain test runs DIRECT and project-proxy paths in parallel with `HEAD`, returning DNS, TCP, TLS and HTTP timings, status category, rolling success rate and sample count. A probe URL must use HTTPS, pass SSRF checks and use the same hostname as the staged DNS/TCP/TLS measurements. Redirects are not followed. Errors are reduced to fixed categories and never include URLs, query strings or credentials.

The diagnostics AUTO decision is advisory evidence. Runtime AUTO selection is performed by Mihomo `url-test`; tolerance provides hysteresis and interval provides probe cooldown. The probe result is not used to replay or fan out a real business request.

## Phase 1 coverage and limits

Covered now:

- Task creation and retry runtime `proxyPolicy` resolution.
- Worker requests made through `OutboundRequestsClient`, including the OpenAI Images and Suno paths that already use it.
- Per-request hostname reevaluation, so a proxied model API does not automatically proxy result CDN, upload or media URLs.

Not yet unified:

- Kling and other video clients that own separate HTTP sessions.
- Result-media persistence paths that bypass `OutboundRequestsClient`.
- Agent Service model clients.
- Complete historical traffic-hit collection. Phase 1 displays probe evidence; an actual hit ledger requires request instrumentation or a Mihomo connection/log collector.

These paths remain DIRECT after global proxy environment variables are removed. They must be migrated to the same routing client before the UI can claim full project coverage.

## Compose and deployment contract

All project application services explicitly clear uppercase and lowercase `HTTP_PROXY`, `HTTPS_PROXY` and `ALL_PROXY` so an old production `.env` cannot silently restore global proxying. Deployment entry points remove legacy application proxy keys idempotently from root and deploy environment files. Unrelated containers are not attached to the project network and receive no environment changes.

## Transparent egress alternative

Application-layer routing cannot control arbitrary clients that do not call the routing library. If every process and protocol must follow identical rules, the alternative is a project-exclusive transparent egress gateway/TProxy:

- Every participating project container must send traffic through that gateway.
- The gateway needs `CAP_NET_ADMIN`, TUN/TProxy support and project-network firewall rules.
- DNS interception, UDP behavior, health checks and rollback need separate design and tests.
- The rules must be scoped to the project bridge; modifying host-global routes or unrelated container networks is prohibited.

The transparent design and the Phase 1 guarantee "unmatched traffic never enters Mihomo" are mutually exclusive: a transparent gateway necessarily receives all participating traffic before deciding DIRECT versus PROXY.
