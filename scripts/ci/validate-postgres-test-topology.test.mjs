import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { validatePostgresTestTopology } from "./validate-postgres-test-topology.mjs";

function fixture(context) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "postgres-topology-"));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  fs.writeFileSync(
    path.join(directory, "SharedPostgresTest.java"),
    "package example; class SharedPostgresTest extends SharedPostgresIntegrationSupport {}\n",
  );
  fs.writeFileSync(
    path.join(directory, "DedicatedPostgresTest.java"),
    "package example; class DedicatedPostgresTest {}\n",
  );
  return directory;
}

function writeManifest(directory, manifest) {
  const manifestPath = path.join(directory, "topology.json");
  fs.writeFileSync(manifestPath, JSON.stringify(manifest));
  return manifestPath;
}

test("모든 테스트를 shared 또는 dedicated 중 하나로만 분류한다", (context) => {
  const directory = fixture(context);
  const manifestPath = writeManifest(directory, {
    schemaVersion: 1,
    shared: ["example.SharedPostgresTest"],
    dedicated: { "example.DedicatedPostgresTest": "special option" },
  });

  assert.deepEqual(validatePostgresTestTopology(directory, manifestPath), {
    testCount: 2,
    sharedCount: 1,
    dedicatedCount: 1,
  });
});

test("누락되거나 중복 등록된 테스트를 거부한다", (context) => {
  const directory = fixture(context);
  const missingManifest = writeManifest(directory, {
    schemaVersion: 1,
    shared: ["example.SharedPostgresTest"],
    dedicated: {},
  });
  assert.throws(
    () => validatePostgresTestTopology(directory, missingManifest),
    /missing=\[example.DedicatedPostgresTest\]/,
  );

  const duplicateManifest = writeManifest(directory, {
    schemaVersion: 1,
    shared: ["example.SharedPostgresTest"],
    dedicated: { "example.SharedPostgresTest": "duplicate", "example.DedicatedPostgresTest": "special" },
  });
  assert.throws(
    () => validatePostgresTestTopology(directory, duplicateManifest),
    /중복 등록/,
  );
});

test("shared 테스트의 공통 지원 클래스 상속을 강제한다", (context) => {
  const directory = fixture(context);
  const manifestPath = writeManifest(directory, {
    schemaVersion: 1,
    shared: ["example.DedicatedPostgresTest"],
    dedicated: { "example.SharedPostgresTest": "wrong side" },
  });

  assert.throws(
    () => validatePostgresTestTopology(directory, manifestPath),
    /공통 지원 클래스를 상속하지 않습니다/,
  );
});
