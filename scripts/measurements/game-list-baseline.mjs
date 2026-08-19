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
const UPSTREAM_ADDRESS_HEADER_NAME = "x-albam-mate-upstream-address";
const RUNNER_FILE = fileURLToPath(import.meta.url);
const RUNNER_REPOSITORY = path.resolve(path.dirname(RUNNER_FILE), "../..");
const RUNNER_RELATIVE_PATH = path.relative(RUNNER_REPOSITORY, RUNNER_FILE);
const CANONICAL_DATASET_QUERIES = {
  games: "select jsonb_build_array(id, bgg_id, name, english_name, alias, image_url, supported_player_count, tag, estimated_play_time, min_players, max_players, min_play_time_minutes, max_play_time_minutes, complexity, release_year, min_age, popularity_score)::text from games order by id",
  gameMechanismRelations: "select jsonb_build_array(game_id, mechanism_id)::text from game_mechanism_relations order by game_id, mechanism_id",
  gameThemeRelations: "select jsonb_build_array(game_id, theme_id)::text from game_theme_relations order by game_id, theme_id",
  gameCategoryRelations: "select jsonb_build_array(game_id, category_id)::text from game_category_relations order by game_id, category_id",
  gamePlayerPreferences: "select jsonb_build_array(game_id, player_count, is_recommended, is_best)::text from game_player_preferences order by game_id, player_count",
  rooms: "select jsonb_build_array(id, game_id, room_type, extract(epoch from start_at), status)::text from rooms order by id",
};

