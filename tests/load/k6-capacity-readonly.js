import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";
import { Gauge, Rate } from "k6/metrics";

const DEFAULT_BASE_URL = "https://wlcloudai.com";
const BASE_URL = (__ENV.BASE_URL || DEFAULT_BASE_URL).replace(/\/+$/, "");
const RESULTS_DIR = __ENV.RESULTS_DIR || "tests/load/results";

const businessFailureRate = new Rate("api_business_failed");
const activeVus = new Gauge("capacity_active_vus");

const endpoints = [
  {
    id: "ping",
    name: "GET /api/v1/ping",
    path: "/api/v1/ping",
  },
  {
    id: "tool_categories",
    name: "GET /api/v1/tool-categories",
    path: "/api/v1/tool-categories",
  },
  {
    id: "model_options",
    name: "GET /api/v1/model-options",
    path: "/api/v1/model-options",
  },
  {
    id: "tools",
    name: "GET /api/v1/tools",
    path: "/api/v1/tools?pageNo=1&pageSize=20",
  },
];

const endpointThresholds = {};
for (const endpoint of endpoints) {
  endpointThresholds[`http_reqs{endpoint:${endpoint.id}}`] = ["count>0"];
  endpointThresholds[`http_req_duration{endpoint:${endpoint.id}}`] = ["p(95)<500"];
  endpointThresholds[`http_req_failed{endpoint:${endpoint.id}}`] = ["rate<0.01"];
  endpointThresholds[`api_business_failed{endpoint:${endpoint.id}}`] = ["rate<0.01"];
}

const capacityPlan = [
  { id: "qps_100", targetQps: 100, rampSeconds: 10, holdSeconds: 180 },
  { id: "qps_300", targetQps: 300, rampSeconds: 15, holdSeconds: 180 },
  { id: "qps_500", targetQps: 500, rampSeconds: 15, holdSeconds: 180 },
  { id: "qps_1000", targetQps: 1000, rampSeconds: 15, holdSeconds: 180 },
  { id: "qps_1500", targetQps: 1500, rampSeconds: 15, holdSeconds: 180 },
  { id: "qps_2000", targetQps: 2000, rampSeconds: 15, holdSeconds: 180 },
];

const capacityStages = [];
const capacityWindows = [];
const stageThresholds = {};
let scheduledSeconds = 0;
for (const stage of capacityPlan) {
  capacityStages.push({ duration: `${stage.rampSeconds}s`, target: stage.targetQps });
  scheduledSeconds += stage.rampSeconds;
  capacityWindows.push({
    ...stage,
    startSeconds: scheduledSeconds,
    endSeconds: scheduledSeconds + stage.holdSeconds,
  });
  capacityStages.push({ duration: `${stage.holdSeconds}s`, target: stage.targetQps });
  scheduledSeconds += stage.holdSeconds;

  const selector = `{capacity_stage:${stage.id}}`;
  stageThresholds[`http_reqs${selector}`] = ["count>0"];
  stageThresholds[`http_req_duration${selector}`] = ["p(95)<500"];
  stageThresholds[`http_req_failed${selector}`] = ["rate<0.01"];
  stageThresholds[`api_business_failed${selector}`] = ["rate<0.01"];
  stageThresholds[`capacity_active_vus${selector}`] = ["value>=0"];
  for (const endpoint of endpoints) {
    stageThresholds[
      `http_reqs{capacity_stage:${stage.id},endpoint:${endpoint.id}}`
    ] = ["count>0"];
  }
}

// Scheduled duration: 10s initial ramp + 6 * 3m plateaus + 5 * 15s ramps = 19m25s.
export const options = {
  scenarios: {
    capacity_readonly: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: 600,
      maxVUs: 2500,
      stages: capacityStages,
      gracefulStop: "30s",
    },
  },
  userAgent: "k6-capacity-readonly/1.0",
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
  thresholds: {
    http_req_failed: [
      {
        threshold: "rate<0.01",
        abortOnFail: true,
        delayAbortEval: "1m",
      },
    ],
    http_req_duration: [
      {
        threshold: "p(95)<500",
        abortOnFail: true,
        delayAbortEval: "1m",
      },
    ],
    api_business_failed: [
      {
        threshold: "rate<0.01",
        abortOnFail: true,
        delayAbortEval: "1m",
      },
    ],
    ...endpointThresholds,
    ...stageThresholds,
  },
};

export function setup() {
  return { startEpochMs: Date.now() };
}

