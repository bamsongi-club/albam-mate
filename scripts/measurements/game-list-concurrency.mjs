#!/usr/bin/env node

import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const REPOSITORY_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_K6_SCRIPT = path.join(REPOSITORY_ROOT, "load-tests/k6/yejin/09-game-list-concurrency.js");
const DEFAULT_FIXTURE_MANIFEST = path.join(
  REPOSITORY_ROOT,
  "docs/measurements/results/game-list-740/game-list-770-fixture-170005-manifest.json",
);
const DEFAULT_OUTPUT = path.join(REPOSITORY_ROOT, "build/k6/game-list-867/concurrency.json");
const HEX_SHA = /^[0-9a-f]{40}$/u;

function fail(message) {
  throw new Error(message);
}

function nonEmptyString(value, label) {
  if (typeof value !== "string" || value.trim() === "") {
    fail(`${label}은 비어 있지 않은 문자열이어야 합니다.`);
  }
  return value;
}

function positiveInteger(value, label) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    fail(`${label}은 양의 정수여야 합니다: ${value}`);
  }
  return parsed;
}

function finiteNonnegative(value, label) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    fail(`${label}은 0 이상의 유한 number여야 합니다.`);
  }
  return value;
}

function parseLevels(value) {
  const levels = nonEmptyString(value, "--levels")
    .split(",")
    .map((item) => positiveInteger(item.trim(), "동시성 단계"));
  if (new Set(levels).size !== levels.length) {
    fail("동시성 단계는 중복될 수 없습니다.");
  }
  return levels;
}

function parseArgs(argv) {
  const options = {
    baseUrl: process.env.BASE_URL || "http://127.0.0.1:5173",
    fixtureManifest: DEFAULT_FIXTURE_MANIFEST,
    serverCommit: process.env.SERVER_COMMIT || "",
    app1Container: process.env.APP1_CONTAINER || "",
    app2Container: process.env.APP2_CONTAINER || "",
    postgresContainer: process.env.POSTGRES_CONTAINER || "",
    dbUser: process.env.ALBAM_MATE_LOCAL_DB_USER || "albam_mate",
    dbName: process.env.ALBAM_MATE_LOCAL_DB_NAME || "albam_mate_local",
    levels: process.env.CONCURRENCY_LEVELS || "2,4,8",
    duration: process.env.DURATION || "20s",
    sampleIntervalMs: process.env.SAMPLE_INTERVAL_MS || "1000",
    output: DEFAULT_OUTPUT,
    script: DEFAULT_K6_SCRIPT,
    k6: process.env.K6_BIN || "k6",
    docker: process.env.DOCKER_BIN || "docker",
    runId: process.env.RUN_ID || `game-list-867-${new Date().toISOString().replace(/[:.]/gu, "-")}`,
  };
  const aliases = new Map([
    ["--base-url", "baseUrl"],
    ["--fixture-manifest", "fixtureManifest"],
    ["--server-commit", "serverCommit"],
    ["--app1-container", "app1Container"],
    ["--app2-container", "app2Container"],
    ["--postgres-container", "postgresContainer"],
    ["--db-user", "dbUser"],
    ["--db-name", "dbName"],
    ["--levels", "levels"],
    ["--duration", "duration"],
    ["--sample-interval-ms", "sampleIntervalMs"],
    ["--output", "output"],
    ["--script", "script"],
    ["--k6", "k6"],
    ["--docker", "docker"],
    ["--run-id", "runId"],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--help" || argument === "-h") {
      options.help = true;
      continue;
    }
    const key = aliases.get(argument);
    if (!key || argv[index + 1] === undefined) {
      fail(`알 수 없는 인자 또는 값이 없습니다: ${argument}`);
    }
    options[key] = argv[index + 1];
    index += 1;
  }
  if (options.help) {
    return options;
  }
  options.levels = parseLevels(options.levels);
  options.sampleIntervalMs = positiveInteger(options.sampleIntervalMs, "--sample-interval-ms");
  options.serverCommit = nonEmptyString(options.serverCommit, "--server-commit");
  if (!HEX_SHA.test(options.serverCommit)) {
    fail("--server-commit은 40자리 소문자 commit SHA여야 합니다.");
  }
  for (const [key, label] of [["app1Container", "--app1-container"], ["app2Container", "--app2-container"], ["postgresContainer", "--postgres-container"]]) {
    options[key] = nonEmptyString(options[key], label);
  }
  options.baseUrl = nonEmptyString(options.baseUrl, "--base-url");
  options.duration = nonEmptyString(options.duration, "--duration");
  options.output = path.resolve(nonEmptyString(options.output, "--output"));
  options.script = path.resolve(nonEmptyString(options.script, "--script"));
  return options;
}

