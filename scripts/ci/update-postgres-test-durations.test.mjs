import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { buildDurationManifest } from "./update-postgres-test-durations.mjs";

test("여러 JUnit 실행의 클래스별 중앙값을 manifest로 만든다", (context) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "postgres-durations-"));
  context.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const directories = ["run-1", "run-2", "run-3"].map((name) => {
    const directory = path.join(root, name);
    fs.mkdirSync(directory);
    return directory;
  });
  [1.2, 4.8, 2.4].forEach((seconds, index) => {
    fs.writeFileSync(
      path.join(directories[index], "TEST-example.AlphaPostgresTest.xml"),
      `<testsuite name="example.AlphaPostgresTest" tests="1" time="${seconds}"></testsuite>`,
    );
  });

  const manifest = buildDurationManifest(directories);

  assert.equal(manifest.schemaVersion, 1);
  assert.deepEqual(manifest.sourceRuns, ["run-1", "run-2", "run-3"]);
  assert.deepEqual(manifest.durationsMs, { "example.AlphaPostgresTest": 2_400 });
});

test("일부 실행에서 누락된 testsuite를 거부한다", (context) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "postgres-durations-missing-"));
  context.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const first = path.join(root, "run-1");
  const second = path.join(root, "run-2");
  fs.mkdirSync(first);
  fs.mkdirSync(second);
  fs.writeFileSync(
    path.join(first, "TEST-example.AlphaPostgresTest.xml"),
    '<testsuite name="example.AlphaPostgresTest" time="1.0"></testsuite>',
  );
  fs.writeFileSync(
    path.join(second, "TEST-example.BravoPostgresTest.xml"),
    '<testsuite name="example.BravoPostgresTest" time="1.0"></testsuite>',
  );

  assert.throws(() => buildDurationManifest([first, second]), /모든 실행에 존재하지 않는/);
});