export default function (setupData) {
  const iteration = exec.scenario.iterationInTest;
  const endpoint = endpoints[iteration % endpoints.length];
  const elapsedSeconds = (Date.now() - setupData.startEpochMs) / 1000;
  const stage = capacityStageAt(elapsedSeconds);
  const tags = requestTags(endpoint, stage.id);

  if (stage.targetQps && iteration % 100 === 0) {
    activeVus.add(exec.instance.vusActive, { capacity_stage: stage.id });
  }

  const response = http.get(`${BASE_URL}${endpoint.path}`, {
    headers: {
      Accept: "application/json",
      "X-Load-Test": "production-capacity-readonly",
    },
    tags,
    timeout: "10s",
    responseCallback: http.expectedStatuses(200),
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
  businessFailureRate.add(apiSucceeded ? 0 : 1, tags);
}

function capacityStageAt(elapsedSeconds) {
  for (const stage of capacityWindows) {
    if (elapsedSeconds >= stage.startSeconds && elapsedSeconds < stage.endSeconds) {
      return stage;
    }
    if (elapsedSeconds < stage.startSeconds) {
      return { id: `ramp_to_${stage.targetQps}`, targetQps: null };
    }
  }
  return { id: "graceful_stop", targetQps: null };
}

function requestTags(endpoint, capacityStage) {
  return {
    name: endpoint.name,
    endpoint: endpoint.id,
    route: endpoint.name,
    access: "anonymous",
    capacity_stage: capacityStage,
    test_type: "production_capacity_readonly",
  };
}

export function handleSummary(data) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
  const outputPath = `${RESULTS_DIR}/capacity-summary-${timestamp}.json`;
  const stageReports = capacityWindows.map(capacityStageReport);
  const lines = [
    "\nProduction read-only capacity test summary",
    `requests: ${formatNumber(metricValue(data, "http_reqs", "count"), 0)}`,
    `average QPS: ${formatNumber(metricValue(data, "http_reqs", "rate"), 2)}`,
    `P95: ${formatNumber(metricValue(data, "http_req_duration", "p(95)"), 2)} ms`,
    `HTTP failure rate: ${formatPercent(metricValue(data, "http_req_failed", "rate"))}`,
    `API business failure rate: ${formatPercent(metricValue(data, "api_business_failed", "rate"))}`,
    `dropped iterations: ${formatNumber(metricValue(data, "dropped_iterations", "count"), 0)}`,
    "stage       target  actual  achieved  dropped  drop%   P50     P95     P99     max     HTTP fail  API fail  max VU  valid",
  ];

  for (const stage of stageReports) {
    lines.push(
      `${stage.id.padEnd(11)} ${formatNumber(stage.targetQps, 0).padStart(6)} `
        + `${formatNumber(stage.actualQps, 2).padStart(7)} `
        + `${formatPercent(stage.achievementRate).padStart(9)} `
        + `${formatNumber(stage.estimatedDroppedIterations, 0).padStart(7)} `
        + `${formatPercent(stage.estimatedDroppedRate).padStart(7)} `
        + `${formatNumber(stage.p50Ms, 1).padStart(7)} `
        + `${formatNumber(stage.p95Ms, 1).padStart(7)} `
        + `${formatNumber(stage.p99Ms, 1).padStart(7)} `
        + `${formatNumber(stage.maxMs, 1).padStart(7)} `
        + `${formatPercent(stage.httpFailureRate).padStart(10)} `
        + `${formatPercent(stage.businessFailureRate).padStart(9)} `
        + `${formatNumber(stage.maxVus, 0).padStart(7)}  ${stage.valid ? "yes" : "no"}`,
    );
  }

  for (const endpoint of endpoints) {
    const selector = `{endpoint:${endpoint.id}}`;
    lines.push(
      `${endpoint.name}: requests=${formatNumber(metricValue(data, `http_reqs${selector}`, "count"), 0)} `
        + `QPS=${formatNumber(metricValue(data, `http_reqs${selector}`, "rate"), 2)} `
        + `P95=${formatNumber(metricValue(data, `http_req_duration${selector}`, "p(95)"), 2)}ms `
        + `failed=${formatPercent(metricValue(data, `http_req_failed${selector}`, "rate"))}`,
    );
  }

  lines.push(
    "A stage is valid only when achieved QPS >= 99% and estimated dropped <= 0.1%.",
    "An invalid stage may indicate load-generator capacity, not the server limit.",
    "The 2000 QPS stage is mixed total traffic, approximately 500 QPS per endpoint.",
    `JSON summary: ${outputPath}`,
    "",
  );
  const summaryDocument = {
    ...data,
    capacityReport: {
      scheduledDurationSeconds: scheduledSeconds,
      mixedScenario: true,
      endpointCount: endpoints.length,
      globalDroppedIterations: metricValue(data, "dropped_iterations", "count"),
      stages: stageReports,
    },
  };
  return {
    stdout: lines.join("\n"),
    [outputPath]: JSON.stringify(summaryDocument, null, 2),
  };

  function capacityStageReport(stage) {
    const selector = `{capacity_stage:${stage.id}}`;
    const requestCount = metricValue(data, `http_reqs${selector}`, "count");
    const expectedRequests = stage.targetQps * stage.holdSeconds;
    const estimatedDroppedIterations = Math.max(expectedRequests - requestCount, 0);
    const achievementRate = expectedRequests > 0 ? requestCount / expectedRequests : 0;
    const estimatedDroppedRate = expectedRequests > 0
      ? estimatedDroppedIterations / expectedRequests
      : 0;
    return {
      id: stage.id,
      targetQps: stage.targetQps,
      holdSeconds: stage.holdSeconds,
      requestCount,
      expectedRequests,
      actualQps: requestCount / stage.holdSeconds,
      achievementRate,
      estimatedDroppedIterations,
      estimatedDroppedRate,
      p50Ms: metricValue(data, `http_req_duration${selector}`, "med"),
      p95Ms: metricValue(data, `http_req_duration${selector}`, "p(95)"),
      p99Ms: metricValue(data, `http_req_duration${selector}`, "p(99)"),
      maxMs: metricValue(data, `http_req_duration${selector}`, "max"),
      httpFailureRate: metricValue(data, `http_req_failed${selector}`, "rate"),
      businessFailureRate: metricValue(data, `api_business_failed${selector}`, "rate"),
      maxVus: metricValue(data, `capacity_active_vus${selector}`, "max"),
      valid: achievementRate >= 0.99 && estimatedDroppedRate <= 0.001,
      endpoints: endpoints.map((endpoint) => {
        const endpointSelector = `{capacity_stage:${stage.id},endpoint:${endpoint.id}}`;
        const endpointRequestCount = metricValue(
          data,
          `http_reqs${endpointSelector}`,
          "count",
        );
        return {
          id: endpoint.id,
          route: endpoint.name,
          requestCount: endpointRequestCount,
          actualQps: endpointRequestCount / stage.holdSeconds,
          expectedQps: stage.targetQps / endpoints.length,
        };
      }),
    };
  }
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
