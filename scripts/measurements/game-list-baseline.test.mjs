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
const serverCommit = "a".repeat(40);
const datasetSha256 = "d".repeat(64);
let dockerFixture;

function gameItems(count) {
  return Array.from(
    { length: count },
    () => ({ name: "Catan", englishName: "Catan" }),
  );
}

function startServer({
  failMeasuredRequest = false,
  invalidMeasuredResponse = false,
  hangMeasuredRequest = false,
  hangDiscoveryPath = null,
  datasetSize = 170005,
  responseSize = 24,
  measuredPageOverride = null,
  upstreamRole = "app1",
  upstreamAddress = "172.28.0.11:8080",
} = {}) {
  let gameRequests = 0;
  const server = createServer((request, response) => {
    response.setHeader("content-type", "application/json");
    response.setHeader("X-Albam-Mate-Upstream", upstreamRole);
    response.setHeader("X-Albam-Mate-Upstream-Address", upstreamAddress);

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
      const requestUrl = new URL(request.url, "http://127.0.0.1");
      if (
        requestUrl.searchParams.get("page") !== "0"
        || requestUrl.searchParams.get("size") !== "24"
      ) {
        response.statusCode = 400;
        response.end(JSON.stringify({ error: "expected page=0 and size=24" }));
        return;
      }
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
      const page = {
        content: gameItems(Math.min(datasetSize, effectiveResponseSize)),
        page: 0,
        size: effectiveResponseSize,
        hasNext: datasetSize > effectiveResponseSize,
      };
      if (gameRequests >= 7 && measuredPageOverride) {
        Object.assign(page, measuredPageOverride);
      }
      response.end(JSON.stringify({
        status: 200,
        data: page,
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

function createDockerFixture({
  app1Revision = serverCommit,
  app2Revision = serverCommit,
  app1ImageId = `sha256:${"1".repeat(64)}`,
  app2ImageId = app1ImageId,
  databaseGameCount = 170005,
  databaseGameCounts = [databaseGameCount],
} = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-docker-"));
  const configPath = path.join(root, "containers.json");
  const countCallPath = path.join(root, "postgres-count-calls");
  const dockerPath = path.join(root, "docker");
  const records = {
    "app1-container": {
      id: "1".repeat(64),
      imageId: app1ImageId,
      labels: {
        "com.docker.compose.project": "albam-mate-771",
        "com.docker.compose.service": "spring-1",
        "org.opencontainers.image.revision": app1Revision,
      },
      networks: {
        "albam-mate-771_default": { IPAddress: "172.28.0.11" },
      },
    },
    "app2-container": {
      id: "2".repeat(64),
      imageId: app2ImageId,
      labels: {
        "com.docker.compose.project": "albam-mate-771",
        "com.docker.compose.service": "spring-2",
        "org.opencontainers.image.revision": app2Revision,
      },
      networks: {
        "albam-mate-771_default": { IPAddress: "172.28.0.12" },
      },
    },
    "proxy-container": {
      id: "3".repeat(64),
      imageId: `sha256:${"3".repeat(64)}`,
      labels: {
        "com.docker.compose.project": "albam-mate-771",
        "com.docker.compose.service": "proxy",
      },
      networks: {
        "albam-mate-771_default": { IPAddress: "172.28.0.10" },
      },
    },
    "postgres-container": {
      id: "4".repeat(64),
      imageId: `sha256:${"4".repeat(64)}`,
      labels: {
        "com.docker.compose.project": "albam-mate-771",
        "com.docker.compose.service": "postgres",
      },
      networks: {
        "albam-mate-771_default": { IPAddress: "172.28.0.13" },
      },
      gameCounts: databaseGameCounts,
    },
  };
  fs.writeFileSync(configPath, JSON.stringify(records));
  fs.writeFileSync(dockerPath, `#!/usr/bin/env node
const fs = require("node:fs");
const records = JSON.parse(fs.readFileSync(process.env.GAME_LIST_DOCKER_FIXTURE, "utf8"));
const arguments = process.argv.slice(2);
if (arguments[0] === "inspect" && arguments[1] === "--format") {
  const [,, format, container] = arguments;
  const record = records[container];
  const values = {
    "{{.Id}}": record?.id,
    "{{.Image}}": record?.imageId,
    "{{json .Config.Labels}}": record ? JSON.stringify(record.labels) : undefined,
    "{{json .NetworkSettings.Networks}}": record ? JSON.stringify(record.networks) : undefined,
  };
  if (!record || !(format in values)) process.exit(2);
  process.stdout.write(values[format] + "\\n");
  process.exit(0);
}
if (arguments[0] === "ps" && arguments.at(-2) === "--format" && arguments.at(-1) === "{{.ID}}") {
  const project = arguments.find((argument) => argument.startsWith("label=com.docker.compose.project=")).split("=").at(-1);
  const service = arguments.find((argument) => argument.startsWith("label=com.docker.compose.service=")).split("=").at(-1);
  const record = Object.values(records).find((candidate) =>
    candidate.labels["com.docker.compose.project"] === project && candidate.labels["com.docker.compose.service"] === service);
  if (!record) process.exit(2);
  process.stdout.write(record.id + "\\n");
  process.exit(0);
}
if (arguments[0] === "exec" && arguments[2] === "sh" && arguments[3] === "-c") {
  const record = Object.values(records).find((candidate) => candidate.id === arguments[1]);
  if (!record || record.labels["com.docker.compose.service"] !== "postgres") process.exit(2);
  const callIndex = Number(fs.existsSync(process.env.GAME_LIST_DOCKER_FIXTURE_CALLS)
    ? fs.readFileSync(process.env.GAME_LIST_DOCKER_FIXTURE_CALLS, "utf8")
    : "0");
  fs.writeFileSync(process.env.GAME_LIST_DOCKER_FIXTURE_CALLS, String(callIndex + 1));
  const gameCounts = record.gameCounts || [];
  process.stdout.write(String(gameCounts[Math.min(callIndex, gameCounts.length - 1)]) + "\\n");
  process.exit(0);
}
{
  process.exit(2);
}
`);
  fs.chmodSync(dockerPath, 0o755);
  return {
    root,
    env: {
      ...process.env,
      GAME_LIST_DOCKER_FIXTURE: configPath,
      GAME_LIST_DOCKER_FIXTURE_CALLS: countCallPath,
      PATH: `${root}${path.delimiter}${process.env.PATH}`,
    },
  };
}

test.before(() => {
  dockerFixture = createDockerFixture();
});

test.after(() => {
  fs.rmSync(dockerFixture.root, { recursive: true, force: true });
});

function createCleanRunnerFixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-runner-"));
  const script = path.join(root, "scripts/measurements/game-list-baseline.mjs");
  fs.mkdirSync(path.dirname(script), { recursive: true });
  fs.copyFileSync(runnerPath, script);
  execFileSync("git", ["init", "-q"], { cwd: root });
  execFileSync("git", ["config", "user.email", "test@example.com"], { cwd: root });
  execFileSync("git", ["config", "user.name", "game-list-740-test"], { cwd: root });
  execFileSync("git", ["add", "scripts/measurements/game-list-baseline.mjs"], { cwd: root });
  execFileSync("git", ["commit", "-qm", "test runner"], { cwd: root });
  return { root, script };
}

function runRunner(
  baseUrl,
  outputDirectory,
  extraArguments = [],
  cwd = repositoryRoot,
  { datasetSha = datasetSha256, script = runnerPath, env = dockerFixture.env } = {},
) {
  const runnerFixture = script === runnerPath ? createCleanRunnerFixture() : null;
  const effectiveScript = runnerFixture?.script ?? script;
  const effectiveCwd = runnerFixture?.root ?? cwd;
  const removeRunnerFixture = () => {
    if (runnerFixture) {
      fs.rmSync(runnerFixture.root, { recursive: true, force: true });
    }
  };
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [
      effectiveScript,
      "--base-url",
      baseUrl,
      "--server-commit",
      serverCommit,
      "--server-container",
      "app1=app1-container",
      "--server-container",
      "app2=app2-container",
      "--proxy-container",
      "proxy-container",
      ...(datasetSha === null ? [] : ["--dataset-sha256", datasetSha]),
      "--output-directory",
      outputDirectory,
      ...extraArguments,
    ], { cwd: effectiveCwd, env });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("error", (error) => {
      removeRunnerFixture();
      reject(error);
    });
    child.on("close", (status, signal) => {
      removeRunnerFixture();
      resolve({ status, signal, stdout, stderr });
    });
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
    assert.deepEqual(report.serverContainers, [
      {
        role: "app1",
        containerId: "1".repeat(64),
        imageId: `sha256:${"1".repeat(64)}`,
        imageRevision: serverCommit,
        composeProject: "albam-mate-771",
        composeService: "spring-1",
        networkNames: ["albam-mate-771_default"],
        networkAddresses: ["172.28.0.11"],
      },
      {
        role: "app2",
        containerId: "2".repeat(64),
        imageId: `sha256:${"1".repeat(64)}`,
        imageRevision: serverCommit,
        composeProject: "albam-mate-771",
        composeService: "spring-2",
        networkNames: ["albam-mate-771_default"],
        networkAddresses: ["172.28.0.12"],
      },
    ]);
    assert.deepEqual(report.proxyContainer, {
      containerId: "3".repeat(64),
      composeProject: "albam-mate-771",
      composeService: "proxy",
      networkNames: ["albam-mate-771_default"],
    });
    assert.deepEqual(report.dataset, {
      gameCount: 170005,
      observedGameCount: 170005,
      sha256: datasetSha256,
      postgresContainerId: "4".repeat(64),
      postgresComposeProject: "albam-mate-771",
    });
    assert.deepEqual(report.endProvenance.dataset, {
      observedGameCount: 170005,
      postgresContainerId: "4".repeat(64),
      postgresComposeProject: "albam-mate-771",
    });
    assert.deepEqual(report.endProvenance.errors, []);
    assert.equal(report.results.length, 6);
    assert.deepEqual(report.results[0].samples[0].pageMetadata, {
      page: 0,
      size: 24,
      hasNext: true,
      contentLength: 24,
    });
    assert.equal(report.results[0].samples[0].upstreamRole, "app1");
    assert.equal(report.results[0].samples[0].upstreamAddress, "172.28.0.11:8080");
    assert.equal(report.results[0].samples[0].upstreamContainerId, "1".repeat(64));
    assert.deepEqual(report.discovered.upstreamRoles, {
      base: "app1",
      themes: "app1",
      mechanisms: "app1",
    });
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("서버 image revision 또는 image ID가 다르면 성공 산출물을 만들지 않는다", async () => {
  const cases = [
    {
      name: "revision mismatch",
      fixture: createDockerFixture({ app2Revision: "b".repeat(40) }),
      error: /image revision이 --server-commit과 다릅니다/u,
    },
    {
      name: "image ID mismatch",
      fixture: createDockerFixture({ app2ImageId: `sha256:${"2".repeat(64)}` }),
      error: /image ID가 서로 다릅니다/u,
    },
  ];

  for (const testCase of cases) {
    const server = await startServer();
    const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-server-provenance-"));
    try {
      const result = await runRunner(server.baseUrl, outputDirectory, [], os.tmpdir(), {
        env: testCase.fixture.env,
      });

      assert.notEqual(result.status, 0, testCase.name);
      const report = readSingleReport(outputDirectory);
      assert.equal(report.status, "failed", testCase.name);
      assert.deepEqual(report.results, [], testCase.name);
      assert.match(report.failure.message, testCase.error, testCase.name);
    } finally {
      await server.close();
      fs.rmSync(outputDirectory, { recursive: true, force: true });
      fs.rmSync(testCase.fixture.root, { recursive: true, force: true });
    }
  }
});

test("검증한 Spring 역할이 아닌 응답은 성공 산출물을 만들지 않는다", async () => {
  const server = await startServer({ upstreamRole: "unknown" });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-upstream-role-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory);

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.deepEqual(report.results, []);
    assert.match(report.failure.message, /x-albam-mate-upstream=unknown/u);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("응답 upstream 주소가 inspect한 역할 컨테이너와 다르면 성공 산출물을 만들지 않는다", async () => {
  const server = await startServer({ upstreamAddress: "172.28.0.12:8080" });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-upstream-address-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory);

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.deepEqual(report.results, []);
    assert.match(report.failure.message, /x-albam-mate-upstream-address=172\.28\.0\.12:8080/u);
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

test("Slice metadata 의미가 요청과 다르면 실패 sample을 보존한다", async () => {
  const cases = [
    {
      name: "hasNext content length",
      measuredPageOverride: { content: gameItems(23) },
      error: /hasNext가 true인데 content 길이가 요청 size와 다릅니다/u,
    },
    {
      name: "totalElements",
      measuredPageOverride: { totalElements: 170005 },
      error: /totalElements를 포함하면 안 됩니다/u,
    },
    {
      name: "totalPages",
      measuredPageOverride: { totalPages: 7084 },
      error: /totalPages를 포함하면 안 됩니다/u,
    },
    {
      name: "hasNext",
      measuredPageOverride: { hasNext: false },
      error: /170005건 fixture의 첫 페이지에서 true여야 합니다/u,
    },
  ];

  for (const testCase of cases) {
    const server = await startServer(testCase);
    const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-page-metadata-mismatch-"));
    try {
      const result = await runRunner(server.baseUrl, outputDirectory);

      assert.notEqual(result.status, 0, testCase.name);
      const report = readSingleReport(outputDirectory);
      assert.equal(report.status, "failed", testCase.name);
      assert.match(report.results[0].samples.at(-1).error, testCase.error, testCase.name);
    } finally {
      await server.close();
      fs.rmSync(outputDirectory, { recursive: true, force: true });
    }
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
    assert.deepEqual(report.endProvenance.dataset, {
      observedGameCount: 170005,
      postgresContainerId: "4".repeat(64),
      postgresComposeProject: "albam-mate-771",
    });
    assert.deepEqual(report.endProvenance.errors, []);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
  }
});

test("discovery 실패 뒤 종료 fixture 대조 오류를 artifact에 남기되 원래 실패를 보존한다", async () => {
  const server = await startServer({ hangDiscoveryPath: "games" });
  const fixture = createDockerFixture({ databaseGameCounts: [170005, 1] });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-discovery-end-provenance-"));
  try {
    const result = await runRunner(
      server.baseUrl,
      outputDirectory,
      ["--request-timeout-ms", "50"],
      repositoryRoot,
      { env: fixture.env },
    );

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.match(report.failure.message, /HTTP 요청 timeout/u);
    assert.equal(report.endProvenance.dataset, null);
    assert.deepEqual(report.endProvenance.errors, [{
      scope: "dataset",
      message: "PostgreSQL fixture games row count 불일치: expected=170005, actual=1",
    }]);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
    fs.rmSync(fixture.root, { recursive: true, force: true });
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

test("Compose PostgreSQL의 실제 dataset count가 기대값과 다르면 실패 artifact를 남긴다", async () => {
  const server = await startServer({ datasetSize: 1 });
  const fixture = createDockerFixture({ databaseGameCount: 1 });
  const outputDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-740-dataset-mismatch-"));
  try {
    const result = await runRunner(server.baseUrl, outputDirectory, [], repositoryRoot, { env: fixture.env });

    assert.notEqual(result.status, 0);
    const report = readSingleReport(outputDirectory);
    assert.equal(report.status, "failed");
    assert.deepEqual(report.results, []);
    assert.match(report.failure.message, /expected=170005, actual=1/u);
  } finally {
    await server.close();
    fs.rmSync(outputDirectory, { recursive: true, force: true });
    fs.rmSync(fixture.root, { recursive: true, force: true });
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
