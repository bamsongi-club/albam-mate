import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  discoverPostgresTests,
  partitionPostgresTests,
} from "./partition-postgres-tests.mjs";

test("PostgreSQL 테스트를 빠짐없이 하나의 shard에만 배치한다", () => {
  const discovered = discoverPostgresTests();
  const shards = partitionPostgresTests(discovered, 2);
  const assigned = shards.flatMap((shard) => shard.tests.map((postgresTest) => postgresTest.className));

  assert.ok(discovered.length > 2);
  assert.equal(assigned.length, discovered.length);
  assert.equal(new Set(assigned).size, discovered.length);
  assert.deepEqual([...assigned].sort(), discovered.map((postgresTest) => postgresTest.className).sort());
});

test("같은 입력은 항상 같은 shard 구성을 만든다", () => {
  const tests = [
    { className: "example.AlphaPostgresTest", weight: 100 },
    { className: "example.BravoPostgresTest", weight: 80 },
    { className: "example.CharliePostgresTest", weight: 60 },
    { className: "example.DeltaPostgresTest", weight: 40 },
  ];

  assert.deepEqual(partitionPostgresTests(tests, 2), partitionPostgresTests([...tests].reverse(), 2));
});

test("큰 테스트부터 누적 가중치가 작은 shard에 배치한다", () => {
  const shards = partitionPostgresTests(
    [
      { className: "example.HeavyPostgresTest", weight: 100 },
      { className: "example.MediumPostgresTest", weight: 60 },
      { className: "example.SmallPostgresTest", weight: 40 },
    ],
    2,
  );

  assert.deepEqual(
    shards.map((shard) => shard.totalWeight),
    [100, 100],
  );
});

test("유효하지 않은 shard 수를 거부한다", () => {
  assert.throws(() => partitionPostgresTests([], 0), /1 이상의 정수/);
  assert.throws(
    () => partitionPostgresTests([{ className: "example.OnlyPostgresTest", weight: 1 }], 2),
    /테스트 수보다 shard 수가 많습니다/,
  );
});

test("JUnit 중앙값을 우선하고 새 테스트만 소스 크기 fallback을 사용한다", (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "postgres-partition-"));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  fs.writeFileSync(
    path.join(directory, "AlphaPostgresTest.java"),
    "package example; class AlphaPostgresTest {}\n",
  );
  fs.writeFileSync(
    path.join(directory, "BravoPostgresTest.java"),
    "package example; class BravoPostgresTest {}\n",
  );
  const manifestPath = path.join(directory, "durations.json");
  fs.writeFileSync(
    manifestPath,
    JSON.stringify({ schemaVersion: 1, durationsMs: { "example.AlphaPostgresTest": 4_200 } }),
  );

  const discovered = discoverPostgresTests(directory, manifestPath);

  assert.equal(discovered.find((entry) => entry.className.endsWith("AlphaPostgresTest")).weight, 4_200);
  assert.equal(
    discovered.find((entry) => entry.className.endsWith("BravoPostgresTest")).weightSource,
    "source-size-fallback",
  );
});

test("class 수준 measurement 테스트는 기본 shard에서 제외한다", (context) => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "postgres-measurement-"));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  fs.writeFileSync(
    path.join(directory, "MeasuredPostgresTest.java"),
    'package example; @Tag("measurement") class MeasuredPostgresTest {}\n',
  );
  fs.writeFileSync(
    path.join(directory, "RegressionPostgresTest.java"),
    "package example; class RegressionPostgresTest {}\n",
  );

  assert.deepEqual(
    discoverPostgresTests(directory, path.join(directory, "missing.json")).map((entry) => entry.className),
    ["example.RegressionPostgresTest"],
  );
});
