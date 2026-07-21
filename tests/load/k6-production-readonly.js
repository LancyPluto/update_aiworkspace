import http from "k6/http";
import { check, fail, sleep } from "k6";
import exec from "k6/execution";
import { Rate } from "k6/metrics";

const DEFAULT_BASE_URL = "https://wlcloudai.com";
const BASE_URL = (__ENV.BASE_URL || DEFAULT_BASE_URL).replace(/\/+$/, "");
const IS_SMOKE = __ENV.SMOKE === "1";
const RESULTS_DIR = __ENV.RESULTS_DIR || "tests/load/results";

const businessFailureRate = new Rate("api_business_failed");

const anonymousEndpoints = [
  {
    name: "GET /api/v1/tool-categories",
    path: "/api/v1/tool-categories",
  },
  {
    name: "GET /api/v1/tools",
    path: "/api/v1/tools?pageNo=1&pageSize=20",
  },
  {
    name: "GET /api/v1/tools/search",
    path: "/api/v1/tools/search?keyword=AI&pageNo=1&pageSize=20",
  },
  {
    name: "GET /api/v1/model-options",
    path: "/api/v1/model-options",
  },
  {
    name: "GET /api/v1/community/posts",
    path: "/api/v1/community/posts?pageNo=1&pageSize=20",
  },
];

const authenticatedEndpoints = [
  {
    name: "GET /api/v1/users/me",
    path: "/api/v1/users/me",
  },
  {
    name: "GET /api/v1/tasks",
    path: "/api/v1/tasks?pageNo=1&pageSize=20",
  },
  {
    name: "GET /api/v1/credits/account",
    path: "/api/v1/credits/account",
  },
  {
    name: "GET /api/v1/credits/logs",
    path: "/api/v1/credits/logs?pageNo=1&pageSize=20",
  },
  {
    name: "GET /api/v1/credits/usage-logs",
    path: "/api/v1/credits/usage-logs?pageNo=1&pageSize=20",
  },
];

const productionStages = [
  { duration: "2m", target: 5 },
  { duration: "3m", target: 15 },
  { duration: "3m", target: 30 },
  { duration: "2m", target: 50 },
  { duration: "2m", target: 0 },
];

const smokeStages = [
  { duration: "20s", target: 1 },
  { duration: "10s", target: 0 },
];

export const options = {
  stages: IS_SMOKE ? smokeStages : productionStages,
  userAgent: "k6-production-readonly/1.0",
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
  thresholds: {
    http_req_failed: [
      {
        threshold: "rate<0.01",
        abortOnFail: true,
        delayAbortEval: IS_SMOKE ? "10s" : "1m",
      },
    ],
    http_req_duration: [
      {
        threshold: "p(95)<2000",
        abortOnFail: true,
        delayAbortEval: IS_SMOKE ? "10s" : "1m",
      },
    ],
    api_business_failed: ["rate<0.01"],
  },
};

export function setup() {
  const accessToken = (__ENV.K6_ACCESS_TOKEN || "").trim();
  const phone = (__ENV.PHONE || "").trim();
  const smsCode = (__ENV.SMS_CODE || "").trim();

  if (accessToken) {
    if (phone || smsCode) {
      fail("K6_ACCESS_TOKEN cannot be combined with PHONE or SMS_CODE");
    }
    return { accessToken };
  }

  if (!phone && !smsCode) {
    return { accessToken: null };
  }
  if (!phone || !smsCode) {
    fail("PHONE and SMS_CODE must either both be set or both be omitted");
  }

  const response = http.post(
    `${BASE_URL}/api/v1/auth/sms-login`,
    JSON.stringify({ phone, code: smsCode }),
    {
      headers: requestHeaders(),
      tags: requestTags("POST /api/v1/auth/sms-login", "setup_auth"),
      timeout: "10s",
    },
  );

  let body;
  try {
    body = response.json();
  } catch (_) {
    fail(`SMS login returned a non-JSON response (HTTP ${response.status})`);
  }

  const authenticated = check(response, {
    "SMS login returned HTTP 200": (res) => res.status === 200,
    "SMS login returned SUCCESS": () => body && body.code === "SUCCESS",
    "SMS login returned access token": () => Boolean(body && body.data && body.data.accessToken),
  });
  if (!authenticated) {
    fail(`SMS login failed (HTTP ${response.status}); response body is intentionally hidden`);
  }

  return { accessToken: body.data.accessToken };
}

export default function (setupData) {
  const iteration = exec.scenario.iterationInTest;
  requestEndpoint(
    anonymousEndpoints[iteration % anonymousEndpoints.length],
    "anonymous",
    null,
  );

  if (setupData.accessToken) {
    requestEndpoint(
      authenticatedEndpoints[iteration % authenticatedEndpoints.length],
      "authenticated",
      setupData.accessToken,
    );
  }

  sleep(1);
}

function requestEndpoint(endpoint, access, accessToken) {
  const response = http.get(`${BASE_URL}${endpoint.path}`, {
    headers: requestHeaders(accessToken),
    tags: requestTags(endpoint.name, access),
    timeout: "10s",
  });

  let apiSucceeded = false;
  if (response.status === 200) {
    try {
      const body = response.json();
      apiSucceeded = body && body.code === "SUCCESS";
    } catch (_) {
      apiSucceeded = false;
    }
  }

  check(response, {
    [`${endpoint.name} returned HTTP 200`]: (res) => res.status === 200,
    [`${endpoint.name} returned SUCCESS`]: () => apiSucceeded,
  });
  businessFailureRate.add(apiSucceeded ? 0 : 1, requestTags(endpoint.name, access));
}

function requestHeaders(accessToken) {
  const headers = {
    Accept: "application/json",
    "Content-Type": "application/json",
    "X-Load-Test": "production-readonly",
  };
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  return headers;
}

function requestTags(name, access) {
  return {
    name,
    endpoint: name,
    access,
    test_type: "production_readonly",
  };
}

export function handleSummary(data) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  const outputPath = `${RESULTS_DIR}/summary-${timestamp}.json`;
  const requests = metricValue(data, "http_reqs", "count");
  const qps = metricValue(data, "http_reqs", "rate");
  const p95 = metricValue(data, "http_req_duration", "p(95)");
  const failed = metricValue(data, "http_req_failed", "rate");
  const businessFailed = metricValue(data, "api_business_failed", "rate");

  return {
    stdout: [
      "\nProduction read-only load test summary",
      `requests: ${formatNumber(requests, 0)}`,
      `average QPS: ${formatNumber(qps, 2)}`,
      `P95: ${formatNumber(p95, 2)} ms`,
      `HTTP failure rate: ${formatPercent(failed)}`,
      `API business failure rate: ${formatPercent(businessFailed)}`,
      `JSON summary: ${outputPath}`,
      "",
    ].join("\n"),
    [outputPath]: JSON.stringify(data, null, 2),
  };
}

function metricValue(data, metricName, valueName) {
  const metric = data.metrics && data.metrics[metricName];
  const value = metric && metric.values && metric.values[valueName];
  return typeof value === "number" ? value : 0;
}

function formatNumber(value, digits) {
  return Number(value || 0).toFixed(digits);
}

function formatPercent(value) {
  return `${formatNumber((value || 0) * 100, 2)}%`;
}
