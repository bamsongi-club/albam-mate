import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { classifyCiPaths, readNulDelimitedPaths } from "./classify-ci-paths.mjs";
import { POSTGRES_DECISIONS } from "./classify-postgres-requirement.mjs";

const scriptPath = fileURLToPath(new URL("./classify-ci-paths.mjs", import.meta.url));
const workflowPath = fileURLToPath(new URL("../../.github/workflows/ci.yml", import.meta.url));

const noBackend = (frontend) => ({
  backend: false,
  frontend,
  postgresDecision: POSTGRES_DECISIONS.NOT_REQUIRED,
  postgresRequired: false,
  dockerRequired: false,
});

const fullBackend = (frontend = false) => ({
  backend: true,
  frontend,
  postgresDecision: POSTGRES_DECISIONS.NEEDS_REVIEW,
  postgresRequired: true,
  dockerRequired: true,
});

test("문서 변경은 backend와 frontend를 실행하지 않는다", () => {
  assert.deepEqual(
    classifyCiPaths([
      "README.md",
      "docs/guides/TESTING.md",
      "src/test/AGENTS.md",
      ".github/ISSUE_TEMPLATE/docs.yml",
      "scripts/docs/check-doc-links.test.mjs",
      "scripts/docs/check-monitoring-contract.mjs",
      "scripts/docs/check-monitoring-contract.test.mjs",
      "scripts/ci/classify-postgres-requirement.test.mjs",
      "scripts/verify-changed-h2-coverage.test.mjs",
    ]),
    noBackend(false),
  );
});

test("frontend 변경은 frontend만 실행한다", () => {
  assert.deepEqual(
    classifyCiPaths(["frontend/src/App.tsx", "frontend/README.md"]),
    noBackend(true),
  );
});

test("backend와 빌드 변경은 backend를 실행한다", () => {
  assert.deepEqual(classifyCiPaths(["src/main/java/example/App.java"]), fullBackend());
  assert.deepEqual(classifyCiPaths(["build.gradle"]), fullBackend());
  assert.deepEqual(classifyCiPaths([".github/workflows/ci.yml"]), fullBackend());
});

test("확실한 not-required 백엔드 변경만 PostgreSQL과 Docker를 생략한다", () => {
  assert.deepEqual(
    classifyCiPaths(["src/main/java/example/dto/Response.java"], {
      postgresClassification: { decision: POSTGRES_DECISIONS.NOT_REQUIRED },
    }),
    {
      backend: true,
      frontend: false,
      postgresDecision: POSTGRES_DECISIONS.NOT_REQUIRED,
      postgresRequired: false,
      dockerRequired: false,
    },
  );

  assert.deepEqual(
    classifyCiPaths(["src/main/resources/db/migration/V2__change.sql"], {
      postgresClassification: { decision: POSTGRES_DECISIONS.REQUIRED },
    }),
    {
      backend: true,
      frontend: false,
      postgresDecision: POSTGRES_DECISIONS.REQUIRED,
      postgresRequired: true,
      dockerRequired: true,
    },
  );
});

test("혼합 변경은 필요한 검증을 모두 실행한다", () => {
  assert.deepEqual(
    classifyCiPaths(["frontend/src/App.tsx", "src/main/java/example/App.java"]),
    fullBackend(true),
  );
});

test("빈 목록과 수동 실행은 전체 검증으로 안전하게 폴백한다", () => {
  assert.deepEqual(classifyCiPaths([]), fullBackend(true));
  assert.deepEqual(classifyCiPaths(["docs/README.md"], { forceAll: true }), fullBackend(true));
});

test("Windows 경로 구분자를 정규화한다", () => {
  assert.deepEqual(classifyCiPaths(["frontend\\src\\App.tsx"]), noBackend(true));
});

function createGitWorktree(t) {
  const worktree = fs.mkdtempSync(path.join(os.tmpdir(), "ci-path-classifier-"));
  t.after(() => fs.rmSync(worktree, { recursive: true, force: true }));
  const git = (...args) =>
    spawnSync("git", ["-C", worktree, ...args], { encoding: "utf8", windowsHide: true });
  git("init", "--quiet");
  const dtoPath = "src/main/java/example/dto/Response.java";
  fs.mkdirSync(path.dirname(path.join(worktree, dtoPath)), { recursive: true });
  fs.writeFileSync(path.join(worktree, dtoPath), "public record Response(long id) {}\n", "utf8");
  git("add", "--all");
  git(
    "-c",
    "user.name=test",
    "-c",
    "user.email=test@example.com",
    "commit",
    "--quiet",
    "--message=baseline",
  );
  fs.writeFileSync(
    path.join(worktree, dtoPath),
    "public record Response(long id, String name) {}\n",
    "utf8",
  );
  const pathsFile = path.join(worktree, "changed-paths.txt");
  fs.writeFileSync(pathsFile, `${dtoPath}\0`, "utf8");
  return { worktree, pathsFile };
}