function parseArgs(argv) {
  const options = {
    baseUrl: DEFAULT_BASE_URL,
    warmUpRuns: DEFAULT_WARM_UP_RUNS,
    measuredRuns: DEFAULT_MEASURED_RUNS,
    datasetSize: DEFAULT_DATASET_SIZE,
    datasetManifest: null,
    responseContract: "slice",
    requestTimeoutMs: DEFAULT_REQUEST_TIMEOUT_MS,
    serverCommit: null,
    serverContainers: [],
    proxyContainer: null,
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
      case "--dataset-manifest":
        options.datasetManifest = next();
        break;
      case "--response-contract":
        options.responseContract = responseContract(next(), argument);
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
      case "--proxy-container":
        if (options.proxyContainer) {
          throw new Error("--proxy-container는 한 번만 지정할 수 있습니다.");
        }
        options.proxyContainer = next();
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
  if (!options.datasetManifest) {
    throw new Error("--dataset-manifest로 검증 가능한 fixture manifest 경로가 필요합니다.");
  }
  if (!options.serverCommit) {
    throw new Error("--server-commit은 측정 대상 서버의 commit SHA가 필요합니다.");
  }
  if (options.serverContainers.length !== 2) {
    throw new Error("--server-container로 app1과 app2 Spring 컨테이너를 각각 지정해야 합니다.");
  }
  if (!options.proxyContainer) {
    throw new Error("--proxy-container로 측정 proxy 컨테이너를 지정해야 합니다.");
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

function responseContract(raw, optionName) {
  if (raw !== "page" && raw !== "slice") {
    throw new Error(`${optionName}은 page 또는 slice여야 합니다: ${raw}`);
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
    `  --dataset-manifest <path> fixture row/ID fingerprint manifest (required)\n` +
    `  --response-contract <type> page or slice response contract (default: slice)\n` +
    `  --request-timeout-ms <n>  timeout per HTTP request/body read (default: ${DEFAULT_REQUEST_TIMEOUT_MS})\n` +
    `  --server-commit <sha>     measured server 40-char commit SHA (required)\n` +
    `  --server-container <role=name>  app1/app2 measured Spring container (required twice)\n` +
    `  --proxy-container <name>  measured Compose proxy container (required)\n` +
    `  --output-directory <dir>  result directory\n`);
}

function upstreamAddressHost(rawAddress) {
  if (!rawAddress || rawAddress.includes(",")) {
    return null;
  }
  try {
    return new URL(`http://${rawAddress}`).hostname;
  } catch {
    return null;
  }
}

function upstreamResponse(response, expectedUpstreams) {
  const role = response.headers.get(UPSTREAM_HEADER_NAME);
  const rawAddress = response.headers.get(UPSTREAM_ADDRESS_HEADER_NAME);
  if (!role) {
    return { error: `응답 ${UPSTREAM_HEADER_NAME} 헤더가 없습니다.` };
  }
  const container = expectedUpstreams.find((candidate) => candidate.role === role);
  if (!container) {
    return { error: `응답 ${UPSTREAM_HEADER_NAME}=${role}이 검증한 app1/app2 컨테이너와 일치하지 않습니다.` };
  }
  const address = upstreamAddressHost(rawAddress);
  if (!address) {
    return { error: `응답 ${UPSTREAM_ADDRESS_HEADER_NAME}이 유효한 단일 host:port가 아닙니다: ${rawAddress ?? "missing"}` };
  }
  if (!container.networkAddresses.includes(address)) {
    return {
      error: `응답 ${UPSTREAM_ADDRESS_HEADER_NAME}=${rawAddress}이 ${role} 컨테이너의 inspect network 주소와 일치하지 않습니다.`,
    };
  }
  return {
    error: null,
    role,
    address: rawAddress,
    containerId: container.containerId,
  };
}

async function fetchJson(url, requestTimeoutMs, expectedUpstreams) {
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
    const upstream = upstreamResponse(response, expectedUpstreams);
    if (upstream.error) {
      throw new Error(`${url} 응답 계약 불일치: ${upstream.error}`);
    }
    return { body, upstream };
  } catch (error) {
    if (timedOut || error?.name === "AbortError") {
      throw new Error(`HTTP 요청 timeout (${requestTimeoutMs}ms): ${url}`);
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

function gameListResponseError(body, expectedPage = null, expectedResponseContract = "slice") {
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
  if (expectedResponseContract === "page") {
    if (!Number.isSafeInteger(page.totalElements) || page.totalElements < 0) {
      return "응답 data.totalElements가 0 이상의 정수가 아닙니다.";
    }
    if (!Number.isSafeInteger(page.totalPages) || page.totalPages < 0) {
      return "응답 data.totalPages가 0 이상의 정수가 아닙니다.";
    }
  } else {
    if (Object.hasOwn(page, "totalElements")) {
      return "응답 data에 totalElements를 포함하면 안 됩니다.";
    }
    if (Object.hasOwn(page, "totalPages")) {
      return "응답 data에 totalPages를 포함하면 안 됩니다.";
    }
  }
  const allowedFields = expectedResponseContract === "page"
    ? new Set(["content", "page", "size", "totalElements", "totalPages", "hasNext"])
    : new Set(["content", "page", "size", "hasNext"]);
  const unexpectedFields = Object.keys(page).filter((field) => !allowedFields.has(field));
  if (unexpectedFields.length > 0) {
    return `응답 data에 허용하지 않는 필드가 있습니다: ${unexpectedFields.sort().join(", ")}`;
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
  if (expectedResponseContract === "page") {
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
    if (expectedPage.expectedTotalElements !== undefined && page.totalElements !== expectedPage.expectedTotalElements) {
      return `응답 data.totalElements가 ${expectedPage.datasetSize}건 fixture와 일치하지 않습니다: expected=${expectedPage.expectedTotalElements}, actual=${page.totalElements}`;
    }
  } else {
    if (page.content.length > expectedPage.size) {
      return `응답 data.content 길이가 요청 size를 초과합니다: expected<=${expectedPage.size}, actual=${page.content.length}`;
    }
    if (page.hasNext && page.content.length !== expectedPage.size) {
      return `응답 data.hasNext가 true인데 content 길이가 요청 size와 다릅니다: expected=${expectedPage.size}, actual=${page.content.length}`;
    }
    if (expectedPage.expectedHasNext !== undefined && page.hasNext !== expectedPage.expectedHasNext) {
      return `응답 data.hasNext가 ${expectedPage.datasetSize}건 fixture의 첫 페이지에서 ${expectedPage.expectedHasNext}여야 합니다: actual=${page.hasNext}`;
    }
  }
  return null;
}

function pageMetadata(body, responseContract) {
  const page = body.data;
  const metadata = {
    page: page.page,
    size: page.size,
    hasNext: page.hasNext,
    contentLength: page.content.length,
  };
  if (responseContract === "page") {
    return {
      ...metadata,
      totalElements: page.totalElements,
      totalPages: page.totalPages,
    };
  }
  return metadata;
}

function firstCode(response, label) {
  const code = response?.data?.find((item) => typeof item?.code === "string" && item.code.length > 0)?.code;
  if (!code) {
    throw new Error(`${label} 메타데이터에서 측정에 사용할 code를 찾지 못했습니다.`);
  }
  return code;
}

async function discoverScenarioValues(
  baseUrl,
  requestTimeoutMs,
  expectedDatasetSize,
  expectedUpstreams,
  responseContract,
) {
  const baseResponse = await fetchJson(
    `${baseUrl}/api/games?upcomingOnly=false&playerCountExact=false&page=0&size=24`,
    requestTimeoutMs,
    expectedUpstreams,
  );
  const base = baseResponse.body;
  const baseResponseError = gameListResponseError(base, {
    page: 0,
    size: 24,
    datasetSize: expectedDatasetSize,
    ...(responseContract === "page"
      ? { expectedTotalElements: expectedDatasetSize }
      : { expectedHasNext: expectedDatasetSize > 24 }),
  }, responseContract);
  if (baseResponseError) {
    throw new Error(`base discovery 응답 계약 불일치: ${baseResponseError}`);
  }
  const firstGame = base?.data?.content?.[0];
  const keywordCandidate = [firstGame?.name, firstGame?.englishName]
    .find((value) => typeof value === "string" && value.trim().length >= 2);
  if (!keywordCandidate) {
    throw new Error("기본 목록 첫 페이지에서 keyword 시나리오용 게임명을 찾지 못했습니다.");
  }

  const [themesResponse, mechanismsResponse] = await Promise.all([
    fetchJson(`${baseUrl}/api/game-themes`, requestTimeoutMs, expectedUpstreams),
    fetchJson(`${baseUrl}/api/game-mechanisms`, requestTimeoutMs, expectedUpstreams),
  ]);

  return {
    keyword: keywordCandidate.trim(),
    theme: firstCode(themesResponse.body, "theme"),
    mechanism: firstCode(mechanismsResponse.body, "mechanism"),
    upstreamRoles: {
      base: baseResponse.upstream.role,
      themes: themesResponse.upstream.role,
      mechanisms: mechanismsResponse.upstream.role,
    },
    upstreams: {
      base: baseResponse.upstream,
      themes: themesResponse.upstream,
      mechanisms: mechanismsResponse.upstream,
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

async function requestOnce(url, requestTimeoutMs, expectedPage, expectedUpstreams, responseContract) {
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
    let upstreamAddress = null;
    let upstreamContainerId = null;
    if (response.status === 200) {
      const upstream = upstreamResponse(response, expectedUpstreams);
      if (upstream.error) {
        error = `응답 계약 불일치: ${upstream.error}`;
      } else {
        upstreamRole = upstream.role;
        upstreamAddress = upstream.address;
        upstreamContainerId = upstream.containerId;
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
        const responseError = gameListResponseError(body, expectedPage, responseContract);
        if (responseError) {
          error = `응답 계약 불일치: ${responseError}`;
        } else {
          responsePageMetadata = pageMetadata(body, responseContract);
        }
      }
    }
    return {
      status: response.status,
      elapsedMs: performance.now() - startedAt,
      bytes,
      pageMetadata: responsePageMetadata,
      upstreamRole,
      upstreamAddress,
      upstreamContainerId,
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
      upstreamAddress: null,
      upstreamContainerId: null,
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
  expectedUpstreams,
  expectedDatasetSize,
  responseContract,
) {
  const url = scenarioUrl(baseUrl, scenario);
  const expectedPage = {
    page: Number(scenario.params.page),
    size: Number(scenario.params.size),
    datasetSize: expectedDatasetSize,
    ...(scenario.name === "base"
      ? { expectedHasNext: expectedDatasetSize > Number(scenario.params.size) }
      : {}),
  };
  const samples = [];

  try {
    for (let index = 0; index < warmUpRuns; index += 1) {
      const warmUp = await requestOnce(
        url,
        requestTimeoutMs,
        expectedPage,
        expectedUpstreams,
        responseContract,
      );
      if (warmUp.error) {
        throw new Error(`${scenario.name} warm-up 실패: ${warmUp.error}`);
      }
      if (warmUp.status !== 200) {
        throw new Error(`${scenario.name} warm-up 실패: status=${warmUp.status}, url=${url}`);
      }
    }

    for (let index = 0; index < measuredRuns; index += 1) {
      const sample = await requestOnce(
        url,
        requestTimeoutMs,
        expectedPage,
        expectedUpstreams,
        responseContract,
      );
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
  return endProvenance;
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
    throw new Error(`컨테이너 ${containerName} inspect 실패: ${errorMessage(error)}`);
  }
}

function containerNetworks(containerName) {
  let networks;
  try {
    networks = JSON.parse(dockerInspect(containerName, "{{json .NetworkSettings.Networks}}"));
  } catch (error) {
    throw new Error(`컨테이너 ${containerName} network을 읽지 못했습니다: ${errorMessage(error)}`);
  }
  if (!networks || typeof networks !== "object" || Array.isArray(networks)) {
    throw new Error(`컨테이너 ${containerName} network이 object가 아닙니다.`);
  }
  const names = Object.keys(networks).sort();
  const addresses = names.map((name) => networks[name]?.IPAddress).filter((address) => typeof address === "string" && address.length > 0);
  if (names.length === 0 || addresses.length === 0) {
    throw new Error(`컨테이너 ${containerName}의 Compose network IPv4 주소를 찾지 못했습니다.`);
  }
  return { names, addresses };
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
  const networks = containerNetworks(container.name);
  return {
    role: container.role,
    containerId,
    imageId,
    imageRevision: revision,
    composeProject: labels["com.docker.compose.project"] ?? null,
    composeService: labels["com.docker.compose.service"],
    networkNames: networks.names,
    networkAddresses: networks.addresses,
  };
}

function proxyContainerProvenance(containerName, expectedProject, springContainers) {
  const containerId = dockerInspect(containerName, "{{.Id}}");
  let labels;
  try {
    labels = JSON.parse(dockerInspect(containerName, "{{json .Config.Labels}}"));
  } catch (error) {
    throw new Error(`proxy 컨테이너 ${containerName} label을 읽지 못했습니다: ${errorMessage(error)}`);
  }
  if (labels?.["com.docker.compose.service"] !== "proxy") {
    throw new Error(`proxy 컨테이너 ${containerName}의 Compose service가 proxy가 아닙니다: ${labels?.["com.docker.compose.service"] ?? "missing"}`);
  }
  if (labels["com.docker.compose.project"] !== expectedProject) {
    throw new Error(`proxy 컨테이너 ${containerName}의 Compose project가 Spring 컨테이너와 다릅니다.`);
  }
  const networks = containerNetworks(containerName);
  for (const springContainer of springContainers) {
    if (!springContainer.networkNames.some((name) => networks.names.includes(name))) {
      throw new Error(`proxy 컨테이너 ${containerName}이 ${springContainer.role} Spring 컨테이너와 Compose network를 공유하지 않습니다.`);
    }
  }
  return {
    containerId,
    composeProject: labels["com.docker.compose.project"],
    composeService: labels["com.docker.compose.service"],
    networkNames: networks.names,
  };
}

function currentServerProvenance(options) {
  const containers = [...options.serverContainers]
    .sort((left, right) => left.role.localeCompare(right.role))
    .map((container) => serverContainerProvenance(container, options.serverCommit));
  if (new Set(containers.map((container) => container.imageId)).size !== 1) {
    throw new Error("app1과 app2 Spring 컨테이너의 image ID가 서로 다릅니다.");
  }
  const projects = new Set(containers.map((container) => container.composeProject));
  if (projects.size !== 1 || !containers[0].composeProject) {
    throw new Error("app1과 app2 Spring 컨테이너의 Compose project가 하나로 고정되지 않았습니다.");
  }
  return {
    commit: options.serverCommit,
    containers,
    proxyContainer: proxyContainerProvenance(options.proxyContainer, containers[0].composeProject, containers),
  };
}

function assertServerProvenanceStable(options, startProvenance) {
  const endProvenance = currentServerProvenance(options);
  if (JSON.stringify(endProvenance) !== JSON.stringify(startProvenance)) {
    throw new Error("측정 중 Spring 또는 proxy 컨테이너 provenance가 변경되어 성공 산출물을 만들 수 없습니다.");
  }
  return endProvenance;
}

function composePostgresContainerId(composeProject) {
  let output;
  try {
    output = execFileSync(
      "docker",
      [
        "ps",
        "--filter", `label=com.docker.compose.project=${composeProject}`,
        "--filter", "label=com.docker.compose.service=postgres",
        "--format", "{{.ID}}",
      ],
      { encoding: "utf8" },
    );
  } catch (error) {
    throw new Error(`Compose project ${composeProject}의 postgres container를 찾지 못했습니다: ${errorMessage(error)}`);
  }
  const ids = output.trim().split(/\s+/u).filter(Boolean);
  if (ids.length !== 1) {
    throw new Error(`Compose project ${composeProject}의 postgres container는 정확히 하나여야 합니다: ${ids.length}개`);
  }
  return ids[0];
}

function fixtureManifest(options) {
  let manifestText;
  try {
    manifestText = fs.readFileSync(options.datasetManifest, "utf8");
  } catch (error) {
    throw new Error(`fixture manifest를 읽지 못했습니다: ${errorMessage(error)}`);
  }

  let manifest;
  try {
    manifest = JSON.parse(manifestText);
  } catch (error) {
    throw new Error(`fixture manifest JSON이 아닙니다: ${errorMessage(error)}`);
  }
  if (!manifest || typeof manifest !== "object" || Array.isArray(manifest)) {
    throw new Error("fixture manifest는 JSON object여야 합니다.");
  }
  if (manifest.schemaVersion !== 2) {
    throw new Error(`fixture manifest schemaVersion은 2이어야 합니다: ${manifest.schemaVersion}`);
  }
  if (typeof manifest.fixtureId !== "string" || manifest.fixtureId.trim() === "") {
    throw new Error("fixture manifest fixtureId가 필요합니다.");
  }
  if (!manifest.games || typeof manifest.games !== "object" || Array.isArray(manifest.games)) {
    throw new Error("fixture manifest games object가 필요합니다.");
  }
  const gameCount = positiveInteger(manifest.games.rowCount, "fixture manifest games.rowCount");
  const bggIdSetSha256 = sha256(manifest.games.bggIdSetSha256, "fixture manifest games.bggIdSetSha256");
  const gamesCanonicalSha256 = sha256(
    manifest.games.canonicalSha256,
    "fixture manifest games.canonicalSha256",
  );
  if (gameCount !== options.datasetSize) {
    throw new Error(
      `fixture manifest games.rowCount가 --dataset-size와 다릅니다: manifest=${gameCount}, option=${options.datasetSize}`,
    );
  }
  if (!manifest.metadata || typeof manifest.metadata !== "object" || Array.isArray(manifest.metadata)) {
    throw new Error("fixture manifest metadata object가 필요합니다.");
  }
  const metadata = {
    gameMechanismRelations: nonnegativeInteger(
      manifest.metadata.gameMechanismRelations,
      "fixture manifest metadata.gameMechanismRelations",
    ),
    gameThemeRelations: nonnegativeInteger(
      manifest.metadata.gameThemeRelations,
      "fixture manifest metadata.gameThemeRelations",
    ),
    gameCategoryRelations: nonnegativeInteger(
      manifest.metadata.gameCategoryRelations,
      "fixture manifest metadata.gameCategoryRelations",
    ),
    gamePlayerPreferences: nonnegativeInteger(
      manifest.metadata.gamePlayerPreferences,
      "fixture manifest metadata.gamePlayerPreferences",
    ),
  };
  const metadataCanonicalSha256 = {
    gameMechanismRelations: sha256(
      manifest.metadata.gameMechanismRelationsSha256,
      "fixture manifest metadata.gameMechanismRelationsSha256",
    ),
    gameThemeRelations: sha256(
      manifest.metadata.gameThemeRelationsSha256,
      "fixture manifest metadata.gameThemeRelationsSha256",
    ),
    gameCategoryRelations: sha256(
      manifest.metadata.gameCategoryRelationsSha256,
      "fixture manifest metadata.gameCategoryRelationsSha256",
    ),
    gamePlayerPreferences: sha256(
      manifest.metadata.gamePlayerPreferencesSha256,
      "fixture manifest metadata.gamePlayerPreferencesSha256",
    ),
  };
  if (!manifest.rooms || typeof manifest.rooms !== "object" || Array.isArray(manifest.rooms)) {
    throw new Error("fixture manifest rooms object가 필요합니다.");
  }
  const roomCount = nonnegativeInteger(manifest.rooms.rowCount, "fixture manifest rooms.rowCount");
  const roomsCanonicalSha256 = sha256(
    manifest.rooms.canonicalSha256,
    "fixture manifest rooms.canonicalSha256",
  );
  return {
    fixtureId: manifest.fixtureId,
    manifestSha256: createHash("sha256").update(manifestText).digest("hex"),
    gameCount,
    bggIdSetSha256,
    gamesCanonicalSha256,
    metadata,
    metadataCanonicalSha256,
    rooms: {
      rowCount: roomCount,
      canonicalSha256: roomsCanonicalSha256,
    },
  };
}

function nonnegativeInteger(raw, optionName) {
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 0) {
    throw new Error(`${optionName}은 0 이상의 정수여야 합니다: ${raw}`);
  }
  return value;
}

function postgresGameCount(containerId) {
  let output;
  try {
    output = execFileSync(
      "docker",
      [
        "exec",
        containerId,
        "sh",
        "-c",
        'PGAPPNAME=game-list-baseline-fixture-check psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select count(*) from games"',
      ],
      { encoding: "utf8" },
    ).trim();
  } catch (error) {
    throw new Error(`PostgreSQL fixture games row count 조회 실패: ${errorMessage(error)}`);
  }
  if (!/^[0-9]+$/u.test(output)) {
    throw new Error(`PostgreSQL fixture games row count가 정수가 아닙니다: ${JSON.stringify(output)}`);
  }
  const count = Number(output);
  if (!Number.isSafeInteger(count)) {
    throw new Error(`PostgreSQL fixture games row count가 안전한 정수가 아닙니다: ${output}`);
  }
  return count;
}

function postgresBggIdSetSha256(containerId) {
  let output;
  try {
    output = execFileSync(
      "docker",
      [
        "exec",
        containerId,
        "sh",
        "-c",
        'PGAPPNAME=game-list-baseline-fixture-check psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select bgg_id from games order by bgg_id"',
      ],
      { encoding: "utf8", maxBuffer: 16 * 1024 * 1024 },
    );
  } catch (error) {
    throw new Error(`PostgreSQL fixture BGG ID 집합 조회 실패: ${errorMessage(error)}`);
  }
  const ids = output.trim().split(/\s+/u).filter(Boolean);
  const digest = createHash("sha256");
  let previousId = 0;
  for (const rawId of ids) {
    const id = Number(rawId);
    if (!Number.isSafeInteger(id) || id <= previousId) {
      throw new Error(`PostgreSQL fixture BGG ID가 정렬된 양의 정수가 아닙니다: ${JSON.stringify(rawId)}`);
    }
    previousId = id;
    digest.update(`${id}\n`);
  }
  return { count: ids.length, bggIdSetSha256: digest.digest("hex") };
}

function postgresMetadataCounts(containerId) {
  let output;
  try {
    output = execFileSync(
      "docker",
      [
        "exec",
        containerId,
        "sh",
        "-c",
        'PGAPPNAME=game-list-baseline-fixture-check psql -v ON_ERROR_STOP=1 -At -F "|" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select (select count(*) from game_mechanism_relations), (select count(*) from game_theme_relations), (select count(*) from game_category_relations), (select count(*) from game_player_preferences)"',
      ],
      { encoding: "utf8" },
    ).trim();
  } catch (error) {
    throw new Error(`PostgreSQL fixture metadata row count 조회 실패: ${errorMessage(error)}`);
  }
  const [gameMechanismRelations, gameThemeRelations, gameCategoryRelations, gamePlayerPreferences, ...unexpected] = output.split("|");
  if (unexpected.length > 0 || gamePlayerPreferences === undefined) {
    throw new Error(`PostgreSQL fixture metadata row count 형식이 아닙니다: ${JSON.stringify(output)}`);
  }
  return {
    gameMechanismRelations: nonnegativeInteger(gameMechanismRelations, "PostgreSQL game_mechanism_relations count"),
    gameThemeRelations: nonnegativeInteger(gameThemeRelations, "PostgreSQL game_theme_relations count"),
    gameCategoryRelations: nonnegativeInteger(gameCategoryRelations, "PostgreSQL game_category_relations count"),
    gamePlayerPreferences: nonnegativeInteger(gamePlayerPreferences, "PostgreSQL game_player_preferences count"),
  };
}

function canonicalRowsDigest(output) {
  const rows = output.split(/\r?\n/u).filter((row) => row.length > 0);
  const digest = createHash("sha256");
  for (const row of rows) {
    digest.update(`${row}\n`);
  }
  return {
    rowCount: rows.length,
    sha256: digest.digest("hex"),
  };
}

function postgresCanonicalRows(containerId, queryName) {
  const query = CANONICAL_DATASET_QUERIES[queryName];
  let output;
  try {
    output = execFileSync(
      "docker",
      [
        "exec",
        containerId,
        "sh",
        "-c",
        `PGAPPNAME=game-list-baseline-fixture-check psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c ${JSON.stringify(query)}`,
      ],
      { encoding: "utf8", maxBuffer: 256 * 1024 * 1024 },
    );
  } catch (error) {
    throw new Error(`PostgreSQL fixture ${queryName} canonical row 조회 실패: ${errorMessage(error)}`);
  }
  return canonicalRowsDigest(output);
}

function currentDatasetProvenance(composeProject, fixture) {
  const postgresContainerId = composePostgresContainerId(composeProject);
  const observedGameCount = postgresGameCount(postgresContainerId);
  if (observedGameCount !== fixture.gameCount) {
    throw new Error(
      `PostgreSQL fixture games row count 불일치: expected=${fixture.gameCount}, actual=${observedGameCount}`,
    );
  }
  const bggIds = postgresBggIdSetSha256(postgresContainerId);
  if (bggIds.count !== observedGameCount) {
    throw new Error(
      `PostgreSQL fixture BGG ID 수와 games row count가 다릅니다: bggIds=${bggIds.count}, games=${observedGameCount}`,
    );
  }
  if (bggIds.bggIdSetSha256 !== fixture.bggIdSetSha256) {
    throw new Error(
      `PostgreSQL fixture BGG ID 집합 지문 불일치: expected=${fixture.bggIdSetSha256}, actual=${bggIds.bggIdSetSha256}`,
    );
  }
  const metadata = postgresMetadataCounts(postgresContainerId);
  if (JSON.stringify(metadata) !== JSON.stringify(fixture.metadata)) {
    throw new Error(
      `PostgreSQL fixture metadata row count 불일치: expected=${JSON.stringify(fixture.metadata)}, actual=${JSON.stringify(metadata)}`,
    );
  }
  const gamesCanonical = postgresCanonicalRows(postgresContainerId, "games");
  if (gamesCanonical.rowCount !== observedGameCount) {
    throw new Error(
      `PostgreSQL fixture games canonical row 수 불일치: expected=${observedGameCount}, actual=${gamesCanonical.rowCount}`,
    );
  }
  if (gamesCanonical.sha256 !== fixture.gamesCanonicalSha256) {
    throw new Error(
      `PostgreSQL fixture games canonical 지문 불일치: expected=${fixture.gamesCanonicalSha256}, actual=${gamesCanonical.sha256}`,
    );
  }
  const metadataCanonicalSha256 = {};
  for (const [queryName, expectedRowCount] of Object.entries(fixture.metadata)) {
    const observed = postgresCanonicalRows(postgresContainerId, queryName);
    if (observed.rowCount !== expectedRowCount) {
      throw new Error(
        `PostgreSQL fixture ${queryName} canonical row 수 불일치: expected=${expectedRowCount}, actual=${observed.rowCount}`,
      );
    }
    const expectedSha256 = fixture.metadataCanonicalSha256[queryName];
    if (observed.sha256 !== expectedSha256) {
      throw new Error(
        `PostgreSQL fixture ${queryName} canonical 지문 불일치: expected=${expectedSha256}, actual=${observed.sha256}`,
      );
    }
    metadataCanonicalSha256[queryName] = observed.sha256;
  }
  const rooms = postgresCanonicalRows(postgresContainerId, "rooms");
  if (rooms.rowCount !== fixture.rooms.rowCount) {
    throw new Error(
      `PostgreSQL fixture rooms canonical row 수 불일치: expected=${fixture.rooms.rowCount}, actual=${rooms.rowCount}`,
    );
  }
  if (rooms.sha256 !== fixture.rooms.canonicalSha256) {
    throw new Error(
      `PostgreSQL fixture rooms canonical 지문 불일치: expected=${fixture.rooms.canonicalSha256}, actual=${rooms.sha256}`,
    );
  }
  return {
    observedGameCount,
    bggIdSetSha256: bggIds.bggIdSetSha256,
    gamesCanonicalSha256: gamesCanonical.sha256,
    metadata,
    metadataCanonicalSha256,
    rooms: {
      rowCount: rooms.rowCount,
      canonicalSha256: rooms.sha256,
    },
    postgresContainerId,
    postgresComposeProject: composeProject,
  };
}

function assertDatasetProvenanceStable(startProvenance, fixture) {
  const endProvenance = currentDatasetProvenance(
    startProvenance.postgresComposeProject,
    fixture,
  );
  if (JSON.stringify(endProvenance) !== JSON.stringify(startProvenance)) {
    throw new Error("측정 중 PostgreSQL fixture provenance, games/metadata/rooms canonical row 또는 BGG ID 집합이 변경되어 성공 산출물을 만들 수 없습니다.");
  }
  return endProvenance;
}

function endProvenance(options, runnerProvenance, serverProvenance, fixture, datasetProvenance) {
  const result = { runner: null, server: null, dataset: null, errors: [] };
  const check = (scope, start, assertion) => {
    if (!start) return;
    try {
      result[scope] = assertion();
    } catch (error) {
      result.errors.push({ scope, message: errorMessage(error) });
    }
  };

  check("runner", runnerProvenance, () => assertRunnerProvenanceStable(runnerProvenance));
  check("server", serverProvenance, () => assertServerProvenanceStable(options, serverProvenance));
  check("dataset", datasetProvenance, () => assertDatasetProvenanceStable(
    datasetProvenance,
    fixture,
  ));
  return result;
}

function csvEscape(value) {
  const text = String(value);
  return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function writeArtifacts(options, discovered, results, failure, runnerProvenance, serverProvenance, fixture, datasetProvenance, finalProvenance) {
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
    proxyContainer: serverProvenance?.proxyContainer ?? null,
    responseContract: options.responseContract,
    baseUrl: options.baseUrl,
    dataset: {
      fixtureId: fixture?.fixtureId ?? null,
      fixtureManifestSha256: fixture?.manifestSha256 ?? null,
      gameCount: fixture?.gameCount ?? options.datasetSize,
      observedGameCount: datasetProvenance?.observedGameCount ?? null,
      bggIdSetSha256: fixture?.bggIdSetSha256 ?? null,
      observedBggIdSetSha256: datasetProvenance?.bggIdSetSha256 ?? null,
      gamesCanonicalSha256: fixture?.gamesCanonicalSha256 ?? null,
      observedGamesCanonicalSha256: datasetProvenance?.gamesCanonicalSha256 ?? null,
      metadata: fixture?.metadata ?? null,
      observedMetadata: datasetProvenance?.metadata ?? null,
      metadataCanonicalSha256: fixture?.metadataCanonicalSha256 ?? null,
      observedMetadataCanonicalSha256: datasetProvenance?.metadataCanonicalSha256 ?? null,
      rooms: fixture?.rooms ?? null,
      observedRooms: datasetProvenance?.rooms ?? null,
      postgresContainerId: datasetProvenance?.postgresContainerId ?? null,
      postgresComposeProject: datasetProvenance?.postgresComposeProject ?? null,
    },
    endProvenance: finalProvenance,
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
  console.log(`[game-list-740] response-contract=${options.responseContract}`);
  let discovered = null;
  const results = [];
  let failure = null;
  let runnerProvenance = null;
  let serverProvenance = null;
  let fixture = null;
  let datasetProvenance = null;
  let finalProvenance = null;

  try {
    runnerProvenance = currentRunnerProvenance();
    assertRunnerProvenance(runnerProvenance);
    console.log(`[game-list-740] runner-commit=${runnerProvenance.commit}`);
    console.log(`[game-list-740] runner-file-sha256=${runnerProvenance.fileSha256}`);

    fixture = fixtureManifest(options);
    console.log(`[game-list-740] fixture-id=${fixture.fixtureId}`);
    console.log(`[game-list-740] fixture-manifest-sha256=${fixture.manifestSha256}`);
    console.log(`[game-list-740] fixture-bgg-id-set-sha256=${fixture.bggIdSetSha256}`);

    serverProvenance = currentServerProvenance(options);
    console.log(`[game-list-740] server-image-id=${serverProvenance.containers[0].imageId}`);

    datasetProvenance = currentDatasetProvenance(
      serverProvenance.containers[0].composeProject,
      fixture,
    );
    console.log(`[game-list-740] postgres-games=${datasetProvenance.observedGameCount}`);

    discovered = await discoverScenarioValues(
      options.baseUrl,
      options.requestTimeoutMs,
      options.datasetSize,
      serverProvenance.containers,
      options.responseContract,
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
        serverProvenance.containers,
        options.datasetSize,
        options.responseContract,
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
  } finally {
    finalProvenance = endProvenance(
      options,
      runnerProvenance,
      serverProvenance,
      fixture,
      datasetProvenance,
    );
    if (!failure && finalProvenance.errors.length > 0) {
      failure = new Error(
        `측정 종료 provenance 대조 실패: ${finalProvenance.errors.map((error) => `${error.scope}: ${error.message}`).join("; ")}`,
      );
    }
  }

  const artifacts = writeArtifacts(
    options,
    discovered,
    results,
    failure,
    runnerProvenance,
    serverProvenance,
    fixture,
    datasetProvenance,
    finalProvenance,
  );
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
