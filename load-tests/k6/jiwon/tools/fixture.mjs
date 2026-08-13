#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  buildCleanupSql,
  buildPrepareSql,
  buildResourceQuery,
  buildSnapshotQuery,
  createFixturePlan,
  evaluateFixture,
  hydrateFixture,
} from './fixture-model.mjs';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '../../../..');
const buildRoot = path.join(repositoryRoot, 'build', 'k6', 'room');
const RUN_MANIFEST_FILE = 'run-manifest.json';
const RUN_SUMMARY_FILE = 'k6-summary.json';
const SOURCE_SHA_PATTERN = /^[0-9a-f]{40}$/i;
const SHA256_PATTERN = /^[0-9a-f]{64}$/i;
const TARGET_ENVIRONMENT_PATTERN = /^[a-z0-9][a-z0-9._-]{0,79}$/;
const SCENARIO_SCRIPTS = {
  t1: 't1-cancel-promotion.js',
  t2: 't2-concurrent-waitlist-registration.js',
  t3: 't3-waitlist-cancel-race.js',
  t4: 't4-last-seat-participation.js',
  t5: 't5-room-detail-by-role.js',
};
const COMMAND_OPTION_KEYS = {
  prepare: new Set([
    'scenario', 'runId', 'profile', 'rounds', 'mode', 'concurrency', 'subcase', 't3Mode', 't5Role', 't5Scale',
  ]),
  run: new Set(['fixture']),
  verify: new Set(['fixture', 'stage']),
  cleanup: new Set(['fixture']),
};

