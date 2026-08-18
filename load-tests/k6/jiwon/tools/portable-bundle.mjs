import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import {
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';

import {
  buildCleanupSql,
  buildPrepareSql,
  buildResourceQuery,
  buildSnapshotQuery,
  createFixturePlan,
  evaluateFixture,
  hydrateFixture,
  normalizeRoomSummary,
  normalizePrepareOwnership,
  RUN_ID_PATTERN,
} from './fixture-model.mjs';
import { readExecutionOptions } from '../lib/read-execution-options.mjs';

const BUNDLE_KIND = 'albam-mate-room-k6-bundle';
const BUNDLE_SCHEMA_VERSION = 2;
const SOURCE_SHA_PATTERN = /^[a-f0-9]{40}$/;
const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const FIXTURE_ID_PATTERN = /^room-k6-t[1-5]-[a-f0-9]{12}$/;
const IDENTIFIER_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

const SCENARIO_ENTRIES = Object.freeze({
  t1: 't1-cancel-promotion.js',
  t2: 't2-concurrent-waitlist-registration.js',
  t3: 't3-waitlist-cancel-race.js',
  t4: 't4-last-seat-participation.js',
  t5: 't5-room-detail-by-role.js',
});

const RUNTIME_FILES = Object.freeze([
  'lib/room-k6.js',
  'lib/read-execution-options.mjs',
  'lib/write-options.mjs',
  'lib/t3-execution-plan.mjs',
  'lib/start-skew.mjs',
  'lib/write-response-contract.mjs',
  'tools/fixture.mjs',
  'tools/fixture-model.mjs',
  'tools/portable-bundle.mjs',
]);

const ARTIFACTS = Object.freeze({
  fixturePlan: 'fixture-plan.json',
  prepareProvenance: 'private/prepare-provenance.json',
  prepareSql: 'prepare.sql',
  resourceQuerySql: 'resource-query.sql',
  executionOptions: 'execution-options.json',
  resourceOutput: 'resource-output.json',
  fixture: 'fixture.json',
  snapshotSql: 'snapshot.sql',
  cleanupSql: 'cleanup.sql',
  beforeSnapshot: 'before-snapshot.json',
  afterSnapshot: 'after-snapshot.json',
  summary: 'k6-summary.json',
  console: 'k6-console.log',
  runManifest: 'run-manifest.json',
  t3AppLogApp1: 't3-app-log-app1.log',
  t3AppLogApp2: 't3-app-log-app2.log',
  t3MetricsApp1: 't3-metrics-app1.csv',
  t3MetricsApp2: 't3-metrics-app2.csv',
  t3MetricsPostgres: 't3-metrics-postgres.csv',
  t3Signals: 't3-signals.json',
  infraExecution: 'infra-execution.json',
  beforeDiagnosis: 'before-diagnosis.json',
  afterDiagnosis: 'after-diagnosis.json',
  finalResult: 'final-result.json',
});

const PREFLIGHT_ARTIFACTS = Object.freeze([
  ARTIFACTS.fixturePlan,
  ARTIFACTS.prepareProvenance,
  ARTIFACTS.prepareSql,
  ARTIFACTS.resourceQuerySql,
  ARTIFACTS.executionOptions,
]);

const EXECUTION_STATE_ARTIFACTS = Object.freeze([
  ARTIFACTS.resourceOutput,
  ARTIFACTS.fixture,
  ARTIFACTS.snapshotSql,
  ARTIFACTS.cleanupSql,
  ARTIFACTS.beforeSnapshot,
  ARTIFACTS.afterSnapshot,
  ARTIFACTS.summary,
  ARTIFACTS.console,
  ARTIFACTS.runManifest,
  ARTIFACTS.t3AppLogApp1,
  ARTIFACTS.t3AppLogApp2,
  ARTIFACTS.t3MetricsApp1,
  ARTIFACTS.t3MetricsApp2,
  ARTIFACTS.t3MetricsPostgres,
  ARTIFACTS.t3Signals,
  ARTIFACTS.infraExecution,
  ARTIFACTS.beforeDiagnosis,
  ARTIFACTS.afterDiagnosis,
  ARTIFACTS.finalResult,
]);

function fail(message) {
  throw new Error(message);
}

function text(value, name) {
  const result = String(value ?? '').trim();
  if (!result) {
    fail(`${name} 값이 필요합니다.`);
  }
  return result;
}

function lstatIfExists(filePath) {
  try {
    return lstatSync(filePath);
  } catch (error) {
    if (error?.code === 'ENOENT') {
      return null;
    }
    throw error;
  }
}

function assertNotLink(filePath, label) {
  const stat = lstatIfExists(filePath);
  if (stat?.isSymbolicLink()) {
    fail(`${label}에 symbolic link를 사용할 수 없습니다: ${filePath}`);
  }
  return stat;
}

function assertRegularFile(filePath, label) {
  const stat = assertNotLink(filePath, label);
  if (!stat?.isFile()) {
    fail(`${label}은 일반 파일이어야 합니다: ${filePath}`);
  }
}

function assertNoLinksInExistingPath(filePath, label) {
  const resolved = path.resolve(filePath);
  const parsed = path.parse(resolved);
  let current = parsed.root;
  for (const segment of resolved.slice(parsed.root.length).split(path.sep)) {
    if (!segment) {
      continue;
    }
    current = path.join(current, segment);
    const stat = lstatIfExists(current);
    if (!stat) {
      return;
    }
    if (stat.isSymbolicLink()) {
      fail(`${label}에 symbolic link를 사용할 수 없습니다: ${current}`);
    }
  }
}

function assertRegularTree(directoryPath) {
  const entries = readdirSync(directoryPath, { withFileTypes: true });
  for (const entry of entries) {
    const entryPath = path.join(directoryPath, entry.name);
    const stat = assertNotLink(entryPath, 'ROOM bundle 항목');
    if (!stat) {
      fail(`ROOM bundle 항목을 찾지 못했습니다: ${entryPath}`);
    }
    if (stat.isDirectory()) {
      assertRegularTree(entryPath);
    } else if (!stat.isFile()) {
      fail(`ROOM bundle 항목은 일반 파일이어야 합니다: ${entryPath}`);
    }
  }
}

function readJson(filePath, label) {
  assertRegularFile(filePath, label);
  try {
    return JSON.parse(readFileSync(filePath, 'utf8'));
  } catch (_) {
    fail(`${label} JSON을 읽을 수 없습니다: ${filePath}`);
  }
}

function writeNewJson(filePath, value) {
  assertNotLink(filePath, 'JSON 출력 파일');
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' });
}

function writeNewText(filePath, value) {
  assertNotLink(filePath, '텍스트 출력 파일');
  writeFileSync(filePath, value, { encoding: 'utf8', flag: 'wx' });
}

function writeJson(filePath, value) {
  assertNotLink(filePath, 'JSON 출력 파일');
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function digestFile(filePath, label = 'ROOM bundle artifact') {
  assertRegularFile(filePath, label);
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function sameJson(left, right) {
  return isDeepStrictEqual(left, right);
}

function pathInside(root, candidate, label) {
  const resolvedRoot = path.resolve(root);
  const resolvedCandidate = path.resolve(candidate);
  const relative = path.relative(resolvedRoot, resolvedCandidate);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    fail(`${label}은 ${resolvedRoot} 아래여야 합니다.`);
  }
  return resolvedCandidate;
}

function artifactPath(bundle, relativePath, label = 'ROOM bundle artifact') {
  const result = pathInside(bundle.directory, path.join(bundle.directory, relativePath), label);
  assertNoLinksInExistingPath(result, label);
  return result;
}

function sourcePath(context, relativePath) {
  return path.join(context.scenarioDirectory, relativePath);
}

function checkedSourcePath(context, relativePath) {
  const filePath = sourcePath(context, relativePath);
  const label = `ROOM bundle source ${relativePath}`;
  assertNoLinksInExistingPath(filePath, label);
  assertRegularFile(filePath, label);
  return filePath;
}

function expectedArtifactMap() {
  return {
    ...ARTIFACTS,
    scenario: 'scenario.js',
    runtimeFiles: [...RUNTIME_FILES],
  };
}

function sourceProvenance(context, override) {
  if (override) {
    return override;
  }
  const requestedRevision = text(context.environment?.ALBAM_MATE_SOURCE_SHA, 'ALBAM_MATE_SOURCE_SHA').toLowerCase();
  if (!SOURCE_SHA_PATTERN.test(requestedRevision)) {
    fail('ALBAM_MATE_SOURCE_SHA는 대상 배포본의 40자리 소문자 Git SHA여야 합니다.');
  }
  const revisionResult = spawnSync('git', ['rev-parse', 'HEAD'], {
    cwd: context.repositoryRoot,
    encoding: 'utf8',
  });
  const statusResult = spawnSync('git', ['status', '--porcelain', '--untracked-files=all'], {
    cwd: context.repositoryRoot,
    encoding: 'utf8',
  });
  const revision = String(revisionResult.stdout || '').trim().toLowerCase();
  if (revisionResult.status !== 0 || !SOURCE_SHA_PATTERN.test(revision)) {
    fail('ROOM bundle sourceRevision을 확인하지 못했습니다. Git checkout에서 생성하세요.');
  }
  if (statusResult.status !== 0 || String(statusResult.stdout || '').trim()) {
    fail('변경된 앱 소스에서는 원격 ROOM bundle을 생성할 수 없습니다. 커밋된 release SHA에서 다시 생성하세요.');
  }
  if (revision !== requestedRevision) {
    fail('ALBAM_MATE_SOURCE_SHA와 현재 앱 checkout HEAD가 일치하지 않습니다. 배포 대상 release에서 다시 생성하세요.');
  }
  return { sourceRevision: revision, sourceDirty: false };
}

function executionOptions(plan, environment) {
  const integer = (name, fallback, minimum, maximum) => {
    const raw = String(environment?.[name] ?? '').trim();
    const value = raw ? Number(raw) : fallback;
    if (!Number.isInteger(value) || value < minimum || value > maximum) {
      fail(`${name}은(는) ${minimum} 이상 ${maximum} 이하의 정수여야 합니다.`);
    }
    return String(value);
  };

  const k6Environment = {
    ROOM_K6_SESSION_WARMUP_SECONDS: integer('ROOM_K6_SESSION_WARMUP_SECONDS', 15, 5, 120),
    ROOM_K6_ROUND_INTERVAL_SECONDS: integer('ROOM_K6_ROUND_INTERVAL_SECONDS', 20, 5, 300),
  };
  const t5ReadOptions = plan.options.scenario === 't5'
    ? readExecutionOptions(environment || {})
    : null;
  if (t5ReadOptions) {
    k6Environment.ROOM_K6_READ_VUS = String(t5ReadOptions.vus);
    k6Environment.ROOM_K6_READ_DURATION_SECONDS = String(t5ReadOptions.durationSeconds);
    k6Environment.ROOM_K6_READ_THINK_TIME_MS = String(t5ReadOptions.thinkTimeMilliseconds);
  }
  return { schemaVersion: 1, k6Environment, t5ReadOptions };
}

function assertExecutionOptions(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || value.schemaVersion !== 1 || !value.k6Environment || typeof value.k6Environment !== 'object'
    || Array.isArray(value.k6Environment)) {
    fail('execution-options.json 형식이 올바르지 않습니다. 새 bundle을 생성하세요.');
  }
  for (const [name, item] of Object.entries(value.k6Environment)) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name) || typeof item !== 'string') {
      fail('execution-options.json의 k6Environment가 안전한 string mapping이 아닙니다.');
    }
  }
  if (value.t5ReadOptions !== null) {
    const options = value.t5ReadOptions;
    if (!options || !Number.isInteger(options.vus) || !Number.isInteger(options.durationSeconds)
      || !Number.isInteger(options.thinkTimeMilliseconds)) {
      fail('execution-options.json의 t5ReadOptions 형식이 올바르지 않습니다.');
    }
  }
}

