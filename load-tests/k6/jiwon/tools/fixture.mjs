#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  realpathSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { readExecutionOptions } from '../lib/read-execution-options.mjs';
import {
  buildCleanupSql,
  buildPrepareSql,
  buildResourceQuery,
  buildSnapshotQuery,
  createFixturePlan,
  evaluateFixture,
  hydrateFixture,
  RUN_ID_PATTERN,
} from './fixture-model.mjs';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const sourceRepositoryRoot = path.resolve(toolDirectory, '../../../..');
const scenarioDirectory = path.resolve(toolDirectory, '..');
const sourceBuildRoot = path.join(sourceRepositoryRoot, 'build', 'k6', 'room');
const bundleRuntimeRoot = path.resolve(toolDirectory, '..');
const bundledRuntime = existsSync(path.join(bundleRuntimeRoot, 'manifest.json'));
const BUNDLE_SCHEMA_VERSION = 1;
const BUNDLE_KIND = 'albam-mate-room-k6-bundle';
const FIXTURE_ID_PATTERN = /^room-k6-t[1-5]-[a-f0-9]{12}$/;
const BUNDLE_ARTIFACTS = Object.freeze({
  entry: 'scenario.js',
  library: 'lib/room-k6.js',
  readExecutionOptions: 'lib/read-execution-options.mjs',
  writeOptions: 'lib/write-options.mjs',
  t3ExecutionPlan: 'lib/t3-execution-plan.mjs',
  runtimeTool: 'tools/fixture.mjs',
  runtimeModel: 'tools/fixture-model.mjs',
  fixturePlan: 'fixture-plan.json',
  prepareProvenance: 'private/prepare-provenance.json',
  prepareSql: 'prepare.sql',
  resourceQuerySql: 'resource-query.sql',
  resourceOutput: 'resource-output.json',
  fixture: 'fixture.json',
  snapshotSql: 'snapshot.sql',
  cleanupSql: 'cleanup.sql',
  beforeSnapshot: 'before-snapshot.json',
  afterSnapshot: 'after-snapshot.json',
  summary: 'k6-summary.json',
  console: 'k6-console.log',
  infraExecution: 'infra-execution.json',
  cloudwatchDirectory: 'cloudwatch',
  beforeDiagnosis: 'before-diagnosis.json',
  afterDiagnosis: 'after-diagnosis.json',
  finalResult: 'final-result.json',
});
const FINAL_RESULT_SCHEMA_VERSION = 1;
const FINAL_RESULT_KIND = 'albam-mate-room-k6-final-result';
const T5_COMPARISON_SCHEMA_VERSION = 1;
const T5_COMPARISON_FILE = 't5-comparison-verification.json';
const T5_ROLES = Object.freeze(['public', 'host', 'participant']);
const T5_SCALES = Object.freeze([1, 10]);
const T5_DEPLOYMENT_PROVENANCE_FIELDS = Object.freeze([
  'applicationRevision',
  'stackId',
  'targetHttpsUrl',
]);
const INFRA_PHASE_NAMES = Object.freeze([
  'prepare',
  'resourceQuery',
  'beforeSnapshot',
  'k6',
  'afterSnapshot',
]);
const HASHED_SOURCE_ARTIFACTS = Object.freeze([
  'entry',
  'library',
  'readExecutionOptions',
  'writeOptions',
  't3ExecutionPlan',
  'runtimeTool',
  'runtimeModel',
]);
const PREFLIGHT_ARTIFACTS = Object.freeze([
  'fixturePlan',
  'prepareProvenance',
  'prepareSql',
  'resourceQuerySql',
]);
const EXECUTION_STATE_ARTIFACTS = Object.freeze([
  'resourceOutput',
  'fixture',
  'snapshotSql',
  'cleanupSql',
  'beforeSnapshot',
  'afterSnapshot',
  'summary',
  'console',
  'infraExecution',
  'cloudwatchDirectory',
  'beforeDiagnosis',
  'afterDiagnosis',
  'finalResult',
]);
const RAW_SQL_TRANSPORT = Object.freeze({
  schemaVersion: 1,
  command: 'psql -X --no-psqlrc -v ON_ERROR_STOP=1 -q -A -t -f <sql>',
  stdout: {
    format: 'single-json-value',
    preserve: 'byte-for-byte',
    destinations: {
      resourceQuerySql: BUNDLE_ARTIFACTS.resourceOutput,
      snapshotSql: [BUNDLE_ARTIFACTS.beforeSnapshot, BUNDLE_ARTIFACTS.afterSnapshot],
    },
  },
  stderr: {
    returned: false,
    handling: 'remote-temporary-no-log-delete',
  },
});
const INFRA_EXECUTION_TRANSPORT = Object.freeze({
  path: BUNDLE_ARTIFACTS.infraExecution,
  schemaVersion: 1,
  applicationRevision: '40-character-git-sha',
  t5ReadOptions: {
    vus: 'integer 1..500',
    durationSeconds: 'integer 5..3600',
    thinkTimeMilliseconds: 'integer 0..10000',
  },
});
const CLOUDWATCH_TRANSPORT = Object.freeze({
  path: BUNDLE_ARTIFACTS.cloudwatchDirectory,
  purpose: 'raw-collector-output-only',
  windowSource: `${BUNDLE_ARTIFACTS.infraExecution}.startedAt/finishedAt`,
});
const EXECUTION_PROTOCOL = Object.freeze({
  schemaVersion: 1,
  stages: [
    {
      name: 'prepare',
      owner: 'app',
      output: [
        'manifest.json',
        BUNDLE_ARTIFACTS.entry,
        BUNDLE_ARTIFACTS.library,
        BUNDLE_ARTIFACTS.readExecutionOptions,
        BUNDLE_ARTIFACTS.writeOptions,
        BUNDLE_ARTIFACTS.t3ExecutionPlan,
        BUNDLE_ARTIFACTS.runtimeTool,
        BUNDLE_ARTIFACTS.runtimeModel,
        BUNDLE_ARTIFACTS.prepareSql,
        BUNDLE_ARTIFACTS.resourceQuerySql,
      ],
    },
    {
      name: 'validate',
      owner: 'app',
      input: [
        BUNDLE_ARTIFACTS.fixturePlan,
        BUNDLE_ARTIFACTS.prepareSql,
        BUNDLE_ARTIFACTS.resourceQuerySql,
      ],
      exitCode: 0,
      requiredBefore: 'fixture-prepare',
    },
    {
      name: 'fixture-prepare',
      owner: 'infra',
      input: BUNDLE_ARTIFACTS.prepareSql,
      stderr: 'remote-temporary-no-log-delete',
    },
    {
      name: 'resource-output',
      owner: 'infra',
      input: BUNDLE_ARTIFACTS.resourceQuerySql,
      output: BUNDLE_ARTIFACTS.resourceOutput,
    },
    { name: 'hydrate', owner: 'app', output: [BUNDLE_ARTIFACTS.fixture, BUNDLE_ARTIFACTS.snapshotSql, BUNDLE_ARTIFACTS.cleanupSql] },
    {
      name: 'before-snapshot',
      owner: 'infra',
      input: BUNDLE_ARTIFACTS.snapshotSql,
      output: BUNDLE_ARTIFACTS.beforeSnapshot,
    },
    { name: 'diagnose-before', owner: 'app', exitCode: 0, requiredBefore: 'k6' },
    {
      name: 'k6',
      owner: 'infra',
      output: [BUNDLE_ARTIFACTS.summary, BUNDLE_ARTIFACTS.console, BUNDLE_ARTIFACTS.infraExecution, BUNDLE_ARTIFACTS.cloudwatchDirectory],
      preserveRawArtifactsOnFailure: true,
    },
    {
      name: 'after-snapshot',
      owner: 'infra',
      input: BUNDLE_ARTIFACTS.snapshotSql,
      output: BUNDLE_ARTIFACTS.afterSnapshot,
      requiredEvenIf: 'k6-nonzero-exit',
    },
    { name: 'diagnose-after', owner: 'app' },
    {
      name: 'aggregate',
      owner: 'app',
      input: [
        BUNDLE_ARTIFACTS.beforeDiagnosis,
        BUNDLE_ARTIFACTS.afterDiagnosis,
        BUNDLE_ARTIFACTS.infraExecution,
        BUNDLE_ARTIFACTS.cloudwatchDirectory,
      ],
      output: BUNDLE_ARTIFACTS.finalResult,
      outcome: 'copy-diagnosis-statuses-without-re-evaluation',
    },
  ],
});
const SCENARIO_ENTRIES = Object.freeze({
  t1: 't1-cancel-promotion.js',
  t2: 't2-concurrent-waitlist-registration.js',
  t3: 't3-waitlist-cancel-race.js',
  t4: 't4-last-seat-participation.js',
  t5: 't5-room-detail-by-role.js',
});
const COMMAND_OPTION_KEYS = {
  prepare: new Set([
    'scenario', 'runId', 'profile', 'rounds', 'mode', 'concurrency', 'subcase', 't3Mode', 't5Role', 't5Scale',
  ]),
  hydrate: new Set(['bundle']),
  validate: new Set(['bundle', 'forExecution']),
  'execution-options': new Set(['bundle']),
  diagnose: new Set(['bundle', 'stage']),
  aggregate: new Set(['bundle']),
  'compare-t5': new Set(['runId']),
  'cleanup-sql': new Set(['bundle']),
  cleanup: new Set(['bundle']),
  'recover-cleanup': new Set(['bundle']),
};
const COMMAND_BOOLEAN_OPTION_KEYS = {
  validate: new Set(['forExecution']),
};

