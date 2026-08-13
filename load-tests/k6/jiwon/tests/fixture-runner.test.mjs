import assert from 'node:assert/strict';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { createHash } from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';

import { createFixturePlan, hydrateFixture } from '../tools/fixture-model.mjs';

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(testDirectory, '../../../..');
const fixtureTool = path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon', 'tools', 'fixture.mjs');
const fixtureBuildRoot = path.join(repositoryRoot, 'build', 'k6', 'room');
const roomK6Library = path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon', 'lib', 'room-k6.js');
const t5Script = path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon', 't5-room-detail-by-role.js');

function createFakeK6(binDirectory) {
  if (process.platform === 'win32') {
    const programPath = path.join(binDirectory, 'fake-k6.mjs');
    writeFileSync(programPath, `import { writeFileSync } from 'node:fs';

const [command, ...args] = process.argv.slice(2);
if (command === 'version') {
  process.stdout.write('k6 v0.0.0-test\\n');
  process.exit(0);
}

if (command !== 'run') {
  process.exit(2);
}
const summaryIndex = args.indexOf('--summary-export');
if (summaryIndex < 0 || !args[summaryIndex + 1]) {
  process.exit(2);
}
const summary = { metrics: {} };
if (process.env.FAKE_K6_CAPTURE_READ_OPTIONS === 'true') {
  summary.t5ReadOptions = {
    vus: process.env.ROOM_K6_READ_VUS,
    durationSeconds: process.env.ROOM_K6_READ_DURATION_SECONDS,
    thinkTimeMilliseconds: process.env.ROOM_K6_READ_THINK_TIME_MS,
  };
}
if (process.env.FAKE_K6_WAIT_FOR_SIGNAL === 'true') {
  if (process.env.FAKE_K6_STARTED_FILE) {
    writeFileSync(process.env.FAKE_K6_STARTED_FILE, 'started\\n', 'utf8');
  }
  if (process.env.FAKE_K6_PID_FILE) {
    writeFileSync(process.env.FAKE_K6_PID_FILE, String(process.pid), 'utf8');
  }
  setInterval(() => {}, 1_000);
} else {
  writeFileSync(args[summaryIndex + 1], JSON.stringify(summary) + '\\n', 'utf8');
  process.exit(Number.parseInt(process.env.FAKE_K6_EXIT || '0', 10));
}
`, 'utf8');
    writeFileSync(
      path.join(binDirectory, 'k6.cmd'),
      `@echo off\r\n"${process.execPath}" "${programPath}" %*\r\n`,
      'utf8',
    );
    return;
  }

  const programPath = path.join(binDirectory, 'fake-k6.mjs');
  writeFileSync(programPath, `import { writeFileSync } from 'node:fs';

const [command, ...args] = process.argv.slice(2);
if (command === 'version') {
  process.stdout.write('k6 v0.0.0-test\\n');
  process.exit(0);
}
if (command !== 'run') {
  process.exit(2);
}
const summaryIndex = args.indexOf('--summary-export');
if (summaryIndex < 0 || !args[summaryIndex + 1]) {
  process.exit(2);
}
const summary = { metrics: {} };
if (process.env.FAKE_K6_CAPTURE_READ_OPTIONS === 'true') {
  summary.t5ReadOptions = {
    vus: process.env.ROOM_K6_READ_VUS,
    durationSeconds: process.env.ROOM_K6_READ_DURATION_SECONDS,
    thinkTimeMilliseconds: process.env.ROOM_K6_READ_THINK_TIME_MS,
  };
}
if (process.env.FAKE_K6_WAIT_FOR_SIGNAL === 'true') {
  if (process.env.FAKE_K6_STARTED_FILE) {
    writeFileSync(process.env.FAKE_K6_STARTED_FILE, 'started\\n', 'utf8');
  }
  if (process.env.FAKE_K6_PID_FILE) {
    writeFileSync(process.env.FAKE_K6_PID_FILE, String(process.pid), 'utf8');
  }
  setInterval(() => {}, 1_000);
} else {
  writeFileSync(args[summaryIndex + 1], JSON.stringify(summary) + '\\n', 'utf8');
  process.exit(Number.parseInt(process.env.FAKE_K6_EXIT || '0', 10));
}
`, 'utf8');

  const executablePath = path.join(binDirectory, 'k6');
  writeFileSync(executablePath, `#!/usr/bin/env node\nimport '${programPath.replaceAll('\\\\', '\\\\\\')}';\n`, 'utf8');
  chmodSync(executablePath, 0o755);
}