function printHelp() {
  console.log(`게임 목록 동시 부하·자원 측정

필수:
  --server-commit <40자리 SHA>
  --app1-container <container>
  --app2-container <container>
  --postgres-container <container>

선택:
  --base-url <URL>                 기본값: http://127.0.0.1:5173
  --fixture-manifest <path>       170,005 fixture manifest
  --levels <2,4,8>                동시성 단계
  --duration <20s>                단계별 k6 시간
  --sample-interval-ms <1000>     App/DB 자원 표본 주기
  --output <path>                 기본값: build/k6/game-list-867/concurrency.json
`);
}

function run(command, args, { cwd = REPOSITORY_ROOT, env = process.env, maxBuffer = 4 * 1024 * 1024 } = {}) {
  const result = spawnSync(command, args, {
    cwd,
    env,
    encoding: "utf8",
    maxBuffer,
  });
  if (result.error) {
    fail(`${command} 실행 실패: ${result.error.message}`);
  }
  if (result.status !== 0) {
    fail(`${command} ${args.join(" ")} 실패 (exit=${result.status}): ${(result.stderr || result.stdout).trim()}`);
  }
  return result.stdout;
}

function sha256File(filePath) {
  return createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

function gitOutput(args) {
  return run("git", args).trim();
}

function sourceProvenance(options) {
  const runnerFileSha256 = sha256File(fileURLToPath(import.meta.url));
  const k6FileSha256 = sha256File(options.script);
  const trackedPaths = [path.relative(REPOSITORY_ROOT, fileURLToPath(import.meta.url)), path.relative(REPOSITORY_ROOT, options.script)];
  const status = run("git", ["status", "--porcelain", "--", ...trackedPaths]).trim();
  return {
    commit: gitOutput(["rev-parse", "HEAD"]),
    sourceClean: status === "",
    runnerFileSha256,
    k6FileSha256,
  };
}

function fixtureProvenance(manifestPath) {
  if (!fs.existsSync(manifestPath)) {
    fail(`fixture manifest가 없습니다: ${manifestPath}`);
  }
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  } catch (error) {
    fail(`fixture manifest JSON을 읽을 수 없습니다: ${error.message}`);
  }
  if (manifest.games?.rowCount !== 170005) {
    fail(`fixture games rowCount가 170,005가 아닙니다: ${manifest.games?.rowCount}`);
  }
  if (typeof manifest.games?.bggIdSetSha256 !== "string") {
    fail("fixture manifest의 BGG ID set SHA-256이 없습니다.");
  }
  return {
    manifestPath: path.relative(REPOSITORY_ROOT, manifestPath),
    manifestSha256: sha256File(manifestPath),
    fixtureId: nonEmptyString(manifest.fixtureId, "fixtureId"),
    gameCount: manifest.games.rowCount,
    bggIdSetSha256: manifest.games.bggIdSetSha256,
    metadata: {
      gameMechanismRelations: manifest.metadata?.gameMechanismRelations,
      gameThemeRelations: manifest.metadata?.gameThemeRelations,
      gameCategoryRelations: manifest.metadata?.gameCategoryRelations,
      gamePlayerPreferences: manifest.metadata?.gamePlayerPreferences,
    },
  };
}

