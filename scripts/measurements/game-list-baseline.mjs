#!/usr/bin/env node

import fs from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import process from "node:process";
import { execFileSync } from "node:child_process";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";

const DEFAULT_BASE_URL = "http://127.0.0.1:5173";
const DEFAULT_WARM_UP_RUNS = 5;
const DEFAULT_MEASURED_RUNS = 20;
const DEFAULT_DATASET_SIZE = 170005;
const DEFAULT_REQUEST_TIMEOUT_MS = 30000;
const UPSTREAM_HEADER_NAME = "x-albam-mate-upstream";
const RUNNER_FILE = fileURLToPath(import.meta.url);
const RUNNER_REPOSITORY = path.resolve(path.dirname(RUNNER_FILE), "../..");
const RUNNER_RELATIVE_PATH = path.relative(RUNNER_REPOSITORY, RUNNER_FILE);

function parseArgs(argv) {
  const options = {
    baseUrl: DEFAULT_BASE_URL,
    warmUpRuns: DEFAULT_WARM_UP_RUNS,
    measuredRuns: DEFAULT_MEASURED_RUNS,
    datasetSize: DEFAULT_DATASET_SIZE,
    datasetSha256: null,
    requestTimeoutMs: DEFAULT_REQUEST_TIMEOUT_MS,
    serverCommit: null,
    serverContainers: [],
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
        options.datasetSha256 = sha256(next(), argument);
        break;
      case "--request-timeout-ms":
        options.requestTimeoutMs = positiveInteger(next(), argument);
        break;
      case "--server-commit":
        options.serverCommit = fullCommitSha(next(), argument);
        break;
      case "--server-container": {
        const container = serverContainer(next(), argument);
        if (options.serverContainers.some(({ role }) => role === container.role)) {
          throw new Error(`${argument}에 ${container.role}을 두 번 지정할 수 없습니다.`);
        }
        options.serverContainers.push(container);
        break;
      }
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
  if (!options.datasetSha256) {
    throw new Error("--dataset-sha256은 측정 대상 데이터셋의 64자리 SHA-256이 필요합니다.");
  }
  if (!options.serverCommit) {
    throw new Error("--server-commit은 측정 대상 서버의 commit SHA가 필요합니다.");
  }
  if (options.serverContainers.length !== 2) {
    throw new Error("--server-container로 app1과 app2 Spring 컨테이너를 각각 지정해야 합니다.");
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

function fullCommitSha(raw, optionName) {
  if (!/^[0-9a-f]{40}$/u.test(raw)) {
    throw new Error(`${optionName}은 40자리 소문자 hexadecimal commit SHA여야 합니다: ${raw}`);
  }
  return raw;
}

function serverContainer(raw, optionName) {
  const matched = /^(app1|app2)=(.+)$/u.exec(raw);
  if (!matched) {
    throw new Error(`${optionName}은 app1=<container> 또는 app2=<container> 형식이어야 합니다: ${raw}`);
  }
  return { role: matched[1], name: matched[2] };
}

function sha256(raw, optionName) {
  if (!/^[0-9a-f]{64}$/u.test(raw)) {
    throw new Error(`${optionName}은 64자리 소문자 hexadecimal SHA-256이어야 합니다: ${raw}`);
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
    `  --dataset-sha256 <sha>    source dataset SHA-256 (required)\n` +
    `  --request-timeout-ms <n>  timeout per HTTP request/body read (default: ${DEFAULT_REQUEST_TIMEOUT_MS})\n` +
    `  --server-commit <sha>     measured server 40-char commit SHA (required)\n` +
    `  --server-container <role=name>  app1/app2 measured Spring container (required twice)\n` +
    `  --output-directory <dir>  result directory\n`);
}

function upstreamRoleError(role, expectedUpstreamRoles) {
  if (!role) {
    return `응답 ${UPSTREAM_HEADER_NAME} 헤더가 없습니다.`;
  }
  if (!expectedUpstreamRoles.includes(role)) {
    return `응답 ${UPSTREAM_HEADER_NAME}=${role}이 검증한 app1/app2 컨테이너와 일치하지 않습니다.`;
  }
  return null;
}

async function fetchJson(url, requestTimeoutMs, expectedUpstreamRoles) {
  const controller = new AbortController();
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, requestTimeoutMs);

  try {
    const response = await fetch(url, {
      headers: { Accept: "application/json" },
      signal: controller.signal,
    });
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
    const upstreamRole = response.headers.get(UPSTREAM_HEADER_NAME);
    const upstreamError = upstreamRoleError(upstreamRole, expectedUpstreamRoles);
    if (upstreamError) {
      throw new Error(`${url} 응답 계약 불일치: ${upstreamError}`);
    }
    return { body, upstreamRole };
  } catch (error) {
    if (timedOut || error?.name === "AbortError") {
      throw new Error(`HTTP 요청 timeout (${requestTimeoutMs}ms): ${url}`);
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

function gameListResponseError(body, expectedPage = null) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return "응답 envelope가 JSON object가 아닙니다.";
  }
  if (body.status !== 200) {
    return `응답 envelope status가 200이 아닙니다: ${body.status}`;
  }

  const page = body.data;
  if (!page || typeof page !== "object" || Array.isArray(page)) {
    return "응답 data가 page object가 아닙니다.";
  }
  if (!Array.isArray(page.content)) {
    return "응답 data.content가 array가 아닙니다.";
  }
  if (!Number.isSafeInteger(page.page) || page.page < 0) {
    return "응답 data.page가 0 이상의 정수가 아닙니다.";
  }
  if (!Number.isSafeInteger(page.size) || page.size <= 0) {
    return "응답 data.size가 1 이상의 정수가 아닙니다.";
  }
  if (!Number.isSafeInteger(page.totalElements) || page.totalElements < 0) {
    return "응답 data.totalElements가 0 이상의 정수가 아닙니다.";
  }
  if (!Number.isSafeInteger(page.totalPages) || page.totalPages < 0) {
    return "응답 data.totalPages가 0 이상의 정수가 아닙니다.";
  }
  if (typeof page.hasNext !== "boolean") {
    return "응답 data.hasNext가 boolean이 아닙니다.";
  }
  if (page.content.some((item) => !item || typeof item !== "object" || Array.isArray(item))) {
    return "응답 data.content의 game item이 object가 아닙니다.";
  }
  if (!expectedPage) {
    return null;
  }
  if (page.page !== expectedPage.page) {
    return `응답 data.page가 요청 page=${expectedPage.page}와 다릅니다: ${page.page}`;
  }
  if (page.size !== expectedPage.size) {
    return `응답 data.size가 요청 size=${expectedPage.size}와 다릅니다: ${page.size}`;
  }
  const expectedTotalPages = Math.ceil(page.totalElements / expectedPage.size);
  if (page.totalPages !== expectedTotalPages) {
    return `응답 data.totalPages가 totalElements/size와 일치하지 않습니다: expected=${expectedTotalPages}, actual=${page.totalPages}`;
  }
  const expectedContentLength = Math.min(
    expectedPage.size,
    Math.max(0, page.totalElements - (expectedPage.page * expectedPage.size)),
  );
  if (page.content.length !== expectedContentLength) {
    return `응답 data.content 길이가 요청 page/size와 일치하지 않습니다: expected=${expectedContentLength}, actual=${page.content.length}`;
  }
  const expectedHasNext = expectedPage.page + 1 < expectedTotalPages;
  if (page.hasNext !== expectedHasNext) {
    return `응답 data.hasNext가 page/totalPages와 일치하지 않습니다: expected=${expectedHasNext}, actual=${page.hasNext}`;
  }
  return null;
}

function pageMetadata(body) {
  const page = body.data;
  return {
    page: page.page,
    size: page.size,
    totalElements: page.totalElements,
    totalPages: page.totalPages,
    hasNext: page.hasNext,
    contentLength: page.content.length,
  };
}

function firstCode(response, label) {
  const code = response?.data?.find((item) => typeof item?.code === "string" && item.code.length > 0)?.code;
  if (!code) {
    throw new Error(`${label} 메타데이터에서 측정에 사용할 code를 찾지 못했습니다.`);
  }
  return code;
}

async function discoverScenarioValues(baseUrl, requestTimeoutMs, expectedDatasetSize, expectedUpstreamRoles) {
  const baseResponse = await fetchJson(
    `${baseUrl}/api/games?upcomingOnly=false&playerCountExact=false&page=0&size=24`,
    requestTimeoutMs,
    expectedUpstreamRoles,
  );
  const base = baseResponse.body;
  const baseResponseError = gameListResponseError(base, { page: 0, size: 24 });
  if (baseResponseError) {
    throw new Error(`base discovery 응답 계약 불일치: ${baseResponseError}`);
  }
  if (base.data.totalElements !== expectedDatasetSize) {
    throw new Error(
      `base discovery dataset count 불일치: expected=${expectedDatasetSize}, actual=${base.data.totalElements}`,
    );
  }
  const firstGame = base?.data?.content?.[0];
  const keywordCandidate = [firstGame?.name, firstGame?.englishName]
    .find((value) => typeof value === "string" && value.trim().length >= 2);
  if (!keywordCandidate) {
    throw new Error("기본 목록 첫 페이지에서 keyword 시나리오용 게임명을 찾지 못했습니다.");
  }

  const [themesResponse, mechanismsResponse] = await Promise.all([
    fetchJson(`${baseUrl}/api/game-themes`, requestTimeoutMs, expectedUpstreamRoles),
    fetchJson(`${baseUrl}/api/game-mechanisms`, requestTimeoutMs, expectedUpstreamRoles),
  ]);

  return {
    keyword: keywordCandidate.trim(),
    theme: firstCode(themesResponse.body, "theme"),
    mechanism: firstCode(mechanismsResponse.body, "mechanism"),
    upstreamRoles: {
      base: baseResponse.upstreamRole,
      themes: themesResponse.upstreamRole,
      mechanisms: mechanismsResponse.upstreamRole,
    },
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

async function requestOnce(url, requestTimeoutMs, expectedPage, expectedUpstreamRoles) {
  const startedAt = performance.now();
  const controller = new AbortController();
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, requestTimeoutMs);

  try {
    const response = await fetch(url, {
      headers: { Accept: "application/json", "Cache-Control": "no-cache" },
      signal: controller.signal,
    });
    const buffer = await response.arrayBuffer();
    const bytes = buffer.byteLength;
    let error = null;
    let responsePageMetadata = null;
    let upstreamRole = null;
    if (response.status === 200) {
      const candidateUpstreamRole = response.headers.get(UPSTREAM_HEADER_NAME);
      const candidateUpstreamRoleError = upstreamRoleError(candidateUpstreamRole, expectedUpstreamRoles);
      if (candidateUpstreamRoleError) {
        error = `응답 계약 불일치: ${candidateUpstreamRoleError}`;
      } else {
        upstreamRole = candidateUpstreamRole;
      }
      let body;
      if (!error) {
        try {
          body = JSON.parse(new TextDecoder().decode(buffer));
        } catch {
          error = "응답 body가 JSON이 아닙니다.";
        }
      }
      if (!error) {
        const responseError = gameListResponseError(body, expectedPage);
        if (responseError) {
          error = `응답 계약 불일치: ${responseError}`;
        } else {
          responsePageMetadata = pageMetadata(body);
        }
      }
    }
    return {
      status: response.status,
      elapsedMs: performance.now() - startedAt,
      bytes,
      pageMetadata: responsePageMetadata,
      upstreamRole,
      error,
    };
  } catch (error) {
    const message = timedOut || error?.name === "AbortError"
      ? `HTTP 요청 timeout (${requestTimeoutMs}ms)`
      : `HTTP 요청 실패: ${errorMessage(error)}`;
    return {
      status: null,
      elapsedMs: performance.now() - startedAt,
      bytes: 0,
      pageMetadata: null,
      upstreamRole: null,
      error: `${message}: ${url}`,
    };
  } finally {
    clearTimeout(timeout);
  }
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

async function measureScenario(
  baseUrl,
  scenario,
  warmUpRuns,
  measuredRuns,
  requestTimeoutMs,
  expectedUpstreamRoles,
) {
  const url = scenarioUrl(baseUrl, scenario);
  const expectedPage = {
    page: Number(scenario.params.page),
    size: Number(scenario.params.size),
  };
  const samples = [];

  try {
    for (let index = 0; index < warmUpRuns; index += 1) {
      const warmUp = await requestOnce(url, requestTimeoutMs, expectedPage, expectedUpstreamRoles);
      if (warmUp.error) {
        throw new Error(`${scenario.name} warm-up 실패: ${warmUp.error}`);
      }
      if (warmUp.status !== 200) {
        throw new Error(`${scenario.name} warm-up 실패: status=${warmUp.status}, url=${url}`);
      }
    }

    for (let index = 0; index < measuredRuns; index += 1) {
      const sample = await requestOnce(url, requestTimeoutMs, expectedPage, expectedUpstreamRoles);
      samples.push({ run: index + 1, ...sample });
      if (sample.error) {
        throw new Error(`${scenario.name} 실측 실패: run=${index + 1}, ${sample.error}`);
      }
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
    return execFileSync("git", ["-C", RUNNER_REPOSITORY, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
  } catch {
    return null;
  }
}

function runnerFileSha256() {
  try {
    return createHash("sha256").update(fs.readFileSync(RUNNER_FILE)).digest("hex");
  } catch {
    return null;
  }
}

function runnerSourceClean() {
  try {
    return execFileSync(
      "git",
      ["-C", RUNNER_REPOSITORY, "status", "--porcelain", "--", RUNNER_RELATIVE_PATH],
      { encoding: "utf8" },
    ).trim() === "";
  } catch {
    return null;
  }
}

function currentRunnerProvenance() {
  return {
    commit: currentGitCommit(),
    fileSha256: runnerFileSha256(),
    sourceClean: runnerSourceClean(),
  };
}

function assertRunnerProvenance(provenance) {
  if (!provenance.commit || !provenance.fileSha256) {
    throw new Error("runner commit 또는 runner 파일 SHA-256을 확인하지 못했습니다.");
  }
  if (provenance.sourceClean !== true) {
    throw new Error("측정 시작 시 runner 파일에 미커밋 변경이 있어 provenance를 고정할 수 없습니다.");
  }
}

function assertRunnerProvenanceStable(startProvenance) {
  const endProvenance = currentRunnerProvenance();
  if (
    endProvenance.commit !== startProvenance.commit
    || endProvenance.fileSha256 !== startProvenance.fileSha256
    || endProvenance.sourceClean !== true
  ) {
    throw new Error("측정 중 runner commit 또는 runner 파일이 변경되어 성공 산출물을 만들 수 없습니다.");
  }
}

function dockerInspect(containerName, format) {
  try {
    const value = execFileSync(
      "docker",
      ["inspect", "--format", format, containerName],
      { encoding: "utf8" },
    ).trim();
    if (!value) {
      throw new Error("빈 inspect 결과");
    }
    return value;
  } catch (error) {
    throw new Error(`Spring 컨테이너 ${containerName} inspect 실패: ${errorMessage(error)}`);
  }
}

function serverContainerProvenance(container, expectedCommit) {
  const containerId = dockerInspect(container.name, "{{.Id}}");
  const imageId = dockerInspect(container.name, "{{.Image}}");
  let labels;
  try {
    labels = JSON.parse(dockerInspect(container.name, "{{json .Config.Labels}}"));
  } catch (error) {
    throw new Error(`Spring 컨테이너 ${container.name} label을 읽지 못했습니다: ${errorMessage(error)}`);
  }
  if (!labels || typeof labels !== "object" || Array.isArray(labels)) {
    throw new Error(`Spring 컨테이너 ${container.name} label이 object가 아닙니다.`);
  }

  const expectedService = container.role === "app1" ? "spring-1" : "spring-2";
  if (labels["com.docker.compose.service"] !== expectedService) {
    throw new Error(
      `Spring 컨테이너 ${container.name}의 Compose service가 ${expectedService}이 아닙니다: ${labels["com.docker.compose.service"] ?? "missing"}`,
    );
  }
  const revision = labels["org.opencontainers.image.revision"];
  if (revision !== expectedCommit) {
    throw new Error(
      `Spring 컨테이너 ${container.name} image revision이 --server-commit과 다릅니다: expected=${expectedCommit}, actual=${revision ?? "missing"}`,
    );
  }
  if (!/^sha256:[0-9a-f]{64}$/u.test(imageId)) {
    throw new Error(`Spring 컨테이너 ${container.name} image ID가 SHA-256이 아닙니다: ${imageId}`);
  }
  return {
    role: container.role,
    containerId,
    imageId,
    imageRevision: revision,
    composeProject: labels["com.docker.compose.project"] ?? null,
    composeService: labels["com.docker.compose.service"],
  };
}

function currentServerProvenance(options) {
  const containers = [...options.serverContainers]
    .sort((left, right) => left.role.localeCompare(right.role))
    .map((container) => serverContainerProvenance(container, options.serverCommit));
  if (new Set(containers.map((container) => container.imageId)).size !== 1) {
    throw new Error("app1과 app2 Spring 컨테이너의 image ID가 서로 다릅니다.");
  }
  return { commit: options.serverCommit, containers };
}

function assertServerProvenanceStable(options, startProvenance) {
  const endProvenance = currentServerProvenance(options);
  if (JSON.stringify(endProvenance) !== JSON.stringify(startProvenance)) {
    throw new Error("측정 중 Spring 컨테이너 revision 또는 image ID가 변경되어 성공 산출물을 만들 수 없습니다.");
  }
}

function csvEscape(value) {
  const text = String(value);
  return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function writeArtifacts(options, discovered, results, failure, runnerProvenance, serverProvenance) {
  fs.mkdirSync(options.outputDirectory, { recursive: true });
  const timestamp = new Date().toISOString().replaceAll(":", "-");
  const baseName = `game-list-740-${timestamp}`;
  const jsonPath = path.join(options.outputDirectory, `${baseName}.json`);
  const csvPath = path.join(options.outputDirectory, `${baseName}.csv`);

  const report = {
    issue: 740,
    status: failure ? "failed" : "success",
    measuredAt: new Date().toISOString(),
    runnerCommit: runnerProvenance?.commit ?? null,
    runnerFileSha256: runnerProvenance?.fileSha256 ?? null,
    runnerSourceClean: runnerProvenance?.sourceClean ?? null,
    serverCommit: serverProvenance?.commit ?? options.serverCommit,
    serverContainers: serverProvenance?.containers ?? [],
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
  let runnerProvenance = null;
  let serverProvenance = null;

  try {
    runnerProvenance = currentRunnerProvenance();
    assertRunnerProvenance(runnerProvenance);
    console.log(`[game-list-740] runner-commit=${runnerProvenance.commit}`);
    console.log(`[game-list-740] runner-file-sha256=${runnerProvenance.fileSha256}`);

    serverProvenance = currentServerProvenance(options);
    console.log(`[game-list-740] server-image-id=${serverProvenance.containers[0].imageId}`);

    const expectedUpstreamRoles = serverProvenance.containers.map((container) => container.role);
    discovered = await discoverScenarioValues(
      options.baseUrl,
      options.requestTimeoutMs,
      options.datasetSize,
      expectedUpstreamRoles,
    );
    console.log(`[game-list-740] discovered keyword=${JSON.stringify(discovered.keyword)}, theme=${discovered.theme}, mechanism=${discovered.mechanism}`);

    for (const scenario of scenarios(discovered)) {
      console.log(`[game-list-740] measuring ${scenario.name}`);
      const result = await measureScenario(
        options.baseUrl,
        scenario,
        options.warmUpRuns,
        options.measuredRuns,
        options.requestTimeoutMs,
        expectedUpstreamRoles,
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

    assertRunnerProvenanceStable(runnerProvenance);
    assertServerProvenanceStable(options, serverProvenance);
  } catch (error) {
    failure = error;
  }

  const artifacts = writeArtifacts(options, discovered, results, failure, runnerProvenance, serverProvenance);
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