function createFakePsql(binDirectory) {
  const programPath = path.join(binDirectory, 'fake-psql.mjs');
  writeFileSync(programPath, `import { writeFileSync } from 'node:fs';

const args = process.argv.slice(2);
const queryIndex = args.indexOf('-c');
if (queryIndex >= 0) {
  if (process.env.FAKE_PSQL_FAIL_QUERY === 'true') {
    process.stderr.write('simulated query failure\\n');
    process.exit(1);
  }
  process.stdout.write(process.env.FAKE_PSQL_QUERY_RESULT || '{}');
  process.exit(0);
}

const fileIndex = args.indexOf('-f');
if (fileIndex >= 0 && args[fileIndex + 1] !== '-') {
  if (process.env.FAKE_PSQL_FAIL_PREPARE === 'true') {
    process.stderr.write('simulated prepare failure\\n');
    process.exit(1);
  }
  process.exit(0);
}

if (fileIndex >= 0 && args[fileIndex + 1] === '-') {
  let input = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    input += chunk;
  });
  process.stdin.on('end', () => {
    if (process.env.FAKE_PSQL_CAPTURE_PATH) {
      writeFileSync(process.env.FAKE_PSQL_CAPTURE_PATH, input, 'utf8');
    }
    process.exit(0);
  });
} else {
  process.exit(0);
}
`, 'utf8');

  const executablePath = path.join(binDirectory, 'psql');
  writeFileSync(executablePath, `#!/usr/bin/env node\nimport ${JSON.stringify(pathToFileURL(programPath).href)};\n`, 'utf8');
  chmodSync(executablePath, 0o755);
}

function writeFixture(directory, runId) {
  const fixturePath = path.join(directory, 'fixture.json');
  writeFileSync(fixturePath, `${JSON.stringify({
    schemaVersion: 1,
    fixtureId: `room-k6-${runId}-t1`,
    options: { scenario: 't1', runId, profile: 'stress', rounds: 1 },
    users: {},
    rooms: {},
  }, null, 2)}\n`, 'utf8');
  return fixturePath;
}

function writeT5Fixture(directory, runId, role, scale) {
  const fixturePath = path.join(directory, 'fixture.json');
  writeFileSync(fixturePath, `${JSON.stringify({
    schemaVersion: 1,
    fixtureId: `room-k6-${runId}-t5-${role}-${scale}`,
    options: {
      scenario: 't5', runId, profile: 'stress', rounds: 1, t5Role: role, t5Scale: scale,
    },
    users: {},
    rooms: {},
    baselineSnapshot: { rooms: [], participations: [], waitlists: [] },
  }, null, 2)}\n`, 'utf8');
  return fixturePath;
}

function writeBoundSummary(fixtureDirectory, summary) {
  const summaryPath = path.join(fixtureDirectory, 'k6-summary.json');
  writeFileSync(summaryPath, `${JSON.stringify(summary)}\n`, 'utf8');
  const manifestPath = path.join(fixtureDirectory, 'run-manifest.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  manifest.summarySha256 = sha256(summaryPath);
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
}

function t5Summary(startSkewCount) {
  const metric = (count) => ({ values: { count } });
  return {
    metrics: {
      room_requests: metric(1),
      room_success: metric(1),
      room_business_failures: metric(0),
      room_concurrent_failures: metric(0),
      room_contract_failures: metric(0),
      room_unexpected_4xx: metric(0),
      room_server_failures: metric(0),
      room_start_skew_ms: metric(startSkewCount),
    },
  };
}

function writeCleanupFixture(runId, idOffset) {
  const plan = createFixturePlan({
    scenario: 't1',
    runId,
    profile: 'spike',
    mode: 'hot',
    concurrency: 2,
  });
  const fixtureDirectory = path.join(fixtureBuildRoot, plan.options.runId, plan.fixtureId);
  const resources = {
    users: Object.fromEntries(plan.users.map((user, index) => [user.email, idOffset + index])),
    rooms: Object.fromEntries(plan.rooms.map((room, index) => [room.title, idOffset + 100 + index])),
  };
  const fixture = hydrateFixture(plan, resources);
  fixture.baselineSnapshot = { rooms: [], participations: [], waitlists: [] };
  const fixturePath = path.join(fixtureDirectory, 'fixture.json');
  mkdirSync(fixtureDirectory, { recursive: true });
  writeFileSync(fixturePath, `${JSON.stringify(fixture, null, 2)}\n`, 'utf8');
  return { fixtureDirectory, fixturePath, fixture };
}

function writeT5AfterVerification(fixturePath, status = 'PASS', overrides = {}) {
  const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
  const verification = {
    fixtureId: fixture.fixtureId,
    scenario: 't5',
    stage: 'after',
    status,
    failures: status === 'PASS' ? [] : ['simulated after verification failure'],
    ...overrides,
  };
  writeFileSync(
    path.join(path.dirname(fixturePath), 'after-verification.json'),
    `${JSON.stringify(verification, null, 2)}\n`,
    'utf8',
  );
}

function runFixture(fixturePath, binDirectory, extraEnvironment = {}) {
  return spawnSync(process.execPath, [fixtureTool, 'run', '--fixture', fixturePath], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${binDirectory}${path.delimiter}${process.env.PATH || ''}`,
      ALBAM_MATE_SOURCE_SHA: 'a'.repeat(40),
      ALBAM_MATE_TARGET_ENVIRONMENT: 'private-loadtest',
      ...extraEnvironment,
    },
  });
}

function startFixture(fixturePath, binDirectory, extraEnvironment = {}) {
  return spawn(process.execPath, [fixtureTool, 'run', '--fixture', fixturePath], {
    cwd: repositoryRoot,
    env: {
      ...process.env,
      PATH: `${binDirectory}${path.delimiter}${process.env.PATH || ''}`,
      ALBAM_MATE_SOURCE_SHA: 'a'.repeat(40),
      ALBAM_MATE_TARGET_ENVIRONMENT: 'private-loadtest',
      ...extraEnvironment,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function waitForFixtureExit(child) {
  return new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.once('error', reject);
    child.once('close', (status, signal) => {
      resolve({ status, signal, stdout, stderr });
    });
  });
}

function waitForFile(filePath, timeoutMillis = 3_000) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMillis;
    const wait = () => {
      if (existsSync(filePath)) {
        resolve();
        return;
      }
      if (Date.now() >= deadline) {
        reject(new Error(`파일 생성 대기 시간이 초과되었습니다: ${filePath}`));
        return;
      }
      setTimeout(wait, 20);
    };
    wait();
  });
}

function stopFakeK6(pidPath) {
  if (!existsSync(pidPath)) {
    return;
  }
  const pid = Number.parseInt(readFileSync(pidPath, 'utf8'), 10);
  if (!Number.isInteger(pid)) {
    return;
  }
  try {
    process.kill(pid, 'SIGKILL');
  } catch (error) {
    if (error.code !== 'ESRCH') {
      throw error;
    }
  }
}

function runPrepare(runId, binDirectory, extraEnvironment = {}) {
  return spawnSync(process.execPath, [
    fixtureTool,
    'prepare',
    '--scenario', 't1',
    '--run-id', runId,
    '--profile', 'spike',
    '--mode', 'hot',
    '--concurrency', '2',
  ], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${binDirectory}${path.delimiter}${process.env.PATH || ''}`,
      ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$test-hash',
      ...extraEnvironment,
    },
  });
}