function parseBytes(value) {
  const matched = /^\s*([0-9]+(?:\.[0-9]+)?)\s*([kmgtpe]?i?b)\s*$/iu.exec(value);
  if (!matched) {
    fail(`Docker memory 값을 해석할 수 없습니다: ${value}`);
  }
  const units = { b: 0, kb: 1, kib: 1, mb: 2, mib: 2, gb: 3, gib: 3, tb: 4, tib: 4, pb: 5, pib: 5, eb: 6, eib: 6 };
  const unit = matched[2].toLowerCase();
  if (!Object.hasOwn(units, unit)) {
    fail(`지원하지 않는 Docker memory 단위입니다: ${matched[2]}`);
  }
  return Number(matched[1]) * (unit.endsWith("ib") ? 1024 ** units[unit] : 1000 ** units[unit]);
}

export function parseDockerStats(output, role) {
  let record;
  try {
    record = JSON.parse(output.trim().split(/\r?\n/u)[0]);
  } catch (error) {
    fail(`${role} docker stats JSON을 읽을 수 없습니다: ${error.message}`);
  }
  const cpuPercent = Number.parseFloat(String(record.CPUPerc || "").replace("%", ""));
  const memory = String(record.MemUsage || "").split("/").map((part) => part.trim());
  const memoryPercent = Number.parseFloat(String(record.MemPerc || "").replace("%", ""));
  const pids = Number.parseInt(record.PIDs, 10);
  if (!Number.isFinite(cpuPercent) || memory.length !== 2 || !Number.isFinite(memoryPercent) || !Number.isSafeInteger(pids)) {
    fail(`${role} docker stats 필드가 올바르지 않습니다.`);
  }
  return {
    cpuPercent,
    memoryUsageBytes: parseBytes(memory[0]),
    memoryLimitBytes: parseBytes(memory[1]),
    memoryPercent,
    pids,
  };
}

function containerStats(options, container, role) {
  const output = run(options.docker, ["stats", "--no-stream", "--format", "{{json .}}", container]);
  return parseDockerStats(output, role);
}

function inspectLabels(options, container) {
  try {
    return JSON.parse(run(options.docker, ["inspect", "--format", "{{json .Config.Labels}}", container]));
  } catch (error) {
    fail(`${container} label을 읽을 수 없습니다: ${error.message}`);
  }
}

function restartCount(options, container) {
  return positiveOrZeroInteger(run(options.docker, ["inspect", "--format", "{{.RestartCount}}", container]).trim(), `${container}.RestartCount`);
}

function positiveOrZeroInteger(value, label) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0) {
    fail(`${label}은 0 이상의 정수여야 합니다: ${value}`);
  }
  return parsed;
}

function validateAppContainers(options) {
  for (const [container, role] of [[options.app1Container, "app1"], [options.app2Container, "app2"]]) {
    const labels = inspectLabels(options, container);
    if (labels?.["org.opencontainers.image.revision"] !== options.serverCommit) {
      fail(`${role} container revision이 serverCommit과 다릅니다.`);
    }
    if (labels?.["com.docker.compose.service"] && !["spring-1", "spring-2"].includes(labels["com.docker.compose.service"])) {
      fail(`${role} container가 Spring service가 아닙니다.`);
    }
  }
}

export function parsePostgresActivity(output) {
  const values = output.trim().split("|").map((value) => Number.parseInt(value, 10));
  if (values.length !== 5 || values.some((value) => !Number.isSafeInteger(value) || value < 0)) {
    fail(`PostgreSQL connection 상태를 해석할 수 없습니다: ${output}`);
  }
  return {
    maxConnections: values[0],
    totalConnections: values[1],
    activeConnections: values[2],
    idleConnections: values[3],
    waitingConnections: values[4],
  };
}