function usage() {
  return `사용법:
  node load-tests/k6/jiwon/tools/fixture.mjs prepare --scenario t1 --run-id <run-id> [옵션]
  node load-tests/k6/jiwon/tools/fixture.mjs validate [--for-execution] --bundle <bundle-directory>
  node load-tests/k6/jiwon/tools/fixture.mjs execution-options --bundle <bundle-directory>
  node load-tests/k6/jiwon/tools/fixture.mjs hydrate --bundle <bundle-directory>
  node load-tests/k6/jiwon/tools/fixture.mjs diagnose --bundle <bundle-directory> --stage before|after
  node load-tests/k6/jiwon/tools/fixture.mjs aggregate --bundle <bundle-directory>
  node load-tests/k6/jiwon/tools/fixture.mjs compare-t5 --run-id <run-id>
  node load-tests/k6/jiwon/tools/fixture.mjs cleanup-sql --bundle <bundle-directory>
  node load-tests/k6/jiwon/tools/fixture.mjs cleanup --bundle <bundle-directory>
  node load-tests/k6/jiwon/tools/fixture.mjs recover-cleanup --bundle <bundle-directory>

prepare 공통 옵션: --profile stress|spike --rounds <1..20>
T1/T2: --mode hot|spread --concurrency 2|4|8
T2: --subcase distinct|duplicate (duplicate는 concurrency=2)
T3: --t3-mode race|wait-first|cancel-first
T4: --concurrency 2|4|8
T5: --t5-role public|host|participant --t5-scale 1|10
`;
}

function fail(message) {
  throw new Error(message);
}

function toCamelCase(name) {
  return name.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
}

function parseArguments(argv) {
  const [command, ...rest] = argv;
  if (!command || command === '--help' || command === '-h') {
    return { command: 'help', values: {} };
  }

  const values = {};
  for (let index = 0; index < rest.length; index += 1) {
    const token = rest[index];
    if (!token.startsWith('--')) {
      fail(`알 수 없는 인수: ${token}`);
    }
    const key = toCamelCase(token.slice(2));
    if (Object.prototype.hasOwnProperty.call(values, key)) {
      fail(`${token} 옵션이 중복되었습니다.`);
    }
    if (COMMAND_BOOLEAN_OPTION_KEYS[command]?.has(key)) {
      values[key] = true;
      continue;
    }
    const value = rest[index + 1];
    if (!value || value.startsWith('--')) {
      fail(`${token} 값이 필요합니다.`);
    }
    values[key] = value;
    index += 1;
  }
  const allowedKeys = COMMAND_OPTION_KEYS[command];
  if (allowedKeys) {
    const unknownKeys = Object.keys(values).filter((key) => !allowedKeys.has(key));
    if (unknownKeys.length > 0) {
      fail(`${command}에서 허용되지 않는 옵션: ${unknownKeys.map((key) => `--${key}`).join(', ')}`);
    }
  }
  return { command, values };
}

function requireEnvironment(name) {
  const value = (process.env[name] || '').trim();
  if (!value) {
    fail(`${name} 환경 변수가 필요합니다.`);
  }
  return value;
}

function requireValue(value, name) {
  const text = String(value || '').trim();
  if (!text) {
    fail(`${name} 값이 필요합니다.`);
  }
  return text;
}

function assertInsideBuild(candidatePath) {
  const resolved = path.resolve(candidatePath);
  if (bundledRuntime) {
    if (resolved !== bundleRuntimeRoot) {
      fail(`bundle 실행 도구는 자신의 bundle만 처리할 수 있습니다: ${bundleRuntimeRoot}`);
    }
    return resolved;
  }

  const relative = path.relative(sourceBuildRoot, resolved);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    fail(`bundle 경로는 ${sourceBuildRoot} 아래여야 합니다.`);
  }
  return resolved;
}

function lstatIfExists(filePath) {
  try {
    return lstatSync(filePath);
  } catch (error) {
    if (error && error.code === 'ENOENT') {
      return null;
    }
    throw error;
  }
}

