import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const runnerPath = fileURLToPath(new URL("./game-list-baseline.mjs", import.meta.url));
const serverCommit = "abcdef1234567";

function startServer({ failMeasuredRequest = false } = {}) {
  let gameRequests = 0;
  const server = createServer((request, response) => {
    response.setHeader("content-type", "application/json");

    if (request.url?.startsWith("/api/game-themes")) {
      response.end(JSON.stringify({ data: [{ code: "THEME" }] }));
      return;
    }
    if (request.url?.startsWith("/api/game-mechanisms")) {
      response.end(JSON.stringify({ data: [{ code: "MECHANISM" }] }));
      return;
    }
    if (request.url?.startsWith("/api/games")) {
      gameRequests += 1;
      if (failMeasuredRequest && gameRequests === 7) {
        response.statusCode = 503;
        response.end(JSON.stringify({ error: "temporary failure" }));
        return;
      }
      response.end(JSON.stringify({ data: { content: [{ name: "Catan", englishName: "Catan" }] } }));
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

function runRunner(baseUrl, outputDirectory, extraArguments = []) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [
      runnerPath,
      "--base-url",
      baseUrl,
      "--server-commit",
      serverCommit,
      "--output-directory",
      outputDirectory,
      ...extraArguments,
    ], { cwd: repositoryRoot });
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
    const result = await runRunner(server.baseUrl, outputDirectory);

    assert.equal(result.status, 0, result.stderr);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "success");
    assert.equal(report.serverCommit, serverCommit);
    assert.match(report.runnerCommit, /^[0-9a-f]{40}$/u);
    assert.equal(report.dataset.gameCount, 170005);
    assert.equal(report.results.length, 6);
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