function postgresActivity(options) {
  const sql = "SELECT current_setting('max_connections')::int, count(*)::int, count(*) FILTER (WHERE state = 'active')::int, count(*) FILTER (WHERE state = 'idle')::int, count(*) FILTER (WHERE wait_event IS NOT NULL)::int FROM pg_stat_activity;";
  const output = run(options.docker, [
    "exec",
    "-e",
    "PGAPPNAME=game-list-867-resource-sampler",
    options.postgresContainer,
    "psql",
    "-U",
    options.dbUser,
    "-d",
    options.dbName,
    "-At",
    "-v",
    "ON_ERROR_STOP=1",
    "-c",
    sql,
  ]);
  return parsePostgresActivity(output);
}

function resourceSample(options, at) {
  return {
    at,
    app1: containerStats(options, options.app1Container, "app1"),
    app2: containerStats(options, options.app2Container, "app2"),
    postgres: containerStats(options, options.postgresContainer, "postgres"),
    postgresConnections: postgresActivity(options),
  };
}

function k6Version(k6) {
  return run(k6, ["version"]).trim().split(/\r?\n/u)[0];
}

function parseMetric(metrics, name, label) {
  const metric = metrics?.[name];
  if (!metric?.values) {
    fail(`k6 summary에 ${label} metric이 없습니다.`);
  }
  return metric.values;
}

export function parseK6Summary(summary) {
  const metrics = summary?.metrics ?? summary;
  const duration = parseMetric(metrics, "http_req_duration", "http_req_duration");
  const requests = parseMetric(metrics, "http_reqs", "http_reqs");
  const failed = parseMetric(metrics, "http_req_failed", "http_req_failed");
  const checks = parseMetric(metrics, "checks", "checks");
  for (const [key, value] of [["med", duration.med], ["p(95)", duration["p(95)"]], ["p(99)", duration["p(99)"]], ["rate", requests.rate], ["failed rate", failed.rate], ["checks rate", checks.rate]]) {
    finiteNonnegative(value, `k6 ${key}`);
  }
  return {
    http: {
      p50Ms: duration.med,
      p95Ms: duration["p(95)"],
      p99Ms: duration["p(99)"],
      maxMs: duration.max,
      requestCount: requests.count,
      throughputRps: requests.rate,
      failedRate: failed.rate,
      failedCount: failed.fails ?? null,
      checksRate: checks.rate,
      checkCount: checks.count ?? null,
    },
  };
}

function phaseRun(options, concurrency, resourcesDirectory) {
  const phase = `vus-${concurrency}`;
  const summaryPath = path.join(resourcesDirectory, `${phase}.summary.json`);
  const logPath = path.join(resourcesDirectory, `${phase}.k6.log`);
  const args = [
    "run",
    "--quiet",
    "--no-color",
    "--summary-export",
    summaryPath,
    "--summary-trend-stats",
    "avg,min,med,max,p(90),p(95),p(99)",
    "-e",
    `BASE_URL=${options.baseUrl}`,
    "-e",
    `CONCURRENCY=${concurrency}`,
    "-e",
    `DURATION=${options.duration}`,
    "-e",
    `RUN_ID=${options.runId}`,
    "-e",
    `PHASE=${phase}`,
    options.script,
  ];
  const output = fs.openSync(logPath, "w");
  const child = spawn(options.k6, args, { cwd: REPOSITORY_ROOT, env: process.env, stdio: ["ignore", output, output] });
  return new Promise((resolve, reject) => {
    child.once("error", (error) => reject(new Error(`k6 실행 실패: ${error.message}`)));
    child.once("exit", (code, signal) => {
      fs.closeSync(output);
      if (code !== 0) {
        reject(new Error(`k6 ${phase} 실패 (exit=${code}, signal=${signal ?? "none"}). 로그: ${logPath}`));
        return;
      }
      try {
        const summary = JSON.parse(fs.readFileSync(summaryPath, "utf8"));
        resolve({
          phase,
          concurrency,
          duration: options.duration,
          summaryPath: path.relative(REPOSITORY_ROOT, summaryPath),
          summarySha256: sha256File(summaryPath),
          ...parseK6Summary(summary),
        });
      } catch (error) {
        reject(new Error(`k6 ${phase} summary를 읽을 수 없습니다: ${error.message}`));
      }
    });
  });
}