function recoverCleanup(recoveryPath, binDirectory, extraEnvironment = {}) {
  return spawnSync(process.execPath, [fixtureTool, 'recover-cleanup', '--recovery', recoveryPath], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${binDirectory}${path.delimiter}${process.env.PATH || ''}`,
      ...extraEnvironment,
    },
  });
}

function verifyAfter(fixturePath, binDirectory = null, extraEnvironment = {}) {
  const environment = {
    ...process.env,
    ...extraEnvironment,
  };
  if (binDirectory) {
    environment.PATH = `${binDirectory}${path.delimiter}${process.env.PATH || ''}`;
  }
  return spawnSync(process.execPath, [fixtureTool, 'verify', '--fixture', fixturePath, '--stage', 'after'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: environment,
  });
}

function compareT5(runId) {
  return spawnSync(process.execPath, [fixtureTool, 'compare-t5', '--run-id', runId], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: process.env,
  });
}

function cleanupFixture(fixturePath, binDirectory, extraEnvironment = {}) {
  return spawnSync(process.execPath, [fixtureTool, 'cleanup', '--fixture', fixturePath], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${binDirectory}${path.delimiter}${process.env.PATH || ''}`,
      ...extraEnvironment,
    },
  });
}

function sha256(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function createTestDirectory() {
  mkdirSync(fixtureBuildRoot, { recursive: true });
  return mkdtempSync(path.join(fixtureBuildRoot, 'fixture-runner-test-'));
}

const fakeK6Skip = process.platform === 'win32'
  ? 'Windows에서는 k6.cmd fake를 셸 없이 실행할 수 없어 직접 실행 보안 계약만 검증한다.'
  : false;

test('k6 실행은 Windows 셸을 사용하지 않는다', () => {
  const source = readFileSync(fixtureTool, 'utf8');
  const runK6 = source.slice(source.indexOf('function runK6('), source.indexOf('function k6Version('));

  assert.match(runK6, /shell:\s*false/);
  assert.doesNotMatch(runK6, /process\.platform\s*===\s*['"]win32['"]/);
});

test('run은 RUNNING manifest 기록 전에 중단 처리기를 설치하고 항상 해제한다', () => {
  const source = readFileSync(fixtureTool, 'utf8');
  const run = source.slice(source.indexOf('async function run('), source.indexOf('function verify('));
  const tryIndex = run.indexOf('  try {');
  const sigintHandlerIndex = run.indexOf("process.on('SIGINT', interruptK6);");
  const sigtermHandlerIndex = run.indexOf("process.on('SIGTERM', interruptK6);");
  const manifestWriteIndex = run.indexOf('writeNewJson(manifestPath, manifest);');
  const finallyIndex = run.indexOf('  } finally {');

  assert.ok(tryIndex >= 0 && tryIndex < sigintHandlerIndex);
  assert.ok(sigintHandlerIndex >= 0 && sigintHandlerIndex < manifestWriteIndex);
  assert.ok(sigtermHandlerIndex >= 0 && sigtermHandlerIndex < manifestWriteIndex);
  assert.ok(manifestWriteIndex >= 0 && manifestWriteIndex < finallyIndex);
  assert.match(run.slice(finallyIndex), /process\.off\('SIGINT', interruptK6\);/);
  assert.match(run.slice(finallyIndex), /process\.off\('SIGTERM', interruptK6\);/);
});

test('T5는 barrier 직후 VU별 측정 시작 편차를 기록한다', () => {
  const source = readFileSync(t5Script, 'utf8');
  const barrierIndex = source.indexOf('waitFor(window.firstBarrierAt);');
  const startSkewIndex = source.indexOf('recordStartSkew(window.firstBarrierAt, tags);');
  const measurementLoopIndex = source.indexOf('while (Date.now() < window.measurementEndsAt)');

  assert.match(source, /recordStartSkew,/);
  assert.ok(barrierIndex >= 0 && barrierIndex < startSkewIndex);
  assert.ok(startSkewIndex >= 0 && startSkewIndex < measurementLoopIndex);
});

test('T5 read 옵션은 시작 편차 count가 포함된 Trend summary를 요청한다', () => {
  const source = readFileSync(roomK6Library, 'utf8');

  assert.match(
    source,
    /summaryTrendStats:\s*\[\s*'avg',\s*'min',\s*'med',\s*'max',\s*'p\(90\)',\s*'p\(95\)',\s*'count',?\s*\]/,
  );
});

test('run은 성공한 k6 실행의 provenance manifest와 summary를 같은 fixture에 남긴다', { skip: fakeK6Skip }, () => {
  const fixtureDirectory = createTestDirectory();
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
    const fixturePath = writeFixture(fixtureDirectory, 'runner-success');
    const result = runFixture(fixturePath, binDirectory);
    assert.equal(result.status, 0, result.stderr || result.stdout);

    const manifest = JSON.parse(readFileSync(path.join(fixtureDirectory, 'run-manifest.json'), 'utf8'));
    assert.equal(manifest.fixtureId, 'room-k6-runner-success-t1');
    assert.equal(manifest.runId, 'runner-success');
    assert.equal(manifest.scenario, 't1');
    assert.equal(manifest.sourceSha, 'a'.repeat(40));
    assert.equal(manifest.targetEnvironment, 'private-loadtest');
    assert.equal(manifest.k6Version, 'k6 v0.0.0-test');
    assert.equal(manifest.runState, 'COMPLETED');
    assert.equal(manifest.completed, true);
    assert.equal(manifest.k6ExitCode, 0);
    assert.equal(manifest.summaryFile, 'k6-summary.json');
    assert.equal(manifest.summarySha256, sha256(path.join(fixtureDirectory, 'k6-summary.json')));
    assert.ok(Date.parse(manifest.startedAtUtc));
    assert.ok(Date.parse(manifest.finishedAtUtc));
    assert.deepEqual(JSON.parse(readFileSync(path.join(fixtureDirectory, 'k6-summary.json'), 'utf8')), {
      metrics: {},
    });

    const rerun = runFixture(fixturePath, binDirectory, { FAKE_K6_EXIT: '23' });
    assert.notEqual(rerun.status, 0);
    assert.match(rerun.stderr, /실행 artifact가 이미 있습니다/);
    assert.equal(JSON.parse(readFileSync(path.join(fixtureDirectory, 'run-manifest.json'), 'utf8')).k6ExitCode, 0);
  } finally {
    rmSync(fixtureDirectory, { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  test('run은 ' + signal + ' 중단도 종료된 manifest로 보존하고 새 run ID 재시도를 안내한다', {
    skip: fakeK6Skip,
  }, async () => {
    const fixtureDirectory = createTestDirectory();
    const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
    const startedPath = path.join(fixtureDirectory, 'fake-k6-started');
    const fakeK6PidPath = path.join(fixtureDirectory, 'fake-k6.pid');
    let runner;
    mkdirSync(binDirectory, { recursive: true });
    createFakeK6(binDirectory);

    try {
      const fixturePath = writeFixture(fixtureDirectory, 'runner-interrupted-' + signal.toLowerCase());
      runner = startFixture(fixturePath, binDirectory, {
        FAKE_K6_WAIT_FOR_SIGNAL: 'true',
        FAKE_K6_STARTED_FILE: startedPath,
        FAKE_K6_PID_FILE: fakeK6PidPath,
      });
      const exit = waitForFixtureExit(runner);
      await waitForFile(startedPath);
      assert.equal(runner.kill(signal), true);

      const result = await exit;
      assert.notEqual(result.status, 0, result.stderr || result.stdout);

      const manifestPath = path.join(fixtureDirectory, 'run-manifest.json');
      const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
      assert.equal(manifest.runState, 'INTERRUPTED');
      assert.equal(manifest.completed, false);
      assert.equal(manifest.k6Signal, signal);
      assert.equal(manifest.k6ExitCode, null);
      assert.equal(manifest.summarySha256, null);
      assert.ok(Date.parse(manifest.finishedAtUtc));

      const verification = verifyAfter(fixturePath);
      assert.equal(verification.status, 2, verification.stderr || verification.stdout);
      const afterVerification = JSON.parse(readFileSync(path.join(fixtureDirectory, 'after-verification.json'), 'utf8'));
      assert.equal(afterVerification.status, 'INVALID');
      assert.match(afterVerification.failures[0], /신호로 중단되었습니다/);

      const rerun = runFixture(fixturePath, binDirectory);
      assert.notEqual(rerun.status, 0);
      assert.match(rerun.stderr, /중단.*새 run ID/);
    } finally {
      if (runner && runner.exitCode === null && runner.signalCode === null) {
        runner.kill('SIGKILL');
      }
      stopFakeK6(fakeK6PidPath);
      rmSync(fixtureDirectory, { recursive: true, force: true });
      rmSync(binDirectory, { recursive: true, force: true });
    }
  });
}

test('T5 run은 유효 VU·duration·think time을 manifest에 기록한다', { skip: fakeK6Skip }, () => {
  const fixtureDirectory = createTestDirectory();
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
    const fixturePath = writeT5Fixture(fixtureDirectory, 'runner-t5-options', 'public', 1);
    const result = runFixture(fixturePath, binDirectory, {
      ROOM_K6_READ_VUS: '7',
      ROOM_K6_READ_DURATION_SECONDS: '75',
      ROOM_K6_READ_THINK_TIME_MS: '25',
      FAKE_K6_CAPTURE_READ_OPTIONS: 'true',
    });
    assert.equal(result.status, 0, result.stderr || result.stdout);

    const manifest = JSON.parse(readFileSync(path.join(fixtureDirectory, 'run-manifest.json'), 'utf8'));
    assert.deepEqual(manifest.t5ReadOptions, {
      vus: 7,
      durationSeconds: 75,
      thinkTimeMilliseconds: 25,
    });
    const summary = JSON.parse(readFileSync(path.join(fixtureDirectory, 'k6-summary.json'), 'utf8'));
    assert.deepEqual(summary.t5ReadOptions, {
      vus: '7',
      durationSeconds: '75',
      thinkTimeMilliseconds: '25',
    });

    delete manifest.t5ReadOptions;
    writeFileSync(path.join(fixtureDirectory, 'run-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
    const verify = verifyAfter(fixturePath);
    assert.equal(verify.status, 2, verify.stderr || verify.stdout);
    const verification = JSON.parse(readFileSync(path.join(fixtureDirectory, 'after-verification.json'), 'utf8'));
    assert.equal(verification.status, 'INVALID');
    assert.match(verification.failures[0], /run-manifest/);
  } finally {
    rmSync(fixtureDirectory, { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

test('T5 after 검증은 VU별 시작 편차 metric을 요구한다', { skip: fakeK6Skip }, () => {
  const fixtureDirectory = createTestDirectory();
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);
  createFakePsql(binDirectory);

  try {
    const fixturePath = writeT5Fixture(fixtureDirectory, 'runner-t5-start-skew', 'public', 1);
    const run = runFixture(fixturePath, binDirectory, {
      ROOM_K6_READ_VUS: '3',
      ROOM_K6_READ_DURATION_SECONDS: '75',
      ROOM_K6_READ_THINK_TIME_MS: '0',
    });
    assert.equal(run.status, 0, run.stderr || run.stdout);

    const queryResult = JSON.stringify({ rooms: [], participations: [], waitlists: [] });
    writeBoundSummary(fixtureDirectory, t5Summary(3));
    const matched = verifyAfter(fixturePath, binDirectory, { FAKE_PSQL_QUERY_RESULT: queryResult });
    assert.equal(matched.status, 0, matched.stderr || matched.stdout);

    writeBoundSummary(fixtureDirectory, t5Summary(2));
    const mismatched = verifyAfter(fixturePath, binDirectory, { FAKE_PSQL_QUERY_RESULT: queryResult });
    assert.equal(mismatched.status, 1, mismatched.stderr || mismatched.stdout);
    const verification = JSON.parse(readFileSync(path.join(fixtureDirectory, 'after-verification.json'), 'utf8'));
    assert.equal(verification.status, 'FAIL');
    assert.match(verification.failures.join('\n'), /room_start_skew_ms 관측 수 2가 VU 수 3과 다릅니다/);
  } finally {
    rmSync(fixtureDirectory, { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

test('T5 비교는 여섯 역할·규모 실행의 read profile 불일치를 거절한다', { skip: fakeK6Skip }, () => {
  const runId = `runner-t5-compare-${process.pid}`;
  const comparisonDirectory = path.join(fixtureBuildRoot, runId);
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  const fixturePaths = new Map();
  mkdirSync(comparisonDirectory, { recursive: true });
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
    for (const role of ['public', 'host', 'participant']) {
      for (const scale of [1, 10]) {
        const fixtureDirectory = path.join(comparisonDirectory, `${role}-${scale}`);
        mkdirSync(fixtureDirectory, { recursive: true });
        const fixturePath = writeT5Fixture(fixtureDirectory, runId, role, scale);
        const run = runFixture(fixturePath, binDirectory, {
          ROOM_K6_READ_VUS: '7',
          ROOM_K6_READ_DURATION_SECONDS: '75',
          ROOM_K6_READ_THINK_TIME_MS: '25',
        });
        assert.equal(run.status, 0, run.stderr || run.stdout);
        writeBoundSummary(fixtureDirectory, t5Summary(7));
        fixturePaths.set(`${role}-${scale}`, fixturePath);
        writeT5AfterVerification(fixturePath);
      }
    }

    const matched = compareT5(runId);
    assert.equal(matched.status, 0, matched.stderr || matched.stdout);
    const matchedResult = JSON.parse(readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'));
    assert.equal(matchedResult.status, 'PASS');
    assert.deepEqual(matchedResult.t5ReadOptions, {
      vus: 7,
      durationSeconds: 75,
      thinkTimeMilliseconds: 25,
    });

    const publicFixturePath = fixturePaths.get('public-1');
    writeBoundSummary(path.dirname(publicFixturePath), t5Summary(6));
    const staleAfterVerification = compareT5(runId);
    assert.equal(staleAfterVerification.status, 1, staleAfterVerification.stderr || staleAfterVerification.stdout);
    const staleAfterVerificationResult = JSON.parse(
      readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'),
    );
    assert.equal(staleAfterVerificationResult.status, 'FAIL');
    assert.match(staleAfterVerificationResult.failures.join('\n'), /public-1: T5 room_start_skew_ms 관측 수 6가 VU 수 7과 다릅니다/);
    writeBoundSummary(path.dirname(publicFixturePath), t5Summary(7));

    const participantFixturePath = fixturePaths.get('participant-10');
    const participantAfterVerificationPath = path.join(
      path.dirname(participantFixturePath),
      'after-verification.json',
    );
    rmSync(participantAfterVerificationPath);
    const missingAfterVerification = compareT5(runId);
    assert.equal(missingAfterVerification.status, 2, missingAfterVerification.stderr || missingAfterVerification.stdout);
    const missingAfterVerificationResult = JSON.parse(
      readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'),
    );
    assert.equal(missingAfterVerificationResult.status, 'INVALID');
    assert.match(missingAfterVerificationResult.failures[0], /after-verification/);

    writeT5AfterVerification(participantFixturePath, 'PASS', { fixtureId: 'different-fixture' });
    const mismatchedAfterVerification = compareT5(runId);
    assert.equal(mismatchedAfterVerification.status, 2, mismatchedAfterVerification.stderr || mismatchedAfterVerification.stdout);
    const mismatchedAfterVerificationResult = JSON.parse(
      readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'),
    );
    assert.equal(mismatchedAfterVerificationResult.status, 'INVALID');
    assert.match(mismatchedAfterVerificationResult.failures[0], /fixture와 맞지 않습니다/);

    writeT5AfterVerification(participantFixturePath, 'FAIL');
    const failedAfterVerification = compareT5(runId);
    assert.equal(failedAfterVerification.status, 1, failedAfterVerification.stderr || failedAfterVerification.stdout);
    const failedAfterVerificationResult = JSON.parse(
      readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'),
    );
    assert.equal(failedAfterVerificationResult.status, 'FAIL');
    assert.match(failedAfterVerificationResult.failures[0], /after 검증이 FAIL/);

    writeT5AfterVerification(participantFixturePath);

    const lifecycleManifestPath = path.join(comparisonDirectory, 'public-1', 'run-manifest.json');
    const completedManifest = JSON.parse(readFileSync(lifecycleManifestPath, 'utf8'));
    for (const lifecycleField of ['runState', 'completed']) {
      const incompleteLifecycleManifest = structuredClone(completedManifest);
      delete incompleteLifecycleManifest[lifecycleField];
      writeFileSync(
        lifecycleManifestPath,
        `${JSON.stringify(incompleteLifecycleManifest, null, 2)}\n`,
        'utf8',
      );
      const incompleteLifecycle = compareT5(runId);
      assert.equal(incompleteLifecycle.status, 2, incompleteLifecycle.stderr || incompleteLifecycle.stdout);
      const incompleteLifecycleResult = JSON.parse(
        readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'),
      );
      assert.equal(incompleteLifecycleResult.status, 'INVALID');
      assert.match(incompleteLifecycleResult.failures[0], /완료 lifecycle/);
    }
    writeFileSync(lifecycleManifestPath, `${JSON.stringify(completedManifest, null, 2)}\n`, 'utf8');

    const mismatchedManifestPath = path.join(comparisonDirectory, 'participant-10', 'run-manifest.json');
    const mismatchedManifest = JSON.parse(readFileSync(mismatchedManifestPath, 'utf8'));
    mismatchedManifest.t5ReadOptions.vus = 8;
    writeFileSync(mismatchedManifestPath, `${JSON.stringify(mismatchedManifest, null, 2)}\n`, 'utf8');
    const comparison = compareT5(runId);
    assert.equal(comparison.status, 1, comparison.stderr || comparison.stdout);
    const result = JSON.parse(readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'));
    assert.equal(result.status, 'FAIL');
    assert.match(result.failures[0], /read profile/);

    rmSync(mismatchedManifestPath);
    const incompleteArtifact = compareT5(runId);
    assert.equal(incompleteArtifact.status, 2, incompleteArtifact.stderr || incompleteArtifact.stdout);
    const incompleteArtifactResult = JSON.parse(readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'));
    assert.equal(incompleteArtifactResult.status, 'INVALID');
    assert.match(incompleteArtifactResult.failures[0], /run-manifest/);

    rmSync(path.join(comparisonDirectory, 'participant-10'), { recursive: true, force: true });
    const incompleteSet = compareT5(runId);
    assert.equal(incompleteSet.status, 1, incompleteSet.stderr || incompleteSet.stdout);
    const incompleteSetResult = JSON.parse(readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'));
    assert.equal(incompleteSetResult.status, 'FAIL');
    assert.match(incompleteSetResult.failures[0], /participant-10 fixture가 없습니다/);
  } finally {
    rmSync(comparisonDirectory, { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

test('cleanup은 다른 run fixture와 결정적 식별자 변조를 psql 전에 거절한다', {
  skip: process.platform === 'win32'
    ? 'psql은 셸을 사용하지 않으므로 Windows에서는 Unix fake 실행을 사용하지 않는다.'
    : false,
}, () => {
  const sourceRunId = `runner-cleanup-source-${process.pid}`;
  const targetRunId = `runner-cleanup-target-${process.pid}`;
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-psql-bin-'));
  const capturePath = path.join(binDirectory, 'cleanup.sql');
  mkdirSync(binDirectory, { recursive: true });
  createFakePsql(binDirectory);

  try {
    const source = writeCleanupFixture(sourceRunId, 100);
    const target = writeCleanupFixture(targetRunId, 1_000);

    writeFileSync(target.fixturePath, readFileSync(source.fixturePath), 'utf8');
    const copiedFixture = cleanupFixture(target.fixturePath, binDirectory, {
      FAKE_PSQL_CAPTURE_PATH: capturePath,
    });
    assert.notEqual(copiedFixture.status, 0);
    assert.match(copiedFixture.stderr, /결정적 fixture plan/);
    assert.equal(existsSync(capturePath), false);

    const alteredFixture = structuredClone(target.fixture);
    const firstUserKey = Object.keys(alteredFixture.users)[0];
    alteredFixture.users[firstUserKey].email = 'tampered@example.invalid';
    writeFileSync(target.fixturePath, `${JSON.stringify(alteredFixture, null, 2)}\n`, 'utf8');
    const changedIdentity = cleanupFixture(target.fixturePath, binDirectory, {
      FAKE_PSQL_CAPTURE_PATH: capturePath,
    });
    assert.notEqual(changedIdentity.status, 0);
    assert.match(changedIdentity.stderr, /결정적 fixture plan/);
    assert.equal(existsSync(capturePath), false);

    writeFileSync(target.fixturePath, `${JSON.stringify(target.fixture, null, 2)}\n`, 'utf8');
    const matchedFixture = cleanupFixture(target.fixturePath, binDirectory, {
      FAKE_PSQL_CAPTURE_PATH: capturePath,
    });
    assert.equal(matchedFixture.status, 0, matchedFixture.stderr || matchedFixture.stdout);
    assert.match(readFileSync(capturePath, 'utf8'), /room_k6_cleanup_users/);
  } finally {
    rmSync(path.join(fixtureBuildRoot, sourceRunId), { recursive: true, force: true });
    rmSync(path.join(fixtureBuildRoot, targetRunId), { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

test('after 검증은 manifest와 다른 k6 summary를 INVALID로 거절한다', { skip: fakeK6Skip }, () => {
  const fixtureDirectory = createTestDirectory();
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
    const fixturePath = writeFixture(fixtureDirectory, 'runner-summary-mismatch');
    const run = runFixture(fixturePath, binDirectory);
    assert.equal(run.status, 0, run.stderr || run.stdout);

    writeFileSync(path.join(fixtureDirectory, 'k6-summary.json'), '{"metrics":{"tampered":{}}}\n', 'utf8');
    const verify = verifyAfter(fixturePath);
    assert.equal(verify.status, 2, verify.stderr || verify.stdout);
    const result = JSON.parse(readFileSync(path.join(fixtureDirectory, 'after-verification.json'), 'utf8'));
    assert.equal(result.status, 'INVALID');
    assert.match(result.failures[0], /SHA-256/);
  } finally {
    rmSync(fixtureDirectory, { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

test('run은 k6 비정상 종료에도 종료 시각과 exit code를 보존한다', { skip: fakeK6Skip }, () => {
  const fixtureDirectory = createTestDirectory();
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
    const fixturePath = writeFixture(fixtureDirectory, 'runner-failure');
    const result = runFixture(fixturePath, binDirectory, { FAKE_K6_EXIT: '23' });
    assert.equal(result.status, 23, result.stderr || result.stdout);

    const manifest = JSON.parse(readFileSync(path.join(fixtureDirectory, 'run-manifest.json'), 'utf8'));
    assert.equal(manifest.k6ExitCode, 23);
    assert.ok(Date.parse(manifest.finishedAtUtc));
    assert.deepEqual(JSON.parse(readFileSync(path.join(fixtureDirectory, 'k6-summary.json'), 'utf8')), {
      metrics: {},
    });
  } finally {
    rmSync(fixtureDirectory, { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

test('prepare 후 조회 실패에도 recovery artifact로 동일 fixture cleanup을 재개한다', {
  skip: process.platform === 'win32'
    ? 'psql은 셸을 사용하지 않으므로 Windows에서는 Unix fake 실행을 사용하지 않는다.'
    : false,
}, () => {
  const runId = `runner-recovery-${process.pid}`;
  const plan = createFixturePlan({
    scenario: 't1',
    runId,
    profile: 'spike',
    mode: 'hot',
    concurrency: '2',
  });
  const fixtureDirectory = path.join(fixtureBuildRoot, runId, plan.fixtureId);
  const recoveryPath = path.join(fixtureDirectory, 'prepare-recovery.json');
  const capturePath = path.join(fixtureDirectory, 'recovered-cleanup.sql');
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-psql-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakePsql(binDirectory);

  try {
    const prepare = runPrepare(runId, binDirectory, { FAKE_PSQL_FAIL_QUERY: 'true' });
    assert.notEqual(prepare.status, 0);
    assert.match(prepare.stderr, /recover-cleanup/);
    assert.ok(existsSync(recoveryPath));
    assert.equal(existsSync(path.join(fixtureDirectory, 'fixture.json')), false);

    const resources = {
      users: Object.fromEntries(plan.users.map((user, index) => [user.email, index + 1])),
      rooms: Object.fromEntries(plan.rooms.map((room, index) => [room.title, index + 101])),
    };
    const recovery = recoverCleanup(recoveryPath, binDirectory, {
      FAKE_PSQL_QUERY_RESULT: JSON.stringify(resources),
      FAKE_PSQL_CAPTURE_PATH: capturePath,
    });
    assert.equal(recovery.status, 0, recovery.stderr || recovery.stdout);
    assert.match(recovery.stdout, /"status":"RECOVERED"/);

    const cleanupSql = readFileSync(capturePath, 'utf8');
    assert.match(cleanupSql, /fixture user identity mismatch/);
    assert.match(cleanupSql, /DELETE FROM users WHERE id IN/);
  } finally {
    rmSync(path.join(fixtureBuildRoot, runId), { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

test('prepare SQL 실행 실패도 recovery artifact 경로를 안내한다', {
  skip: process.platform === 'win32'
    ? 'psql은 셸을 사용하지 않으므로 Windows에서는 Unix fake 실행을 사용하지 않는다.'
    : false,
}, () => {
  const runId = `runner-prepare-failure-${process.pid}`;
  const plan = createFixturePlan({
    scenario: 't1',
    runId,
    profile: 'spike',
    mode: 'hot',
    concurrency: '2',
  });
  const fixtureDirectory = path.join(fixtureBuildRoot, runId, plan.fixtureId);
  const recoveryPath = path.join(fixtureDirectory, 'prepare-recovery.json');
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-psql-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakePsql(binDirectory);

  try {
    const prepare = runPrepare(runId, binDirectory, { FAKE_PSQL_FAIL_PREPARE: 'true' });
    assert.notEqual(prepare.status, 0);
    assert.match(prepare.stderr, /recover-cleanup --recovery/);
    assert.ok(prepare.stderr.includes(recoveryPath));
    assert.ok(existsSync(recoveryPath));
    assert.equal(existsSync(path.join(fixtureDirectory, 'fixture.json')), false);
  } finally {
    rmSync(path.join(fixtureBuildRoot, runId), { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});
