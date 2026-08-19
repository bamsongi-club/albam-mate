import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  REQUIRED_SCENARIOS,
  containsGamesExactCount,
  compareVariants,
  validateEvidenceRoot,
} from "./game-list-variant-comparison.mjs";

const comparisonPath = fileURLToPath(new URL("./game-list-variant-comparison.mjs", import.meta.url));
const evidenceRoot = fileURLToPath(new URL(
  "../../docs/measurements/results/game-list-740/game-list-867-2026-08-19/sql-captures/",
  import.meta.url,
));
const measuredHttpRoot = fileURLToPath(new URL(
  "../../docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/",
  import.meta.url,
));
const fixture = {
  fixtureId: "game-list-170005-observed-2026-08-19",
  fixtureManifestSha256: "58263d92f6f1f39f7cf3619f9f7666cf9d48c6f420b59606116a1e353f6000eb",
  gameCount: 170005,
  bggIdSetSha256: "75bcb893bcfef7f3b0a0de363e06037d332392c038ad5eb46c33de2b553c8744",
  metadata: {
    gameMechanismRelations: 428488,
    gameThemeRelations: 461973,
    gameCategoryRelations: 17337,
    gamePlayerPreferences: 263463,
  },
};
const baseP95 = {
  base: 100,
  keyword: 100,
  "player-count": 100,
  "relation-theme-mechanism": 200,
  complex: 200,
  "flags-upcoming-exact": 100,
};

function artifact({
  p95 = baseP95,
  fixtureOverride = {},
  runnerFileSha256 = "c".repeat(64),
  serverCommit = "f".repeat(40),
  sampleStatus = 200,
} = {}) {
  const dataset = {
    ...fixture,
    observedGameCount: fixture.gameCount,
    observedBggIdSetSha256: fixture.bggIdSetSha256,
    observedMetadata: fixture.metadata,
    postgresContainerId: "d".repeat(64),
    postgresComposeProject: "game-list-variant-test",
    ...fixtureOverride,
  };
  return {
    issue: 740,
    status: "success",
    runnerCommit: "e".repeat(40),
    runnerFileSha256,
    runnerSourceClean: true,
    serverCommit,
    serverContainers: [
      { role: "app1", containerId: "app1", imageRevision: serverCommit },
      { role: "app2", containerId: "app2", imageRevision: serverCommit },
    ],
    dataset,
    endProvenance: {
      runner: {
        commit: "e".repeat(40),
        fileSha256: runnerFileSha256,
        sourceClean: true,
      },
      server: {
        commit: serverCommit,
        containers: [
          { role: "app1", containerId: "app1", imageRevision: serverCommit },
          { role: "app2", containerId: "app2", imageRevision: serverCommit },
        ],
      },
      dataset: {
        observedGameCount: dataset.observedGameCount,
        bggIdSetSha256: dataset.observedBggIdSetSha256,
        metadata: dataset.observedMetadata,
        postgresContainerId: dataset.postgresContainerId,
        postgresComposeProject: dataset.postgresComposeProject,
      },
      errors: [],
    },
    warmUpRuns: 5,
    measuredRuns: 20,
    results: REQUIRED_SCENARIOS.map((name) => scenarioResult(name, p95[name], sampleStatus)),
  };
}

function scenarioResult(name, elapsedMs, sampleStatus) {
  return {
    name,
    params: { page: "0", size: "24" },
    status: "success",
    samples: Array.from({ length: 20 }, (_, index) => ({
      run: index + 1,
      status: sampleStatus,
      elapsedMs,
      pageMetadata: { page: 0, size: 24, hasNext: true, contentLength: 24 },
      upstreamRole: "app1",
      upstreamContainerId: "app1",
      error: sampleStatus === 200 ? null : "HTTP failure",
    })),
    summary: {
      p50Ms: elapsedMs,
      p95Ms: elapsedMs,
      maxMs: elapsedMs,
      minMs: elapsedMs,
      statuses: { [sampleStatus]: 20 },
      responseBytes: [100],
    },
    error: null,
  };
}

function p95(overrides) {
  return { ...baseP95, ...overrides };
}

function validSpecs(byVariant = {}) {
  return ["V0", "V1", "V2", "V3"].flatMap((variant) =>
    [1, 2, 3, 4].map((round) => ({
      variant,
      round,
      path: `${variant.toLowerCase()}-r${round}.json`,
      artifact: artifact({ p95: byVariant[variant] ?? baseP95 }),
    })),
  );
}

