import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { createServer } from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const runnerPath = fileURLToPath(new URL("./game-list-baseline.mjs", import.meta.url));
const serverCommit = "abcdef1234567";
const datasetSha256 = "d".repeat(64);

function startServer({
  failMeasuredRequest = false,
  invalidMeasuredResponse = false,
  hangMeasuredRequest = false,
  hangDiscoveryPath = null,
  datasetSize = 170005,
  responseSize = 24,
} = {}) {
  let gameRequests = 0;
  const server = createServer((request, response) => {
    response.setHeader("content-type", "application/json");

    if (request.url?.startsWith("/api/game-themes")) {
      if (hangDiscoveryPath === "themes") {
        return;
      }
      response.end(JSON.stringify({ data: [{ code: "THEME" }] }));
      return;
    }
    if (request.url?.startsWith("/api/game-mechanisms")) {
      if (hangDiscoveryPath === "mechanisms") {
        return;
      }
      response.end(JSON.stringify({ data: [{ code: "MECHANISM" }] }));
      return;
    }
    if (request.url?.startsWith("/api/games")) {
      gameRequests += 1;
      if (hangDiscoveryPath === "games") {
        return;
      }
      if (failMeasuredRequest && gameRequests === 7) {
        response.statusCode = 503;
        response.end(JSON.stringify({ error: "temporary failure" }));
        return;
      }
      if (invalidMeasuredResponse && gameRequests === 7) {
        response.end(JSON.stringify({ status: 200, data: { content: "not-an-array" } }));
        return;
      }
      if (hangMeasuredRequest && gameRequests === 7) {
        return;
      }
      const effectiveResponseSize = gameRequests >= 7 ? responseSize : 24;
      const totalPages = Math.ceil(datasetSize / effectiveResponseSize);
      response.end(JSON.stringify({
        status: 200,
        data: {
          content: Array.from(
            { length: Math.min(datasetSize, effectiveResponseSize) },
            () => ({ name: "Catan", englishName: "Catan" }),
          ),
          page: 0,
          size: effectiveResponseSize,
          totalElements: datasetSize,
          totalPages,
          hasNext: totalPages > 1,
        },
      }));
      return;
    }

    response.statusCode = 404;
    response.end(JSON.stringify({ error: "not found" }));
  });

  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      resolve({
        baseUrl: `http://127.0.0.1:${address.port}`,
        close: () => new Promise((closed) => {
          server.closeAllConnections();
          server.close(closed);
        }),
      });
    });
  });
}

function runRunner(
  baseUrl,
  outputDirectory,
  extraArguments = [],
  cwd = repositoryRoot,
  { datasetSha = datasetSha256, script = runnerPath } = {},
) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [
      script,
      "--base-url",
      baseUrl,
      "--server-commit",
      serverCommit,
      ...(datasetSha === null ? [] : ["--dataset-sha256", datasetSha]),
      "--output-directory",
      outputDirectory,
      ...extraArguments,
    ], { cwd });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("error", reject);
    child.on("close", (status, signal) => resolve({ status, signal, stdout, stderr }));
  });
}

function readSingleReport(outputDirectory) {
  const [reportName] = fs.readdirSync(outputDirectory).filter((name) => name.endsWith(".json"));
  assert.ok(reportName, "JSON 산출물이 생성되어야 합니다.");
  return JSON.parse(fs.readFileSync(path.join(outputDirectory, reportName), "utf8"));
}

