import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  parseDockerStats,
  parsePostgresBggIdSet,
  parsePostgresGameCount,
  parseK6Summary,
  parsePostgresActivity,
  parseWorkload,
  summarizeResourceSampling,
} from "./game-list-concurrency.mjs";

const scriptPath = fileURLToPath(new URL("./game-list-concurrency.mjs", import.meta.url));

test("Docker stats에서 App/DB CPU·memory 자원을 추출한다", () => {
  const result = parseDockerStats(
    '{"Name":"app1","CPUPerc":"2.50%","MemUsage":"10.0MiB / 1GiB","MemPerc":"0.98%","PIDs":"3"}',
    "app1",
  );

  assert.equal(result.cpuPercent, 2.5);
  assert.equal(result.memoryUsageBytes, 10 * 1024 ** 2);
  assert.equal(result.memoryLimitBytes, 1024 ** 3);
  assert.equal(result.memoryPercent, 0.98);
  assert.equal(result.pids, 3);
});

test("PostgreSQL pg_stat_activity 요약을 추출한다", () => {
  assert.deepEqual(parsePostgresActivity("100|8|3|5|1\n"), {
    maxConnections: 100,
    totalConnections: 8,
    activeConnections: 3,
    idleConnections: 5,
    waitingConnections: 1,
  });
});

test("PostgreSQL fixture 게임 수와 BGG ID 집합 지문을 추출한다", () => {
  assert.equal(parsePostgresGameCount("3\n"), 3);
  assert.deepEqual(parsePostgresBggIdSet("10\n20\n"), {
    count: 2,
    bggIdSetSha256: "4a1547d4d4d247f66eb1df3b9a673357715e8e64761f48b32fa592ae764d2383",
  });
  assert.throws(() => parsePostgresGameCount("not-a-count"), /row count/u);
  assert.throws(() => parsePostgresBggIdSet("20\n10\n"), /정렬된 양의 정수/u);
});

test("게임 목록 workload 선택값을 제한한다", () => {
  assert.equal(parseWorkload("relation"), "relation");
  assert.throws(() => parseWorkload("unknown"), /mixed, base, relation, complex/u);
});

test("k6 summary에서 p50/p95/p99·처리량·오류율을 추출한다", () => {
  const result = parseK6Summary({
    metrics: {
      http_req_duration: { values: { med: 12, "p(95)": 30, "p(99)": 45, max: 60 } },
      http_reqs: { values: { count: 120, rate: 4 } },
      http_req_failed: { values: { rate: 0.01, passes: 2 } },
      checks: { values: { rate: 0.99, count: 120 } },
    },
  });

  assert.deepEqual(result.http, {
    p50Ms: 12,
    p95Ms: 30,
    p99Ms: 45,
    maxMs: 60,
    requestCount: 120,
    throughputRps: 4,
    failedRate: 0.01,
    failedCount: 2,
    checksRate: 0.99,
    checkCount: 120,
  });
});

test("k6 2.x summary의 metric value 형식도 추출한다", () => {
  const result = parseK6Summary({
    metrics: {
      http_req_duration: { med: 12, "p(95)": 30, "p(99)": 45, max: 60 },
      http_reqs: { count: 120, rate: 4 },
      http_req_failed: { value: 0, fails: 0 },
      checks: { value: 1, passes: 120, fails: 0 },
    },
  });

  assert.equal(result.http.p95Ms, 30);
  assert.equal(result.http.p99Ms, 45);
  assert.equal(result.http.throughputRps, 4);
  assert.equal(result.http.failedRate, 0);
  assert.equal(result.http.checksRate, 1);
});

test("p99가 없는 k6 summary는 fail-closed로 거절한다", () => {
  assert.throws(
    () => parseK6Summary({
      metrics: {
        http_req_duration: { values: { med: 12, "p(95)": 30, max: 60 } },
        http_reqs: { values: { count: 120, rate: 4 } },
        http_req_failed: { values: { rate: 0, fails: 0 } },
        checks: { values: { rate: 1, count: 120 } },
      },
    }),
    /k6 p\(99\)/u,
  );
});

test("자원 표본 수와 설정 대비 실제 간격을 기록한다", () => {
  assert.deepEqual(
    summarizeResourceSampling([
      { at: "2026-08-20T00:00:00.000Z" },
      { at: "2026-08-20T00:00:01.000Z" },
      { at: "2026-08-20T00:00:02.500Z" },
    ], 1000),
    {
      configuredIntervalMs: 1000,
      sampleCount: 3,
      observedIntervalMs: { min: 1000, max: 1500, average: 1250 },
    },
  );
});

test("--help는 측정 대상 컨테이너 없이 실행할 수 있다", () => {
  const result = spawnSync(process.execPath, [path.resolve(scriptPath), "--help"], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(result.stdout, /게임 목록 동시 부하·자원 측정/u);
});