function prepareProvenance(plan, passwordHash, ownership) {
  if (!String(passwordHash).startsWith('{bcrypt}$')) {
    fail('ROOM_K6_FIXTURE_PASSWORD_HASH는 {bcrypt}$로 시작해야 합니다.');
  }
  return {
    schemaVersion: 1,
    fixtureId: plan.fixtureId,
    options: plan.options,
    prepareOwnership: normalizePrepareOwnership(ownership),
    passwordHash,
  };
}

function canonicalPlan(plan) {
  if (!plan || typeof plan !== 'object' || Array.isArray(plan) || !plan.options) {
    fail('fixture-plan.json 형식이 올바르지 않습니다.');
  }
  const { fixtureId, ...input } = plan.options;
  const expected = createFixturePlan(input);
  if (fixtureId !== expected.fixtureId || !sameJson(plan, expected)) {
    fail('fixture-plan.json이 현재 앱의 결정적 fixture 계획과 일치하지 않습니다.');
  }
  return expected;
}

function assertBundleIdentity(directory, manifest) {
  const runId = manifest?.options?.runId;
  const fixtureId = manifest?.fixtureId;
  if (!RUN_ID_PATTERN.test(runId || '') || !FIXTURE_ID_PATTERN.test(fixtureId || '')
    || !IDENTIFIER_PATTERN.test(runId) || !IDENTIFIER_PATTERN.test(fixtureId)) {
    fail('bundle manifest의 runId 또는 fixtureId 형식이 안전하지 않습니다.');
  }
  const expected = path.join(path.dirname(path.dirname(directory)), runId, fixtureId);
  if (path.resolve(directory) !== path.resolve(expected)) {
    fail('bundle 경로와 manifest runId·fixtureId가 일치하지 않습니다.');
  }
}