function assertNotSymbolicLink(filePath, label) {
  const stat = lstatIfExists(filePath);
  if (!stat) {
    return null;
  }
  if (stat.isSymbolicLink()) {
    fail(`${label}에 symbolic link를 사용할 수 없습니다: ${filePath}`);
  }
  return stat;
}

function assertNoSymbolicLinksInsideBundle(bundleDirectory, candidatePath, label) {
  const relative = path.relative(bundleDirectory, candidatePath);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    fail(`${label} 경로가 bundle 밖을 가리킵니다.`);
  }

  let currentPath = bundleDirectory;
  for (const segment of relative.split(path.sep)) {
    if (!segment) {
      continue;
    }
    currentPath = path.join(currentPath, segment);
    const stat = assertNotSymbolicLink(currentPath, label);
    if (!stat) {
      return;
    }
  }
}

function assertRegularFile(filePath, label) {
  const stat = assertNotSymbolicLink(filePath, label);
  if (!stat || !stat.isFile()) {
    fail(`${label}은 일반 파일이어야 합니다: ${filePath}`);
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

function writeJson(filePath, value) {
  assertNotSymbolicLink(filePath, 'JSON 출력 파일');
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function writeText(filePath, value) {
  assertNotSymbolicLink(filePath, '텍스트 출력 파일');
  writeFileSync(filePath, value, 'utf8');
}

function sameJson(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function sha256File(filePath, label = 'bundle source artifact') {
  assertRegularFile(filePath, label);
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function sourceProvenance() {
  const revisionResult = spawnSync('git', ['rev-parse', 'HEAD'], {
    cwd: sourceRepositoryRoot,
    encoding: 'utf8',
  });
  const statusResult = spawnSync('git', ['status', '--porcelain', '--untracked-files=all'], {
    cwd: sourceRepositoryRoot,
    encoding: 'utf8',
  });
  const revision = revisionResult.status === 0 ? String(revisionResult.stdout || '').trim() : '';
  const cleanSource = statusResult.status === 0
    && !String(statusResult.stdout || '').trim()
    && /^[a-f0-9]{40}$/.test(revision);

  return {
    sourceRevision: cleanSource ? revision : null,
    sourceDirty: !cleanSource,
  };
}

function assertSafeBundleIdentity(outputDirectory, manifest) {
  const runId = manifest.options?.runId;
  const fixtureId = manifest.fixtureId;
  if (typeof runId !== 'string' || !RUN_ID_PATTERN.test(runId)
    || path.basename(runId) !== runId || path.normalize(runId) !== runId) {
    fail('bundle manifest의 runId가 안전한 형식이 아닙니다.');
  }
  if (typeof fixtureId !== 'string' || !FIXTURE_ID_PATTERN.test(fixtureId)
    || path.basename(fixtureId) !== fixtureId || path.normalize(fixtureId) !== fixtureId) {
    fail('bundle manifest의 fixtureId가 안전한 형식이 아닙니다.');
  }
  const expectedDirectory = bundledRuntime
    ? bundleRuntimeRoot
    : path.join(sourceBuildRoot, runId, fixtureId);
  if (path.basename(outputDirectory) !== fixtureId
    || path.basename(path.dirname(outputDirectory)) !== runId
    || outputDirectory !== expectedDirectory) {
    fail('bundle 경로와 manifest runId·fixtureId가 일치하지 않습니다.');
  }
}

function createBundleManifest(plan) {
  const provenance = sourceProvenance();
  return {
    schemaVersion: BUNDLE_SCHEMA_VERSION,
    kind: BUNDLE_KIND,
    fixtureSchemaVersion: plan.schemaVersion,
    fixtureId: plan.fixtureId,
    options: plan.options,
    artifacts: BUNDLE_ARTIFACTS,
    sourceRevision: provenance.sourceRevision,
    sourceDirty: provenance.sourceDirty,
    rawSqlTransport: RAW_SQL_TRANSPORT,
    rawTransport: {
      infraExecution: INFRA_EXECUTION_TRANSPORT,
      cloudwatch: CLOUDWATCH_TRANSPORT,
    },
    executionProtocol: EXECUTION_PROTOCOL,
  };
}

function createPrepareProvenance(plan, passwordHash) {
  if (typeof passwordHash !== 'string' || !passwordHash.startsWith('{bcrypt}$')) {
    fail('ROOM_K6_FIXTURE_PASSWORD_HASH는 {bcrypt}$로 시작해야 합니다.');
  }
  return {
    schemaVersion: 1,
    fixtureId: plan.fixtureId,
    options: plan.options,
    passwordHash,
  };
}

function sourceHashes(bundle) {
  return Object.fromEntries(
    HASHED_SOURCE_ARTIFACTS.map((artifactName) => [artifactName, sha256File(artifactPath(bundle, artifactName))]),
  );
}

function preflightArtifactHashes(bundle) {
  return Object.fromEntries(
    PREFLIGHT_ARTIFACTS.map((artifactName) => [
      artifactName,
      sha256File(artifactPath(bundle, artifactName), 'bundle preflight artifact'),
    ]),
  );
}

function verifySourceHashes(bundle) {
  const hashes = bundle.manifest.sourceHashes;
  if (!hashes || Object.keys(hashes).length !== HASHED_SOURCE_ARTIFACTS.length
    || HASHED_SOURCE_ARTIFACTS.some((artifactName) => !/^[a-f0-9]{64}$/.test(hashes[artifactName] || ''))) {
    fail('ROOM k6 bundle source hash 계약이 없습니다. 새 bundle을 생성하세요.');
  }

  HASHED_SOURCE_ARTIFACTS.forEach((artifactName) => {
    if (sha256File(artifactPath(bundle, artifactName)) !== hashes[artifactName]) {
      fail(`bundle ${artifactName} source hash가 manifest와 다릅니다.`);
    }
  });
}

function verifyPreflightArtifactHashes(bundle) {
  const hashes = bundle.manifest.artifactHashes;
  if (!hashes || Object.keys(hashes).length !== PREFLIGHT_ARTIFACTS.length
    || PREFLIGHT_ARTIFACTS.some((artifactName) => !/^[a-f0-9]{64}$/.test(hashes[artifactName] || ''))) {
    fail('ROOM k6 bundle preflight artifact hash 계약이 없습니다. 새 bundle을 생성하세요.');
  }

  PREFLIGHT_ARTIFACTS.forEach((artifactName) => {
    const actual = sha256File(artifactPath(bundle, artifactName), 'bundle preflight artifact');
    if (actual !== hashes[artifactName]) {
      fail(`bundle ${artifactName} artifact hash가 manifest와 다릅니다.`);
    }
  });
}

function artifactPath(bundle, artifactName) {
  const relativePath = bundle.manifest.artifacts?.[artifactName];
  if (relativePath !== BUNDLE_ARTIFACTS[artifactName]) {
    fail(`bundle manifest의 ${artifactName} artifact 계약이 다릅니다.`);
  }
  const resolved = path.resolve(bundle.outputDirectory, relativePath);
  const relative = path.relative(bundle.outputDirectory, resolved);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    fail(`bundle ${artifactName} artifact 경로가 bundle 밖을 가리킵니다.`);
  }
  assertNoSymbolicLinksInsideBundle(bundle.outputDirectory, resolved, `bundle ${artifactName} artifact`);
  return resolved;
}

function readBundle(rawPath) {
  const outputDirectory = assertInsideBuild(requireValue(rawPath, '--bundle'));
  const bundleDirectory = assertNotSymbolicLink(outputDirectory, 'bundle 디렉터리');
  if (!bundleDirectory || !bundleDirectory.isDirectory()) {
    fail(`bundle 디렉터리를 찾지 못했습니다: ${outputDirectory}`);
  }
  const runtimeRoot = bundledRuntime ? bundleRuntimeRoot : sourceBuildRoot;
  const realBuildRoot = realpathSync(runtimeRoot);
  const realOutputDirectory = realpathSync(outputDirectory);
  const realRelative = path.relative(realBuildRoot, realOutputDirectory);
  if (realRelative.startsWith('..') || path.isAbsolute(realRelative)) {
    fail('bundle 실제 경로가 build root 밖을 가리킵니다.');
  }
  const manifestPath = path.join(outputDirectory, 'manifest.json');
  const manifest = readJson(manifestPath, 'bundle manifest');
  if (manifest.kind !== BUNDLE_KIND || manifest.schemaVersion !== BUNDLE_SCHEMA_VERSION) {
    fail('지원하지 않는 ROOM k6 bundle manifest 버전입니다. 새 bundle을 생성하세요.');
  }
  if (manifest.fixtureSchemaVersion !== 1 || !manifest.fixtureId || !manifest.options) {
    fail('ROOM k6 bundle manifest에 fixture 계약이 없습니다.');
  }
  const hasImmutableSourceRevision = typeof manifest.sourceRevision === 'string'
    && /^[a-f0-9]{40}$/.test(manifest.sourceRevision);
  const hasDirtySourceProvenance = manifest.sourceRevision === null && manifest.sourceDirty === true;
  if (!hasImmutableSourceRevision && !hasDirtySourceProvenance) {
    fail('ROOM k6 bundle source provenance가 올바르지 않습니다. 새 bundle을 생성하세요.');
  }
  if (hasImmutableSourceRevision && manifest.sourceDirty !== false) {
    fail('immutable ROOM k6 bundle source revision은 clean source provenance가 필요합니다. 새 bundle을 생성하세요.');
  }
  assertSafeBundleIdentity(outputDirectory, manifest);
  if (!sameJson(manifest.rawSqlTransport, RAW_SQL_TRANSPORT)
    || !sameJson(manifest.rawTransport?.infraExecution, INFRA_EXECUTION_TRANSPORT)
    || !sameJson(manifest.rawTransport?.cloudwatch, CLOUDWATCH_TRANSPORT)
    || !sameJson(manifest.executionProtocol, EXECUTION_PROTOCOL)) {
    fail('ROOM k6 bundle의 raw transport 또는 실행 단계 계약이 다릅니다. 새 bundle을 생성하세요.');
  }
  const bundle = { outputDirectory, manifest };
  Object.keys(BUNDLE_ARTIFACTS).forEach((artifactName) => artifactPath(bundle, artifactName));
  verifySourceHashes(bundle);
  return bundle;
}

function readPlan(bundle) {
  const plan = readJson(artifactPath(bundle, 'fixturePlan'), 'fixture plan');
  const manifest = bundle.manifest;
  if (plan.schemaVersion !== manifest.fixtureSchemaVersion
    || plan.fixtureId !== manifest.fixtureId
    || plan.options?.runId !== manifest.options.runId
    || plan.options?.scenario !== manifest.options.scenario) {
    fail('fixture plan과 bundle manifest가 일치하지 않습니다.');
  }
  return plan;
}

function assertCanonicalPlan(plan) {
  if (!plan || typeof plan !== 'object' || Array.isArray(plan)
    || !plan.options || typeof plan.options !== 'object' || Array.isArray(plan.options)) {
    fail('fixture plan 형식이 올바르지 않습니다. 새 bundle을 생성하세요.');
  }

  const { fixtureId, ...input } = plan.options;
  const expectedPlan = createFixturePlan(input);
  if (fixtureId !== expectedPlan.fixtureId || !sameJson(plan, expectedPlan)) {
    fail('fixture plan은 앱 생성 계획과 일치하지 않습니다. 새 bundle을 생성하세요.');
  }
}

function readPrepareProvenance(bundle, plan) {
  const provenance = readJson(artifactPath(bundle, 'prepareProvenance'), 'prepare provenance');
  const passwordHash = provenance?.passwordHash;
  if (typeof passwordHash !== 'string' || !passwordHash.startsWith('{bcrypt}$')) {
    fail('prepare provenance의 password hash 형식이 올바르지 않습니다. 새 bundle을 생성하세요.');
  }
  if (!sameJson(provenance, createPrepareProvenance(plan, passwordHash))) {
    fail('prepare provenance가 fixture plan·options와 일치하지 않습니다. 새 bundle을 생성하세요.');
  }
  return passwordHash;
}

function assertPreflightSqlIntegrity(bundle, plan) {
  const prepareSql = readFileSync(
    artifactPath(bundle, 'prepareSql'),
    'utf8',
  );
  const passwordHash = readPrepareProvenance(bundle, plan);
  if (prepareSql !== buildPrepareSql(plan, passwordHash)) {
    fail('prepare.sql이 fixture plan과 private password provenance에 일치하지 않습니다. 새 bundle을 생성하세요.');
  }

  const resourceQuerySql = readFileSync(
    artifactPath(bundle, 'resourceQuerySql'),
    'utf8',
  );
  if (resourceQuerySql !== buildResourceQuery(plan)) {
    fail('resource-query.sql이 fixture plan과 일치하지 않습니다. 새 bundle을 생성하세요.');
  }
}

function readPreflightBundle(rawBundlePath) {
  const bundle = readBundle(rawBundlePath);
  const plan = readPlan(bundle);
  verifyPreflightArtifactHashes(bundle);
  assertCanonicalPlan(plan);
  assertPreflightSqlIntegrity(bundle, plan);
  return { bundle, plan };
}

function assertPristineExecutionState(bundle) {
  const existingArtifacts = EXECUTION_STATE_ARTIFACTS
    .filter((artifactName) => lstatIfExists(artifactPath(bundle, artifactName)))
    .map((artifactName) => BUNDLE_ARTIFACTS[artifactName]);
  if (existingArtifacts.length > 0) {
    fail(`실행 전용 validate는 새 bundle만 허용합니다. 실행 상태 artifact가 이미 있습니다: ${existingArtifacts.join(', ')}`);
  }
}

function readFixture(bundle) {
  const fixture = readJson(artifactPath(bundle, 'fixture'), 'fixture');
  const manifest = bundle.manifest;
  if (fixture.schemaVersion !== manifest.fixtureSchemaVersion
    || fixture.fixtureId !== manifest.fixtureId
    || fixture.options?.runId !== manifest.options.runId
    || fixture.options?.scenario !== manifest.options.scenario) {
    fail('fixture와 bundle manifest가 일치하지 않습니다.');
  }
  return fixture;
}

function readValidatedFixture(bundle) {
  const fixture = readFixture(bundle);
  const plan = readPlan(bundle);
  const resources = readJson(artifactPath(bundle, 'resourceOutput'), 'resource output');
  const expectedFixture = hydrateFixture(plan, resources);
  if (!sameJson(fixture, expectedFixture)) {
    fail('fixture JSON이 fixture plan과 raw resource output에 일치하지 않습니다.');
  }
  return fixture;
}

function readSnapshot(bundle, artifactName) {
  const snapshot = readJson(artifactPath(bundle, artifactName), artifactName);
  if (!snapshot || !Array.isArray(snapshot.rooms)
    || !Array.isArray(snapshot.participations)
    || !Array.isArray(snapshot.waitlists)) {
    fail(`${artifactName}은 rooms, participations, waitlists 배열을 포함해야 합니다.`);
  }
  return snapshot;
}

function psql(psqlArgs, input = undefined) {
  const result = spawnSync('psql', ['-X', '--no-psqlrc', '-v', 'ON_ERROR_STOP=1', ...psqlArgs], {
    cwd: bundledRuntime ? bundleRuntimeRoot : sourceRepositoryRoot,
    encoding: 'utf8',
    env: process.env,
    input,
  });

  if (result.error) {
    fail(`psql을 실행하지 못했습니다. PostgreSQL 연결 환경과 psql 설치를 확인하세요: ${result.error.message}`);
  }
  if (result.status !== 0) {
    fail(`psql 실행이 실패했습니다(exit=${result.status}). 비밀값을 출력하지 않으므로 대상 DB의 psql 오류를 직접 확인하세요.`);
  }
  return result.stdout;
}

export function renderBundle(values, passwordHash) {
  if (bundledRuntime) {
    fail('실행 bundle 안에서는 prepare를 실행할 수 없습니다. 앱 소스에서 새 bundle을 생성하세요.');
  }
  const plan = createFixturePlan(values);
  const outputDirectory = assertInsideBuild(path.join(sourceBuildRoot, plan.options.runId, plan.fixtureId));
  if (existsSync(outputDirectory)) {
    fail(`같은 run ID·scenario fixture가 이미 있습니다: ${outputDirectory}. 기존 bundle을 교체하지 말고 새 run ID를 사용하세요.`);
  }

  const manifest = createBundleManifest(plan);
  const bundle = { outputDirectory, manifest };
  const entryName = SCENARIO_ENTRIES[plan.options.scenario];
  if (!entryName) {
    fail(`scenario=${plan.options.scenario}의 k6 entry를 찾지 못했습니다.`);
  }
  const prepareProvenance = createPrepareProvenance(plan, passwordHash);
  const prepareSql = buildPrepareSql(plan, passwordHash);

  mkdirSync(outputDirectory, { recursive: true });
  mkdirSync(path.dirname(artifactPath(bundle, 'library')), { recursive: true });
  mkdirSync(path.dirname(artifactPath(bundle, 'runtimeTool')), { recursive: true });
  mkdirSync(path.dirname(artifactPath(bundle, 'prepareProvenance')), { recursive: true });

  writeJson(artifactPath(bundle, 'fixturePlan'), plan);
  writeJson(artifactPath(bundle, 'prepareProvenance'), prepareProvenance);
  writeText(artifactPath(bundle, 'prepareSql'), prepareSql);
  writeText(artifactPath(bundle, 'resourceQuerySql'), buildResourceQuery(plan));
  copyFileSync(path.join(scenarioDirectory, entryName), artifactPath(bundle, 'entry'));
  copyFileSync(path.join(scenarioDirectory, 'lib', 'room-k6.js'), artifactPath(bundle, 'library'));
  copyFileSync(
    path.join(scenarioDirectory, 'lib', 'read-execution-options.mjs'),
    artifactPath(bundle, 'readExecutionOptions'),
  );
  copyFileSync(
    path.join(scenarioDirectory, 'lib', 'write-options.mjs'),
    artifactPath(bundle, 'writeOptions'),
  );
  copyFileSync(
    path.join(scenarioDirectory, 'lib', 't3-execution-plan.mjs'),
    artifactPath(bundle, 't3ExecutionPlan'),
  );
  copyFileSync(path.join(toolDirectory, 'fixture.mjs'), artifactPath(bundle, 'runtimeTool'));
  copyFileSync(path.join(toolDirectory, 'fixture-model.mjs'), artifactPath(bundle, 'runtimeModel'));
  manifest.sourceHashes = sourceHashes(bundle);
  manifest.artifactHashes = preflightArtifactHashes(bundle);
  writeJson(path.join(outputDirectory, 'manifest.json'), manifest);

  return {
    bundlePath: outputDirectory,
    fixtureId: plan.fixtureId,
    scenario: plan.options.scenario,
    options: plan.options,
  };
}

export function hydrateBundle(rawBundlePath) {
  const { bundle, plan } = readPreflightBundle(rawBundlePath);
  const fixturePath = artifactPath(bundle, 'fixture');
  const generatedArtifacts = ['fixture', 'snapshotSql', 'cleanupSql'];
  if (generatedArtifacts.some((artifactName) => existsSync(artifactPath(bundle, artifactName)))) {
    fail(`이미 hydrate된 bundle입니다: ${fixturePath}`);
  }

  const resources = readJson(artifactPath(bundle, 'resourceOutput'), 'resource output');
  const fixture = hydrateFixture(plan, resources);
  writeJson(fixturePath, fixture);
  writeText(artifactPath(bundle, 'snapshotSql'), buildSnapshotQuery(fixture));
  writeText(artifactPath(bundle, 'cleanupSql'), buildCleanupSql(fixture));

  return {
    bundlePath: bundle.outputDirectory,
    fixturePath,
    fixtureId: fixture.fixtureId,
    snapshotSqlPath: artifactPath(bundle, 'snapshotSql'),
    cleanupSqlPath: artifactPath(bundle, 'cleanupSql'),
  };
}

export function validateBundle(rawBundlePath, { forExecution = false } = {}) {
  const { bundle } = readPreflightBundle(rawBundlePath);
  if (forExecution) {
    assertPristineExecutionState(bundle);
  }

  return {
    bundlePath: bundle.outputDirectory,
    runId: bundle.manifest.options.runId,
    fixtureId: bundle.manifest.fixtureId,
  };
}

export function executionOptionsBundle(rawBundlePath, environment = process.env) {
  const { bundle } = readPreflightBundle(rawBundlePath);
  if (bundle.manifest.options.scenario !== 't5') {
    return {
      bundlePath: bundle.outputDirectory,
      scenario: bundle.manifest.options.scenario,
      t5ReadOptions: null,
      k6Environment: {},
    };
  }

  const t5ReadOptions = readExecutionOptions(environment);
  return {
    bundlePath: bundle.outputDirectory,
    scenario: 't5',
    t5ReadOptions,
    k6Environment: {
      ROOM_K6_READ_VUS: String(t5ReadOptions.vus),
      ROOM_K6_READ_DURATION_SECONDS: String(t5ReadOptions.durationSeconds),
      ROOM_K6_READ_THINK_TIME_MS: String(t5ReadOptions.thinkTimeMilliseconds),
    },
  };
}

export function recoverCleanupBundle(rawBundlePath, executeSql = psql) {
  const { bundle, plan } = readPreflightBundle(rawBundlePath);
  requireCleanupAcknowledgement(bundle.manifest.fixtureId);
  const rawResources = executeSql(['-q', '-A', '-t', '-f', '-'], buildResourceQuery(plan));
  let resources;
  try {
    resources = JSON.parse(String(rawResources || ''));
  } catch (_) {
    fail('recovery resource query의 JSON 결과를 읽을 수 없습니다. cleanup을 실행하지 않았습니다.');
  }

  const fixture = hydrateFixture(plan, resources);
  executeSql(['-q', '-f', '-'], buildCleanupSql(fixture));
  return { fixtureId: fixture.fixtureId, status: 'RECOVERED_CLEANED' };
}

export function diagnoseBundle(values) {
  const stage = String(values.stage || '').trim();
  if (stage !== 'before' && stage !== 'after') {
    fail('--stage는 before 또는 after여야 합니다.');
  }

  const { bundle } = readPreflightBundle(values.bundle);
  const fixture = readValidatedFixture(bundle);
  const snapshot = readSnapshot(bundle, stage === 'before' ? 'beforeSnapshot' : 'afterSnapshot');
  const summary = stage === 'after'
    ? readJson(artifactPath(bundle, 'summary'), 'k6 summary')
    : null;
  const baselineSnapshot = stage === 'after' && fixture.options.scenario === 't5'
    ? readSnapshot(bundle, 'beforeSnapshot')
    : null;
  const result = {
    fixtureId: fixture.fixtureId,
    scenario: fixture.options.scenario,
    stage,
    ...evaluateFixture(fixture, snapshot, stage, summary, baselineSnapshot),
  };
  writeJson(
    artifactPath(bundle, stage === 'before' ? 'beforeDiagnosis' : 'afterDiagnosis'),
    result,
  );
  return result;
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isStringArray(value) {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

function parseIso8601UtcTimestamp(value) {
  if (typeof value !== 'string'
    || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) {
    return null;
  }

  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) {
    return null;
  }

  const expected = value.includes('.') ? value : value.replace('Z', '.000Z');
  return new Date(timestamp).toISOString() === expected ? timestamp : null;
}

function addAggregationIssue(issues, artifact, type, message) {
  issues.push({ artifact, type, message });
}

function readAggregationJson(bundle, artifactName, label, issues) {
  const filePath = artifactPath(bundle, artifactName);
  const stat = lstatIfExists(filePath);
  if (!stat) {
    addAggregationIssue(issues, BUNDLE_ARTIFACTS[artifactName], 'MISSING', `${label}이 없습니다.`);
    return undefined;
  }
  if (!stat.isFile()) {
    addAggregationIssue(issues, BUNDLE_ARTIFACTS[artifactName], 'INVALID', `${label}은 일반 파일이어야 합니다.`);
    return undefined;
  }
  try {
    return readJson(filePath, label);
  } catch (_) {
    addAggregationIssue(issues, BUNDLE_ARTIFACTS[artifactName], 'INVALID', `${label} JSON을 읽을 수 없습니다.`);
    return undefined;
  }
}

function readDiagnosisForAggregation(bundle, artifactName, stage, issues) {
  const diagnosis = readAggregationJson(bundle, artifactName, `${stage} diagnosis`, issues);
  if (diagnosis === undefined) {
    return null;
  }
  if (!isRecord(diagnosis)
    || diagnosis.fixtureId !== bundle.manifest.fixtureId
    || diagnosis.scenario !== bundle.manifest.options.scenario
    || diagnosis.stage !== stage
    || typeof diagnosis.status !== 'string'
    || !diagnosis.status.trim()
    || !isStringArray(diagnosis.failures)) {
    addAggregationIssue(
      issues,
      BUNDLE_ARTIFACTS[artifactName],
      'INVALID',
      `${stage} diagnosis가 이 bundle의 앱 진단 계약과 일치하지 않습니다.`,
    );
    return null;
  }
  return diagnosis;
}

function isExitCode(value) {
  return value === null || Number.isInteger(value);
}

function isApplicationRevision(value) {
  return typeof value === 'string' && /^[a-f0-9]{40}$/i.test(value);
}

function isT5ReadOptions(value) {
  return isRecord(value)
    && Number.isInteger(value.vus) && value.vus >= 1 && value.vus <= 500
    && Number.isInteger(value.durationSeconds) && value.durationSeconds >= 5 && value.durationSeconds <= 3600
    && Number.isInteger(value.thinkTimeMilliseconds)
    && value.thinkTimeMilliseconds >= 0 && value.thinkTimeMilliseconds <= 10000;
}

function readInfraExecutionForAggregation(bundle, issues) {
  const execution = readAggregationJson(bundle, 'infraExecution', 'infra execution', issues);
  if (execution === undefined) {
    return null;
  }
  if (!isRecord(execution)) {
    addAggregationIssue(
      issues,
      BUNDLE_ARTIFACTS.infraExecution,
      'INVALID',
      'infra execution이 원시 전달 metadata 계약과 일치하지 않습니다.',
    );
    return null;
  }

  const hasIdentity = execution.schemaVersion === INFRA_EXECUTION_TRANSPORT.schemaVersion
    && execution.runId === bundle.manifest.options.runId
    && execution.fixtureId === bundle.manifest.fixtureId;
  const hasMetadata = ['stackId', 'targetHttpsUrl']
    .every((fieldName) => typeof execution[fieldName] === 'string' && execution[fieldName].trim());
  const startedAt = parseIso8601UtcTimestamp(execution.startedAt);
  const finishedAt = parseIso8601UtcTimestamp(execution.finishedAt);
  const hasExecutionWindow = startedAt !== null
    && finishedAt !== null
    && finishedAt >= startedAt;
  const hasApplicationRevision = isApplicationRevision(execution.applicationRevision);
  const hasPhaseExitCodes = isRecord(execution.phases)
    && INFRA_PHASE_NAMES.every((phaseName) => isRecord(execution.phases[phaseName])
      && Object.hasOwn(execution.phases[phaseName], 'exitCode')
      && isExitCode(execution.phases[phaseName].exitCode));

  const hasT5ReadOptions = bundle.manifest.options.scenario !== 't5'
    || isT5ReadOptions(execution.t5ReadOptions);
  if (!hasIdentity || !hasMetadata || !hasExecutionWindow || !hasApplicationRevision
    || !hasPhaseExitCodes || !hasT5ReadOptions) {
    addAggregationIssue(
      issues,
      BUNDLE_ARTIFACTS.infraExecution,
      'INVALID',
      'infra execution이 원시 전달 metadata 계약과 일치하지 않습니다.',
    );
    return null;
  }
  return execution;
}

function listCloudwatchArtifacts(directoryPath, relativePath, artifacts, issues) {
  let entries;
  try {
    entries = readdirSync(directoryPath, { withFileTypes: true });
  } catch (_) {
    addAggregationIssue(issues, BUNDLE_ARTIFACTS.cloudwatchDirectory, 'INVALID', 'cloudwatch 디렉터리를 읽을 수 없습니다.');
    return;
  }

  entries.sort((left, right) => left.name.localeCompare(right.name));
  entries.forEach((entry) => {
    const entryPath = path.join(directoryPath, entry.name);
    const artifactPathText = relativePath ? `${relativePath}/${entry.name}` : entry.name;
    const stat = lstatIfExists(entryPath);
    if (!stat) {
      addAggregationIssue(issues, BUNDLE_ARTIFACTS.cloudwatchDirectory, 'INVALID', `cloudwatch artifact를 읽을 수 없습니다: ${artifactPathText}`);
      return;
    }
    if (stat.isSymbolicLink()) {
      addAggregationIssue(issues, BUNDLE_ARTIFACTS.cloudwatchDirectory, 'INVALID', `cloudwatch symbolic link는 허용하지 않습니다: ${artifactPathText}`);
      return;
    }
    if (stat.isDirectory()) {
      listCloudwatchArtifacts(entryPath, artifactPathText, artifacts, issues);
      return;
    }
    if (!stat.isFile()) {
      addAggregationIssue(issues, BUNDLE_ARTIFACTS.cloudwatchDirectory, 'INVALID', `cloudwatch artifact는 일반 파일이어야 합니다: ${artifactPathText}`);
      return;
    }
    artifacts.push({
      path: artifactPathText,
      sizeBytes: stat.size,
      modifiedAt: stat.mtime.toISOString(),
    });
  });
}

function collectCloudwatchMetadata(bundle, issues) {
  const directoryPath = artifactPath(bundle, 'cloudwatchDirectory');
  const directory = lstatIfExists(directoryPath);
  if (!directory) {
    addAggregationIssue(issues, BUNDLE_ARTIFACTS.cloudwatchDirectory, 'MISSING', 'cloudwatch 원시 결과 디렉터리가 없습니다.');
    return { presence: 'MISSING', artifacts: [] };
  }
  if (!directory.isDirectory()) {
    addAggregationIssue(issues, BUNDLE_ARTIFACTS.cloudwatchDirectory, 'INVALID', 'cloudwatch 원시 결과는 디렉터리여야 합니다.');
    return { presence: 'INVALID', artifacts: [] };
  }

  const issueCount = issues.length;
  const artifacts = [];
  listCloudwatchArtifacts(directoryPath, '', artifacts, issues);
  return {
    presence: issues.length === issueCount ? 'PRESENT' : 'INVALID',
    artifacts,
  };
}

function aggregationStatus(issues) {
  if (issues.some((issue) => issue.type === 'INVALID')) {
    return 'INVALID_INPUT';
  }
  return issues.length > 0 ? 'INCOMPLETE' : 'COMPLETE';
}

export function aggregateBundle(rawBundlePath) {
  const bundle = readBundle(rawBundlePath);
  const issues = [];
  const beforeDiagnosis = readDiagnosisForAggregation(bundle, 'beforeDiagnosis', 'before', issues);
  const afterDiagnosis = readDiagnosisForAggregation(bundle, 'afterDiagnosis', 'after', issues);
  const infraExecution = readInfraExecutionForAggregation(bundle, issues);
  const cloudwatch = collectCloudwatchMetadata(bundle, issues);
  const result = {
    schemaVersion: FINAL_RESULT_SCHEMA_VERSION,
    kind: FINAL_RESULT_KIND,
    aggregationStatus: aggregationStatus(issues),
    runId: bundle.manifest.options.runId,
    fixtureId: bundle.manifest.fixtureId,
    scenario: bundle.manifest.options.scenario,
    diagnoses: {
      before: beforeDiagnosis,
      after: afterDiagnosis,
    },
    infraExecution,
    cloudwatch,
    inputIssues: issues,
  };
  writeJson(artifactPath(bundle, 'finalResult'), result);
  return result;
}

function t5ComparisonDirectory(runId) {
  if (!RUN_ID_PATTERN.test(runId)) {
    fail('runId는 영문 소문자 또는 숫자로 시작하는 80자 이하의 안전한 값이어야 합니다.');
  }
  const outputDirectory = assertInsideBuild(path.join(sourceBuildRoot, runId));
  const stat = assertNotSymbolicLink(outputDirectory, 'T5 비교 디렉터리');
  if (!stat || !stat.isDirectory()) {
    fail('T5 비교 run ID 경로를 찾지 못했습니다: ' + outputDirectory);
  }
  return outputDirectory;
}

function t5CaseKey(options) {
  return options.t5Role + '-' + options.t5Scale;
}

function sameT5ReadOptions(left, right) {
  return left.vus === right.vus
    && left.durationSeconds === right.durationSeconds
    && left.thinkTimeMilliseconds === right.thinkTimeMilliseconds;
}

function differentT5DeploymentProvenanceFields(left, right) {
  return T5_DEPLOYMENT_PROVENANCE_FIELDS.filter((fieldName) => left[fieldName] !== right[fieldName]);
}

function t5BundlesForRun(runId) {
  const runDirectory = t5ComparisonDirectory(runId);
  return readdirSync(runDirectory, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && !entry.isSymbolicLink())
    .map((entry) => path.join(runDirectory, entry.name))
    .sort()
    .map((bundlePath) => {
      try {
        return readBundle(bundlePath);
      } catch (_) {
        return null;
      }
    })
    .filter((bundle) => bundle
      && bundle.manifest.options.scenario === 't5'
      && bundle.manifest.options.runId === runId);
}

export function compareT5Bundles(runId) {
  const runDirectory = t5ComparisonDirectory(runId);
  const failures = [];
  const bundlesByCase = new Map();
  let invalidArtifact = false;

  for (const bundle of t5BundlesForRun(runId)) {
    const caseKey = t5CaseKey(bundle.manifest.options);
    if (bundlesByCase.has(caseKey)) {
      failures.push('T5 ' + caseKey + ' bundle이 비교 run ID에 둘 이상 있습니다.');
      continue;
    }

    const issues = [];
    const execution = readInfraExecutionForAggregation(bundle, issues);
    const result = readAggregationJson(bundle, 'finalResult', 'final result', issues);
    if (!execution || !result || !isRecord(result)
      || result.schemaVersion !== FINAL_RESULT_SCHEMA_VERSION
      || result.kind !== FINAL_RESULT_KIND
      || result.runId !== runId
      || result.fixtureId !== bundle.manifest.fixtureId
      || result.scenario !== 't5'
      || result.aggregationStatus !== 'COMPLETE') {
      invalidArtifact = true;
      failures.push('T5 ' + caseKey + ': 비교 가능한 완결 final-result와 infra metadata가 없습니다.');
      continue;
    }
    if (execution.phases.k6.exitCode !== 0) {
      failures.push('T5 ' + caseKey + ': k6 실행이 exit=' + execution.phases.k6.exitCode + '로 종료되었습니다.');
    }
    bundlesByCase.set(caseKey, { bundle, execution });
  }

  const expectedCases = T5_ROLES.flatMap((role) => T5_SCALES.map((scale) => role + '-' + scale));
  for (const expectedCase of expectedCases) {
    if (!bundlesByCase.has(expectedCase)) {
      failures.push('T5 비교에 필요한 ' + expectedCase + ' bundle이 없습니다.');
    }
  }

  let deploymentProvenance = null;
  for (const expectedCase of expectedCases) {
    const entry = bundlesByCase.get(expectedCase);
    if (!entry) {
      continue;
    }
    if (!deploymentProvenance) {
      deploymentProvenance = entry.execution;
      continue;
    }
    const differentFields = differentT5DeploymentProvenanceFields(
      deploymentProvenance,
      entry.execution,
    );
    if (differentFields.length > 0) {
      invalidArtifact = true;
      failures.push(
        'T5 ' + expectedCase + '의 배포 provenance가 다른 T5 실행과 다릅니다: '
        + differentFields.join(', ') + '.',
      );
    }
  }

  let readOptions = null;
  for (const expectedCase of expectedCases) {
    const entry = bundlesByCase.get(expectedCase);
    if (!entry) {
      continue;
    }
    if (!readOptions) {
      readOptions = entry.execution.t5ReadOptions;
      continue;
    }
    if (!sameT5ReadOptions(readOptions, entry.execution.t5ReadOptions)) {
      failures.push('T5 ' + expectedCase + '의 read profile이 다른 T5 실행과 다릅니다.');
    }
  }

  const result = {
    schemaVersion: T5_COMPARISON_SCHEMA_VERSION,
    runId,
    scenario: 't5',
    status: invalidArtifact ? 'INVALID' : failures.length === 0 ? 'PASS' : 'FAIL',
    t5ReadOptions: readOptions,
    fixtureCount: bundlesByCase.size,
    failures,
  };
  writeJson(path.join(runDirectory, T5_COMPARISON_FILE), result);
  return result;
}

function readCleanupSqlBundle(rawBundlePath) {
  const { bundle } = readPreflightBundle(rawBundlePath);
  const fixture = readValidatedFixture(bundle);
  return { bundle, cleanupSql: buildCleanupSql(fixture) };
}

function requireCleanupAcknowledgement(fixtureId) {
  if (process.env.ROOM_K6_CLEANUP_ACK !== fixtureId) {
    fail('ROOM_K6_CLEANUP_ACK는 검증된 fixtureId와 정확히 일치해야 합니다. cleanup을 실행하지 않았습니다.');
  }
}

export function cleanupBundle(rawBundlePath, executeSql = psql) {
  const { bundle, cleanupSql } = readCleanupSqlBundle(rawBundlePath);
  requireCleanupAcknowledgement(bundle.manifest.fixtureId);
  executeSql(['-q', '-f', '-'], cleanupSql);
  return { fixtureId: bundle.manifest.fixtureId, status: 'CLEANED' };
}

export function cleanupSqlBundle(rawBundlePath) {
  return readCleanupSqlBundle(rawBundlePath).cleanupSql;
}

function prepare(values) {
  const result = renderBundle(values, requireEnvironment('ROOM_K6_FIXTURE_PASSWORD_HASH'));
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

function hydrate(values) {
  const result = hydrateBundle(values.bundle);
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

function validate(values) {
  const result = validateBundle(values.bundle, { forExecution: values.forExecution === true });
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

function executionOptions(values) {
  const result = executionOptionsBundle(values.bundle);
  process.stdout.write(JSON.stringify(result) + '\n');
}

function diagnose(values) {
  const result = diagnoseBundle(values);
  process.stdout.write(`${JSON.stringify(result)}\n`);
  if (result.status === 'INVALID') {
    process.exitCode = 2;
  } else if (result.status !== 'PASS') {
    process.exitCode = 1;
  }
}

function aggregate(values) {
  const result = aggregateBundle(values.bundle);
  process.stdout.write(`${JSON.stringify(result)}\n`);
  if (result.aggregationStatus !== 'COMPLETE') {
    process.exitCode = 2;
  }
}

function compareT5(values) {
  const result = compareT5Bundles(requireValue(values.runId, '--run-id'));
  process.stdout.write(JSON.stringify(result) + '\n');
  if (result.status === 'INVALID') {
    process.exitCode = 2;
  } else if (result.status !== 'PASS') {
    process.exitCode = 1;
  }
}

function cleanup(values) {
  const result = cleanupBundle(values.bundle);
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

function cleanupSql(values) {
  process.stdout.write(cleanupSqlBundle(values.bundle));
}

function recoverCleanup(values) {
  const result = recoverCleanupBundle(values.bundle);
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

function main() {
  const { command, values } = parseArguments(process.argv.slice(2));
  switch (command) {
    case 'help':
      process.stdout.write(usage());
      return;
    case 'prepare':
      prepare(values);
      return;
    case 'hydrate':
      hydrate(values);
      return;
    case 'validate':
      validate(values);
      return;
    case 'execution-options':
      executionOptions(values);
      return;
    case 'diagnose':
      diagnose(values);
      return;
    case 'aggregate':
      aggregate(values);
      return;
    case 'compare-t5':
      compareT5(values);
      return;
    case 'cleanup-sql':
      cleanupSql(values);
      return;
    case 'cleanup':
      cleanup(values);
      return;
    case 'recover-cleanup':
      recoverCleanup(values);
      return;
    default:
      fail(`지원하지 않는 명령: ${command}\n\n${usage()}`);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