function usage() {
  return `사용법:
  node load-tests/k6/jiwon/tools/fixture.mjs prepare --scenario t1 --run-id <run-id> [옵션]
  node load-tests/k6/jiwon/tools/fixture.mjs run --fixture <fixture.json>
  node load-tests/k6/jiwon/tools/fixture.mjs verify --fixture <fixture.json> --stage before|after
  node load-tests/k6/jiwon/tools/fixture.mjs cleanup --fixture <fixture.json>

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

function assertInsideBuild(candidatePath) {
  const resolved = path.resolve(candidatePath);
  const relative = path.relative(buildRoot, resolved);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    fail(`fixture 경로는 ${buildRoot} 아래여야 합니다.`);
  }
  return resolved;
}

function psql(psqlArgs, input = undefined) {
  const result = spawnSync('psql', ['-X', '--no-psqlrc', '-v', 'ON_ERROR_STOP=1', ...psqlArgs], {
    cwd: repositoryRoot,
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

function readFixture(rawPath) {
  const fixturePath = assertInsideBuild(rawPath);
  if (!existsSync(fixturePath)) {
    fail(`fixture 파일을 찾지 못했습니다: ${fixturePath}`);
  }
  try {
    return { fixturePath, fixture: JSON.parse(readFileSync(fixturePath, 'utf8')) };
  } catch (_) {
    fail(`fixture JSON을 읽을 수 없습니다: ${fixturePath}`);
  }
}

function writeJson(filePath, value) {
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
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
  return spawnSync('k6', k6Arguments, {
    ...options,
    shell: process.platform === 'win32',
  });
}

function k6Version() {
  const result = runK6(['version'], {
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

function isUtcTimestamp(value) {
  return typeof value === 'string' && value.endsWith('Z') && !Number.isNaN(Date.parse(value));
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

  if (manifest.schemaVersion !== 1
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
    || !SHA256_PATTERN.test(manifest.summarySha256 || '')
    || manifest.summaryFile !== RUN_SUMMARY_FILE) {
    return { failure: 'run-manifest.json이 현재 fixture의 완료된 실행 기록과 맞지 않습니다.' };
  }

  const summaryPath = runSummaryPath(fixturePath);
  if (!existsSync(summaryPath)) {
    return { failure: 'run-manifest.json이 가리키는 k6-summary.json을 찾지 못했습니다.' };
  }
  try {
    if (sha256(summaryPath) !== manifest.summarySha256) {
      return { failure: 'k6-summary.json의 SHA-256이 run-manifest.json과 다릅니다.' };
    }
    return { manifestPath, manifest, summary: JSON.parse(readFileSync(summaryPath, 'utf8')) };
  } catch (_) {
    return { failure: 'k6-summary.json을 읽을 수 없습니다.' };
  }
}

function prepare(values) {
  const passwordHash = requireEnvironment('ROOM_K6_FIXTURE_PASSWORD_HASH');
  const plan = createFixturePlan(values);
  const outputDirectory = assertInsideBuild(path.join(buildRoot, plan.options.runId, plan.fixtureId));
  if (existsSync(outputDirectory)) {
    fail(`같은 run ID·scenario fixture가 이미 있습니다: ${outputDirectory}. 기존 fixture를 교체하지 말고 새 run ID를 사용하거나 명시적으로 cleanup하세요.`);
  }

  mkdirSync(path.dirname(outputDirectory), { recursive: true });
  mkdirSync(outputDirectory);

  const preparePath = path.join(outputDirectory, 'prepare.sql');
  writeFileSync(preparePath, buildPrepareSql(plan, passwordHash), 'utf8');
  psql(['-q', '-f', preparePath]);

  const fixture = hydrateFixture(plan, queryJson(buildResourceQuery(plan)));
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
}

function run(values) {
  const { fixturePath, fixture } = readFixture(values.fixture);
  const manifestPath = runManifestPath(fixturePath);
  const summaryPath = runSummaryPath(fixturePath);
  if (existsSync(manifestPath) || existsSync(summaryPath)) {
    fail(`같은 fixture의 실행 artifact가 이미 있습니다: ${path.dirname(fixturePath)}. 기존 결과를 덮어쓰지 말고 새 run ID를 사용하세요.`);
  }

  const sourceSha = requireSourceSha();
  const targetEnvironment = requireTargetEnvironment();
  const version = k6Version();
  const scriptPath = scenarioScriptPath(fixture);
  const manifest = {
    schemaVersion: 1,
    fixtureId: fixture.fixtureId,
    runId: fixture.options.runId,
    scenario: fixture.options.scenario,
    sourceSha,
    targetEnvironment,
    k6Version: version,
    startedAtUtc: new Date().toISOString(),
    finishedAtUtc: null,
    k6ExitCode: null,
    summaryFile: RUN_SUMMARY_FILE,
    summarySha256: null,
  };
  writeNewJson(manifestPath, manifest);

  const result = runK6(['run', '--summary-export', summaryPath, scriptPath], {
    cwd: repositoryRoot,
    env: {
      ...process.env,
      ALBAM_MATE_RUN_ID: fixture.options.runId,
      ROOM_K6_FIXTURE: fixturePath,
    },
    stdio: 'inherit',
  });
  manifest.finishedAtUtc = new Date().toISOString();
  manifest.k6ExitCode = Number.isInteger(result.status) ? result.status : null;
  if (result.signal) {
    manifest.k6Signal = result.signal;
  }
  if (result.error) {
    manifest.k6Error = result.error.message;
  }
  if (existsSync(summaryPath)) {
    manifest.summarySha256 = sha256(summaryPath);
  }
  writeJson(manifestPath, manifest);

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

  const snapshot = queryJson(buildSnapshotQuery(fixture));
  const evaluation = evaluateFixture(fixture, snapshot, stage, summary);
  const failures = [...evaluation.failures];
  if (runManifest && runManifest.k6ExitCode !== 0) {
    failures.push(`k6 run이 exit=${runManifest.k6ExitCode}로 종료되었습니다.`);
  }
  const result = {
    fixtureId: fixture.fixtureId,
    scenario: fixture.options.scenario,
    stage,
    status: failures.length === 0 ? evaluation.status : 'FAIL',
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
  const { fixture } = readFixture(values.fixture);
  psql(['-q', '-f', '-'], buildCleanupSql(fixture));
  process.stdout.write(`${JSON.stringify({ fixtureId: fixture.fixtureId, status: 'CLEANED' })}\n`);
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
    case 'run':
      run(values);
      return;
    case 'verify':
      verify(values);
      return;
    case 'cleanup':
      cleanup(values);
      return;
    default:
      fail(`지원하지 않는 명령: ${command}\n\n${usage()}`);
  }
}

try {
  main();
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
}
