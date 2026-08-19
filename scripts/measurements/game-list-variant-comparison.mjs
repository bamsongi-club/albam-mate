#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

export const REQUIRED_SCENARIOS = [
  "base",
  "keyword",
  "player-count",
  "relation-theme-mechanism",
  "complex",
  "flags-upcoming-exact",
];

const VARIANTS = ["V0", "V1", "V2", "V3"];
const ROUNDS = [1, 2, 3, 4];
const FIXTURE_GAME_COUNT = 170005;
const FIXTURE_METADATA = {
  gameMechanismRelations: 428488,
  gameThemeRelations: 461973,
  gameCategoryRelations: 17337,
  gamePlayerPreferences: 263463,
};
const EXPECTED_FIXTURE = {
  fixtureId: "game-list-170005-observed-2026-08-19",
  fixtureManifestSha256: "58263d92f6f1f39f7cf3619f9f7666cf9d48c6f420b59606116a1e353f6000eb",
  bggIdSetSha256: "75bcb893bcfef7f3b0a0de363e06037d332392c038ad5eb46c33de2b553c8744",
};
const NO_REGRESSION_RATIO = 1.05;

function fail(message) {
  throw new Error(message);
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function nonEmptyString(value, label) {
  if (typeof value !== "string" || value.trim() === "") {
    fail(`${label}은 비어 있지 않은 문자열이어야 합니다.`);
  }
  return value;
}

function sha256(value, label) {
  if (typeof value !== "string" || !/^[0-9a-f]{64}$/u.test(value)) {
    fail(`${label}은 64자리 소문자 hexadecimal SHA-256이어야 합니다.`);
  }
  return value;
}

function finiteNonnegative(value, label) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    fail(`${label}은 0 이상의 유한 number여야 합니다.`);
  }
  return value;
}

function nonnegativeInteger(value, label) {
  if (!Number.isSafeInteger(value) || value < 0) {
    fail(`${label}은 0 이상의 안전한 정수여야 합니다.`);
  }
  return value;
}

