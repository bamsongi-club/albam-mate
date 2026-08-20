#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
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
import { executePortableBundleCommand, portableBundleArtifacts } from './portable-bundle.mjs';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '../../../..');
const buildRoot = path.join(repositoryRoot, 'build', 'k6', 'room');
const RUN_MANIFEST_FILE = 'run-manifest.json';
const RUN_SUMMARY_FILE = 'k6-summary.json';
const T5_COMPARISON_FILE = 't5-comparison-verification.json';
const PREPARE_RECOVERY_FILE = 'prepare-recovery.json';
const SOURCE_SHA_PATTERN = /^[0-9a-f]{40}$/i;
const SHA256_PATTERN = /^[0-9a-f]{64}$/i;
const TARGET_ENVIRONMENT_PATTERN = /^[a-z0-9][a-z0-9._-]{0,79}$/;
const T5_ROLES = ['public', 'host', 'participant'];
const T5_SCALES = [1, 10];
const PORTABLE_PHASE_NAMES = ['prepare', 'resourceQuery', 'beforeSnapshot', 'k6', 'afterSnapshot'];
const SCENARIO_SCRIPTS = {
  t1: 't1-cancel-promotion.js',
  t2: 't2-concurrent-waitlist-registration.js',
  t3: 't3-waitlist-cancel-race.js',
  t4: 't4-last-seat-participation.js',
  t5: 't5-room-detail-by-role.js',
  mixed: 'room-mixed-write-read.js',
};
const MIXED_PROFILE_OPTION_KEYS = [
  'hotRoomCount', 'spreadRoomCount', 'hotRequestPercent', 'spreadRequestPercent',
  't1Percent', 't2Percent', 't5Percent', 'arrivalRate', 'arrivalTimeUnit',
  'durationSeconds', 'preAllocatedVUs', 'maxVUs', 'seed',
];
const COMMAND_OPTION_KEYS = {
  prepare: new Set([
    'scenario', 'runId', 'profile', 'rounds', 'mode', 'concurrency', 'subcase', 't3Mode', 't5Role', 't5Scale',
    ...MIXED_PROFILE_OPTION_KEYS,
  ]),
  run: new Set(['fixture']),
  verify: new Set(['fixture', 'stage']),
  'compare-t5': new Set(['runId']),
  cleanup: new Set(['fixture']),
  'recover-cleanup': new Set(['recovery']),
  'render-bundle': new Set([
    'scenario', 'runId', 'profile', 'rounds', 'mode', 'concurrency', 'subcase', 't3Mode', 't5Role', 't5Scale',
    ...MIXED_PROFILE_OPTION_KEYS,
  ]),
  validate: new Set(['bundle', 'forExecution']),
  'execution-options': new Set(['bundle']),
  hydrate: new Set(['bundle']),
  diagnose: new Set(['bundle', 'stage']),
  aggregate: new Set(['bundle']),
};
const COMMAND_BOOLEAN_OPTION_KEYS = {
  validate: new Set(['forExecution']),
};
const BUNDLE_RUNTIME_DIRECT_COMMANDS = new Set([
  'prepare',
  'run',
  'verify',
  'compare-t5',
  'cleanup',
  'recover-cleanup',
  'render-bundle',
]);

