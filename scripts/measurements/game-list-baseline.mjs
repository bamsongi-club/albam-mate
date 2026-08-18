#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { execFileSync } from "node:child_process";
import { performance } from "node:perf_hooks";

const DEFAULT_BASE_URL = "http://127.0.0.1:5173";
const DEFAULT_WARM_UP_RUNS = 5;
const DEFAULT_MEASURED_RUNS = 20;
const DEFAULT_DATASET_SIZE = 170005;
const DEFAULT_DATASET_SHA256 = "09da6ecbc6f3be18b4233a26a4715b7af0011929b3e5c2b549b1b021dc5fa079";

function parseArgs(argv) {
  const options = {
    baseUrl: DEFAULT_BASE_URL,
    warmUpRuns: DEFAULT_WARM_UP_RUNS,
    measuredRuns: DEFAULT_MEASURED_RUNS,
    datasetSize: DEFAULT_DATASET_SIZE,
    datasetSha256: DEFAULT_DATASET_SHA256,
    serverCommit: null,
    outputDirectory: "docs/measurements/results/game-list-740",
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const next = () => {
      const value = argv[index + 1];
      if (value === undefined) {
        throw new Error(`${argument} 값이 필요합니다.`);
      }
      index += 1;
      return value;
    };

    switch (argument) {
      case "--base-url":
        options.baseUrl = next().replace(/\/$/, "");
        break;
      case "--warm-up":
        options.warmUpRuns = positiveInteger(next(), argument);
        break;
      case "--runs":
        options.measuredRuns = positiveInteger(next(), argument);
        break;
      case "--dataset-size":
        options.datasetSize = positiveInteger(next(), argument);
        break;
      case "--dataset-sha256":
        options.datasetSha256 = next();
        break;
      case "--server-commit":
        options.serverCommit = commitSha(next(), argument);
        break;
      case "--output-directory":
        options.outputDirectory = next();
        break;
      case "--help":
        printHelp();
        process.exit(0);
        break;
      default:
        throw new Error(`알 수 없는 인자입니다: ${argument}`);
    }
  }

  if (options.measuredRuns < 20) {
    throw new Error("#740 기준을 지키기 위해 --runs는 20 이상이어야 합니다.");
  }
  if (!options.serverCommit) {
    throw new Error("--server-commit은 측정 대상 서버의 commit SHA가 필요합니다.");
  }
  return options;
}