function equivalent(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

export function medianOfFour(values) {
  if (!Array.isArray(values) || values.length !== 4) {
    fail(`medianOfFour는 정확히 네 값이 필요합니다: ${values?.length ?? "missing"}`);
  }
  const sorted = values.map((value, index) => finiteNonnegative(value, `median 값 ${index + 1}`))
    .sort((left, right) => left - right);
  return (sorted[1] + sorted[2]) / 2;
}

function nearestRank(samples, percentile) {
  const sorted = [...samples].sort((left, right) => left - right);
  const rank = Math.max(1, Math.ceil(sorted.length * percentile));
  return sorted[rank - 1];
}

function expectedSummary(samples) {
  const elapsed = samples.map((sample) => sample.elapsedMs);
  return {
    p50Ms: nearestRank(elapsed, 0.5),
    p95Ms: nearestRank(elapsed, 0.95),
    maxMs: Math.max(...elapsed),
  };
}

function validateMetadata(value, label) {
  if (!isPlainObject(value)) {
    fail(`${label}는 object여야 합니다.`);
  }
  for (const [key, expected] of Object.entries(FIXTURE_METADATA)) {
    const actual = nonnegativeInteger(value[key], `${label}.${key}`);
    if (actual !== expected) {
      fail(`${label}.${key}가 170,005 fixture fingerprint와 다릅니다: expected=${expected}, actual=${actual}`);
    }
  }
  return { ...FIXTURE_METADATA };
}

function validateFixture(artifact, label) {
  if (!isPlainObject(artifact.dataset)) {
    fail(`${label}.dataset이 object가 아닙니다.`);
  }
  const dataset = artifact.dataset;
  const fixtureId = nonEmptyString(dataset.fixtureId, `${label}.dataset.fixtureId`);
  const fixtureManifestSha256 = sha256(dataset.fixtureManifestSha256, `${label}.dataset.fixtureManifestSha256`);
  if (fixtureId !== EXPECTED_FIXTURE.fixtureId) {
    fail(`${label}.dataset.fixtureId가 승인된 fixture와 다릅니다: expected=${EXPECTED_FIXTURE.fixtureId}, actual=${fixtureId}`);
  }
  if (fixtureManifestSha256 !== EXPECTED_FIXTURE.fixtureManifestSha256) {
    fail(`${label}.dataset.fixtureManifestSha256가 승인된 fixture와 다릅니다.`);
  }
  const gameCount = nonnegativeInteger(dataset.gameCount, `${label}.dataset.gameCount`);
  const observedGameCount = nonnegativeInteger(dataset.observedGameCount, `${label}.dataset.observedGameCount`);
  if (gameCount !== FIXTURE_GAME_COUNT || observedGameCount !== FIXTURE_GAME_COUNT) {
    fail(`${label}.dataset games가 170005 fixture와 다릅니다: expected=170005, declared=${gameCount}, observed=${observedGameCount}`);
  }
  const bggIdSetSha256 = sha256(dataset.bggIdSetSha256, `${label}.dataset.bggIdSetSha256`);
  const observedBggIdSetSha256 = sha256(
    dataset.observedBggIdSetSha256,
    `${label}.dataset.observedBggIdSetSha256`,
  );
  if (dataset.bggIdSetSha256 !== EXPECTED_FIXTURE.bggIdSetSha256) {
    fail(`${label}.dataset.bggIdSetSha256가 승인된 fixture와 다릅니다.`);
  }
  if (bggIdSetSha256 !== observedBggIdSetSha256) {
    fail(`${label}.dataset BGG ID set fingerprint가 시작·종료에 다릅니다.`);
  }
  const metadata = validateMetadata(dataset.metadata, `${label}.dataset.metadata`);
  const observedMetadata = validateMetadata(dataset.observedMetadata, `${label}.dataset.observedMetadata`);
  if (!equivalent(metadata, observedMetadata)) {
    fail(`${label}.dataset metadata fingerprint가 시작·종료에 다릅니다.`);
  }
  nonEmptyString(dataset.postgresContainerId, `${label}.dataset.postgresContainerId`);
  nonEmptyString(dataset.postgresComposeProject, `${label}.dataset.postgresComposeProject`);

  if (!isPlainObject(artifact.endProvenance)) {
    fail(`${label}.endProvenance가 object가 아닙니다.`);
  }
  if (!Array.isArray(artifact.endProvenance.errors) || artifact.endProvenance.errors.length !== 0) {
    fail(`${label}.endProvenance.errors가 비어 있지 않습니다.`);
  }
  if (!isPlainObject(artifact.endProvenance.dataset)) {
    fail(`${label}.endProvenance.dataset이 object가 아닙니다.`);
  }
  const endDataset = artifact.endProvenance.dataset;
  if (nonnegativeInteger(endDataset.observedGameCount, `${label}.endProvenance.dataset.observedGameCount`) !== gameCount) {
    fail(`${label}.endProvenance.dataset games가 시작 fingerprint와 다릅니다.`);
  }
  if (sha256(endDataset.bggIdSetSha256, `${label}.endProvenance.dataset.bggIdSetSha256`) !== bggIdSetSha256) {
    fail(`${label}.endProvenance.dataset BGG ID set fingerprint가 시작 fingerprint와 다릅니다.`);
  }
  if (!equivalent(validateMetadata(endDataset.metadata, `${label}.endProvenance.dataset.metadata`), metadata)) {
    fail(`${label}.endProvenance.dataset metadata가 시작 fingerprint와 다릅니다.`);
  }
  if (endDataset.postgresContainerId !== dataset.postgresContainerId
    || endDataset.postgresComposeProject !== dataset.postgresComposeProject) {
    fail(`${label}.endProvenance.dataset PostgreSQL provenance가 시작 fingerprint와 다릅니다.`);
  }

  return {
    fixtureId,
    fixtureManifestSha256,
    gameCount,
    bggIdSetSha256,
    metadata,
  };
}

function validateServerProvenance(artifact, label) {
  const serverCommit = artifact.serverCommit;
  if (typeof serverCommit !== "string" || !/^[0-9a-f]{40}$/u.test(serverCommit)) {
    fail(`${label}.serverCommit은 40자리 commit SHA여야 합니다.`);
  }
  const endServer = artifact.endProvenance.server;
  if (!isPlainObject(endServer) || endServer.commit !== serverCommit) {
    fail(`${label} server provenance의 시작·종료 commit이 다릅니다.`);
  }
  const startContainers = artifact.serverContainers;
  const endContainers = endServer.containers;
  if (!Array.isArray(startContainers) || !Array.isArray(endContainers)
    || startContainers.length === 0 || startContainers.length !== endContainers.length) {
    fail(`${label} server provenance의 시작·종료 container 목록이 다릅니다.`);
  }
  for (const startContainer of startContainers) {
    const role = nonEmptyString(startContainer.role, `${label}.serverContainers.role`);
    const startRevision = nonEmptyString(
      startContainer.imageRevision,
      `${label}.serverContainers[${role}].imageRevision`,
    );
    if (startRevision !== serverCommit) {
      fail(`${label} server container ${role}의 imageRevision이 serverCommit과 다릅니다.`);
    }
    const endContainer = endContainers.find((candidate) => candidate?.role === role);
    if (!endContainer || endContainer.imageRevision !== startRevision) {
      fail(`${label} server container ${role}의 시작·종료 imageRevision이 다릅니다.`);
    }
  }
  return serverCommit;
}

function validateScenario(result, label) {
  if (!isPlainObject(result)) {
    fail(`${label} result가 object가 아닙니다.`);
  }
  if (result.status !== "success") {
    fail(`${label} status가 success가 아닙니다: ${result.status}`);
  }
  if (!Array.isArray(result.samples) || result.samples.length !== 20) {
    fail(`${label} samples는 정확히 20개여야 합니다: ${result.samples?.length ?? "missing"}`);
  }
  result.samples.forEach((sample, index) => {
    if (!isPlainObject(sample)) {
      fail(`${label} samples[${index}]가 object가 아닙니다.`);
    }
    if (sample.status !== 200) {
      fail(`${label} samples[${index}]는 HTTP 200이어야 합니다: ${sample.status ?? "missing"}`);
    }
    if (sample.error !== null && sample.error !== undefined) {
      fail(`${label} samples[${index}]에 오류가 있습니다: ${sample.error}`);
    }
    finiteNonnegative(sample.elapsedMs, `${label} samples[${index}].elapsedMs`);
  });
  if (!isPlainObject(result.summary)) {
    fail(`${label}.summary가 object가 아닙니다.`);
  }
  const calculated = expectedSummary(result.samples);
  for (const [field, expected] of Object.entries(calculated)) {
    const actual = finiteNonnegative(result.summary[field], `${label}.summary.${field}`);
    if (actual !== expected) {
      fail(`${label}.summary.${field}가 raw sample nearest-rank 결과와 다릅니다: expected=${expected}, actual=${actual}`);
    }
  }
  return calculated;
}

function validateArtifact(spec) {
  const label = `${spec.variant} round ${spec.round}`;
  if (!isPlainObject(spec.artifact)) {
    fail(`${label} artifact가 object가 아닙니다.`);
  }
  const artifact = spec.artifact;
  if (artifact.status !== "success") {
    fail(`${label} artifact status가 success가 아닙니다: ${artifact.status}`);
  }
  if (artifact.runnerSourceClean !== true) {
    fail(`${label} runnerSourceClean이 true가 아닙니다.`);
  }
  const runnerFileSha256 = sha256(artifact.runnerFileSha256, `${label}.runnerFileSha256`);
  const serverCommit = validateServerProvenance(artifact, label);
  if (!isPlainObject(artifact.endProvenance?.runner)) {
    fail(`${label}.endProvenance.runner가 object가 아닙니다.`);
  }
  if (artifact.endProvenance.runner.sourceClean !== true
    || artifact.endProvenance.runner.fileSha256 !== runnerFileSha256) {
    fail(`${label} runner provenance가 시작과 종료에 다릅니다.`);
  }
  if (artifact.warmUpRuns !== 5 || artifact.measuredRuns !== 20) {
    fail(`${label}은 warm-up 5회와 measured 20회를 정확히 기록해야 합니다: warmUpRuns=${artifact.warmUpRuns}, measuredRuns=${artifact.measuredRuns}`);
  }
  const fixture = validateFixture(artifact, label);
  if (!Array.isArray(artifact.results) || artifact.results.length !== REQUIRED_SCENARIOS.length) {
    fail(`${label}.results는 여섯 scenario여야 합니다.`);
  }
  const scenarios = {};
  for (const result of artifact.results) {
    const name = result?.name;
    if (!REQUIRED_SCENARIOS.includes(name)) {
      fail(`${label}에 허용하지 않는 scenario가 있습니다: ${name ?? "missing"}`);
    }
    if (Object.hasOwn(scenarios, name)) {
      fail(`${label}에 중복 scenario가 있습니다: ${name}`);
    }
    scenarios[name] = validateScenario(result, `${label} ${name}`);
  }
  for (const name of REQUIRED_SCENARIOS) {
    if (!Object.hasOwn(scenarios, name)) {
      fail(`${label}에 필수 scenario가 없습니다: ${name}`);
    }
  }
  return {
    path: spec.path,
    round: spec.round,
    serverCommit,
    runnerFileSha256,
    fixture,
    scenarios,
  };
}

function validateSpec(spec) {
  if (!isPlainObject(spec)) {
    fail("artifact spec은 object여야 합니다.");
  }
  if (!VARIANTS.includes(spec.variant)) {
    fail(`artifact variant는 V0~V3이어야 합니다: ${spec.variant ?? "missing"}`);
  }
  if (!ROUNDS.includes(spec.round)) {
    fail(`artifact round는 1~4여야 합니다: ${spec.round ?? "missing"}`);
  }
  nonEmptyString(spec.path, `${spec.variant} round ${spec.round} path`);
}

function canonicalVariantArtifacts(specs) {
  if (!Array.isArray(specs)) {
    fail("artifact spec 목록이 array가 아닙니다.");
  }
  const byVariant = Object.fromEntries(VARIANTS.map((variant) => [variant, new Map()]));
  for (const spec of specs) {
    validateSpec(spec);
    const rounds = byVariant[spec.variant];
    if (rounds.has(spec.round)) {
      fail(`${spec.variant} round ${spec.round} artifact가 중복되었습니다.`);
    }
    rounds.set(spec.round, spec);
  }
  for (const variant of VARIANTS) {
    const rounds = byVariant[variant];
    if (rounds.size !== ROUNDS.length) {
      fail(`${variant} artifact는 정확히 4개여야 합니다: actual=${rounds.size}`);
    }
    for (const round of ROUNDS) {
      if (!rounds.has(round)) {
        fail(`${variant} round ${round} artifact가 없습니다.`);
      }
    }
  }
  if (specs.length !== VARIANTS.length * ROUNDS.length) {
    fail(`artifact는 V0~V3의 4 round씩 정확히 16개여야 합니다: actual=${specs.length}`);
  }
  return Object.fromEntries(
    VARIANTS.map((variant) => [variant, ROUNDS.map((round) => byVariant[variant].get(round))]),
  );
}

function variantResult(artifacts) {
  const scenarios = Object.fromEntries(REQUIRED_SCENARIOS.map((name) => [name, {
    batches: artifacts.map((artifact) => ({
      round: artifact.round,
      path: artifact.path,
      serverCommit: artifact.serverCommit,
      p50Ms: artifact.scenarios[name].p50Ms,
      p95Ms: artifact.scenarios[name].p95Ms,
      maxMs: artifact.scenarios[name].maxMs,
    })),
  }]));
  for (const scenario of Object.values(scenarios)) {
    scenario.medianP50Ms = medianOfFour(scenario.batches.map((batch) => batch.p50Ms));
    scenario.medianP95Ms = medianOfFour(scenario.batches.map((batch) => batch.p95Ms));
    scenario.medianMaxMs = medianOfFour(scenario.batches.map((batch) => batch.maxMs));
  }
  return { scenarios };
}

function candidateGates(candidate, control) {
  const reasons = REQUIRED_SCENARIOS
    .map((scenario) => ({
      scenario,
      candidateP95Ms: candidate.scenarios[scenario].medianP95Ms,
      controlP95Ms: control.scenarios[scenario].medianP95Ms,
      thresholdP95Ms: control.scenarios[scenario].medianP95Ms * NO_REGRESSION_RATIO,
    }))
    .filter((entry) => entry.candidateP95Ms > entry.thresholdP95Ms);
  const relationImproved = candidate.scenarios["relation-theme-mechanism"].medianP95Ms
    < control.scenarios["relation-theme-mechanism"].medianP95Ms;
  const complexImproved = candidate.scenarios.complex.medianP95Ms < control.scenarios.complex.medianP95Ms;
  return {
    noRegression: reasons.length === 0,
    relationComplexImprovement: relationImproved && complexImproved,
    relationImproved,
    complexImproved,
    reasons,
  };
}

export function compareVariants(inputSpecs) {
  const specsByVariant = canonicalVariantArtifacts(inputSpecs);
  const validatedByVariant = Object.fromEntries(
    VARIANTS.map((variant) => [variant, specsByVariant[variant].map(validateArtifact)]),
  );
  const allArtifacts = VARIANTS.flatMap((variant) => validatedByVariant[variant]);
  const runnerFileSha256 = allArtifacts[0].runnerFileSha256;
  const fixture = allArtifacts[0].fixture;
  for (const artifact of allArtifacts.slice(1)) {
    if (artifact.runnerFileSha256 !== runnerFileSha256) {
      fail(`runnerFileSha256가 모든 16 artifact에서 같아야 합니다: ${artifact.path}`);
    }
    if (!equivalent(artifact.fixture, fixture)) {
      fail(`fixture fingerprint가 모든 16 artifact에서 같아야 합니다: ${artifact.path}`);
    }
  }

  const variants = Object.fromEntries(
    VARIANTS.map((variant) => [variant, variantResult(validatedByVariant[variant])]),
  );
  variants.V0.gates = {
    noRegression: true,
    relationComplexImprovement: true,
    relationImproved: true,
    complexImproved: true,
    reasons: [],
  };
  for (const variant of VARIANTS.slice(1)) {
    variants[variant].gates = candidateGates(variants[variant], variants.V0);
  }

  const candidates = VARIANTS.slice(1)
    .filter((variant) => variants[variant].gates.noRegression && variants[variant].gates.relationComplexImprovement)
    .map((variant) => ({
      variant,
      relationComplexMedianP95Sum: variants[variant].scenarios["relation-theme-mechanism"].medianP95Ms
        + variants[variant].scenarios.complex.medianP95Ms,
    }))
    .sort((left, right) => left.relationComplexMedianP95Sum - right.relationComplexMedianP95Sum
      || VARIANTS.indexOf(left.variant) - VARIANTS.indexOf(right.variant));
  const selectedVariant = candidates[0]?.variant ?? null;

  return {
    schemaVersion: 1,
    status: "success",
    fixture,
    runnerFileSha256,
    variants,
    selection: {
      noRegressionRatio: NO_REGRESSION_RATIO,
      requiredImprovementScenarios: ["relation-theme-mechanism", "complex"],
      candidates,
    },
    selectedVariant,
  };
}

function parseArtifactArgument(value) {
  const matched = /^(V[0-3]):([1-4]):(.+)$/u.exec(value);
  if (!matched) {
    fail(`--artifact는 V0:1:path/to/artifact.json 형식이어야 합니다: ${value}`);
  }
  return { variant: matched[1], round: Number(matched[2]), path: matched[3] };
}

function parseArgs(argv) {
  const options = { artifactSpecs: [], output: null, markdownOutput: null };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const next = () => {
      const value = argv[index + 1];
      if (value === undefined) {
        fail(`${argument} 값이 필요합니다.`);
      }
      index += 1;
      return value;
    };
    switch (argument) {
      case "--artifact":
        options.artifactSpecs.push(parseArtifactArgument(next()));
        break;
      case "--output":
        if (options.output) fail("--output은 한 번만 지정할 수 있습니다.");
        options.output = next();
        break;
      case "--markdown-output":
        if (options.markdownOutput) fail("--markdown-output은 한 번만 지정할 수 있습니다.");
        options.markdownOutput = next();
        break;
      case "--help":
        console.log("Usage: node scripts/measurements/game-list-variant-comparison.mjs --artifact V0:1:path.json ... --output result.json --markdown-output result.md");
        process.exit(0);
        break;
      default:
        fail(`알 수 없는 인자입니다: ${argument}`);
    }
  }
  if (!options.output || !options.markdownOutput) {
    fail("--output과 --markdown-output을 모두 지정해야 합니다.");
  }
  return options;
}