function readBundle(context, rawBundlePath) {
  const requested = text(rawBundlePath, '--bundle');
  const directory = context.isBundleRuntime
    ? path.resolve(context.bundleRoot)
    : pathInside(context.buildRoot, requested, 'ROOM bundle');
  if (context.isBundleRuntime && path.resolve(requested) !== directory) {
    fail('실행 bundle 도구는 자신의 bundle 디렉터리만 처리할 수 있습니다.');
  }
  assertNoLinksInExistingPath(directory, 'ROOM bundle 경로');
  const stat = assertNotLink(directory, 'ROOM bundle 디렉터리');
  if (!stat?.isDirectory()) {
    fail(`ROOM bundle 디렉터리를 찾지 못했습니다: ${directory}`);
  }
  if (path.resolve(path.dirname(path.dirname(directory))) !== path.resolve(context.buildRoot)) {
    fail(`ROOM bundle은 ${context.buildRoot}/<run-id>/<fixture-id> 아래여야 합니다.`);
  }
  assertRegularTree(directory);
  const manifest = readJson(path.join(directory, 'manifest.json'), 'bundle manifest');
  if (manifest?.kind !== BUNDLE_KIND || manifest.schemaVersion !== BUNDLE_SCHEMA_VERSION
    || manifest.fixtureSchemaVersion !== 2 || !sameJson(manifest.artifacts, expectedArtifactMap())
    || !SOURCE_SHA_PATTERN.test(manifest.sourceRevision || '') || manifest.sourceDirty !== false) {
    fail('ROOM bundle manifest 계약이 올바르지 않습니다. 새 bundle을 생성하세요.');
  }
  assertBundleIdentity(directory, manifest);
  const bundle = { directory, manifest };
  verifyImmutableArtifacts(bundle);
  const plan = canonicalPlan(readJson(artifactPath(bundle, ARTIFACTS.fixturePlan), 'fixture plan'));
  if (manifest.fixtureId !== plan.fixtureId || !sameJson(manifest.options, plan.options)) {
    fail('bundle manifest와 fixture plan이 일치하지 않습니다.');
  }
  const provenance = readJson(artifactPath(bundle, ARTIFACTS.prepareProvenance), 'prepare provenance');
  const ownership = normalizePrepareOwnership(provenance?.prepareOwnership);
  if (!sameJson(provenance, prepareProvenance(plan, provenance?.passwordHash, ownership))) {
    fail('prepare provenance가 fixture plan과 일치하지 않습니다.');
  }
  const prepareSql = readFileSync(artifactPath(bundle, ARTIFACTS.prepareSql), 'utf8');
  if (prepareSql !== buildPrepareSql(plan, provenance.passwordHash, ownership)) {
    fail('prepare.sql이 fixture plan·prepare ownership과 일치하지 않습니다.');
  }
  const resourceQuerySql = readFileSync(artifactPath(bundle, ARTIFACTS.resourceQuerySql), 'utf8');
  if (resourceQuerySql !== buildResourceQuery(plan, ownership)) {
    fail('resource-query.sql이 fixture plan·prepare ownership과 일치하지 않습니다.');
  }
  assertExecutionOptions(readJson(artifactPath(bundle, ARTIFACTS.executionOptions), 'execution options'));
  return { bundle, plan, ownership };
}