async function measurePhase(options, concurrency, resourcesDirectory) {
  const startedAt = new Date().toISOString();
  const startRestartCounts = {
    app1: restartCount(options, options.app1Container),
    app2: restartCount(options, options.app2Container),
    postgres: restartCount(options, options.postgresContainer),
  };
  const samples = [];
  const sampleErrors = [];
  const sample = () => {
    try {
      samples.push(resourceSample(options, new Date().toISOString()));
    } catch (error) {
      sampleErrors.push(error.message);
    }
  };
  sample();
  const timer = setInterval(sample, options.sampleIntervalMs);
  let result;
  try {
    result = await phaseRun(options, concurrency, resourcesDirectory);
  } finally {
    clearInterval(timer);
    sample();
  }
  if (sampleErrors.length > 0) {
    fail(`자원 표본 수집이 실패했습니다: ${sampleErrors.join("; ")}`);
  }
  return {
    ...result,
    startedAt,
    finishedAt: new Date().toISOString(),
    resourceSamples: samples,
    restartCounts: {
      start: startRestartCounts,
      end: {
      app1: restartCount(options, options.app1Container),
      app2: restartCount(options, options.app2Container),
      postgres: restartCount(options, options.postgresContainer),
      },
    },
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    printHelp();
    return;
  }
  if (!fs.existsSync(options.script)) {
    fail(`k6 script가 없습니다: ${options.script}`);
  }
  validateAppContainers(options);
  const startedAt = new Date().toISOString();
  const resourcesDirectory = path.join(path.dirname(options.output), "raw");
  fs.mkdirSync(resourcesDirectory, { recursive: true });
  const provenance = {
    runner: sourceProvenance(options),
    serverCommit: options.serverCommit,
    fixture: fixtureProvenance(path.resolve(options.fixtureManifest)),
    k6: k6Version(options.k6),
    docker: run(options.docker, ["version", "--format", "{{.Server.Version}}"]).trim(),
    node: process.version,
    host: {
      platform: process.platform,
      arch: process.arch,
      cpus: os.cpus().length,
    },
  };
  const phases = [];
  for (const concurrency of options.levels) {
    phases.push(await measurePhase(options, concurrency, resourcesDirectory));
  }
  const report = {
    schemaVersion: 1,
    report: "game-list-867-concurrency",
    status: "completed",
    runId: options.runId,
    baseUrl: options.baseUrl,
    startedAt,
    finishedAt: new Date().toISOString(),
    configuration: {
      levels: options.levels,
      duration: options.duration,
      sampleIntervalMs: options.sampleIntervalMs,
      appRoles: ["app1", "app2"],
      databaseRole: "postgres",
    },
    provenance,
    phases,
  };
  fs.mkdirSync(path.dirname(options.output), { recursive: true });
  fs.writeFileSync(options.output, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`게임 목록 동시 부하 측정 완료: ${options.output}`);
  for (const phase of phases) {
    console.log(`${phase.phase}: p50=${phase.http.p50Ms.toFixed(3)}ms p95=${phase.http.p95Ms.toFixed(3)}ms p99=${phase.http.p99Ms.toFixed(3)}ms rps=${phase.http.throughputRps.toFixed(3)} error=${(phase.http.failedRate * 100).toFixed(3)}%`);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  main().catch((error) => {
    console.error(`game-list-concurrency: ${error.message}`);
    process.exitCode = 1;
  });
}
