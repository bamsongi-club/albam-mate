import assert from 'node:assert/strict';
import {
  chmodSync,
  copyFileSync,
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
const PREPARE_OWNERSHIP = 'a'.repeat(32);

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
    createFakePsql(binDirectory);
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
  createFakePsql(binDirectory);
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
  const sql = args[queryIndex + 1];
  const result = sql.includes('jsonb_object_agg(email, id)')
    ? (process.env.FAKE_PSQL_RESOURCE_RESULT || process.env.FAKE_PSQL_QUERY_RESULT || '{}')
    : (process.env.FAKE_PSQL_SNAPSHOT_RESULT || process.env.FAKE_PSQL_QUERY_RESULT || '{}');
  process.stdout.write(result);
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

function createOwnershipFakePsql(binDirectory) {
  const programPath = path.join(binDirectory, 'ownership-fake-psql.mjs');
  writeFileSync(programPath, `import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const args = process.argv.slice(2);
const statePath = process.env.FAKE_PSQL_STATE_PATH;

function readState() {
  return existsSync(statePath) ? JSON.parse(readFileSync(statePath, 'utf8')) : {};
}

function writeState(state) {
  writeFileSync(statePath, JSON.stringify(state), 'utf8');
}

function waitFor(predicate, onReady) {
  if (predicate()) {
    onReady();
    return;
  }
  setTimeout(() => waitFor(predicate, onReady), 10);
}

function ownershipFromSql(sql) {
  return sql.match(/ROOM k6 fixture ([0-9a-f]{32})/)?.[1] || null;
}

const queryIndex = args.indexOf('-c');
if (queryIndex >= 0) {
  const sql = args[queryIndex + 1];
  if (sql.includes('jsonb_object_agg(email, id)')) {
    const ownership = ownershipFromSql(sql);
    const state = readState();
    state.resourceOwnershipRequests = [...(state.resourceOwnershipRequests || []), ownership];
    writeState(state);
    if (state.committedOwnership !== ownership) {
      process.stdout.write(JSON.stringify({ users: state.resources.users, rooms: {} }));
      process.exit(0);
    }
    process.stdout.write(process.env.FAKE_PSQL_RESOURCE_RESULT);
    process.exit(0);
  }
  process.stdout.write(process.env.FAKE_PSQL_SNAPSHOT_RESULT);
  process.exit(0);
}

const fileIndex = args.indexOf('-f');
if (fileIndex >= 0 && args[fileIndex + 1] !== '-') {
  const ownership = ownershipFromSql(readFileSync(args[fileIndex + 1], 'utf8'));
  const state = readState();
  if (!state.firstOwnership) {
    writeState({ ...state, firstOwnership: ownership });
    writeFileSync(process.env.FAKE_PSQL_OWNER_ENTERED_PATH, 'entered\\n', 'utf8');
    waitFor(
      () => existsSync(process.env.FAKE_PSQL_RELEASE_OWNER_PATH),
      () => {
        const committed = readState();
        writeState({ ...committed, committedOwnership: ownership });
        process.exit(0);
      },
    );
  } else {
    writeFileSync(process.env.FAKE_PSQL_LOSER_ENTERED_PATH, 'entered\\n', 'utf8');
    waitFor(
      () => Boolean(readState().committedOwnership),
      () => {
        process.stderr.write('duplicate key value violates unique constraint\\n');
        process.exit(1);
      },
    );
  }
} else if (fileIndex >= 0 && args[fileIndex + 1] === '-') {
  let input = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    input += chunk;
  });
  process.stdin.on('end', () => {
    const state = readState();
    state.cleanupAttempts = (state.cleanupAttempts || 0) + 1;
    writeState(state);
    if (ownershipFromSql(input) !== state.committedOwnership) {
      process.stderr.write('prepare ownership marker mismatch\\n');
      process.exit(1);
    }
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

function createIsolatedFixtureTool() {
  const root = mkdtempSync(path.join(os.tmpdir(), 'room-k6-fixture-root-'));
  const copySource = (relativePath) => {
    const sourcePath = path.join(repositoryRoot, relativePath);
    const destinationPath = path.join(root, relativePath);
    mkdirSync(path.dirname(destinationPath), { recursive: true });
    copyFileSync(sourcePath, destinationPath);
  };

  copySource(path.join('load-tests', 'k6', 'jiwon', 'tools', 'fixture.mjs'));
  copySource(path.join('load-tests', 'k6', 'jiwon', 'tools', 'fixture-model.mjs'));
  copySource(path.join('load-tests', 'k6', 'jiwon', 'lib', 'read-execution-options.mjs'));

  return {
    root,
    fixtureTool: path.join(root, 'load-tests', 'k6', 'jiwon', 'tools', 'fixture.mjs'),
    buildRoot: path.join(root, 'build', 'k6', 'room'),
  };
}

function fixtureResources(plan, idOffset = 100) {
  return {
    users: Object.fromEntries(plan.users.map((user, index) => [user.email, idOffset + index])),
    rooms: Object.fromEntries(plan.rooms.map((room, index) => [room.title, idOffset + 100 + index])),
  };
}

function fixtureSnapshot(fixture) {
  const rooms = [];
  const participations = [];
  const waitlists = [];
  let queueOrder = 1;

  for (const room of Object.values(fixture.rooms)) {
    rooms.push({
      id: room.id,
      hostUserId: fixture.users[room.hostKey].id,
      title: room.title,
      capacity: room.capacity,
      activeParticipantCount: room.activeKeys.length,
      status: room.status,
      version: 0,
      startAt: '2030-01-01T00:00:00Z',
      updatedAt: '2030-01-01T00:00:00Z',
    });
    room.activeKeys.forEach((userKey) => {
      participations.push({
        roomId: room.id,
        userId: fixture.users[userKey].id,
        status: 'ACTIVE',
        joinedAt: '2030-01-01T00:00:00Z',
        canceledAt: null,
      });
    });
    room.waiterKeys.forEach((userKey) => {
      waitlists.push({
        roomId: room.id,
        userId: fixture.users[userKey].id,
        status: 'WAITING',
        queueOrder,
        queuedAt: '2030-01-01T00:00:00Z',
      });
      queueOrder += 1;
    });
  }

  return { rooms, participations, waitlists };
}

function writeFixturePlan(plan, idOffset = 100) {
  const fixtureDirectory = path.join(fixtureBuildRoot, plan.options.runId, plan.fixtureId);
  const fixture = hydrateFixture(plan, fixtureResources(plan, idOffset), PREPARE_OWNERSHIP);
  fixture.baselineSnapshot = plan.options.scenario === 't5'
    ? fixtureSnapshot(fixture)
    : { rooms: [], participations: [], waitlists: [] };
  const fixturePath = path.join(fixtureDirectory, 'fixture.json');
  mkdirSync(fixtureDirectory, { recursive: true });
  writeFileSync(fixturePath, `${JSON.stringify(fixture, null, 2)}\n`, 'utf8');
  return { fixtureDirectory, fixturePath, fixture };
}

function writeFixture(runId) {
  const plan = createFixturePlan({
    scenario: 't1',
    runId,
    profile: 'stress',
    mode: 'hot',
    concurrency: 2,
  });
  return writeFixturePlan(plan);
}

function writeT5Fixture(runId, role, scale) {
  const plan = createFixturePlan({
    scenario: 't5',
    runId,
    profile: 'stress',
    t5Role: role,
    t5Scale: scale,
  });
  return writeFixturePlan(plan);
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
  return writeFixturePlan(plan, idOffset);
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

function fixtureResourceQueryResult(fixturePath) {
  const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
  return JSON.stringify({
    users: Object.fromEntries(Object.values(fixture.users).map((user) => [user.email, user.id])),
    rooms: Object.fromEntries(Object.values(fixture.rooms).map((room) => [room.title, room.id])),
  });
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
      FAKE_PSQL_RESOURCE_RESULT: fixtureResourceQueryResult(fixturePath),
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
      FAKE_PSQL_RESOURCE_RESULT: fixtureResourceQueryResult(fixturePath),
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

function startPrepareFromTool(tool, runId, binDirectory, extraEnvironment = {}) {
  return spawn(process.execPath, [
    tool,
    'prepare',
    '--scenario', 't1',
    '--run-id', runId,
    '--profile', 'spike',
    '--mode', 'hot',
    '--concurrency', '2',
  ], {
    cwd: path.dirname(tool),
    env: {
      ...process.env,
      PATH: `${binDirectory}${path.delimiter}${process.env.PATH || ''}`,
      ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$test-hash',
      ...extraEnvironment,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
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

function recoverCleanupFromTool(tool, recoveryPath, binDirectory, extraEnvironment = {}) {
  return spawnSync(process.execPath, [tool, 'recover-cleanup', '--recovery', recoveryPath], {
    cwd: path.dirname(tool),
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
    FAKE_PSQL_RESOURCE_RESULT: fixtureResourceQueryResult(fixturePath),
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

test('run은 k6 시작 전에 현재 DB resource identity를 검증한다', () => {
  const source = readFileSync(fixtureTool, 'utf8');
  const run = source.slice(source.indexOf('async function run('), source.indexOf('function verify('));
  const identityCheckIndex = run.indexOf('assertFixtureMatchesCurrentResources(fixturePath, fixture);');
  const k6VersionIndex = run.indexOf('const version = k6Version();');

  assert.ok(identityCheckIndex >= 0);
  assert.ok(identityCheckIndex < k6VersionIndex);
});

test('run manifest는 fixture SHA-256을 기록하고 after 검증에서 다시 대조한다', () => {
  const source = readFileSync(fixtureTool, 'utf8');
  const run = source.slice(source.indexOf('async function run('), source.indexOf('function verify('));
  const completedArtifact = source.slice(
    source.indexOf('function completedRunArtifact('),
    source.indexOf('function t5ComparisonDirectory('),
  );

  assert.match(run, /const fixtureSha256 = sha256\(fixturePath\);/);
  assert.match(run, /fixtureSha256,/);
  assert.match(completedArtifact, /sha256\(fixturePath\) !== manifest\.fixtureSha256/);
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

test('T5 read 옵션은 시작 편차를 summary에 남기고 1초를 넘으면 실패한다', () => {
  const source = readFileSync(roomK6Library, 'utf8');

  assert.match(
    source,
    /summaryTrendStats:\s*\[\s*'avg',\s*'min',\s*'med',\s*'max',\s*'p\(90\)',\s*'p\(95\)',\s*'count',?\s*\]/,
  );
  assert.match(source, /room_start_skew_ms:\s*\[\s*START_SKEW_THRESHOLD\s*\]/);
});

test('k6 runtime은 ownership marker가 있는 fixture schema 2만 실행한다', () => {
  const source = readFileSync(roomK6Library, 'utf8');

  assert.match(source, /const FIXTURE_SCHEMA_VERSION = 2;/);
  assert.match(source, /fixture\.schemaVersion !== FIXTURE_SCHEMA_VERSION/);
  assert.match(source, /PREPARE_OWNERSHIP_PATTERN\.test\(fixture\.prepareOwnership\)/);
});

test('fixture tool은 ownership marker가 없는 legacy fixture를 k6 실행 전에 거절한다', () => {
  const prepared = writeFixture(`runner-legacy-fixture-${process.pid}`);

  try {
    const fixturePath = prepared.fixturePath;
    const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
    fixture.schemaVersion = 1;
    delete fixture.prepareOwnership;
    writeFileSync(fixturePath, `${JSON.stringify(fixture)}\n`, 'utf8');

    const result = runFixture(fixturePath, prepared.fixtureDirectory);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /현재 schemaVersion=2/);
  } finally {
    rmSync(prepared.fixtureDirectory, { recursive: true, force: true });
  }
});

test('run은 현재 DB resource ID와 다른 fixture를 k6 시작 전에 거절한다', {
  skip: fakeK6Skip,
}, () => {
  const runId = `runner-resource-identity-${process.pid}`;
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);
  createFakePsql(binDirectory);

  try {
    const prepared = writeCleanupFixture(runId, 100);
    const alteredFixture = structuredClone(prepared.fixture);
    const firstRoomKey = Object.keys(alteredFixture.rooms)[0];
    alteredFixture.rooms[firstRoomKey].id += 10_000;
    writeFileSync(prepared.fixturePath, `${JSON.stringify(alteredFixture, null, 2)}\n`, 'utf8');

    const result = runFixture(prepared.fixturePath, binDirectory, {
      FAKE_PSQL_RESOURCE_RESULT: JSON.stringify({
        users: Object.fromEntries(Object.values(prepared.fixture.users).map((user) => [user.email, user.id])),
        rooms: Object.fromEntries(Object.values(prepared.fixture.rooms).map((room) => [room.title, room.id])),
      }),
    });

    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /DB resource identity/);
    assert.equal(existsSync(path.join(prepared.fixtureDirectory, 'run-manifest.json')), false);
  } finally {
    rmSync(path.join(fixtureBuildRoot, runId), { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

test('run은 성공한 k6 실행의 provenance manifest와 summary를 같은 fixture에 남긴다', { skip: fakeK6Skip }, () => {
  const runId = `runner-success-${process.pid}`;
  const prepared = writeFixture(runId);
  const { fixtureDirectory, fixturePath } = prepared;
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
    const result = runFixture(fixturePath, binDirectory);
    assert.equal(result.status, 0, result.stderr || result.stdout);

    const manifest = JSON.parse(readFileSync(path.join(fixtureDirectory, 'run-manifest.json'), 'utf8'));
    assert.equal(manifest.fixtureId, prepared.fixture.fixtureId);
    assert.equal(manifest.runId, runId);
    assert.equal(manifest.scenario, 't1');
    assert.equal(manifest.sourceSha, 'a'.repeat(40));
    assert.equal(manifest.targetEnvironment, 'private-loadtest');
    assert.equal(manifest.k6Version, 'k6 v0.0.0-test');
    assert.equal(manifest.runState, 'COMPLETED');
    assert.equal(manifest.completed, true);
    assert.equal(manifest.k6ExitCode, 0);
    assert.equal(manifest.fixtureSha256, sha256(fixturePath));
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
    const runId = `runner-interrupted-${signal.toLowerCase()}-${process.pid}`;
    const prepared = writeFixture(runId);
    const { fixtureDirectory, fixturePath } = prepared;
    const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
    const startedPath = path.join(fixtureDirectory, 'fake-k6-started');
    const fakeK6PidPath = path.join(fixtureDirectory, 'fake-k6.pid');
    let runner;
    mkdirSync(binDirectory, { recursive: true });
    createFakeK6(binDirectory);

    try {
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
  const prepared = writeT5Fixture(`runner-t5-options-${process.pid}`, 'public', 1);
  const { fixtureDirectory, fixturePath } = prepared;
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
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
  const prepared = writeT5Fixture(`runner-t5-start-skew-${process.pid}`, 'public', 1);
  const { fixtureDirectory, fixturePath } = prepared;
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);
  createFakePsql(binDirectory);

  try {
    const run = runFixture(fixturePath, binDirectory, {
      ROOM_K6_READ_VUS: '3',
      ROOM_K6_READ_DURATION_SECONDS: '75',
      ROOM_K6_READ_THINK_TIME_MS: '0',
    });
    assert.equal(run.status, 0, run.stderr || run.stdout);

    const queryResult = JSON.stringify(fixtureSnapshot(prepared.fixture));
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

test('after 검증은 run 뒤 fixture baselineSnapshot 변조를 INVALID로 거절한다', {
  skip: fakeK6Skip,
}, () => {
  const prepared = writeT5Fixture(`runner-baseline-tamper-${process.pid}`, 'public', 1);
  const { fixtureDirectory, fixturePath } = prepared;
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
    const run = runFixture(fixturePath, binDirectory);
    assert.equal(run.status, 0, run.stderr || run.stdout);

    const currentSnapshot = fixtureSnapshot(prepared.fixture);
    writeBoundSummary(fixtureDirectory, t5Summary(10));
    const tamperedFixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
    tamperedFixture.baselineSnapshot = {
      ...currentSnapshot,
      rooms: [],
    };
    writeFileSync(fixturePath, `${JSON.stringify(tamperedFixture, null, 2)}\n`, 'utf8');

    const verify = verifyAfter(fixturePath, binDirectory, {
      FAKE_PSQL_SNAPSHOT_RESULT: JSON.stringify(currentSnapshot),
    });
    assert.equal(verify.status, 2, verify.stderr || verify.stdout);
    const verification = JSON.parse(readFileSync(path.join(fixtureDirectory, 'after-verification.json'), 'utf8'));
    assert.equal(verification.status, 'INVALID');
    assert.match(verification.failures[0], /fixture\.json의 SHA-256/);
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
        const prepared = writeT5Fixture(runId, role, scale);
        const { fixtureDirectory, fixturePath } = prepared;
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

    const lifecycleManifestPath = path.join(path.dirname(publicFixturePath), 'run-manifest.json');
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

    const mismatchedManifestPath = path.join(path.dirname(participantFixturePath), 'run-manifest.json');
    const mismatchedManifest = JSON.parse(readFileSync(mismatchedManifestPath, 'utf8'));
    mismatchedManifest.t5ReadOptions.vus = 8;
    writeFileSync(mismatchedManifestPath, `${JSON.stringify(mismatchedManifest, null, 2)}\n`, 'utf8');
    writeBoundSummary(path.dirname(participantFixturePath), t5Summary(8));
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

    rmSync(path.dirname(participantFixturePath), { recursive: true, force: true });
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
  const prepared = writeFixture(`runner-summary-mismatch-${process.pid}`);
  const { fixtureDirectory, fixturePath } = prepared;
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
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
  const prepared = writeFixture(`runner-failure-${process.pid}`);
  const { fixtureDirectory, fixturePath } = prepared;
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
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

test('다른 작업 디렉터리의 실패한 prepare recovery는 commit한 fixture를 정리하지 못한다', {
  skip: process.platform === 'win32'
    ? 'psql은 셸을 사용하지 않으므로 Windows에서는 Unix fake 실행을 사용하지 않는다.'
    : false,
}, async () => {
  const runId = `runner-prepare-ownership-${process.pid}`;
  const plan = createFixturePlan({
    scenario: 't1',
    runId,
    profile: 'spike',
    mode: 'hot',
    concurrency: 2,
  });
  const resources = {
    users: Object.fromEntries(plan.users.map((user, index) => [user.email, index + 1])),
    rooms: Object.fromEntries(plan.rooms.map((room, index) => [room.title, index + 101])),
  };
  const snapshot = {
    rooms: plan.rooms.map((room, index) => ({
      id: index + 101,
      hostUserId: resources.users[plan.users.find((user) => user.key === room.hostKey).email],
      title: room.title,
      capacity: room.capacity,
      activeParticipantCount: room.activeKeys.length,
      status: room.status,
      version: 0,
      startAt: '2030-01-01T00:00:00Z',
      updatedAt: '2030-01-01T00:00:00Z',
    })),
    participations: plan.rooms.flatMap((room, roomIndex) => room.activeKeys.map((userKey, index) => ({
      roomId: roomIndex + 101,
      userId: resources.users[plan.users.find((user) => user.key === userKey).email],
      status: 'ACTIVE',
      joinedAt: `2030-01-01T00:00:0${index}Z`,
      canceledAt: null,
    }))),
    waitlists: plan.rooms.flatMap((room, roomIndex) => room.waiterKeys.map((userKey, index) => ({
      roomId: roomIndex + 101,
      userId: resources.users[plan.users.find((user) => user.key === userKey).email],
      status: 'WAITING',
      queueOrder: roomIndex + index + 1,
      queuedAt: '2030-01-01T00:00:00Z',
    }))),
  };
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-psql-bin-'));
  const statePath = path.join(binDirectory, 'state.json');
  const ownerEnteredPath = path.join(binDirectory, 'owner-entered');
  const loserEnteredPath = path.join(binDirectory, 'loser-entered');
  const releaseOwnerPath = path.join(binDirectory, 'release-owner');
  const owner = createIsolatedFixtureTool();
  const loser = createIsolatedFixtureTool();
  mkdirSync(binDirectory, { recursive: true });
  createOwnershipFakePsql(binDirectory);
  writeFileSync(
    statePath,
    JSON.stringify({ resources, snapshot, cleanupAttempts: 0, resourceOwnershipRequests: [] }),
    'utf8',
  );

  const environment = {
    FAKE_PSQL_STATE_PATH: statePath,
    FAKE_PSQL_RESOURCE_RESULT: JSON.stringify(resources),
    FAKE_PSQL_SNAPSHOT_RESULT: JSON.stringify(snapshot),
    FAKE_PSQL_OWNER_ENTERED_PATH: ownerEnteredPath,
    FAKE_PSQL_LOSER_ENTERED_PATH: loserEnteredPath,
    FAKE_PSQL_RELEASE_OWNER_PATH: releaseOwnerPath,
  };
  const recoveryPath = (tool) => path.join(tool.buildRoot, runId, plan.fixtureId, 'prepare-recovery.json');
  let ownerPrepare = null;
  let loserPrepare = null;

  try {
    ownerPrepare = startPrepareFromTool(owner.fixtureTool, runId, binDirectory, environment);
    const ownerExit = waitForFixtureExit(ownerPrepare);
    await waitForFile(ownerEnteredPath);

    loserPrepare = startPrepareFromTool(loser.fixtureTool, runId, binDirectory, environment);
    const loserExit = waitForFixtureExit(loserPrepare);
    await waitForFile(loserEnteredPath);
    writeFileSync(releaseOwnerPath, 'release\n', 'utf8');

    const ownerResult = await ownerExit;
    const loserResult = await loserExit;
    assert.equal(ownerResult.status, 0, ownerResult.stderr || ownerResult.stdout);
    assert.notEqual(loserResult.status, 0);
    assert.match(loserResult.stderr, /recover-cleanup/);

    const ownerRecovery = JSON.parse(readFileSync(recoveryPath(owner), 'utf8'));
    const loserRecovery = JSON.parse(readFileSync(recoveryPath(loser), 'utf8'));
    assert.equal(ownerRecovery.fixtureId, loserRecovery.fixtureId);
    assert.deepEqual(ownerRecovery.options, loserRecovery.options);
    assert.match(ownerRecovery.prepareOwnership, /^[0-9a-f]{32}$/);
    assert.match(loserRecovery.prepareOwnership, /^[0-9a-f]{32}$/);
    assert.notEqual(ownerRecovery.prepareOwnership, loserRecovery.prepareOwnership);

    const recovered = recoverCleanupFromTool(loser.fixtureTool, recoveryPath(loser), binDirectory, environment);
    assert.notEqual(recovered.status, 0);
    assert.match(recovered.stderr, /fixture ROOM ID를 찾지 못했습니다/);

    const state = JSON.parse(readFileSync(statePath, 'utf8'));
    assert.equal(state.committedOwnership, ownerRecovery.prepareOwnership);
    assert.deepEqual(state.resourceOwnershipRequests, [
      ownerRecovery.prepareOwnership,
      loserRecovery.prepareOwnership,
    ]);
    assert.equal(state.cleanupAttempts, 0);
  } finally {
    if (!existsSync(releaseOwnerPath)) {
      writeFileSync(releaseOwnerPath, 'release\n', 'utf8');
    }
    ownerPrepare?.kill('SIGKILL');
    loserPrepare?.kill('SIGKILL');
    rmSync(owner.root, { recursive: true, force: true });
    rmSync(loser.root, { recursive: true, force: true });
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