function immutablePaths(bundle) {
  return [
    'scenario.js',
    ...RUNTIME_FILES,
    ...PREFLIGHT_ARTIFACTS,
  ];
}

function verifyImmutableArtifacts(bundle) {
  const hashes = bundle.manifest.immutableSha256;
  const paths = immutablePaths(bundle);
  if (!hashes || typeof hashes !== 'object' || Array.isArray(hashes)
    || !sameJson(Object.keys(hashes).sort(), [...paths].sort())) {
    fail('bundle immutable hash 계약이 없습니다. 새 bundle을 생성하세요.');
  }
  for (const relativePath of paths) {
    const expected = hashes[relativePath];
    if (!SHA256_PATTERN.test(expected || '')) {
      fail('bundle immutable hash 형식이 올바르지 않습니다.');
    }
    if (digestFile(artifactPath(bundle, relativePath)) !== expected) {
      fail(`bundle immutable artifact가 변조되었습니다: ${relativePath}`);
    }
  }
}

function assertPristineExecutionState(bundle) {
  const existing = EXECUTION_STATE_ARTIFACTS.filter((relativePath) => existsSync(artifactPath(bundle, relativePath)));
  if (existing.length > 0) {
    fail(`실행 전 bundle에 실행 상태 artifact가 이미 있습니다: ${existing.join(', ')}`);
  }
}

function readHydratedFixture(bundle, plan, ownership) {
  const fixture = readJson(artifactPath(bundle, ARTIFACTS.fixture), 'fixture');
  const resources = readJson(artifactPath(bundle, ARTIFACTS.resourceOutput), 'resource output');
  const expected = hydrateFixture(plan, resources, ownership);
  const fixtureCore = { ...fixture };
  delete fixtureCore.baselineSnapshot;
  if (!sameJson(fixtureCore, expected)) {
    fail('fixture.json이 fixture plan·resource output·prepare ownership과 일치하지 않습니다.');
  }
  return fixture;
}

function isSnapshot(value) {
  return value && typeof value === 'object' && !Array.isArray(value)
    && Array.isArray(value.rooms) && Array.isArray(value.participations) && Array.isArray(value.waitlists);
}

function readSnapshot(bundle, relativePath) {
  const snapshot = readJson(artifactPath(bundle, relativePath), relativePath);
  if (!isSnapshot(snapshot)) {
    fail(`${relativePath}은 rooms, participations, waitlists 배열을 포함해야 합니다.`);
  }
  return snapshot;
}

function isUtcTimestamp(value) {
  return typeof value === 'string' && value.endsWith('Z') && !Number.isNaN(Date.parse(value));
}

function isNonNegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function isNonNegativeNumber(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0;
}

function readMeasuredSummary(bundle) {
  const summary = readJson(artifactPath(bundle, ARTIFACTS.summary), 'k6 summary');
  if (!summary || typeof summary !== 'object' || Array.isArray(summary)
    || !summary.metrics || typeof summary.metrics !== 'object' || Array.isArray(summary.metrics)) {
    fail('k6-summary.json에 metrics object가 필요합니다.');
  }

  for (const category of ['success', 'business', 'concurrency', 'unexpected']) {
    const metricName = `room_request_duration{outcome:${category}}`;
    if (!Object.hasOwn(summary.metrics, metricName)) {
      fail(`k6-summary.json에 ${metricName}이 없습니다.`);
    }
  }

  const normalized = normalizeRoomSummary(summary);
  for (const category of ['success', 'business', 'concurrency', 'unexpected']) {
    const metricName = `room_request_duration{outcome:${category}}`;
    const values = normalized.metrics[metricName]?.values;
    if (!isNonNegativeInteger(values?.count)) {
      fail(`k6-summary.json의 ${metricName} count가 유효하지 않습니다.`);
    }
    const statisticNames = ['p50', 'p95', 'p99', 'max'];
    if (values.count === 0) {
      if (!statisticNames.every((name) => values[name] === null)) {
        fail(`k6-summary.json의 ${metricName} 무표본 통계는 null이어야 합니다.`);
      }
    } else if (!statisticNames.every((name) => Number.isFinite(values[name]))) {
      fail(`k6-summary.json의 ${metricName} p50·p95·p99·max가 부족합니다.`);
    }
  }
  return normalized;
}