test("NUL 경로 파일은 비ASCII와 개행을 포함한 경로를 원문 그대로 읽는다", (t) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "ci-nul-paths-"));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const pathsFile = path.join(directory, "changed-paths.bin");
  const paths = [
    "src/main/java/example/한글.java",
    "src/main/java/example/줄바꿈\n경로.java",
  ];
  fs.writeFileSync(pathsFile, `${paths.join("\0")}\0`, "utf8");

  assert.deepEqual(readNulDelimitedPaths(pathsFile), paths);
});

test("workflow는 git diff 경로를 NUL 구분으로 전달한다", () => {
  const workflow = fs.readFileSync(workflowPath, "utf8");

  assert.match(workflow, /git diff --name-only --no-renames -z /u);
});

function parseOutputs(stdout) {
  return Object.fromEntries(
    stdout
      .trim()
      .split(/\r?\n/)
      .map((line) => line.split(/=(.*)/s).slice(0, 2)),
  );
}

test("CLI는 실제 diff의 safe 변경에서 PostgreSQL과 Docker를 생략한다", (t) => {
  const { worktree, pathsFile } = createGitWorktree(t);
  const result = spawnSync(
    process.execPath,
    [
      scriptPath,
      "--paths-file",
      pathsFile,
      "--base",
      "HEAD",
      "--worktree",
      worktree,
    ],
    { encoding: "utf8" },
  );

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(parseOutputs(result.stdout), {
    backend: "true",
    frontend: "false",
    postgres_decision: "not-required",
    postgres_required: "false",
    docker_required: "false",
    postgres_reasons: "pure-java-change",
  });
});

test("CLI는 PostgreSQL 실행 대상과 선택 검증 제어 스크립트 단독 변경에서 PostgreSQL과 Docker를 실행한다", (t) => {
  for (const changedPath of [
    "scripts/ci/partition-postgres-tests.mjs",
    "scripts/ci/classify-postgres-requirement.mjs",
    "scripts/verify-changed-h2-coverage.mjs",
  ]) {
    const worktree = fs.mkdtempSync(path.join(os.tmpdir(), "ci-postgres-control-"));
    t.after(() => fs.rmSync(worktree, { recursive: true, force: true }));
    const git = (...args) =>
      spawnSync("git", ["-C", worktree, ...args], { encoding: "utf8", windowsHide: true });
    const sourcePath = path.join(worktree, changedPath);
    const initialized = git("init", "--quiet");
    assert.equal(initialized.status, 0, initialized.stderr);
    fs.mkdirSync(path.dirname(sourcePath), { recursive: true });
    fs.writeFileSync(sourcePath, "export const gate = 2;\n", "utf8");
    git("add", "--all");
    const baseline = git(
      "-c",
      "user.name=test",
      "-c",
      "user.email=test@example.com",
      "commit",
      "--quiet",
      "--message=baseline",
    );
    assert.equal(baseline.status, 0, baseline.stderr);
    fs.writeFileSync(sourcePath, "export const gate = 3;\n", "utf8");
    const pathsFile = path.join(worktree, "changed-paths.txt");
    fs.writeFileSync(pathsFile, `${changedPath}\0`, "utf8");

    const result = spawnSync(
      process.execPath,
      [
        scriptPath,
        "--paths-file",
        pathsFile,
        "--base",
        "HEAD",
        "--worktree",
        worktree,
      ],
      { encoding: "utf8" },
    );

    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(
      parseOutputs(result.stdout),
      {
        backend: "true",
        frontend: "false",
        postgres_decision: "needs-review",
        postgres_required: "true",
        docker_required: "true",
        postgres_reasons: "postgres-execution-control",
      },
      changedPath,
    );
  }
});

test("CLI 분류기가 실패하면 성공 상태로 needs-review 전체 검증에 폴백한다", (t) => {
  const { worktree, pathsFile } = createGitWorktree(t);
  const result = spawnSync(
    process.execPath,
    [
      scriptPath,
      "--paths-file",
      pathsFile,
      "--base",
      "missing-base",
      "--worktree",
      worktree,
    ],
    { encoding: "utf8" },
  );

  assert.equal(result.status, 0, result.stderr);
  const outputs = parseOutputs(result.stdout);
  assert.equal(outputs.postgres_decision, "needs-review");
  assert.equal(outputs.postgres_required, "true");
  assert.equal(outputs.docker_required, "true");
  assert.equal(outputs.postgres_reasons, "classifier-error");
});