function measuredSpecs(httpRoot = measuredHttpRoot) {
  return ["V0", "V1", "V2", "V3"].flatMap((variant) =>
    [1, 2, 3, 4].map((round) => {
      const pathName = path.join(httpRoot, `${variant.toLowerCase()}-r${round}.json`);
      return {
        variant,
        round,
        path: pathName,
        artifact: JSON.parse(fs.readFileSync(pathName, "utf8")),
      };
    }),
  );
}

test("각 variant의 네 artifact와 같은 fixture fingerprint가 없으면 비교를 거절한다", () => {
  const missingRoundArtifacts = validSpecs().filter(
    (candidate) => !(candidate.variant === "V2" && candidate.round === 4),
  );
  assert.throws(() => compareVariants(missingRoundArtifacts), /V2.*4/u);

  const mismatchedFixtureArtifacts = validSpecs();
  const mismatchedFixture = mismatchedFixtureArtifacts.find(
    (candidate) => candidate.variant === "V3" && candidate.round === 4,
  ).artifact;
  mismatchedFixture.dataset.bggIdSetSha256 = "9".repeat(64);
  mismatchedFixture.dataset.observedBggIdSetSha256 = "9".repeat(64);
  mismatchedFixture.endProvenance.dataset.bggIdSetSha256 = "9".repeat(64);
  assert.throws(() => compareVariants(mismatchedFixtureArtifacts), /fixture/u);

  const consistentlyWrongFixtureArtifacts = validSpecs();
  for (const candidate of consistentlyWrongFixtureArtifacts) {
    candidate.artifact.dataset.fixtureId = "another-fixture";
    candidate.artifact.dataset.fixtureManifestSha256 = "9".repeat(64);
    candidate.artifact.dataset.bggIdSetSha256 = "8".repeat(64);
    candidate.artifact.dataset.observedBggIdSetSha256 = "8".repeat(64);
    candidate.artifact.endProvenance.dataset.bggIdSetSha256 = "8".repeat(64);
  }
  assert.throws(() => compareVariants(consistentlyWrongFixtureArtifacts), /승인된 fixture/u);
});

test("한 scenario라도 V0 median p95의 105%를 넘으면 후보를 탈락시킨다", () => {
  const regression = p95({ keyword: 106 });
  const result = compareVariants(validSpecs({ V1: regression, V2: regression, V3: regression }));

  assert.equal(result.variants.V1.gates.noRegression, false);
  assert.equal(result.variants.V1.gates.reasons[0].scenario, "keyword");
  assert.equal(result.selectedVariant, null);
});

test("relation과 complex가 모두 낮아진 후보만 선정한다", () => {
  const result = compareVariants(validSpecs({
    V1: p95({ "relation-theme-mechanism": 190, complex: 200 }),
    V2: p95({ "relation-theme-mechanism": 200, complex: 190 }),
    V3: p95({ "relation-theme-mechanism": 180, complex: 180 }),
  }));

  assert.equal(result.variants.V1.gates.relationComplexImprovement, false);
  assert.equal(result.variants.V2.gates.relationComplexImprovement, false);
  assert.equal(result.variants.V3.gates.relationComplexImprovement, true);
  assert.equal(result.selectedVariant, "V3");
});

test("같은 relation·complex 합계면 V1, V2, V3 순으로 선택한다", () => {
  const tiedP95 = p95({ "relation-theme-mechanism": 185, complex: 185 });
  const result = compareVariants(validSpecs({ V1: tiedP95, V2: tiedP95, V3: tiedP95 }));

  assert.equal(result.selectedVariant, "V1");
  assert.deepEqual(result.selection.candidates.map((candidate) => candidate.variant), ["V1", "V2", "V3"]);
});