function readRunManifest(bundle) {
  const manifest = readJson(artifactPath(bundle, ARTIFACTS.runManifest), 'run manifest');
  const expectedOptions = bundle.manifest.options;
  const valid = manifest && typeof manifest === 'object' && !Array.isArray(manifest)
    && manifest.schemaVersion === 2
    && manifest.fixtureId === bundle.manifest.fixtureId
    && manifest.runId === expectedOptions.runId
    && manifest.scenario === expectedOptions.scenario
    && sameJson(manifest.condition, expectedOptions)
    && manifest.sourceSha === bundle.manifest.sourceRevision
    && typeof manifest.targetEnvironment === 'string'
    && IDENTIFIER_PATTERN.test(manifest.targetEnvironment)
    && typeof manifest.k6Version === 'string' && manifest.k6Version.trim().length > 0
    && isUtcTimestamp(manifest.startedAtUtc)
    && isUtcTimestamp(manifest.finishedAtUtc)
    && Date.parse(manifest.finishedAtUtc) >= Date.parse(manifest.startedAtUtc)
    && manifest.runState === 'COMPLETED'
    && manifest.completed === true
    && Number.isInteger(manifest.k6ExitCode)
    && SHA256_PATTERN.test(manifest.fixtureSha256 || '')
    && manifest.summaryFile === ARTIFACTS.summary
    && SHA256_PATTERN.test(manifest.summarySha256 || '');
  if (!valid) {
    fail('run-manifest.json이 현재 bundle의 완료된 실행 provenance 계약과 맞지 않습니다.');
  }

  const fixturePath = artifactPath(bundle, ARTIFACTS.fixture);
  const summaryPath = artifactPath(bundle, ARTIFACTS.summary);
  if (digestFile(fixturePath, 'fixture') !== manifest.fixtureSha256) {
    fail('fixture.json의 SHA-256이 run-manifest.json과 다릅니다.');
  }
  if (digestFile(summaryPath, 'k6 summary') !== manifest.summarySha256) {
    fail('k6-summary.json의 SHA-256이 run-manifest.json과 다릅니다.');
  }
  return manifest;
}

function readT3SignalDistribution(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || !value.attempts || typeof value.attempts !== 'object' || Array.isArray(value.attempts)
    || !isNonNegativeInteger(value.retries) || !isNonNegativeInteger(value.exhausted)) {
    fail(`t3-signals.json의 ${label} retry distribution이 올바르지 않습니다.`);
  }
  for (const [attempt, count] of Object.entries(value.attempts)) {
    if (!/^\d+$/.test(attempt) || !isNonNegativeInteger(count)) {
      fail(`t3-signals.json의 ${label} attempt distribution이 올바르지 않습니다.`);
    }
  }
}

function readT3Signals(bundle, runManifest, summary) {
  const signals = readJson(artifactPath(bundle, ARTIFACTS.t3Signals), 'T3 signals');
  const expectedCoverage = Object.fromEntries(
    ['success', 'business', 'concurrency', 'unexpected'].map((category) => {
      const values = summary.metrics[`room_request_duration{outcome:${category}}`].values;
      return [category, {
        p50: values.p50,
        p95: values.p95,
        p99: values.p99,
        max: values.max,
        count: values.count,
      }];
    }),
  );
  const validIdentity = signals && typeof signals === 'object' && !Array.isArray(signals)
    && signals.schemaVersion === 1
    && signals.status === 'PASS'
    && signals.runId === bundle.manifest.options.runId
    && signals.fixtureId === bundle.manifest.fixtureId
    && signals.scenario === 't3'
    && sameJson(signals.condition, bundle.manifest.options)
    && sameJson(signals.window, {
      startedAtUtc: runManifest.startedAtUtc,
      finishedAtUtc: runManifest.finishedAtUtc,
    })
    && sameJson(signals.outcomeCoverage, expectedCoverage);
  if (!validIdentity) {
    fail('t3-signals.json이 현재 T3 run·condition·outcome window와 맞지 않습니다.');
  }

  readT3SignalDistribution(signals.retry?.commonRetrier, 'commonRetrier');
  readT3SignalDistribution(signals.retry?.coordinator, 'coordinator');
  const database = signals.database;
  if (!database || !isNonNegativeNumber(database.queryCalls)
    || !isNonNegativeNumber(database.queryTimeMs)
    || !isNonNegativeNumber(database.transactionCount)
    || !isNonNegativeNumber(database.transactionDurationMs)
    || !isNonNegativeNumber(database.lockWaitCount)) {
    fail('t3-signals.json의 DB query·transaction·lock signal이 부족합니다.');
  }
  const connection = signals.connection;
  if (!connection || !isNonNegativeNumber(connection.hikariPendingMax)
    || !isNonNegativeInteger(connection.hikariPendingSamples)
    || !isNonNegativeNumber(connection.hikariActiveMax)
    || !isNonNegativeInteger(connection.hikariActiveSamples)) {
    fail('t3-signals.json의 Hikari connection signal이 부족합니다.');
  }

  const expectedSources = {
    appLogApp1: ARTIFACTS.t3AppLogApp1,
    appLogApp2: ARTIFACTS.t3AppLogApp2,
    metricsApp1: ARTIFACTS.t3MetricsApp1,
    metricsApp2: ARTIFACTS.t3MetricsApp2,
    metricsPostgres: ARTIFACTS.t3MetricsPostgres,
  };
  if (!signals.sources || typeof signals.sources !== 'object' || Array.isArray(signals.sources)) {
    fail('t3-signals.json의 source provenance가 없습니다.');
  }
  for (const [name, relativePath] of Object.entries(expectedSources)) {
    const source = signals.sources[name];
    if (!source || source.path !== relativePath || !SHA256_PATTERN.test(source.sha256 || '')
      || !isNonNegativeInteger(source.sampleCount)) {
      fail(`t3-signals.json의 ${name} source provenance가 올바르지 않습니다.`);
    }
    if (digestFile(artifactPath(bundle, relativePath), name) !== source.sha256) {
      fail(`T3 source artifact의 SHA-256이 t3-signals.json과 다릅니다: ${relativePath}`);
    }
  }
  return signals;
}