function positiveInteger(raw, optionName) {
  const value = Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${optionName}은 1 이상의 정수여야 합니다: ${raw}`);
  }
  return value;
}

function commitSha(raw, optionName) {
  if (!/^[0-9a-f]{7,40}$/u.test(raw)) {
    throw new Error(`${optionName}은 7~40자리 소문자 hexadecimal commit SHA여야 합니다: ${raw}`);
  }
  return raw;
}

function printHelp() {
  console.log(`Usage: node scripts/measurements/game-list-baseline.mjs [options]\n\n` +
    `Options:\n` +
    `  --base-url <url>          API base URL (default: ${DEFAULT_BASE_URL})\n` +
    `  --warm-up <n>             warm-up calls per scenario (default: ${DEFAULT_WARM_UP_RUNS})\n` +
    `  --runs <n>                measured calls per scenario, >= 20 (default: ${DEFAULT_MEASURED_RUNS})\n` +
    `  --dataset-size <n>        loaded game row count (default: ${DEFAULT_DATASET_SIZE})\n` +
    `  --dataset-sha256 <sha>    source dataset SHA-256\n` +
    `  --server-commit <sha>     measured server commit SHA (required)\n` +
    `  --output-directory <dir>  result directory\n`);
}

async function fetchJson(url) {
  const response = await fetch(url, { headers: { Accept: "application/json" } });
  const text = await response.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error(`${url} 응답이 JSON이 아닙니다. status=${response.status}`);
  }
  if (!response.ok) {
    throw new Error(`${url} 호출 실패: status=${response.status}, body=${text.slice(0, 500)}`);
  }
  return body;
}

function firstCode(response, label) {
  const code = response?.data?.find((item) => typeof item?.code === "string" && item.code.length > 0)?.code;
  if (!code) {
    throw new Error(`${label} 메타데이터에서 측정에 사용할 code를 찾지 못했습니다.`);
  }
  return code;
}

async function discoverScenarioValues(baseUrl) {
  const base = await fetchJson(
    `${baseUrl}/api/games?upcomingOnly=false&playerCountExact=false&page=0&size=24`,
  );
  const firstGame = base?.data?.content?.[0];
  const keywordCandidate = [firstGame?.name, firstGame?.englishName]
    .find((value) => typeof value === "string" && value.trim().length >= 2);
  if (!keywordCandidate) {
    throw new Error("기본 목록 첫 페이지에서 keyword 시나리오용 게임명을 찾지 못했습니다.");
  }

  const [themes, mechanisms] = await Promise.all([
    fetchJson(`${baseUrl}/api/game-themes`),
    fetchJson(`${baseUrl}/api/game-mechanisms`),
  ]);

  return {
    keyword: keywordCandidate.trim(),
    theme: firstCode(themes, "theme"),
    mechanism: firstCode(mechanisms, "mechanism"),
  };
}

function scenarios(values) {
  const common = {
    upcomingOnly: "false",
    playerCountExact: "false",
    page: "0",
    size: "24",
  };
  return [
    { name: "base", params: common },
    { name: "keyword", params: { ...common, keyword: values.keyword } },
    { name: "player-count", params: { ...common, playerCount: "4" } },
    {
      name: "relation-theme-mechanism",
      params: { ...common, theme: values.theme, mechanism: values.mechanism },
    },
    {
      name: "complex",
      params: {
        ...common,
        playerCount: "4",
        complexityMin: "2.00",
        complexityMax: "4.00",
        theme: values.theme,
        mechanism: values.mechanism,
      },
    },
    {
      name: "flags-upcoming-exact",
      params: {
        upcomingOnly: "true",
        playerCountExact: "true",
        playerCountMin: "4",
        playerCountMax: "4",
        page: "0",
        size: "24",
      },
    },
  ];
}

function scenarioUrl(baseUrl, scenario) {
  const params = new URLSearchParams(scenario.params);
  return `${baseUrl}/api/games?${params.toString()}`;
}

async function requestOnce(url) {
  const startedAt = performance.now();
  const response = await fetch(url, {
    headers: { Accept: "application/json", "Cache-Control": "no-cache" },
  });
  const bytes = (await response.arrayBuffer()).byteLength;
  const elapsedMs = performance.now() - startedAt;
  return { status: response.status, elapsedMs, bytes };
}

function percentile(sortedValues, percentileValue) {
  if (sortedValues.length === 0) {
    throw new Error("percentile 계산 대상이 비어 있습니다.");
  }
  const rank = Math.max(1, Math.ceil(percentileValue * sortedValues.length));
  return sortedValues[rank - 1];
}

function summarize(samples) {
  const elapsed = samples.map((sample) => sample.elapsedMs).sort((a, b) => a - b);
  const statuses = Object.fromEntries(
    [...new Set(samples.map((sample) => sample.status))]
      .sort((a, b) => a - b)
      .map((status) => [status, samples.filter((sample) => sample.status === status).length]),
  );
  return {
    p50Ms: percentile(elapsed, 0.5),
    p95Ms: percentile(elapsed, 0.95),
    maxMs: elapsed.at(-1),
    minMs: elapsed[0],
    statuses,
    responseBytes: [...new Set(samples.map((sample) => sample.bytes))].sort((a, b) => a - b),
  };
}

async function measureScenario(baseUrl, scenario, warmUpRuns, measuredRuns) {
  const url = scenarioUrl(baseUrl, scenario);
  const samples = [];

  try {
    for (let index = 0; index < warmUpRuns; index += 1) {
      const warmUp = await requestOnce(url);
      if (warmUp.status !== 200) {
        throw new Error(`${scenario.name} warm-up 실패: status=${warmUp.status}, url=${url}`);
      }
    }

    for (let index = 0; index < measuredRuns; index += 1) {
      const sample = await requestOnce(url);
      samples.push({ run: index + 1, ...sample });
      if (sample.status !== 200) {
        throw new Error(`${scenario.name} 실측 실패: run=${index + 1}, status=${sample.status}, url=${url}`);
      }
    }

    return {
      name: scenario.name,
      url,
      params: scenario.params,
      status: "success",
      samples,
      summary: summarize(samples),
      error: null,
    };
  } catch (error) {
    return {
      name: scenario.name,
      url,
      params: scenario.params,
      status: "failed",
      samples,
      summary: null,
      error: { message: errorMessage(error) },
    };
  }
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}

function currentGitCommit() {
  try {
    return execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim();
  } catch {
    return "UNKNOWN";
  }
}

function csvEscape(value) {
  const text = String(value);
  return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function writeArtifacts(options, discovered, results, failure) {
  fs.mkdirSync(options.outputDirectory, { recursive: true });
  const timestamp = new Date().toISOString().replaceAll(":", "-");
  const baseName = `game-list-740-${timestamp}`;
  const jsonPath = path.join(options.outputDirectory, `${baseName}.json`);
  const csvPath = path.join(options.outputDirectory, `${baseName}.csv`);

  const report = {
    issue: 740,
    status: failure ? "failed" : "success",
    measuredAt: new Date().toISOString(),
    runnerCommit: currentGitCommit(),
    serverCommit: options.serverCommit,
    baseUrl: options.baseUrl,
    dataset: { gameCount: options.datasetSize, sha256: options.datasetSha256 },
    warmUpRuns: options.warmUpRuns,
    measuredRuns: options.measuredRuns,
    discovered,
    percentileMethod: "nearest-rank (ceil(p * N))",
    failure: failure ? { message: errorMessage(failure) } : null,
    results,
  };
  fs.writeFileSync(jsonPath, `${JSON.stringify(report, null, 2)}\n`);

  const rows = [["scenario", "status", "p50_ms", "p95_ms", "max_ms", "min_ms", "statuses", "url", "error"]];
  for (const result of results) {
    const summary = result.summary;
    rows.push([
      result.name,
      result.status,
      summary ? summary.p50Ms.toFixed(3) : "",
      summary ? summary.p95Ms.toFixed(3) : "",
      summary ? summary.maxMs.toFixed(3) : "",
      summary ? summary.minMs.toFixed(3) : "",
      JSON.stringify(summary?.statuses ?? {}),
      result.url,
      result.error?.message ?? "",
    ]);
  }
  fs.writeFileSync(csvPath, `${rows.map((row) => row.map(csvEscape).join(",")).join("\n")}\n`);
  return { jsonPath, csvPath };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  console.log(`[game-list-740] target=${options.baseUrl}`);
  console.log(`[game-list-740] warm-up=${options.warmUpRuns}, measured=${options.measuredRuns}`);
  console.log(`[game-list-740] server-commit=${options.serverCommit}`);
  let discovered = null;
  const results = [];
  let failure = null;

  try {
    discovered = await discoverScenarioValues(options.baseUrl);
    console.log(`[game-list-740] discovered keyword=${JSON.stringify(discovered.keyword)}, theme=${discovered.theme}, mechanism=${discovered.mechanism}`);

    for (const scenario of scenarios(discovered)) {
      console.log(`[game-list-740] measuring ${scenario.name}`);
      const result = await measureScenario(
        options.baseUrl,
        scenario,
        options.warmUpRuns,
        options.measuredRuns,
      );
      results.push(result);
      if (result.status === "failed") {
        failure = new Error(result.error.message);
        console.error(`[game-list-740] ${scenario.name}: FAILED ${result.error.message}`);
        break;
      }
      console.log(
        `[game-list-740] ${scenario.name}: p50=${result.summary.p50Ms.toFixed(3)}ms ` +
          `p95=${result.summary.p95Ms.toFixed(3)}ms max=${result.summary.maxMs.toFixed(3)}ms`,
      );
    }
  } catch (error) {
    failure = error;
  }

  const artifacts = writeArtifacts(options, discovered, results, failure);
  console.log(`[game-list-740] json=${artifacts.jsonPath}`);
  console.log(`[game-list-740] csv=${artifacts.csvPath}`);
  if (failure) {
    throw failure;
  }
}

main().catch((error) => {
  console.error(`[game-list-740] FAILED: ${error.stack ?? error.message}`);
  process.exitCode = 1;
});