function usage() {
  return `사용법:
  node load-tests/k6/jiwon/tools/fixture.mjs prepare --scenario t1|t2|t3|t4|t5|mixed --run-id <run-id> [옵션]
  node load-tests/k6/jiwon/tools/fixture.mjs run --fixture <fixture.json>
  node load-tests/k6/jiwon/tools/fixture.mjs verify --fixture <fixture.json> --stage before|after
  node load-tests/k6/jiwon/tools/fixture.mjs compare-t5 --run-id <run-id>
  node load-tests/k6/jiwon/tools/fixture.mjs cleanup --fixture <fixture.json>
  node load-tests/k6/jiwon/tools/fixture.mjs recover-cleanup --recovery <prepare-recovery.json>
  node load-tests/k6/jiwon/tools/fixture.mjs render-bundle --scenario t1 --run-id <run-id> [옵션]
  node load-tests/k6/jiwon/tools/fixture.mjs validate [--for-execution] --bundle <bundle-directory>
  node load-tests/k6/jiwon/tools/fixture.mjs execution-options --bundle <bundle-directory>
  node load-tests/k6/jiwon/tools/fixture.mjs hydrate --bundle <bundle-directory>
  node load-tests/k6/jiwon/tools/fixture.mjs diagnose --bundle <bundle-directory> --stage before|after
  node load-tests/k6/jiwon/tools/fixture.mjs aggregate --bundle <bundle-directory>

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

function portableBundleContext() {
  const bundleRoot = path.resolve(toolDirectory, '..');
  const isBundleRuntime = existsSync(path.join(bundleRoot, 'manifest.json'));
  return {
    repositoryRoot,
    scenarioDirectory: path.resolve(toolDirectory, '..'),
    buildRoot: isBundleRuntime ? path.dirname(path.dirname(bundleRoot)) : buildRoot,
    bundleRoot,
    isBundleRuntime,
    environment: process.env,
  };
}

function portableBundleCommand(command, values, context) {
  const result = executePortableBundleCommand(command, values, context);
  process.stdout.write(`${JSON.stringify(result)}\n`);
  if (result.status === 'INVALID') {
    process.exitCode = 2;
  } else if (result.status === 'FAIL') {
    process.exitCode = 1;
  }
}

function assertBundleRuntimeCommand(command, context) {
  if (context.isBundleRuntime && BUNDLE_RUNTIME_DIRECT_COMMANDS.has(command)) {
    fail('실행 bundle에서는 직접 실행 명령을 사용할 수 없습니다. '
      + 'validate, execution-options, hydrate, diagnose, aggregate만 사용하세요.');
  }
}

function requireEnvironment(name) {
  const value = (process.env[name] || '').trim();
  if (!value) {
    fail(`${name} 환경 변수가 필요합니다.`);
  }
  return value;
}

function assertInsideBuild(candidatePath) {
  const resolved = path.resolve(candidatePath);
  const relative = path.relative(buildRoot, resolved);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    fail(`fixture 경로는 ${buildRoot} 아래여야 합니다.`);
  }
  return resolved;
}

function commandWithPrefix(environment, executableVariable, prefixVariable, defaultExecutable) {
  const executable = String(environment[executableVariable] || defaultExecutable).trim();
  if (!executable) {
    fail(`${executableVariable}은 비어 있을 수 없습니다.`);
  }
  const rawPrefix = environment[prefixVariable];
  if (!rawPrefix) {
    return { executable, prefixArguments: [] };
  }
  let prefixArguments;
  try {
    prefixArguments = JSON.parse(rawPrefix);
  } catch (_) {
    fail(`${prefixVariable}는 string 배열 JSON이어야 합니다.`);
  }
  if (!Array.isArray(prefixArguments) || prefixArguments.some((argument) => typeof argument !== 'string' || !argument.trim())) {
    fail(`${prefixVariable}는 비어 있지 않은 string 배열 JSON이어야 합니다.`);
  }
  return { executable, prefixArguments };
}

function psql(psqlArgs, input = undefined) {
  const { executable, prefixArguments } = commandWithPrefix(
    process.env,
    'ROOM_K6_PSQL_EXECUTABLE',
    'ROOM_K6_PSQL_ARGUMENT_PREFIX',
    'psql',
  );
  const result = spawnSync(executable, [
    ...prefixArguments,
    '-X',
    '--no-psqlrc',
    '-v',
    'ON_ERROR_STOP=1',
    ...psqlArgs,
  ], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: process.env,
    input,
    shell: false,
  });

  if (result.error) {
    fail(`psql을 실행하지 못했습니다. PostgreSQL 연결 환경과 psql 설치를 확인하세요: ${result.error.message}`);
  }
  if (result.status !== 0) {
    fail(`psql 실행이 실패했습니다(exit=${result.status}). 비밀값을 출력하지 않으므로 대상 DB의 psql 오류를 직접 확인하세요.`);
  }
  return result.stdout || '';
}

function queryJson(sql) {
  const output = psql(['-q', '-A', '-t', '-c', sql]).trim();
  if (!output) {
    fail('fixture 조회 결과가 비어 있습니다.');
  }
  try {
    return JSON.parse(output);
  } catch (_) {
    fail('fixture 조회 결과가 JSON 형식이 아닙니다.');
  }
}

function assertCurrentFixtureSchema(fixture) {
  try {
    if (!fixture || typeof fixture !== 'object' || Array.isArray(fixture)
      || fixture.schemaVersion !== 2) {
      throw new Error('fixture schema mismatch');
    }
    normalizePrepareOwnership(fixture.prepareOwnership);
  } catch (_) {
    fail('fixture JSON 형식이 현재 schemaVersion=2와 맞지 않습니다. 새로 prepare한 fixture를 사용하세요.');
  }
}

function readFixture(rawPath) {
  const fixturePath = assertInsideBuild(rawPath);
  if (!existsSync(fixturePath)) {
    fail(`fixture 파일을 찾지 못했습니다: ${fixturePath}`);
  }
  let fixture;
  try {
    fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
  } catch (_) {
    fail(`fixture JSON을 읽을 수 없습니다: ${fixturePath}`);
  }
  assertCurrentFixtureSchema(fixture);
  return { fixturePath, fixture };
}

function hasExactKeys(value, expectedKeys) {
  return value
    && typeof value === 'object'
    && !Array.isArray(value)
    && isDeepStrictEqual(Object.keys(value).sort(), [...expectedKeys].sort());
}

function fixturePlanMismatch() {
  fail('fixture가 결정적 fixture plan과 맞지 않습니다. 새 run ID로 prepare한 fixture만 사용하세요.');
}

function assertFixtureMatchesPlan(fixturePath, fixture) {
  let plan;
  try {
    if (!fixture || typeof fixture !== 'object' || Array.isArray(fixture)
      || !fixture.options || typeof fixture.options !== 'object' || Array.isArray(fixture.options)) {
      fixturePlanMismatch();
    }
    const { fixtureId, ...options } = fixture.options;
    if (typeof fixtureId !== 'string') {
      fixturePlanMismatch();
    }
    normalizePrepareOwnership(fixture.prepareOwnership);
    plan = createFixturePlan(options);
  } catch (_) {
    fixturePlanMismatch();
  }

  const expectedPath = path.join(buildRoot, plan.options.runId, plan.fixtureId, 'fixture.json');
  if (fixturePath !== expectedPath
    || fixture.schemaVersion !== plan.schemaVersion
    || fixture.fixtureId !== plan.fixtureId
    || !isDeepStrictEqual(fixture.options, plan.options)
    || !hasExactKeys(fixture, [
      'schemaVersion', 'fixtureId', 'options', 'prepareOwnership', 'users', 'rooms', 'targets', 'sessionUserKeys', 'baselineSnapshot',
      ...(plan.fixturePartitions ? ['fixturePartitions'] : []),
      ...(plan.mixedProfile ? ['mixedProfile'] : []),
    ])
    || !fixture.baselineSnapshot || typeof fixture.baselineSnapshot !== 'object' || Array.isArray(fixture.baselineSnapshot)
    || !isDeepStrictEqual(fixture.targets, plan.targets)
    || !isDeepStrictEqual(fixture.sessionUserKeys, [...new Set(plan.sessionUserKeys)])
    || !isDeepStrictEqual(fixture.fixturePartitions, plan.fixturePartitions)
    || !isDeepStrictEqual(fixture.mixedProfile, plan.mixedProfile)) {
    fixturePlanMismatch();
  }

  if (!hasExactKeys(fixture.users, plan.users.map((user) => user.key))
    || !hasExactKeys(fixture.rooms, plan.rooms.map((room) => room.key))) {
    fixturePlanMismatch();
  }

  for (const user of plan.users) {
    const actual = fixture.users[user.key];
    if (!hasExactKeys(actual, ['id', 'email', 'nickname'])
      || !Number.isSafeInteger(actual.id) || actual.id <= 0
      || actual.email !== user.email
      || actual.nickname !== user.nickname) {
      fixturePlanMismatch();
    }
  }

  for (const room of plan.rooms) {
    const actual = fixture.rooms[room.key];
    if (!hasExactKeys(actual, [
      'id', 'title', 'hostKey', 'capacity', 'status', 'activeKeys', 'waiterKeys',
      'cancelKeys', 'candidateKeys', 'raceWaitKey',
    ])
      || !Number.isSafeInteger(actual.id) || actual.id <= 0
      || actual.title !== room.title
      || actual.hostKey !== room.hostKey
      || actual.capacity !== room.capacity
      || actual.status !== room.status
      || !isDeepStrictEqual(actual.activeKeys, room.activeKeys)
      || !isDeepStrictEqual(actual.waiterKeys, room.waiterKeys)
      || !isDeepStrictEqual(actual.cancelKeys, room.cancelKeys || [])
      || !isDeepStrictEqual(actual.candidateKeys, room.candidateKeys || [])
      || actual.raceWaitKey !== (room.raceWaitKey || null)) {
      fixturePlanMismatch();
    }
  }

  return plan;
}

function assertFixtureMatchesCurrentResources(fixturePath, fixture) {
  const plan = assertFixtureMatchesPlan(fixturePath, fixture);
  const currentFixture = hydrateFixture(
    plan,
    queryJson(buildResourceQuery(plan, fixture.prepareOwnership)),
    fixture.prepareOwnership,
  );

  if (!isDeepStrictEqual(fixture.users, currentFixture.users)
    || !isDeepStrictEqual(fixture.rooms, currentFixture.rooms)) {
    fail('fixture가 현재 DB resource identity와 맞지 않습니다. 새 run ID로 prepare한 fixture만 사용하세요.');
  }
}

function recoveryOptions(plan) {
  const { fixtureId, ...options } = plan.options;
  return options;
}

function writePrepareRecovery(outputDirectory, plan, prepareOwnership) {
  const recoveryPath = path.join(outputDirectory, PREPARE_RECOVERY_FILE);
  writeNewJson(recoveryPath, {
    schemaVersion: 2,
    fixtureId: plan.fixtureId,
    options: recoveryOptions(plan),
    prepareOwnership: normalizePrepareOwnership(prepareOwnership),
  });
  return recoveryPath;
}

function readPrepareRecovery(rawPath) {
  const recoveryPath = assertInsideBuild(rawPath);
  if (!existsSync(recoveryPath)) {
    fail(`prepare 복구 파일을 찾지 못했습니다: ${recoveryPath}`);
  }

  let recovery;
  try {
    recovery = JSON.parse(readFileSync(recoveryPath, 'utf8'));
  } catch (_) {
    fail(`prepare 복구 JSON을 읽을 수 없습니다: ${recoveryPath}`);
  }

  if (!recovery || recovery.schemaVersion !== 2 || typeof recovery.fixtureId !== 'string'
    || !recovery.options || typeof recovery.options !== 'object'
    || typeof recovery.prepareOwnership !== 'string') {
    fail('prepare 복구 파일 형식이 맞지 않습니다.');
  }

  const plan = createFixturePlan(recovery.options);
  const prepareOwnership = normalizePrepareOwnership(recovery.prepareOwnership);
  const expectedPath = path.join(
    buildRoot,
    plan.options.runId,
    plan.fixtureId,
    PREPARE_RECOVERY_FILE,
  );
  if (recovery.fixtureId !== plan.fixtureId || recoveryPath !== expectedPath) {
    fail('prepare 복구 파일이 결정적 fixture 식별자와 맞지 않습니다.');
  }
  return { recoveryPath, plan, prepareOwnership };
}

function writeJson(filePath, value) {
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function normalizeSummaryFile(summaryPath) {
  try {
    const summary = JSON.parse(readFileSync(summaryPath, 'utf8'));
    writeJson(summaryPath, normalizeRoomSummary(summary));
  } catch (_) {
    // 손상된 summary는 원본 artifact와 hash를 보존하고 after 검증에서 INVALID로 판정한다.
  }
}

function writeNewJson(filePath, value) {
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, { encoding: 'utf8', flag: 'wx' });
}

function sha256(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function requireSourceSha() {
  const sourceSha = requireEnvironment('ALBAM_MATE_SOURCE_SHA');
  if (!SOURCE_SHA_PATTERN.test(sourceSha)) {
    fail('ALBAM_MATE_SOURCE_SHA는 대상 배포본의 40자리 Git SHA여야 합니다.');
  }
  return sourceSha.toLowerCase();
}

function requireTargetEnvironment() {
  const targetEnvironment = requireEnvironment('ALBAM_MATE_TARGET_ENVIRONMENT');
  if (!TARGET_ENVIRONMENT_PATTERN.test(targetEnvironment)) {
    fail('ALBAM_MATE_TARGET_ENVIRONMENT는 영문 소문자 또는 숫자로 시작하는 80자 이하의 안전한 식별자여야 합니다.');
  }
  return targetEnvironment;
}

function runK6(k6Arguments, options = {}) {
  const { executable, prefixArguments } = commandWithPrefix(
    options.env || process.env,
    'ROOM_K6_EXECUTABLE',
    'ROOM_K6_ARGUMENT_PREFIX',
    'k6',
  );
  return spawn(executable, [...prefixArguments, ...k6Arguments], {
    ...options,
    shell: false,
  });
}

function runK6Sync(k6Arguments, options = {}) {
  const { executable, prefixArguments } = commandWithPrefix(
    options.env || process.env,
    'ROOM_K6_EXECUTABLE',
    'ROOM_K6_ARGUMENT_PREFIX',
    'k6',
  );
  return spawnSync(executable, [...prefixArguments, ...k6Arguments], {
    ...options,
    shell: false,
  });
}

function installTestInterruptWatcher(onInterrupt) {
  const signalFile = String(process.env.ROOM_K6_TEST_INTERRUPT_FILE || '').trim();
  if (!signalFile) {
    return null;
  }
  const watcher = setInterval(() => {
    if (!existsSync(signalFile)) {
      return;
    }
    let signal;
    try {
      signal = readFileSync(signalFile, 'utf8').trim();
    } catch (error) {
      if (error?.code === 'ENOENT') {
        return;
      }
      onInterrupt('SIGTERM');
      return;
    }
    if (signal === 'SIGINT' || signal === 'SIGTERM') {
      onInterrupt(signal);
    }
  }, 10);
  watcher.unref();
  return watcher;
}

function k6Version() {
  const result = runK6Sync(['version'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: process.env,
  });
  if (result.error || result.status !== 0) {
    fail('k6 version을 확인하지 못했습니다. k6 설치와 PATH를 확인하세요.');
  }
  const version = (result.stdout || '').trim().split(/\r?\n/, 1)[0];
  if (!version) {
    fail('k6 version 출력이 비어 있습니다.');
  }
  return version;
}

function scenarioScriptPath(fixture) {
  const scriptName = SCENARIO_SCRIPTS[fixture.options.scenario];
  if (!scriptName) {
    fail(`지원하지 않는 scenario: ${fixture.options.scenario}`);
  }
  const scriptPath = path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon', scriptName);
  if (!existsSync(scriptPath)) {
    fail(`scenario 스크립트를 찾지 못했습니다: ${scriptPath}`);
  }
  return scriptPath;
}

function runManifestPath(fixturePath) {
  return path.join(path.dirname(fixturePath), RUN_MANIFEST_FILE);
}

function runSummaryPath(fixturePath) {
  return path.join(path.dirname(fixturePath), RUN_SUMMARY_FILE);
}

function interruptedRunMessage(manifest, fixturePath) {
  const signal = typeof manifest.k6Signal === 'string' ? manifest.k6Signal : '알 수 없는';
  return `같은 fixture의 실행이 ${signal} 신호로 중단되었습니다. 중단 artifact를 보존하기 위해 재실행하지 않습니다. `
    + `fixture.mjs cleanup --fixture ${fixturePath}로 DB fixture를 안전하게 정리한 뒤 새 run ID로 prepare하세요.`;
}

function existingRunArtifactMessage(manifestPath, fixturePath) {
  try {
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    if (manifest.runState === 'INTERRUPTED') {
      return interruptedRunMessage(manifest, fixturePath);
    }
  } catch (_) {
    // 기존 artifact가 JSON이 아니어도 덮어쓰지 않는다.
  }
  return `같은 fixture의 실행 artifact가 이미 있습니다: ${path.dirname(fixturePath)}. 기존 결과를 덮어쓰지 말고 새 run ID를 사용하세요.`;
}

function waitForK6(k6Process) {
  return new Promise((resolve) => {
    let error = null;
    k6Process.once('error', (spawnError) => {
      error = spawnError;
    });
    k6Process.once('close', (status, signal) => {
      resolve({ status, signal, error });
    });
  });
}

function isUtcTimestamp(value) {
  return typeof value === 'string' && value.endsWith('Z') && !Number.isNaN(Date.parse(value));
}

function isT5ReadOptions(value) {
  return value
    && Number.isInteger(value.vus) && value.vus >= 1 && value.vus <= 500
    && Number.isInteger(value.durationSeconds) && value.durationSeconds >= 5 && value.durationSeconds <= 3600
    && Number.isInteger(value.thinkTimeMilliseconds) && value.thinkTimeMilliseconds >= 0 && value.thinkTimeMilliseconds <= 10000;
}

function isPortableSnapshot(value) {
  return value && typeof value === 'object' && !Array.isArray(value)
    && Array.isArray(value.rooms) && Array.isArray(value.participations) && Array.isArray(value.waitlists);
}

function completedRunArtifact(fixturePath, fixture) {
  const manifestPath = runManifestPath(fixturePath);
  if (!existsSync(manifestPath)) {
    return { failure: 'after 검증에는 fixture.mjs run이 남긴 run-manifest.json이 필요합니다.' };
  }

  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch (_) {
    return { failure: 'run-manifest.json을 읽을 수 없습니다.' };
  }

  if (manifest.runState === 'INTERRUPTED') {
    return { failure: interruptedRunMessage(manifest, fixturePath) };
  }
  if (manifest.runState !== 'COMPLETED' || manifest.completed !== true) {
    return { failure: 'run-manifest.json에 완료 lifecycle 기록이 없습니다.' };
  }

  const mixedProfileMatches = fixture.options.scenario === 'mixed'
    ? isDeepStrictEqual(manifest.mixedProfile, fixture.mixedProfile)
    : !Object.hasOwn(manifest, 'mixedProfile');

  if (manifest.schemaVersion !== 2
    || manifest.fixtureId !== fixture.fixtureId
    || manifest.runId !== fixture.options.runId
    || manifest.scenario !== fixture.options.scenario
    || !SOURCE_SHA_PATTERN.test(manifest.sourceSha || '')
    || !TARGET_ENVIRONMENT_PATTERN.test(manifest.targetEnvironment || '')
    || typeof manifest.k6Version !== 'string' || !manifest.k6Version
    || !isUtcTimestamp(manifest.startedAtUtc)
    || !isUtcTimestamp(manifest.finishedAtUtc)
    || Date.parse(manifest.finishedAtUtc) < Date.parse(manifest.startedAtUtc)
    || !Number.isInteger(manifest.k6ExitCode)
    || !SHA256_PATTERN.test(manifest.fixtureSha256 || '')
    || !SHA256_PATTERN.test(manifest.summarySha256 || '')
    || manifest.summaryFile !== RUN_SUMMARY_FILE
    || !mixedProfileMatches
    || (fixture.options.scenario === 't5' && !isT5ReadOptions(manifest.t5ReadOptions))) {
    return { failure: 'run-manifest.json이 현재 fixture의 완료된 실행 기록과 맞지 않습니다.' };
  }

  const summaryPath = runSummaryPath(fixturePath);
  if (!existsSync(summaryPath)) {
    return { failure: 'run-manifest.json이 가리키는 k6-summary.json을 찾지 못했습니다.' };
  }
  try {
    if (sha256(fixturePath) !== manifest.fixtureSha256) {
      return { failure: 'fixture.json의 SHA-256이 run-manifest.json과 다릅니다.' };
    }
    if (sha256(summaryPath) !== manifest.summarySha256) {
      return { failure: 'k6-summary.json의 SHA-256이 run-manifest.json과 다릅니다.' };
    }
    return { manifestPath, manifest, summary: JSON.parse(readFileSync(summaryPath, 'utf8')) };
  } catch (_) {
    return { failure: 'k6-summary.json을 읽을 수 없습니다.' };
  }
}

function t5ComparisonDirectory(runId) {
  if (!RUN_ID_PATTERN.test(runId)) {
    fail('runId는 영문 소문자 또는 숫자로 시작하는 80자 이하의 안전한 값이어야 합니다.');
  }
  const directory = assertInsideBuild(path.join(buildRoot, runId));
  if (!existsSync(directory)) {
    fail(`T5 비교 run ID 경로를 찾지 못했습니다: ${directory}`);
  }
  return directory;
}

function t5CaseKey(fixture) {
  return `${fixture.options.t5Role}-${fixture.options.t5Scale}`;
}

function sameT5ReadOptions(left, right) {
  return left.vus === right.vus
    && left.durationSeconds === right.durationSeconds
    && left.thinkTimeMilliseconds === right.thinkTimeMilliseconds;
}

function metricCount(summary, name) {
  const metric = summary?.metrics?.[name];
  const nestedCount = metric?.values?.count;
  if (nestedCount !== undefined) {
    return Number.isSafeInteger(nestedCount) && nestedCount >= 0 ? nestedCount : null;
  }
  const directCount = metric?.count;
  return Number.isSafeInteger(directCount) && directCount >= 0 ? directCount : null;
}

function t5StartSkewMetricFailure(summary, t5ReadOptions) {
  const observedCount = metricCount(summary, 'room_start_skew_ms');
  const expectedCount = t5ReadOptions.vus;
  if (!Number.isInteger(observedCount)) {
    return 'T5 room_start_skew_ms metric이 부족합니다.';
  }
  if (observedCount !== expectedCount) {
    return `T5 room_start_skew_ms 관측 수 ${observedCount}가 VU 수 ${expectedCount}과 다릅니다.`;
  }
  return null;
}

function readPortableArtifact(directory, relativePath) {
  const artifactPath = path.join(directory, relativePath);
  if (!existsSync(artifactPath)) {
    return { invalid: `portable T5 비교에는 ${relativePath}이 필요합니다.` };
  }
  try {
    return { value: JSON.parse(readFileSync(artifactPath, 'utf8')) };
  } catch (_) {
    return { invalid: `portable T5 ${relativePath}을 읽을 수 없습니다.` };
  }
}

function completedPortableT5Artifact(fixturePath, fixture, context) {
  const directory = path.dirname(fixturePath);
  try {
    executePortableBundleCommand('validate', { bundle: directory }, context);
  } catch (_) {
    return { invalid: 'portable bundle manifest 또는 immutable artifact 계약을 검증하지 못했습니다.' };
  }

  const artifacts = {};
  for (const relativePath of [
    'manifest.json',
    portableBundleArtifacts.executionOptions,
    portableBundleArtifacts.summary,
    portableBundleArtifacts.infraExecution,
    portableBundleArtifacts.beforeDiagnosis,
    portableBundleArtifacts.afterDiagnosis,
    portableBundleArtifacts.finalResult,
  ]) {
    const artifact = readPortableArtifact(directory, relativePath);
    if (artifact.invalid) {
      return artifact;
    }
    artifacts[relativePath] = artifact.value;
  }

  const manifest = artifacts['manifest.json'];
  if (manifest.fixtureId !== fixture.fixtureId
    || manifest.options?.runId !== fixture.options.runId
    || manifest.options?.scenario !== 't5'
    || !isDeepStrictEqual(manifest.options, fixture.options)) {
    return { invalid: 'portable manifest.json이 현재 T5 fixture와 맞지 않습니다.' };
  }

  const t5ReadOptions = artifacts[portableBundleArtifacts.executionOptions]?.t5ReadOptions;
  if (!isT5ReadOptions(t5ReadOptions)) {
    return { invalid: 'portable execution-options.json에 유효한 T5 read profile이 없습니다.' };
  }

  const summary = artifacts[portableBundleArtifacts.summary];
  if (!summary || typeof summary !== 'object' || Array.isArray(summary)
    || !summary.metrics || typeof summary.metrics !== 'object' || Array.isArray(summary.metrics)) {
    return { invalid: 'portable k6-summary.json 형식이 올바르지 않습니다.' };
  }

  const execution = artifacts[portableBundleArtifacts.infraExecution];
  if (!execution || typeof execution !== 'object' || Array.isArray(execution)
    || execution.schemaVersion !== 1
    || execution.runId !== fixture.options.runId
    || execution.fixtureId !== fixture.fixtureId
    || !execution.phases || typeof execution.phases !== 'object' || Array.isArray(execution.phases)
    || !PORTABLE_PHASE_NAMES.every((name) => Number.isInteger(execution.phases[name]?.exitCode))) {
    return { invalid: 'portable infra-execution.json이 현재 T5 fixture와 맞지 않습니다.' };
  }

  const beforeDiagnosis = artifacts[portableBundleArtifacts.beforeDiagnosis];
  const afterDiagnosis = artifacts[portableBundleArtifacts.afterDiagnosis];
  const matchesDiagnosis = (diagnosis, stage) => diagnosis && typeof diagnosis === 'object' && !Array.isArray(diagnosis)
    && diagnosis.fixtureId === fixture.fixtureId
    && diagnosis.scenario === 't5'
    && diagnosis.stage === stage
    && ['PASS', 'FAIL', 'INVALID'].includes(diagnosis.status)
    && Array.isArray(diagnosis.failures)
    && (diagnosis.status !== 'PASS' || diagnosis.failures.length === 0)
    && (stage !== 'before' || isPortableSnapshot(diagnosis.baselineSnapshot));
  if (!matchesDiagnosis(beforeDiagnosis, 'before') || !matchesDiagnosis(afterDiagnosis, 'after')) {
    return { invalid: 'portable diagnosis artifact가 현재 T5 fixture와 맞지 않습니다.' };
  }

  const finalResult = artifacts[portableBundleArtifacts.finalResult];
  const phaseCodes = PORTABLE_PHASE_NAMES.map((name) => execution.phases[name].exitCode);
  const expectedStatus = finalResult?.issues?.length > 0
    || beforeDiagnosis.status === 'INVALID'
    || afterDiagnosis.status === 'INVALID'
    ? 'INVALID'
    : beforeDiagnosis.status === 'FAIL' || afterDiagnosis.status === 'FAIL' || phaseCodes.some((code) => code !== 0)
      ? 'FAIL'
      : 'PASS';
  if (!finalResult || typeof finalResult !== 'object' || Array.isArray(finalResult)
    || finalResult.schemaVersion !== 1
    || finalResult.fixtureId !== fixture.fixtureId
    || finalResult.runId !== fixture.options.runId
    || finalResult.scenario !== 't5'
    || !Array.isArray(finalResult.issues)
    || !isDeepStrictEqual(finalResult.beforeDiagnosis, beforeDiagnosis)
    || !isDeepStrictEqual(finalResult.afterDiagnosis, afterDiagnosis)
    || !isDeepStrictEqual(finalResult.infraExecution, execution)
    || finalResult.status !== expectedStatus) {
    return { invalid: 'portable final-result.json이 현재 T5 실행 결과와 맞지 않습니다.' };
  }
  if (finalResult.status === 'INVALID') {
    return { invalid: 'portable final-result.json이 INVALID로 끝났습니다.' };
  }
  return {
    manifest: { k6ExitCode: execution.phases.k6.exitCode, t5ReadOptions },
    summary,
    afterArtifact: afterDiagnosis.status === 'PASS'
      ? { verification: afterDiagnosis }
      : { verification: afterDiagnosis, failed: 'portable after diagnosis가 FAIL로 끝났습니다.' },
    finalFailure: finalResult.status === 'FAIL' ? 'portable final-result.json이 FAIL로 끝났습니다.' : null,
  };
}

function completedT5Artifact(fixturePath, fixture, context) {
  if (existsSync(path.join(path.dirname(fixturePath), 'manifest.json'))) {
    const artifact = completedPortableT5Artifact(fixturePath, fixture, context);
    return artifact.invalid ? { failure: artifact.invalid } : artifact;
  }
  return completedRunArtifact(fixturePath, fixture);
}

function t5AfterVerificationArtifact(fixturePath, fixture) {
  const verificationPath = path.join(path.dirname(fixturePath), 'after-verification.json');
  if (!existsSync(verificationPath)) {
    return { invalid: 'T5 비교에는 after-verification.json이 필요합니다.' };
  }

  let verification;
  try {
    verification = JSON.parse(readFileSync(verificationPath, 'utf8'));
  } catch (_) {
    return { invalid: 'after-verification.json을 읽을 수 없습니다.' };
  }

  if (!verification || typeof verification !== 'object'
    || verification.fixtureId !== fixture.fixtureId
    || verification.scenario !== 't5'
    || verification.stage !== 'after'
    || !Array.isArray(verification.failures)) {
    return { invalid: 'after-verification.json이 현재 T5 fixture와 맞지 않습니다.' };
  }
  if (verification.status === 'PASS') {
    if (verification.failures.length !== 0) {
      return { invalid: 'after-verification.json이 PASS인데 failures가 비어 있지 않습니다.' };
    }
    return { verification };
  }
  if (verification.status === 'FAIL') {
    return { verification, failed: 'after 검증이 FAIL로 끝났습니다.' };
  }
  return { invalid: 'after-verification.json의 status가 PASS 또는 FAIL이 아닙니다.' };
}

function compareT5(values) {
  const runId = String(values.runId || '').trim();
  const outputDirectory = t5ComparisonDirectory(runId);
  const bundleContext = portableBundleContext();
  const fixturePaths = readdirSync(outputDirectory, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => path.join(outputDirectory, entry.name, 'fixture.json'))
    .filter((fixturePath) => existsSync(fixturePath))
    .sort();
  const failures = [];
  const fixturesByCase = new Map();
  let invalidArtifact = false;

  for (const candidatePath of fixturePaths) {
    const { fixturePath, fixture } = readFixture(candidatePath);
    if (fixture.options.scenario !== 't5') {
      continue;
    }
    if (fixture.options.runId !== runId) {
      failures.push(`T5 fixture ${fixture.fixtureId}의 runId가 비교 run ID와 다릅니다.`);
      continue;
    }

    const caseKey = t5CaseKey(fixture);
    if (fixturesByCase.has(caseKey)) {
      failures.push(`T5 ${caseKey} fixture가 비교 run ID에 둘 이상 있습니다.`);
      continue;
    }

    const runArtifact = completedT5Artifact(fixturePath, fixture, bundleContext);
    if (runArtifact.failure) {
      invalidArtifact = true;
      failures.push(`T5 ${caseKey}: ${runArtifact.failure}`);
      continue;
    }
    if (runArtifact.finalFailure) {
      failures.push(`T5 ${caseKey}: ${runArtifact.finalFailure}`);
    }
    if (runArtifact.manifest.k6ExitCode !== 0) {
      failures.push(`T5 ${caseKey}: k6 run이 exit=${runArtifact.manifest.k6ExitCode}로 종료되었습니다.`);
    }
    const startSkewFailure = t5StartSkewMetricFailure(runArtifact.summary, runArtifact.manifest.t5ReadOptions);
    if (startSkewFailure) {
      failures.push(`T5 ${caseKey}: ${startSkewFailure}`);
      continue;
    }
    const afterArtifact = runArtifact.afterArtifact || t5AfterVerificationArtifact(fixturePath, fixture);
    if (afterArtifact.invalid) {
      invalidArtifact = true;
      failures.push(`T5 ${caseKey}: ${afterArtifact.invalid}`);
      continue;
    }
    if (afterArtifact.failed) {
      failures.push(`T5 ${caseKey}: ${afterArtifact.failed}`);
    }
    fixturesByCase.set(caseKey, { fixture, manifest: runArtifact.manifest });
  }

  const expectedCases = T5_ROLES.flatMap((role) => T5_SCALES.map((scale) => `${role}-${scale}`));
  for (const expectedCase of expectedCases) {
    if (!fixturesByCase.has(expectedCase)) {
      failures.push(`T5 비교에 필요한 ${expectedCase} fixture가 없습니다.`);
    }
  }

  let readOptions = null;
  for (const expectedCase of expectedCases) {
    const entry = fixturesByCase.get(expectedCase);
    if (!entry) {
      continue;
    }
    if (!readOptions) {
      readOptions = entry.manifest.t5ReadOptions;
      continue;
    }
    if (!sameT5ReadOptions(readOptions, entry.manifest.t5ReadOptions)) {
      failures.push(`T5 ${expectedCase}의 read profile이 다른 T5 실행과 다릅니다.`);
    }
  }

  const result = {
    runId,
    scenario: 't5',
    status: invalidArtifact ? 'INVALID' : failures.length === 0 ? 'PASS' : 'FAIL',
    t5ReadOptions: readOptions,
    fixtureCount: fixturesByCase.size,
    failures,
  };
  writeJson(path.join(outputDirectory, T5_COMPARISON_FILE), result);
  process.stdout.write(`${JSON.stringify(result)}\n`);
  if (result.status === 'INVALID') {
    process.exitCode = 2;
  } else if (result.status !== 'PASS') {
    process.exitCode = 1;
  }
}

function prepare(values) {
  const passwordHash = requireEnvironment('ROOM_K6_FIXTURE_PASSWORD_HASH');
  const plan = createFixturePlan(values);
  const prepareOwnership = randomUUID().replaceAll('-', '');
  const outputDirectory = assertInsideBuild(path.join(buildRoot, plan.options.runId, plan.fixtureId));
  if (existsSync(outputDirectory)) {
    fail(`같은 run ID·scenario fixture가 이미 있습니다: ${outputDirectory}. 기존 fixture를 교체하지 말고 새 run ID를 사용하거나 명시적으로 cleanup하세요.`);
  }

  mkdirSync(path.dirname(outputDirectory), { recursive: true });
  mkdirSync(outputDirectory);

  const preparePath = path.join(outputDirectory, 'prepare.sql');
  writeFileSync(preparePath, buildPrepareSql(plan, passwordHash, prepareOwnership), 'utf8');
  const recoveryPath = writePrepareRecovery(outputDirectory, plan, prepareOwnership);

  try {
    psql(['-q', '-f', preparePath]);
    const fixture = hydrateFixture(
      plan,
      queryJson(buildResourceQuery(plan, prepareOwnership)),
      prepareOwnership,
    );
    fixture.baselineSnapshot = queryJson(buildSnapshotQuery(fixture));
    const fixturePath = path.join(outputDirectory, 'fixture.json');
    writeJson(fixturePath, fixture);
    writeFileSync(path.join(outputDirectory, 'cleanup.sql'), buildCleanupSql(fixture), 'utf8');

    const before = evaluateFixture(fixture, fixture.baselineSnapshot, 'before');
    writeJson(path.join(outputDirectory, 'before-verification.json'), before);
    if (before.status !== 'PASS') {
      fail(`fixture 사전 검증이 실패했습니다: ${before.failures.join(' | ')}. 정리가 필요하면 ${fixturePath}를 사용하세요.`);
    }

    process.stdout.write(`${JSON.stringify({
      fixturePath,
      fixtureId: fixture.fixtureId,
      scenario: fixture.options.scenario,
      options: fixture.options,
      outputDirectory,
    })}\n`);
  } catch (error) {
    fail(`${error.message}\nfixture 준비가 DB commit 뒤에 중단되었을 수 있습니다. `
      + `recover-cleanup --recovery ${recoveryPath}로 안전한 정리를 시도하세요.`);
  }
}

async function run(values) {
  const { fixturePath, fixture } = readFixture(values.fixture);
  const manifestPath = runManifestPath(fixturePath);
  const summaryPath = runSummaryPath(fixturePath);
  if (existsSync(manifestPath) || existsSync(summaryPath)) {
    fail(existingRunArtifactMessage(manifestPath, fixturePath));
  }

  const fixtureSha256 = sha256(fixturePath);
  assertFixtureMatchesCurrentResources(fixturePath, fixture);
  if (sha256(fixturePath) !== fixtureSha256) {
    fail('fixture.json이 실행 전 검증 중 바뀌었습니다. 새 run ID로 prepare한 fixture만 사용하세요.');
  }
  const sourceSha = requireSourceSha();
  const targetEnvironment = requireTargetEnvironment();
  const t5ReadOptions = fixture.options.scenario === 't5'
    ? readExecutionOptions(process.env)
    : null;
  const mixedProfile = fixture.options.scenario === 'mixed'
    ? fixture.mixedProfile
    : null;
  const version = k6Version();
  const scriptPath = scenarioScriptPath(fixture);
  const manifest = {
    schemaVersion: 2,
    fixtureId: fixture.fixtureId,
    runId: fixture.options.runId,
    scenario: fixture.options.scenario,
    sourceSha,
    targetEnvironment,
    k6Version: version,
    startedAtUtc: new Date().toISOString(),
    finishedAtUtc: null,
    runState: 'RUNNING',
    completed: false,
    k6ExitCode: null,
    fixtureSha256,
    summaryFile: RUN_SUMMARY_FILE,
    summarySha256: null,
  };
  if (t5ReadOptions) {
    manifest.t5ReadOptions = t5ReadOptions;
  }
  if (mixedProfile) {
    manifest.mixedProfile = mixedProfile;
  }
  let k6Process = null;
  let interruptedSignal = null;
  let testInterruptWatcher = null;
  const interruptK6 = (signal) => {
    if (interruptedSignal) {
      return;
    }
    interruptedSignal = signal;
    if (k6Process && k6Process.exitCode === null && k6Process.signalCode === null) {
      k6Process.kill(signal);
    }
  };

  try {
    process.on('SIGINT', interruptK6);
    process.on('SIGTERM', interruptK6);
    writeNewJson(manifestPath, manifest);
    testInterruptWatcher = installTestInterruptWatcher(interruptK6);

    const k6Environment = {
      ...process.env,
      ALBAM_MATE_RUN_ID: fixture.options.runId,
      ROOM_K6_FIXTURE: fixturePath,
    };
    if (t5ReadOptions) {
      k6Environment.ROOM_K6_READ_VUS = String(t5ReadOptions.vus);
      k6Environment.ROOM_K6_READ_DURATION_SECONDS = String(t5ReadOptions.durationSeconds);
      k6Environment.ROOM_K6_READ_THINK_TIME_MS = String(t5ReadOptions.thinkTimeMilliseconds);
    }

    k6Process = runK6(['run', '--summary-export', summaryPath, scriptPath], {
      cwd: repositoryRoot,
      env: k6Environment,
      stdio: 'inherit',
    });
    if (interruptedSignal && k6Process.exitCode === null && k6Process.signalCode === null) {
      k6Process.kill(interruptedSignal);
    }

    const result = await waitForK6(k6Process);
    const k6Signal = interruptedSignal || result.signal;
    manifest.finishedAtUtc = new Date().toISOString();
    manifest.k6ExitCode = Number.isInteger(result.status) ? result.status : null;
    if (k6Signal) {
      manifest.k6Signal = k6Signal;
    }
    if (result.error) {
      manifest.k6Error = result.error.message;
    }
    if (existsSync(summaryPath)) {
      normalizeSummaryFile(summaryPath);
      manifest.summarySha256 = sha256(summaryPath);
    }
    manifest.runState = k6Signal ? 'INTERRUPTED' : result.error ? 'FAILED_TO_START' : 'COMPLETED';
    manifest.completed = manifest.runState === 'COMPLETED';
    writeJson(manifestPath, manifest);

    if (k6Signal) {
      process.stderr.write(`${interruptedRunMessage(manifest, fixturePath)}\n`);
      process.exitCode = k6Signal === 'SIGINT' ? 130 : k6Signal === 'SIGTERM' ? 143 : 1;
      return;
    }
    if (result.error) {
      fail(`k6 실행을 시작하지 못했습니다. k6 설치와 PATH를 확인하세요: ${result.error.message}`);
    }
    if (manifest.k6ExitCode === null) {
      fail(`k6 실행이 ${manifest.k6Signal || '알 수 없는 이유'}로 끝났습니다.`);
    }

    process.stdout.write(`${JSON.stringify({
      fixtureId: fixture.fixtureId,
      scenario: fixture.options.scenario,
      manifestPath,
      summaryPath,
      k6ExitCode: manifest.k6ExitCode,
    })}\n`);
    if (manifest.k6ExitCode !== 0) {
      process.exitCode = manifest.k6ExitCode;
    }
  } finally {
    if (testInterruptWatcher) {
      clearInterval(testInterruptWatcher);
    }
    process.off('SIGINT', interruptK6);
    process.off('SIGTERM', interruptK6);
  }
}

function verify(values) {
  const stage = String(values.stage || '').trim();
  if (stage !== 'before' && stage !== 'after') {
    fail('--stage는 before 또는 after여야 합니다.');
  }
  const { fixturePath, fixture } = readFixture(values.fixture);
  let summary = null;
  let runManifest = null;
  if (stage === 'after') {
    const runArtifact = completedRunArtifact(fixturePath, fixture);
    if (runArtifact.failure) {
      const result = {
        fixtureId: fixture.fixtureId,
        scenario: fixture.options.scenario,
        stage,
        status: 'INVALID',
        failures: [runArtifact.failure],
      };
      writeJson(path.join(path.dirname(fixturePath), `${stage}-verification.json`), result);
      process.stdout.write(`${JSON.stringify(result)}\n`);
      process.exitCode = 2;
      return;
    }
    summary = runArtifact.summary;
    runManifest = runArtifact.manifest;
  }

  assertFixtureMatchesCurrentResources(fixturePath, fixture);
  const snapshot = queryJson(buildSnapshotQuery(fixture));
  const evaluation = evaluateFixture(fixture, snapshot, stage, summary);
  const failures = [...evaluation.failures];
  if (runManifest && runManifest.k6ExitCode !== 0) {
    failures.push(`k6 run이 exit=${runManifest.k6ExitCode}로 종료되었습니다.`);
  }
  if (runManifest && fixture.options.scenario === 't5') {
    const startSkewFailure = t5StartSkewMetricFailure(summary, runManifest.t5ReadOptions);
    if (startSkewFailure) {
      failures.push(startSkewFailure);
    }
  }
  const result = {
    fixtureId: fixture.fixtureId,
    scenario: fixture.options.scenario,
    stage,
    status: evaluation.status === 'INVALID' ? 'INVALID' : failures.length === 0 ? evaluation.status : 'FAIL',
    failures,
  };
  writeJson(path.join(path.dirname(fixturePath), `${stage}-verification.json`), result);
  process.stdout.write(`${JSON.stringify(result)}\n`);
  if (result.status === 'INVALID') {
    process.exitCode = 2;
  } else if (result.status !== 'PASS') {
    process.exitCode = 1;
  }
}

function cleanup(values) {
  const { fixturePath, fixture } = readFixture(values.fixture);
  assertFixtureMatchesPlan(fixturePath, fixture);
  psql(['-q', '-f', '-'], buildCleanupSql(fixture));
  process.stdout.write(`${JSON.stringify({ fixtureId: fixture.fixtureId, status: 'CLEANED' })}\n`);
}

function recoverCleanup(values) {
  const { recoveryPath, plan, prepareOwnership } = readPrepareRecovery(values.recovery);
  const fixture = hydrateFixture(
    plan,
    queryJson(buildResourceQuery(plan, prepareOwnership)),
    prepareOwnership,
  );
  psql(['-q', '-f', '-'], buildCleanupSql(fixture));
  process.stdout.write(`${JSON.stringify({
    fixtureId: fixture.fixtureId,
    recoveryPath,
    status: 'RECOVERED',
  })}\n`);
}

async function main() {
  const { command, values } = parseArguments(process.argv.slice(2));
  const bundleContext = portableBundleContext();
  assertBundleRuntimeCommand(command, bundleContext);
  switch (command) {
    case 'help':
      process.stdout.write(usage());
      return;
    case 'prepare':
      prepare(values);
      return;
    case 'run':
      await run(values);
      return;
    case 'verify':
      verify(values);
      return;
    case 'compare-t5':
      compareT5(values);
      return;
    case 'cleanup':
      cleanup(values);
      return;
    case 'recover-cleanup':
      recoverCleanup(values);
      return;
    case 'render-bundle':
    case 'validate':
    case 'execution-options':
    case 'hydrate':
    case 'diagnose':
    case 'aggregate':
      portableBundleCommand(command, values, bundleContext);
      return;
    default:
      fail(`지원하지 않는 명령: ${command}\n\n${usage()}`);
  }
}

main().catch((error) => {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
});