function readInfraExecution(bundle) {
  const execution = readJson(artifactPath(bundle, ARTIFACTS.infraExecution), 'infra execution');
  const phaseNames = ['prepare', 'resourceQuery', 'beforeSnapshot', 'k6', 'afterSnapshot'];
  if (!execution || typeof execution !== 'object' || Array.isArray(execution)
    || execution.schemaVersion !== 1 || execution.runId !== bundle.manifest.options.runId
    || execution.fixtureId !== bundle.manifest.fixtureId || !execution.phases || typeof execution.phases !== 'object'
    || !phaseNames.every((name) => Object.hasOwn(execution.phases, name)
      && Object.hasOwn(execution.phases[name], 'exitCode')
      && (execution.phases[name].exitCode === null || Number.isInteger(execution.phases[name].exitCode)))) {
    fail('infra-execution.json이 이 bundle의 원시 실행 metadata 계약과 일치하지 않습니다.');
  }
  return execution;
}

function readDiagnosis(bundle, relativePath, stage) {
  const diagnosis = readJson(artifactPath(bundle, relativePath), `${stage} diagnosis`);
  const status = diagnosis?.status;
  const failures = diagnosis?.failures;
  const hasValidIdentity = diagnosis?.fixtureId === bundle.manifest.fixtureId
    && diagnosis.scenario === bundle.manifest.options.scenario
    && diagnosis.stage === stage;
  const hasValidStatus = ['PASS', 'FAIL', 'INVALID'].includes(status);
  const hasValidFailures = Array.isArray(failures)
    && (status === 'PASS' ? failures.length === 0 : failures.length > 0);
  const hasRequiredBaseline = stage !== 'before'
    || bundle.manifest.options.scenario !== 't5'
    || isSnapshot(diagnosis?.baselineSnapshot);
  if (!hasValidIdentity || !hasValidStatus || !hasValidFailures || !hasRequiredBaseline) {
    fail(`${relativePath}이 현재 bundle의 ${stage} 진단 계약과 맞지 않습니다.`);
  }
  return diagnosis;
}