test("성공 산출물은 serverCommit과 runnerCommit 및 최신 170,005 dataset을 구분해 기록한다", async () => {
  const server = await startServer();
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-success-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory, [], os.tmpdir());

    assert.equal(result.status, 0, result.stderr);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "success");
    assert.equal(report.serverCommit, serverCommit);
    assert.match(report.runnerCommit, /^[0-9a-f]{40}$/u);
    assert.match(report.runnerFileSha256, /^[0-9a-f]{64}$/u);
    assert.equal(report.runnerSourceClean, true);
    assert.equal(report.dataset.gameCount, 170005);
    assert.equal(report.dataset.sha256, datasetSha256);
    assert.equal(report.results.length, 6);
    assert.deepEqual(report.results[0].samples[0].pageMetadata, {
      page: 0,
      size: 24,
      totalElements: 170005,
      totalPages: Math.ceil(170005 / 24),
      hasNext: true,
      contentLength: 24,
    });
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("실측 non-200이면 실패 상태와 이미 수집한 raw sample을 산출물에 남긴다", async () => {
  const server = await startServer({ failMeasuredRequest: true });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-failure-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory);

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.equal(report.results.length, 1);
    assert.equal(report.results[0].status, "failed");
    assert.equal(report.results[0].samples.at(-1).status, 503);
    assert.match(report.results[0].error.message, /실측 실패/u);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("dataset SHA-256이 없으면 실행을 거부한다", async () => {
  const server = await startServer();
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-missing-dataset-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory, [], repositoryRoot, { datasetSha: null });

    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /--dataset-sha256/u);
    assert.deepEqual(fs.readdirSync(outputDirectory), []);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("200 응답의 game list 계약이 다르면 실패 sample을 보존한다", async () => {
  const server = await startServer({ invalidMeasuredResponse: true });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-invalid-response-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory);

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.equal(report.results[0].samples.at(-1).status, 200);
    assert.match(report.results[0].samples.at(-1).error, /응답 계약 불일치/u);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("요청한 page/size와 다른 200 응답은 실패 sample을 보존한다", async () => {
  const server = await startServer({ responseSize: 10 });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-page-size-mismatch-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory);

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.equal(report.results[0].samples.at(-1).status, 200);
    assert.match(report.results[0].samples.at(-1).error, /data.size가 요청 size=24와 다릅니다/u);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("멈춘 HTTP 요청은 timeout 실패 sample과 함께 종료된다", async () => {
  const server = await startServer({ hangMeasuredRequest: true });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-timeout-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory, ["--request-timeout-ms", "50"]);

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.equal(report.results[0].samples.at(-1).status, null);
    assert.match(report.results[0].samples.at(-1).error, /timeout/u);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("discovery 게임 목록 요청은 timeout 실패 artifact를 남긴다", async () => {
  const server = await startServer({ hangDiscoveryPath: "games" });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-discovery-timeout-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory, ["--request-timeout-ms", "50"]);

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.deepEqual(report.results, []);
    assert.match(report.failure.message, /HTTP 요청 timeout/u);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("discovery metadata 요청은 timeout 실패 artifact를 남긴다", async () => {
  const server = await startServer({ hangDiscoveryPath: "themes" });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-metadata-timeout-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory, ["--request-timeout-ms", "50"]);

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.deepEqual(report.results, []);
    assert.match(report.failure.message, /HTTP 요청 timeout/u);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("base discovery의 실제 dataset count가 기대값과 다르면 실패 artifact를 남긴다", async () => {
  const server = await startServer({ datasetSize: 1 });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-dataset-mismatch-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory);

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.deepEqual(report.results, []);
    assert.match(report.failure.message, /expected=170005, actual=1/u);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("수정된 runner source에서는 성공 산출물을 만들지 않는다", async () => {
  const server = await startServer();
  const fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-dirty-runner-"));
  const fixtureRunnerPath = path.join(fixtureRoot, "scripts/measurements/game-list-baseline.mjs");
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-dirty-output-"));
  fs.mkdirSync(path.dirname(fixtureRunnerPath), { recursive: true });
  fs.copyFileSync(runnerPath, fixtureRunnerPath);
  execFileSync("git", ["init", "-q"], { cwd: fixtureRoot });
  execFileSync("git", ["config", "user.email", "test@example.com"], { cwd: fixtureRoot });
  execFileSync("git", ["config", "user.name", "game-list-740-test"], { cwd: fixtureRoot });
  execFileSync("git", ["add", "scripts/measurements/game-list-baseline.mjs"], { cwd: fixtureRoot });
  execFileSync("git", ["commit", "-qm", "test runner"], { cwd: fixtureRoot });
  fs.appendFileSync(fixtureRunnerPath, "\n// dirty runner fixture\n");

  try {
    const result = await runRunner(
      server.baseUrl,
      outputDirectory,
      [],
      fixtureRoot,
      { script: fixtureRunnerPath },
    );

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.equal(report.runnerSourceClean, false);
    assert.match(report.failure.message, /미커밋 변경/u);
  } finally {
    await server.close();
    fs.rmSync(fixtureRoot, { recursive: true, force: true });
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});