test("runner SHA와 모든 200 sample이 같지 않으면 성공 비교를 만들지 않는다", () => {
  const runnerMismatch = validSpecs();
  const mismatchedRunner = runnerMismatch.find(
    (candidate) => candidate.variant === "V1" && candidate.round === 1,
  ).artifact;
  mismatchedRunner.runnerFileSha256 = "1".repeat(64);
  mismatchedRunner.endProvenance.runner.fileSha256 = "1".repeat(64);
  assert.throws(() => compareVariants(runnerMismatch), /runnerFileSha256/u);

  const failedSample = validSpecs();
  failedSample.find((candidate) => candidate.variant === "V3" && candidate.round === 2)
    .artifact.results[0] = scenarioResult("base", 100, 503);
  assert.throws(() => compareVariants(failedSample), /base.*200/u);

  const serverMismatch = validSpecs();
  serverMismatch.find((candidate) => candidate.variant === "V2" && candidate.round === 3)
    .artifact.endProvenance.server.commit = "1".repeat(40);
  assert.throws(() => compareVariants(serverMismatch), /server provenance/u);

  const serverContainerMismatch = validSpecs();
  serverContainerMismatch.find((candidate) => candidate.variant === "V3" && candidate.round === 1)
    .artifact.endProvenance.server.containers[0].imageRevision = "1".repeat(40);
  assert.throws(() => compareVariants(serverContainerMismatch), /server container/u);

  const sliceMetadataMismatch = validSpecs();
  sliceMetadataMismatch.find((candidate) => candidate.variant === "V1" && candidate.round === 2)
    .artifact.results[0].samples[0].pageMetadata.hasNext = false;
  assert.throws(() => compareVariants(sliceMetadataMismatch), /pageMetadata/u);

  const upstreamContainerMismatch = validSpecs();
  upstreamContainerMismatch.find((candidate) => candidate.variant === "V2" && candidate.round === 1)
    .artifact.results[0].samples[0].upstreamContainerId = "stale-container";
  assert.throws(() => compareVariants(upstreamContainerMismatch), /upstream container/u);
});

test("EXPLAIN target·provenance와 qualified games exact count를 검증한다", () => {
  assert.equal(containsGamesExactCount("select count(*) from games"), true);
  assert.equal(containsGamesExactCount("select count(*) from public.games"), true);
  assert.equal(containsGamesExactCount('select count(*) from "public"."games"'), true);
  assert.equal(containsGamesExactCount("select count(*) from game_themes"), false);

  const root = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-evidence-"));
  const copiedEvidenceRoot = path.join(root, "sql-captures");
  const copiedHttpRoot = path.join(root, "http");
  try {
    fs.cpSync(evidenceRoot, copiedEvidenceRoot, { recursive: true });
    fs.cpSync(measuredHttpRoot, copiedHttpRoot, { recursive: true });
    const specs = measuredSpecs(copiedHttpRoot);
    assert.doesNotThrow(() => validateEvidenceRoot(copiedEvidenceRoot, specs));

    const slowestPath = path.join(copiedEvidenceRoot, "v0/explain-input/base-slowest.sql");
    const slowestInput = fs.readFileSync(slowestPath, "utf8");
    fs.writeFileSync(slowestPath, slowestInput.replace("from games", "from other_table"));
    assert.throws(
      () => validateEvidenceRoot(copiedEvidenceRoot, specs),
      /slowest.*SQL/iu,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("CLI는 JSON과 Markdown 결과에 선정 후보와 raw artifact를 함께 기록한다", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "game-list-variant-comparison-"));
  try {
    const args = [];
    for (const spec of measuredSpecs()) {
      args.push("--artifact", `${spec.variant}:${spec.round}:${spec.path}`);
    }
    const outputPath = path.join(root, "comparison.json");
    const markdownPath = path.join(root, "comparison.md");
    assert.throws(
      () => execFileSync(process.execPath, [
        comparisonPath,
        ...args,
        "--output",
        outputPath,
        "--markdown-output",
        markdownPath,
      ], { stdio: "pipe" }),
      (error) => error.status === 1 && String(error.stderr).includes("--evidence-root"),
    );
    execFileSync(process.execPath, [
      comparisonPath,
      ...args,
      "--evidence-root",
      evidenceRoot,
      "--output",
      outputPath,
      "--markdown-output",
      markdownPath,
    ], { stdio: "pipe" });

    const result = JSON.parse(fs.readFileSync(outputPath, "utf8"));
    assert.equal(result.selectedVariant, "V1");
    assert.equal(result.variants.V1.scenarios.base.batches.length, 4);
    assert.match(fs.readFileSync(markdownPath, "utf8"), /선정 후보: V1/u);
    assert.match(fs.readFileSync(markdownPath, "utf8"), /v1-r1\.json/u);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