export function renderBundle(values, context, provenanceOverride = undefined) {
  if (context.isBundleRuntime) {
    fail('실행 bundle 안에서는 render-bundle을 실행할 수 없습니다. 앱 source checkout에서 생성하세요.');
  }
  const plan = createFixturePlan(values);
  const outputDirectory = pathInside(context.buildRoot, path.join(context.buildRoot, plan.options.runId, plan.fixtureId), 'ROOM bundle');
  if (existsSync(outputDirectory)) {
    fail(`같은 run ID·scenario bundle이 이미 있습니다: ${outputDirectory}. 기존 bundle을 교체하지 말고 새 run ID를 사용하세요.`);
  }
  assertNoLinksInExistingPath(context.buildRoot, 'ROOM build root');
  const provenance = sourceProvenance(context, provenanceOverride);
  if (!SOURCE_SHA_PATTERN.test(provenance?.sourceRevision || '') || provenance.sourceDirty !== false) {
    fail('원격 ROOM bundle에는 clean sourceRevision이 필요합니다.');
  }
  const passwordHash = text(context.environment?.ROOM_K6_FIXTURE_PASSWORD_HASH, 'ROOM_K6_FIXTURE_PASSWORD_HASH');
  const entry = SCENARIO_ENTRIES[plan.options.scenario];
  if (!entry) {
    fail(`지원하지 않는 scenario: ${plan.options.scenario}`);
  }
  const scenarioSource = checkedSourcePath(context, entry);
  const runtimeSources = RUNTIME_FILES.map((relativePath) => ({
    relativePath,
    filePath: checkedSourcePath(context, relativePath),
  }));
  const ownership = randomUUID().replaceAll('-', '');
  const prepared = prepareProvenance(plan, passwordHash, ownership);
  const bundle = {
    directory: outputDirectory,
    manifest: {
      schemaVersion: BUNDLE_SCHEMA_VERSION,
      kind: BUNDLE_KIND,
      fixtureSchemaVersion: plan.schemaVersion,
      fixtureId: plan.fixtureId,
      options: plan.options,
      sourceRevision: provenance.sourceRevision,
      sourceDirty: false,
      artifacts: expectedArtifactMap(),
    },
  };

  mkdirSync(path.dirname(outputDirectory), { recursive: true });
  mkdirSync(outputDirectory);
  mkdirSync(path.dirname(artifactPath(bundle, ARTIFACTS.prepareProvenance)), { recursive: true });
  mkdirSync(path.dirname(artifactPath(bundle, 'lib/room-k6.js')), { recursive: true });
  mkdirSync(path.dirname(artifactPath(bundle, 'tools/fixture.mjs')), { recursive: true });

  writeNewJson(artifactPath(bundle, ARTIFACTS.fixturePlan), plan);
  writeNewJson(artifactPath(bundle, ARTIFACTS.prepareProvenance), prepared);
  writeNewText(artifactPath(bundle, ARTIFACTS.prepareSql), buildPrepareSql(plan, passwordHash, ownership));
  writeNewText(artifactPath(bundle, ARTIFACTS.resourceQuerySql), buildResourceQuery(plan, ownership));
  writeNewJson(artifactPath(bundle, ARTIFACTS.executionOptions), executionOptions(plan, context.environment));

  copyFileSync(scenarioSource, artifactPath(bundle, 'scenario.js'));
  for (const { relativePath, filePath } of runtimeSources) {
    copyFileSync(filePath, artifactPath(bundle, relativePath));
  }
  bundle.manifest.immutableSha256 = Object.fromEntries(
    immutablePaths(bundle).map((relativePath) => [relativePath, digestFile(artifactPath(bundle, relativePath))]),
  );
  writeNewJson(path.join(outputDirectory, 'manifest.json'), bundle.manifest);
  return { bundlePath: outputDirectory, fixtureId: plan.fixtureId, scenario: plan.options.scenario, options: plan.options };
}

export function validateBundle(rawBundlePath, context, { forExecution = false } = {}) {
  const { bundle } = readBundle(context, rawBundlePath);
  if (forExecution) {
    assertPristineExecutionState(bundle);
  }
  return { bundlePath: bundle.directory, runId: bundle.manifest.options.runId, fixtureId: bundle.manifest.fixtureId };
}

export function bundleExecutionOptions(rawBundlePath, context) {
  const { bundle } = readBundle(context, rawBundlePath);
  return readJson(artifactPath(bundle, ARTIFACTS.executionOptions), 'execution options');
}

export function hydrateBundle(rawBundlePath, context) {
  const { bundle, plan, ownership } = readBundle(context, rawBundlePath);
  const generated = [ARTIFACTS.fixture, ARTIFACTS.snapshotSql, ARTIFACTS.cleanupSql];
  if (generated.some((relativePath) => existsSync(artifactPath(bundle, relativePath)))) {
    fail('이미 hydrate된 bundle입니다. 새 bundle로 실행하세요.');
  }
  const resources = readJson(artifactPath(bundle, ARTIFACTS.resourceOutput), 'resource output');
  const fixture = hydrateFixture(plan, resources, ownership);
  writeNewJson(artifactPath(bundle, ARTIFACTS.fixture), fixture);
  writeNewText(artifactPath(bundle, ARTIFACTS.snapshotSql), buildSnapshotQuery(fixture));
  // 정상 실행에는 전달하지 않는다. 앱 소유 recovery 의미만 bundle에 보존한다.
  writeNewText(artifactPath(bundle, ARTIFACTS.cleanupSql), buildCleanupSql(fixture));
  return { fixturePath: artifactPath(bundle, ARTIFACTS.fixture), fixtureId: fixture.fixtureId };
}