function readArtifact(spec) {
  let text;
  try {
    text = fs.readFileSync(spec.path, "utf8");
  } catch (error) {
    fail(`${spec.variant} round ${spec.round} artifact를 읽지 못했습니다: ${spec.path} (${error.message})`);
  }
  try {
    return { ...spec, artifact: JSON.parse(text) };
  } catch (error) {
    fail(`${spec.variant} round ${spec.round} artifact JSON이 올바르지 않습니다: ${spec.path} (${error.message})`);
  }
}

function formatMs(value) {
  return `${value.toFixed(3)}ms`;
}

export function comparisonMarkdown(result) {
  const lines = [
    "# 게임 목록 relation·complex 후보 비교 결과",
    "",
    `- 상태: ${result.status}`,
    `- fixture: ${result.fixture.fixtureId} (${result.fixture.gameCount.toLocaleString("en-US")} games)`,
    `- fixture manifest SHA-256: \`${result.fixture.fixtureManifestSha256}\``,
    `- runner file SHA-256: \`${result.runnerFileSha256}\``,
    `- 선정 후보: ${result.selectedVariant ?? "없음 — V0 유지"}`,
    "",
    "## Scenario median (네 batch 가운데 두 p95의 산술평균)",
    "",
    "| scenario | V0 p50 / p95 / max | V1 p50 / p95 / max | V2 p50 / p95 / max | V3 p50 / p95 / max |",
    "| --- | --- | --- | --- | --- |",
  ];
  for (const scenario of REQUIRED_SCENARIOS) {
    const values = VARIANTS.map((variant) => {
      const summary = result.variants[variant].scenarios[scenario];
      return `${formatMs(summary.medianP50Ms)} / ${formatMs(summary.medianP95Ms)} / ${formatMs(summary.medianMaxMs)}`;
    });
    lines.push(`| ${scenario} | ${values.join(" | ")} |`);
  }
  lines.push("", "## Gate", "", "| variant | six-scenario 5% no-regression | relation improved | complex improved | candidate |", "| --- | --- | --- | --- | --- |");
  for (const variant of VARIANTS.slice(1)) {
    const gates = result.variants[variant].gates;
    lines.push(`| ${variant} | ${gates.noRegression ? "PASS" : "FAIL"} | ${gates.relationImproved ? "PASS" : "FAIL"} | ${gates.complexImproved ? "PASS" : "FAIL"} | ${result.selection.candidates.some((candidate) => candidate.variant === variant) ? "PASS" : "FAIL"} |`);
    for (const reason of gates.reasons) {
      lines.push(`- ${variant} ${reason.scenario}: ${formatMs(reason.candidateP95Ms)} > ${formatMs(reason.thresholdP95Ms)} (V0 ${formatMs(reason.controlP95Ms)} × 1.05)`);
    }
  }
  lines.push("", "## Raw artifacts", "", "| variant | round | artifact | server commit |", "| --- | ---: | --- | --- |");
  for (const variant of VARIANTS) {
    const representative = result.variants[variant].scenarios.base.batches;
    for (const batch of representative) {
      lines.push(`| ${variant} | ${batch.round} | \`${batch.path}\` | \`${batch.serverCommit ?? "missing"}\` |`);
    }
  }
  return `${lines.join("\n")}\n`;
}

function writeText(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, "utf8");
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const result = compareVariants(options.artifactSpecs.map(readArtifact));
  writeText(options.output, `${JSON.stringify(result, null, 2)}\n`);
  writeText(options.markdownOutput, comparisonMarkdown(result));
  console.log(`selectedVariant=${result.selectedVariant ?? "null"}`);
}

const entryPoint = process.argv[1] ? path.resolve(process.argv[1]) : null;
if (entryPoint === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(`game-list variant comparison failed: ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 1;
  }
}