export function diagnoseBundle(values, context) {
  const stage = text(values.stage, '--stage');
  if (stage !== 'before' && stage !== 'after') {
    fail('--stage는 before 또는 after여야 합니다.');
  }
  const { bundle, plan, ownership } = readBundle(context, values.bundle);
  if (stage === 'before') {
    const executionArtifact = [
      ARTIFACTS.summary,
      ARTIFACTS.console,
      ARTIFACTS.runManifest,
      ARTIFACTS.t3AppLogApp1,
      ARTIFACTS.t3AppLogApp2,
      ARTIFACTS.t3MetricsApp1,
      ARTIFACTS.t3MetricsApp2,
      ARTIFACTS.t3MetricsPostgres,
      ARTIFACTS.t3Signals,
      ARTIFACTS.afterSnapshot,
      ARTIFACTS.afterDiagnosis,
      ARTIFACTS.finalResult,
      ARTIFACTS.infraExecution,
    ].find((relativePath) => existsSync(artifactPath(bundle, relativePath)));
    if (executionArtifact) {
      fail(`before diagnosis 전에 사후 실행 artifact가 이미 있습니다: ${executionArtifact}. 새 bundle을 사용하세요.`);
    }
  }
  const output = stage === 'before' ? ARTIFACTS.beforeDiagnosis : ARTIFACTS.afterDiagnosis;
  const outputPath = artifactPath(bundle, output);
  const snapshotPath = stage === 'before' ? ARTIFACTS.beforeSnapshot : ARTIFACTS.afterSnapshot;
  const snapshot = readSnapshot(bundle, snapshotPath);
  const fixture = readHydratedFixture(bundle, plan, ownership);
  if (stage === 'after' && plan.options.scenario === 't5') {
    const beforeDiagnosis = readDiagnosis(bundle, ARTIFACTS.beforeDiagnosis, 'before');
    fixture.baselineSnapshot = beforeDiagnosis.baselineSnapshot;
  }
  let summary = null;
  if (stage === 'after') {
    const summaryPath = artifactPath(bundle, ARTIFACTS.summary);
    summary = normalizeRoomSummary(readJson(summaryPath, 'k6 summary'));
  }
  const evaluation = evaluateFixture(fixture, snapshot, stage, summary);
  const result = {
    fixtureId: fixture.fixtureId,
    scenario: fixture.options.scenario,
    stage,
    ...evaluation,
  };
  if (stage === 'before' && plan.options.scenario === 't5') {
    result.baselineSnapshot = snapshot;
  }
  writeNewJson(outputPath, result);
  return result;
}

export function aggregateBundle(rawBundlePath, context) {
  const { bundle } = readBundle(context, rawBundlePath);
  const issues = [];
  const readCurrentDiagnosis = (relativePath, stage) => {
    try {
      return readDiagnosis(bundle, relativePath, stage);
    } catch (_) {
      issues.push(`${relativePath}이 없거나 현재 bundle의 ${stage} 진단 계약과 맞지 않습니다.`);
      return null;
    }
  };
  const before = readCurrentDiagnosis(ARTIFACTS.beforeDiagnosis, 'before');
  const after = readCurrentDiagnosis(ARTIFACTS.afterDiagnosis, 'after');
  let summary = null;
  try {
    summary = readMeasuredSummary(bundle);
  } catch (_) {
    issues.push('k6-summary.json이 없거나 outcome별 p50·p95·p99·max 계약과 맞지 않습니다.');
  }
  let runManifest = null;
  try {
    runManifest = readRunManifest(bundle);
  } catch (_) {
    issues.push('run-manifest.json이 없거나 현재 bundle의 완료된 실행 provenance 계약과 맞지 않습니다.');
  }
  let t3Signals = null;
  if (bundle.manifest.options.scenario === 't3') {
    if (summary && runManifest) {
      try {
        t3Signals = readT3Signals(bundle, runManifest, summary);
      } catch (_) {
        issues.push('t3-signals.json이 없거나 run·condition·outcome·DB·connection 계측 계약과 맞지 않습니다.');
      }
    } else {
      issues.push('T3 aggregate에는 유효한 summary와 run-manifest가 먼저 필요합니다.');
    }
  }
  let execution = null;
  try {
    execution = readInfraExecution(bundle);
  } catch (_) {
    issues.push('infra-execution.json이 없거나 현재 bundle의 원시 실행 metadata 계약과 맞지 않습니다.');
  }
  const phaseCodes = execution ? Object.values(execution.phases).map((phase) => phase.exitCode) : [];
  if (phaseCodes.some((code) => code === null)) {
    issues.push('원시 실행 phase exit code가 완결되지 않았습니다.');
  }
  const hasInvalid = issues.length > 0 || before?.status === 'INVALID' || after?.status === 'INVALID';
  const hasFailure = before?.status === 'FAIL' || after?.status === 'FAIL'
    || runManifest?.k6ExitCode !== undefined && runManifest.k6ExitCode !== 0
    || phaseCodes.some((code) => code !== null && code !== 0);
  const result = {
    schemaVersion: 1,
    fixtureId: bundle.manifest.fixtureId,
    runId: bundle.manifest.options.runId,
    scenario: bundle.manifest.options.scenario,
    status: hasInvalid ? 'INVALID' : hasFailure ? 'FAIL' : 'PASS',
    issues,
    beforeDiagnosis: before,
    afterDiagnosis: after,
    summary,
    runManifest,
    t3Signals,
    infraExecution: execution,
  };
  const output = artifactPath(bundle, ARTIFACTS.finalResult);
  if (existsSync(output)) {
    fail('final-result.json이 이미 있습니다. 기존 실행 결과를 덮어쓰지 않습니다.');
  }
  writeNewJson(output, result);
  return result;
}

export function executePortableBundleCommand(command, values, context) {
  switch (command) {
    case 'render-bundle':
      return renderBundle(values, context);
    case 'validate':
      return validateBundle(values.bundle, context, { forExecution: values.forExecution === true });
    case 'execution-options':
      return bundleExecutionOptions(values.bundle, context);
    case 'hydrate':
      return hydrateBundle(values.bundle, context);
    case 'diagnose':
      return diagnoseBundle(values, context);
    case 'aggregate':
      return aggregateBundle(values.bundle, context);
    default:
      fail(`지원하지 않는 portable ROOM bundle 명령: ${command}`);
  }
}

export const portableBundleArtifacts = ARTIFACTS;
