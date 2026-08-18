import assert from 'node:assert/strict';
import {
  chmodSync,
  copyFileSync,
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { createHash } from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';

import { createFixturePlan, hydrateFixture } from '../tools/fixture-model.mjs';
import {
  aggregateBundle,
  bundleExecutionOptions,
  diagnoseBundle,
  hydrateBundle,
  renderBundle,
  validateBundle,
} from '../tools/portable-bundle.mjs';

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(testDirectory, '../../../..');
const fixtureTool = path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon', 'tools', 'fixture.mjs');
const fixtureBuildRoot = path.join(repositoryRoot, 'build', 'k6', 'room');
const roomK6Library = path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon', 'lib', 'room-k6.js');
const writeOptionsLibrary = path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon', 'lib', 'write-options.mjs');
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
  copySource(path.join('load-tests', 'k6', 'jiwon', 'tools', 'portable-bundle.mjs'));
  copySource(path.join('load-tests', 'k6', 'jiwon', 'lib', 'read-execution-options.mjs'));

  return {
    root,
    fixtureTool: path.join(root, 'load-tests', 'k6', 'jiwon', 'tools', 'fixture.mjs'),
    buildRoot: path.join(root, 'build', 'k6', 'room'),
  };
}

test('격리 fixture 도구는 portable bundle 정적 의존성을 포함한다', () => {
  const tool = createIsolatedFixtureTool();

  try {
    const result = spawnSync(process.execPath, [tool.fixtureTool, 'help'], { encoding: 'utf8' });

    assert.equal(result.status, 0, result.stderr || result.stdout);
  } finally {
    rmSync(tool.root, { recursive: true, force: true });
  }
});

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
  const durationMetric = (count) => ({
    values: {
      p50: count > 0 ? 10 : null,
      p95: count > 0 ? 20 : null,
      p99: count > 0 ? 30 : null,
      max: count > 0 ? 40 : null,
      count,
    },
  });
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
      'room_request_duration{outcome:success}': durationMetric(1),
      'room_request_duration{outcome:business}': durationMetric(0),
      'room_request_duration{outcome:concurrency}': durationMetric(0),
      'room_request_duration{outcome:unexpected}': durationMetric(0),
    },
  };
}

function t5SummaryWithTopLevelCounts(startSkewCount) {
  return {
    metrics: Object.fromEntries(
      Object.entries(t5Summary(startSkewCount).metrics)
        .map(([name, metric]) => [
          name,
          name.startsWith('room_request_duration{outcome:')
            ? metric
            : { count: metric.values.count },
        ]),
    ),
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

function fakeK6ExecutionEnvironment(binDirectory) {
  const programPath = path.join(binDirectory, 'fake-k6.mjs');
  if (!existsSync(programPath)) {
    return {};
  }
  return {
    ROOM_K6_EXECUTABLE: process.execPath,
    ROOM_K6_ARGUMENT_PREFIX: JSON.stringify([programPath]),
  };
}

function fakePsqlExecutionEnvironment(binDirectory) {
  const programPath = ['fake-psql.mjs', 'ownership-fake-psql.mjs']
    .map((fileName) => path.join(binDirectory, fileName))
    .find((candidate) => existsSync(candidate));
  if (!programPath) {
    return {};
  }
  return {
    ROOM_K6_PSQL_EXECUTABLE: process.execPath,
    ROOM_K6_PSQL_ARGUMENT_PREFIX: JSON.stringify([programPath]),
  };
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
      ...fakeK6ExecutionEnvironment(binDirectory),
      ...fakePsqlExecutionEnvironment(binDirectory),
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
      ...fakeK6ExecutionEnvironment(binDirectory),
      ...fakePsqlExecutionEnvironment(binDirectory),
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
      ...fakePsqlExecutionEnvironment(binDirectory),
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
      ...fakePsqlExecutionEnvironment(binDirectory),
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
      ...fakePsqlExecutionEnvironment(binDirectory),
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
      ...fakePsqlExecutionEnvironment(binDirectory),
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
    Object.assign(environment, fakePsqlExecutionEnvironment(binDirectory));
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
      ...fakePsqlExecutionEnvironment(binDirectory),
      ...extraEnvironment,
    },
  });
}

function sha256(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function createPortableBundleSource(root) {
  const sourceDirectory = path.join(root, 'source', 'jiwon');
  cpSync(path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon'), sourceDirectory, { recursive: true });
  return sourceDirectory;
}

function portableBundleContext(scenarioDirectory, buildRoot) {
  return {
    repositoryRoot,
    scenarioDirectory,
    buildRoot,
    bundleRoot: null,
    isBundleRuntime: false,
    environment: { ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$portable-source-link-test' },
  };
}

function renderPortableBundle(scenarioDirectory, buildRoot, suffix) {
  return renderBundle({
    scenario: 't1',
    runId: `portable-source-link-${process.pid}-${Date.now()}-${suffix}`,
    profile: 'spike',
    mode: 'hot',
    concurrency: '2',
  }, portableBundleContext(scenarioDirectory, buildRoot), {
    sourceRevision: 'd'.repeat(40),
    sourceDirty: false,
  });
}

function createSymbolicLinkOrSkip(t, target, linkPath, type, label) {
  try {
    symlinkSync(target, linkPath, type);
    return true;
  } catch (error) {
    if (process.platform === 'win32' && (error?.code === 'EPERM' || error?.code === 'EACCES')) {
      const message = `${label}: 현재 Windows 권한에서는 symbolic link 생성 검증을 건너뜁니다.`;
      t.diagnostic(message);
      t.skip(message);
      return false;
    }
    throw error;
  }
}

test('k6 실행은 셸 없이 명시적 executable과 prefix arguments를 사용한다', () => {
  const source = readFileSync(fixtureTool, 'utf8');
  const runK6 = source.slice(source.indexOf('function runK6('), source.indexOf('function k6Version('));

  assert.match(runK6, /shell:\s*false/);
  assert.match(runK6, /ROOM_K6_EXECUTABLE/);
  assert.match(runK6, /ROOM_K6_ARGUMENT_PREFIX/);
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
  const writeOptionsSource = readFileSync(writeOptionsLibrary, 'utf8');

  assert.match(
    source,
    /summaryTrendStats:\s*\[\s*'avg',\s*'min',\s*'med',\s*'max',\s*'p\(90\)',\s*'p\(95\)',\s*'p\(99\)',\s*'count',?\s*\]/,
  );
  assert.match(
    writeOptionsSource,
    /summaryTrendStats:\s*\[\s*'avg',\s*'min',\s*'med',\s*'max',\s*'p\(90\)',\s*'p\(95\)',\s*'p\(99\)',\s*'count',?\s*\]/,
  );
  assert.match(source, /thresholds:\s*\{\s*\.\.\.outcomeDurationThresholds\(\)/);
  assert.match(writeOptionsSource, /thresholds:\s*\{\s*\.\.\.outcomeDurationThresholds\(\)/);
  assert.match(source, /room_start_skew_ms:\s*\[\s*START_SKEW_THRESHOLD\s*\]/);
});

test('k6 runtime은 ownership marker가 있는 fixture schema 2만 실행한다', () => {
  const source = readFileSync(roomK6Library, 'utf8');

  assert.match(source, /const FIXTURE_SCHEMA_VERSION = 2;/);
  assert.match(source, /fixture\.schemaVersion !== FIXTURE_SCHEMA_VERSION/);
  assert.match(source, /PREPARE_OWNERSHIP_PATTERN\.test\(fixture\.prepareOwnership\)/);
});

test('k6 runtime은 fixture 계정마다 독립 CookieJar를 만든다', () => {
  const source = readFileSync(roomK6Library, 'utf8');

  assert.doesNotMatch(source, /http\.cookieJar\(\)/);
  assert.match(source, /const client = \{ jar: new http\.CookieJar\(\), csrf: null, sessionId: null \};/);
  assert.match(source, /const jar = new http\.CookieJar\(\);/);
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

test('run은 현재 DB resource ID와 다른 fixture를 k6 시작 전에 거절한다', () => {
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

test('run은 성공한 k6 실행의 provenance manifest와 summary를 같은 fixture에 남긴다', () => {
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
    const summary = JSON.parse(readFileSync(path.join(fixtureDirectory, 'k6-summary.json'), 'utf8'));
    for (const category of ['success', 'business', 'concurrency', 'unexpected']) {
      assert.deepEqual(summary.metrics[`room_request_duration{outcome:${category}}`].values, {
        p50: null,
        p95: null,
        p99: null,
        max: null,
        count: 0,
      });
    }

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
  test('run은 ' + signal + ' 중단도 종료된 manifest로 보존하고 새 run ID 재시도를 안내한다', async () => {
    const runId = `runner-interrupted-${signal.toLowerCase()}-${process.pid}`;
    const prepared = writeFixture(runId);
    const { fixtureDirectory, fixturePath } = prepared;
    const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
    const startedPath = path.join(fixtureDirectory, 'fake-k6-started');
    const fakeK6PidPath = path.join(fixtureDirectory, 'fake-k6.pid');
    const interruptPath = path.join(fixtureDirectory, 'interrupt-signal');
    let runner;
    mkdirSync(binDirectory, { recursive: true });
    createFakeK6(binDirectory);

    try {
      runner = startFixture(fixturePath, binDirectory, {
        FAKE_K6_WAIT_FOR_SIGNAL: 'true',
        FAKE_K6_STARTED_FILE: startedPath,
        FAKE_K6_PID_FILE: fakeK6PidPath,
        ROOM_K6_TEST_INTERRUPT_FILE: interruptPath,
      });
      const exit = waitForFixtureExit(runner);
      await waitForFile(startedPath);
      if (process.platform === 'win32') {
        writeFileSync(interruptPath, `${signal}\n`, 'utf8');
      } else {
        assert.equal(runner.kill(signal), true);
      }

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

test('테스트 interrupt 파일을 읽지 못해도 k6 자식과 manifest를 종료한다', async () => {
  const runId = `runner-interrupt-read-error-${process.pid}`;
  const prepared = writeFixture(runId);
  const { fixtureDirectory, fixturePath } = prepared;
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
  const fakeK6PidPath = path.join(fixtureDirectory, 'fake-k6.pid');
  const interruptDirectory = path.join(fixtureDirectory, 'interrupt-directory');
  let runner;
  mkdirSync(binDirectory, { recursive: true });
  mkdirSync(interruptDirectory);
  createFakeK6(binDirectory);

  try {
    runner = startFixture(fixturePath, binDirectory, {
      FAKE_K6_WAIT_FOR_SIGNAL: 'true',
      FAKE_K6_PID_FILE: fakeK6PidPath,
      ROOM_K6_TEST_INTERRUPT_FILE: interruptDirectory,
    });
    const result = await waitForFixtureExit(runner);
    assert.notEqual(result.status, 0, result.stderr || result.stdout);

    const manifest = JSON.parse(readFileSync(path.join(fixtureDirectory, 'run-manifest.json'), 'utf8'));
    assert.equal(manifest.runState, 'INTERRUPTED');
    assert.equal(manifest.completed, false);
    assert.equal(manifest.k6Signal, 'SIGTERM');
    assert.ok(Date.parse(manifest.finishedAtUtc));
  } finally {
    if (runner && runner.exitCode === null && runner.signalCode === null) {
      runner.kill('SIGKILL');
    }
    stopFakeK6(fakeK6PidPath);
    rmSync(fixtureDirectory, { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

test('T5 run은 유효 VU·duration·think time을 manifest에 기록한다', () => {
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

test('T5 after 검증은 VU별 시작 편차 metric을 요구한다', () => {
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

test('after 검증은 run 뒤 fixture baselineSnapshot 변조를 INVALID로 거절한다', () => {
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

test('T5 비교는 여섯 역할·규모 실행의 read profile 불일치를 거절한다', () => {
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

function writePortableT5CompletionArtifacts(bundle, rendered) {
  const portableManifest = JSON.parse(readFileSync(path.join(bundle, 'manifest.json'), 'utf8'));
  const executionOptions = JSON.parse(readFileSync(path.join(bundle, 'execution-options.json'), 'utf8'));
  const fixturePath = path.join(bundle, 'fixture.json');
  const summaryPath = path.join(bundle, 'k6-summary.json');
  const startedAtUtc = '2026-08-18T00:00:00.000Z';
  const finishedAtUtc = '2026-08-18T00:01:00.000Z';

  writeFileSync(path.join(bundle, 'run-manifest.json'), `${JSON.stringify({
    schemaVersion: 2,
    fixtureId: rendered.fixtureId,
    runId: rendered.options.runId,
    scenario: 't5',
    sourceSha: portableManifest.sourceRevision,
    targetEnvironment: 'private-loadtest',
    k6Version: 'v1.3.0',
    startedAtUtc,
    finishedAtUtc,
    runState: 'COMPLETED',
    completed: true,
    k6ExitCode: 0,
    fixtureSha256: sha256(fixturePath),
    summaryFile: 'k6-summary.json',
    summarySha256: sha256(summaryPath),
    t5ReadOptions: executionOptions.t5ReadOptions,
  }, null, 2)}\n`, 'utf8');
  writeFileSync(path.join(bundle, 'resource-signals.json'), `${JSON.stringify({
    schemaVersion: 1,
    runId: rendered.options.runId,
    fixtureId: rendered.fixtureId,
    window: { startedAtUtc, finishedAtUtc },
    http: { requestCount: 1, failedRequestCount: 0 },
    tomcat: { activeThreads: 2 },
    hikari: { activeConnections: 1 },
    jvm: { heapUsedBytes: 100 },
    postgresql: { activeConnections: 2 },
    query: {
      callCount: 1,
      totalTimeMilliseconds: 20,
      sharedBuffersHit: 100,
      sharedBuffersRead: 2,
    },
  }, null, 2)}\n`, 'utf8');
}

function completePortableT5Bundle(bundle, rendered, context) {
  const plan = JSON.parse(readFileSync(path.join(bundle, 'fixture-plan.json'), 'utf8'));
  writeFileSync(
    path.join(bundle, 'resource-output.json'),
    `${JSON.stringify(fixtureResources(plan))}\n`,
    'utf8',
  );
  const hydrated = hydrateBundle(bundle, context);
  const fixture = JSON.parse(readFileSync(hydrated.fixturePath, 'utf8'));
  const snapshot = fixtureSnapshot(fixture);
  writeFileSync(path.join(bundle, 'before-snapshot.json'), `${JSON.stringify(snapshot)}\n`, 'utf8');
  assert.equal(diagnoseBundle({ bundle, stage: 'before' }, context).status, 'PASS');
  writeFileSync(path.join(bundle, 'after-snapshot.json'), `${JSON.stringify(snapshot)}\n`, 'utf8');
  writeFileSync(path.join(bundle, 'k6-summary.json'), `${JSON.stringify(t5SummaryWithTopLevelCounts(7))}\n`, 'utf8');
  writePortableT5CompletionArtifacts(bundle, rendered);
  assert.equal(diagnoseBundle({ bundle, stage: 'after' }, context).status, 'PASS');
  writeFileSync(path.join(bundle, 'infra-execution.json'), `${JSON.stringify({
    schemaVersion: 1,
    runId: rendered.options.runId,
    fixtureId: rendered.fixtureId,
    phases: {
      prepare: { exitCode: 0 },
      resourceQuery: { exitCode: 0 },
      beforeSnapshot: { exitCode: 0 },
      k6: { exitCode: 0 },
      afterSnapshot: { exitCode: 0 },
    },
  })}\n`, 'utf8');
  return aggregateBundle(bundle, context);
}

test('T5 비교는 portable bundle 완료 artifact와 k6 v1.3 top-level count를 검증한다', () => {
  const runId = `portable-t5-compare-${process.pid}-${Date.now()}`;
  const comparisonDirectory = path.join(fixtureBuildRoot, runId);
  const context = {
    repositoryRoot,
    scenarioDirectory: path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon'),
    buildRoot: fixtureBuildRoot,
    bundleRoot: null,
    isBundleRuntime: false,
    environment: {
      ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$portable-t5-compare-test',
      ROOM_K6_READ_VUS: '7',
      ROOM_K6_READ_DURATION_SECONDS: '75',
      ROOM_K6_READ_THINK_TIME_MS: '25',
    },
  };
  const bundles = new Map();

  try {
    for (const role of ['public', 'host', 'participant']) {
      for (const scale of [1, 10]) {
        const rendered = renderBundle({
          scenario: 't5',
          runId,
          profile: 'spike',
          t5Role: role,
          t5Scale: String(scale),
        }, context, { sourceRevision: 'e'.repeat(40), sourceDirty: false });
        assert.equal(completePortableT5Bundle(rendered.bundlePath, rendered, context).status, 'PASS');
        bundles.set(`${role}-${scale}`, rendered.bundlePath);
      }
    }

    const compared = compareT5(runId);
    assert.equal(compared.status, 0, compared.stderr || compared.stdout);
    const result = JSON.parse(readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'));
    assert.equal(result.status, 'PASS');
    assert.equal(result.fixtureCount, 6);
    assert.deepEqual(result.t5ReadOptions, {
      vus: 7,
      durationSeconds: 75,
      thinkTimeMilliseconds: 25,
    });

    const baselineBundle = bundles.get('host-1');
    const beforeDiagnosisPath = path.join(baselineBundle, 'before-diagnosis.json');
    const baselineFinalResultPath = path.join(baselineBundle, 'final-result.json');
    const beforeDiagnosis = JSON.parse(readFileSync(beforeDiagnosisPath, 'utf8'));
    const baselineFinalResult = JSON.parse(readFileSync(baselineFinalResultPath, 'utf8'));
    const diagnosisWithoutBaseline = structuredClone(beforeDiagnosis);
    const finalResultWithoutBaseline = structuredClone(baselineFinalResult);
    delete diagnosisWithoutBaseline.baselineSnapshot;
    delete finalResultWithoutBaseline.beforeDiagnosis.baselineSnapshot;
    writeFileSync(beforeDiagnosisPath, `${JSON.stringify(diagnosisWithoutBaseline)}\n`, 'utf8');
    writeFileSync(baselineFinalResultPath, `${JSON.stringify(finalResultWithoutBaseline)}\n`, 'utf8');

    const missingBaseline = compareT5(runId);
    assert.equal(missingBaseline.status, 2, missingBaseline.stderr || missingBaseline.stdout);
    const missingBaselineResult = JSON.parse(
      readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'),
    );
    assert.equal(missingBaselineResult.status, 'INVALID');
    assert.match(missingBaselineResult.failures.join('\n'), /portable diagnosis artifact/);

    writeFileSync(beforeDiagnosisPath, `${JSON.stringify(beforeDiagnosis)}\n`, 'utf8');
    writeFileSync(baselineFinalResultPath, `${JSON.stringify(baselineFinalResult)}\n`, 'utf8');

    const finalResultPath = path.join(bundles.get('participant-10'), 'final-result.json');
    const tamperedFinalResult = JSON.parse(readFileSync(finalResultPath, 'utf8'));
    tamperedFinalResult.fixtureId = 'different-fixture';
    writeFileSync(finalResultPath, `${JSON.stringify(tamperedFinalResult)}\n`, 'utf8');
    const invalid = compareT5(runId);
    assert.equal(invalid.status, 2, invalid.stderr || invalid.stdout);
    const invalidResult = JSON.parse(readFileSync(path.join(comparisonDirectory, 't5-comparison-verification.json'), 'utf8'));
    assert.equal(invalidResult.status, 'INVALID');
    assert.match(invalidResult.failures.join('\n'), /portable final-result\.json/);
  } finally {
    rmSync(comparisonDirectory, { recursive: true, force: true });
  }
});

test('cleanup은 다른 run fixture와 결정적 식별자 변조를 psql 전에 거절한다', () => {
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

test('after 검증은 manifest와 다른 k6 summary를 INVALID로 거절한다', () => {
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

test('run은 k6 비정상 종료에도 종료 시각과 exit code를 보존한다', () => {
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
    const summary = JSON.parse(readFileSync(path.join(fixtureDirectory, 'k6-summary.json'), 'utf8'));
    for (const category of ['success', 'business', 'concurrency', 'unexpected']) {
      assert.deepEqual(summary.metrics[`room_request_duration{outcome:${category}}`].values, {
        p50: null,
        p95: null,
        p99: null,
        max: null,
        count: 0,
      });
    }
  } finally {
    rmSync(fixtureDirectory, { recursive: true, force: true });
    rmSync(binDirectory, { recursive: true, force: true });
  }
});

test('prepare 후 조회 실패에도 recovery artifact로 동일 fixture cleanup을 재개한다', () => {
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

test('다른 작업 디렉터리의 실패한 prepare recovery는 commit한 fixture를 정리하지 못한다', async () => {
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

test('prepare SQL 실행 실패도 recovery artifact 경로를 안내한다', () => {
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

test('portable bundle render는 source scenario/runtime과 부모 경로 symbolic link를 거절한다', async (t) => {
  const verifySourceLinkRejection = async (name, suffix, configure) => {
    await t.test(name, (subtest) => {
      const root = mkdtempSync(path.join(os.tmpdir(), 'room-k6-portable-source-link-'));
      try {
        const sourceDirectory = createPortableBundleSource(root);
        const scenarioDirectory = configure({ root, sourceDirectory, subtest });
        if (!scenarioDirectory) {
          return;
        }

        assert.throws(
          () => renderPortableBundle(scenarioDirectory, path.join(root, 'build', 'k6', 'room'), suffix),
          /symbolic link/,
        );
      } finally {
        rmSync(root, { recursive: true, force: true });
      }
    });
  };

  await verifySourceLinkRejection('scenario entry symbolic link', 'scenario', ({ root, sourceDirectory, subtest }) => {
    const scenarioPath = path.join(sourceDirectory, 't1-cancel-promotion.js');
    const targetPath = path.join(root, 'outside-scenario.js');
    copyFileSync(scenarioPath, targetPath);
    rmSync(scenarioPath);
    return createSymbolicLinkOrSkip(subtest, targetPath, scenarioPath, 'file', 'scenario entry')
      ? sourceDirectory
      : null;
  });
  await verifySourceLinkRejection('runtime file symbolic link', 'runtime-file', ({ root, sourceDirectory, subtest }) => {
    const runtimePath = path.join(sourceDirectory, 'tools', 'fixture-model.mjs');
    const targetPath = path.join(root, 'outside-runtime.mjs');
    copyFileSync(runtimePath, targetPath);
    rmSync(runtimePath);
    return createSymbolicLinkOrSkip(subtest, targetPath, runtimePath, 'file', 'runtime file')
      ? sourceDirectory
      : null;
  });
  await verifySourceLinkRejection('runtime lib parent symbolic link', 'runtime-lib', ({ root, sourceDirectory, subtest }) => {
    const libDirectory = path.join(sourceDirectory, 'lib');
    const targetDirectory = path.join(root, 'outside-lib');
    cpSync(libDirectory, targetDirectory, { recursive: true });
    rmSync(libDirectory, { recursive: true, force: true });
    return createSymbolicLinkOrSkip(subtest, targetDirectory, libDirectory, 'dir', 'runtime lib parent')
      ? sourceDirectory
      : null;
  });
  await verifySourceLinkRejection('scenarioDirectory symbolic link', 'scenario-directory', ({ root, sourceDirectory, subtest }) => {
    const linkedDirectory = path.join(root, 'linked-scenario-directory');
    return createSymbolicLinkOrSkip(subtest, sourceDirectory, linkedDirectory, 'dir', 'scenarioDirectory')
      ? linkedDirectory
      : null;
  });
  await verifySourceLinkRejection('scenarioDirectory ancestor symbolic link', 'scenario-ancestor', ({ root, sourceDirectory, subtest }) => {
    const linkedParent = path.join(root, 'linked-source-parent');
    return createSymbolicLinkOrSkip(subtest, path.dirname(sourceDirectory), linkedParent, 'dir', 'scenarioDirectory ancestor')
      ? path.join(linkedParent, path.basename(sourceDirectory))
      : null;
  });
});

test('portable bundle은 DB·k6 없이 full closure와 immutable 계약을 생성한다', (t) => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'room-k6-portable-bundle-'));
  const buildRoot = path.join(root, 'build', 'k6', 'room');
  const context = {
    repositoryRoot,
    scenarioDirectory: path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon'),
    buildRoot,
    bundleRoot: null,
    isBundleRuntime: false,
    environment: {
      ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$portable-bundle-test',
      ROOM_K6_SESSION_WARMUP_SECONDS: '15',
      ROOM_K6_ROUND_INTERVAL_SECONDS: '20',
      ROOM_K6_READ_VUS: '7',
      ROOM_K6_READ_DURATION_SECONDS: '75',
      ROOM_K6_READ_THINK_TIME_MS: '25',
    },
  };
  const provenance = { sourceRevision: 'a'.repeat(40), sourceDirty: false };

  try {
    const rendered = renderBundle({
      scenario: 't5',
      runId: `portable-${process.pid}-${Date.now()}`,
      profile: 'spike',
      t5Role: 'host',
      t5Scale: '1',
    }, context, provenance);
    const bundle = rendered.bundlePath;
    const requiredPaths = [
      'manifest.json',
      'fixture-plan.json',
      'private/prepare-provenance.json',
      'prepare.sql',
      'resource-query.sql',
      'execution-options.json',
      'scenario.js',
      'lib/room-k6.js',
      'lib/read-execution-options.mjs',
      'lib/write-options.mjs',
      'lib/start-skew.mjs',
      'lib/write-response-contract.mjs',
      'lib/t3-execution-plan.mjs',
      'tools/fixture.mjs',
      'tools/fixture-model.mjs',
      'tools/portable-bundle.mjs',
    ];
    for (const relativePath of requiredPaths) {
      assert.ok(existsSync(path.join(bundle, relativePath)), relativePath);
    }

    assert.deepEqual(validateBundle(bundle, context, { forExecution: true }), {
      bundlePath: bundle,
      runId: rendered.options.runId,
      fixtureId: rendered.fixtureId,
    });
    writeFileSync(path.join(bundle, 'run-manifest.json'), '{}\n', 'utf8');
    assert.throws(
      () => validateBundle(bundle, context, { forExecution: true }),
      /run-manifest\.json/,
    );
    rmSync(path.join(bundle, 'run-manifest.json'));
    assert.deepEqual(bundleExecutionOptions(bundle, context), {
      schemaVersion: 1,
      k6Environment: {
        ROOM_K6_SESSION_WARMUP_SECONDS: '15',
        ROOM_K6_ROUND_INTERVAL_SECONDS: '20',
        ROOM_K6_READ_VUS: '7',
        ROOM_K6_READ_DURATION_SECONDS: '75',
        ROOM_K6_READ_THINK_TIME_MS: '25',
      },
      t5ReadOptions: { vus: 7, durationSeconds: 75, thinkTimeMilliseconds: 25 },
    });

    const bundleTool = path.join(bundle, 'tools', 'fixture.mjs');
    const runtimeValidation = spawnSync(process.execPath, [
      bundleTool,
      'validate',
      '--for-execution',
      '--bundle',
      bundle,
    ], { encoding: 'utf8' });
    assert.equal(runtimeValidation.status, 0, runtimeValidation.stderr || runtimeValidation.stdout);

    const directCommands = [
      ['prepare', '--scenario', 't1', '--run-id', `bundle-direct-${process.pid}`],
      ['run', '--fixture', path.join(bundle, 'fixture.json')],
      ['verify', '--fixture', path.join(bundle, 'fixture.json'), '--stage', 'before'],
      ['compare-t5', '--run-id', `bundle-direct-${process.pid}`],
      ['cleanup', '--fixture', path.join(bundle, 'fixture.json')],
      ['recover-cleanup', '--recovery', path.join(bundle, 'prepare-recovery.json')],
      ['render-bundle', '--scenario', 't1', '--run-id', `bundle-direct-${process.pid}`],
    ];
    for (const args of directCommands) {
      const directCommand = spawnSync(process.execPath, [bundleTool, ...args], { encoding: 'utf8' });
      assert.notEqual(directCommand.status, 0, directCommand.stderr || directCommand.stdout);
      assert.match(directCommand.stderr, /실행 bundle에서는 직접 실행 명령을 사용할 수 없습니다/);
    }

    const symlinkTarget = path.join(root, 'outside-scenario.js');
    const symlinkPath = path.join(bundle, 'scenario.js');
    copyFileSync(symlinkPath, symlinkTarget);
    rmSync(symlinkPath);
    try {
      symlinkSync(symlinkTarget, symlinkPath, 'file');
      assert.throws(
        () => validateBundle(bundle, context, { forExecution: true }),
        /symbolic link/,
      );
    } catch (error) {
      if (error?.code === 'EPERM' || error?.code === 'EACCES') {
        t.diagnostic('현재 Windows 권한에서는 symbolic link 생성 검증을 건너뜁니다.');
      } else {
        throw error;
      }
    } finally {
      rmSync(symlinkPath, { force: true });
    }

    writeFileSync(path.join(bundle, 'scenario.js'), '// altered\n', 'utf8');
    assert.throws(
      () => validateBundle(bundle, context, { forExecution: true }),
      /immutable artifact가 변조되었습니다: scenario\.js/,
    );

    copyFileSync(symlinkTarget, symlinkPath);
    rmSync(path.join(bundle, 'lib', 'write-options.mjs'));
    assert.throws(
      () => validateBundle(bundle, context, { forExecution: true }),
      /일반 파일이어야 합니다/,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('portable bundle hydrate와 before diagnosis는 prepare ownership과 raw DB artifact를 다시 대조한다', () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'room-k6-portable-hydrate-'));
  const buildRoot = path.join(root, 'build', 'k6', 'room');
  const context = {
    repositoryRoot,
    scenarioDirectory: path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon'),
    buildRoot,
    bundleRoot: null,
    isBundleRuntime: false,
    environment: { ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$portable-hydrate-test' },
  };
  const provenance = { sourceRevision: 'b'.repeat(40), sourceDirty: false };

  try {
    const rendered = renderBundle({
      scenario: 't1',
      runId: `portable-hydrate-${process.pid}-${Date.now()}`,
      profile: 'spike',
      mode: 'hot',
      concurrency: '2',
    }, context, provenance);
    const bundle = rendered.bundlePath;
    const plan = JSON.parse(readFileSync(path.join(bundle, 'fixture-plan.json'), 'utf8'));
    writeFileSync(
      path.join(bundle, 'resource-output.json'),
      `${JSON.stringify(fixtureResources(plan))}\n`,
      'utf8',
    );

    const hydrated = hydrateBundle(bundle, context);
    const fixture = JSON.parse(readFileSync(hydrated.fixturePath, 'utf8'));
    assert.match(fixture.prepareOwnership, /^[0-9a-f]{32}$/);
    assert.match(readFileSync(path.join(bundle, 'snapshot.sql'), 'utf8'), /jsonb_build_object/);
    assert.match(readFileSync(path.join(bundle, 'cleanup.sql'), 'utf8'), /pg_advisory_xact_lock/);

    writeFileSync(
      path.join(bundle, 'before-snapshot.json'),
      `${JSON.stringify(fixtureSnapshot(fixture))}\n`,
      'utf8',
    );
    const before = diagnoseBundle({ bundle, stage: 'before' }, context);
    assert.equal(before.status, 'PASS');
    assert.equal(existsSync(path.join(bundle, 'before-diagnosis.json')), true);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('portable bundle before diagnosis 재실행은 create-only T5 baseline을 보존한다', () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'room-k6-portable-before-diagnosis-'));
  const buildRoot = path.join(root, 'build', 'k6', 'room');
  const context = {
    repositoryRoot,
    scenarioDirectory: path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon'),
    buildRoot,
    bundleRoot: null,
    isBundleRuntime: false,
    environment: {
      ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$portable-before-diagnosis-test',
      ROOM_K6_READ_VUS: '7',
      ROOM_K6_READ_DURATION_SECONDS: '75',
      ROOM_K6_READ_THINK_TIME_MS: '1000',
    },
  };
  const provenance = { sourceRevision: 'b'.repeat(40), sourceDirty: false };

  try {
    const rendered = renderBundle({
      scenario: 't5',
      runId: `portable-before-diagnosis-${process.pid}-${Date.now()}`,
      profile: 'spike',
      t5Role: 'host',
      t5Scale: '1',
    }, context, provenance);
    const bundle = rendered.bundlePath;
    const plan = JSON.parse(readFileSync(path.join(bundle, 'fixture-plan.json'), 'utf8'));
    writeFileSync(
      path.join(bundle, 'resource-output.json'),
      `${JSON.stringify(fixtureResources(plan))}\n`,
      'utf8',
    );

    const hydrated = hydrateBundle(bundle, context);
    const fixtureBeforeDiagnosis = readFileSync(hydrated.fixturePath, 'utf8');
    const fixture = JSON.parse(fixtureBeforeDiagnosis);
    const baselineSnapshot = fixtureSnapshot(fixture);
    writeFileSync(path.join(bundle, 'before-snapshot.json'), `${JSON.stringify(baselineSnapshot)}\n`, 'utf8');
    assert.equal(diagnoseBundle({ bundle, stage: 'before' }, context).status, 'PASS');
    const beforeDiagnosis = JSON.parse(readFileSync(path.join(bundle, 'before-diagnosis.json'), 'utf8'));
    assert.deepEqual(beforeDiagnosis.baselineSnapshot, baselineSnapshot);
    assert.equal(readFileSync(hydrated.fixturePath, 'utf8'), fixtureBeforeDiagnosis);

    const changedSnapshot = { ...baselineSnapshot, rooms: [] };
    writeFileSync(path.join(bundle, 'before-snapshot.json'), `${JSON.stringify(changedSnapshot)}\n`, 'utf8');

    assert.throws(
      () => diagnoseBundle({ bundle, stage: 'before' }, context),
      /before-diagnosis\.json/,
    );
    assert.equal(readFileSync(hydrated.fixturePath, 'utf8'), fixtureBeforeDiagnosis);

    writeFileSync(path.join(bundle, 'after-snapshot.json'), `${JSON.stringify(baselineSnapshot)}\n`, 'utf8');
    writeFileSync(path.join(bundle, 'k6-summary.json'), `${JSON.stringify(t5Summary(7))}\n`, 'utf8');
    writePortableT5CompletionArtifacts(bundle, rendered);
    assert.equal(diagnoseBundle({ bundle, stage: 'after' }, context).status, 'PASS');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('portable bundle before diagnosis는 잘못된 snapshot에서 부분 artifact를 남기지 않는다', () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'room-k6-portable-before-reservation-'));
  const buildRoot = path.join(root, 'build', 'k6', 'room');
  const context = {
    repositoryRoot,
    scenarioDirectory: path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon'),
    buildRoot,
    bundleRoot: null,
    isBundleRuntime: false,
    environment: {
      ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$portable-before-reservation-test',
      ROOM_K6_READ_VUS: '7',
      ROOM_K6_READ_DURATION_SECONDS: '75',
      ROOM_K6_READ_THINK_TIME_MS: '1000',
    },
  };
  const provenance = { sourceRevision: 'b'.repeat(40), sourceDirty: false };

  try {
    const rendered = renderBundle({
      scenario: 't5',
      runId: `portable-before-reservation-${process.pid}-${Date.now()}`,
      profile: 'spike',
      t5Role: 'host',
      t5Scale: '1',
    }, context, provenance);
    const bundle = rendered.bundlePath;
    const plan = JSON.parse(readFileSync(path.join(bundle, 'fixture-plan.json'), 'utf8'));
    writeFileSync(
      path.join(bundle, 'resource-output.json'),
      `${JSON.stringify(fixtureResources(plan))}\n`,
      'utf8',
    );

    const hydrated = hydrateBundle(bundle, context);
    const fixtureBeforeDiagnosis = readFileSync(hydrated.fixturePath, 'utf8');
    writeFileSync(path.join(bundle, 'before-snapshot.json'), '{}\n', 'utf8');

    assert.throws(
      () => diagnoseBundle({ bundle, stage: 'before' }, context),
      /before-snapshot\.json은 rooms, participations, waitlists 배열을 포함해야 합니다/,
    );
    assert.equal(existsSync(path.join(bundle, 'before-diagnosis.json')), false);
    assert.equal(readFileSync(hydrated.fixturePath, 'utf8'), fixtureBeforeDiagnosis);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('portable bundle before diagnosis는 사후 실행 artifact가 있으면 baseline을 기록하지 않는다', () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'room-k6-portable-before-artifact-'));
  const buildRoot = path.join(root, 'build', 'k6', 'room');
  const context = {
    repositoryRoot,
    scenarioDirectory: path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon'),
    buildRoot,
    bundleRoot: null,
    isBundleRuntime: false,
    environment: { ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$portable-before-artifact-test' },
  };
  const provenance = { sourceRevision: 'b'.repeat(40), sourceDirty: false };
  const artifactContents = new Map([
    ['k6-summary.json', '{}\n'],
    ['k6-console.log', 'k6 output\n'],
    ['after-snapshot.json', '{}\n'],
    ['after-diagnosis.json', '{}\n'],
    ['final-result.json', '{}\n'],
    ['infra-execution.json', '{}\n'],
  ]);

  try {
    for (const [artifactName, contents] of artifactContents) {
      const rendered = renderBundle({
        scenario: 't1',
        runId: `portable-before-artifact-${artifactName.replace(/[^a-z0-9]/g, '-')}-${process.pid}-${Date.now()}`,
        profile: 'spike',
        mode: 'hot',
        concurrency: '2',
      }, context, provenance);
      const bundle = rendered.bundlePath;
      const plan = JSON.parse(readFileSync(path.join(bundle, 'fixture-plan.json'), 'utf8'));
      writeFileSync(path.join(bundle, 'resource-output.json'), `${JSON.stringify(fixtureResources(plan))}\n`, 'utf8');

      const hydrated = hydrateBundle(bundle, context);
      const fixtureBeforeDiagnosis = readFileSync(hydrated.fixturePath, 'utf8');
      const fixture = JSON.parse(fixtureBeforeDiagnosis);
      writeFileSync(path.join(bundle, 'before-snapshot.json'), `${JSON.stringify(fixtureSnapshot(fixture))}\n`, 'utf8');
      writeFileSync(path.join(bundle, artifactName), contents, 'utf8');

      assert.throws(
        () => diagnoseBundle({ bundle, stage: 'before' }, context),
        /사후 실행 artifact가 이미 있습니다/,
      );
      assert.equal(existsSync(path.join(bundle, 'before-diagnosis.json')), false);
      assert.equal(readFileSync(hydrated.fixturePath, 'utf8'), fixtureBeforeDiagnosis);
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('portable bundle은 원격 raw metadata와 두 진단을 PASS·FAIL·INVALID로 집계한다', () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'room-k6-portable-aggregate-'));
  const buildRoot = path.join(root, 'build', 'k6', 'room');
  const context = {
    repositoryRoot,
    scenarioDirectory: path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon'),
    buildRoot,
    bundleRoot: null,
    isBundleRuntime: false,
    environment: {
      ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$portable-aggregate-test',
      ROOM_K6_READ_VUS: '7',
      ROOM_K6_READ_DURATION_SECONDS: '75',
      ROOM_K6_READ_THINK_TIME_MS: '25',
    },
  };
  const provenance = { sourceRevision: 'c'.repeat(40), sourceDirty: false };

  try {
    const rendered = renderBundle({
      scenario: 't5',
      runId: `portable-aggregate-${process.pid}-${Date.now()}`,
      profile: 'spike',
      t5Role: 'host',
      t5Scale: '1',
    }, context, provenance);
    const bundle = rendered.bundlePath;
    const plan = JSON.parse(readFileSync(path.join(bundle, 'fixture-plan.json'), 'utf8'));
    writeFileSync(
      path.join(bundle, 'resource-output.json'),
      `${JSON.stringify(fixtureResources(plan))}\n`,
      'utf8',
    );

    const hydrated = hydrateBundle(bundle, context);
    const fixture = JSON.parse(readFileSync(hydrated.fixturePath, 'utf8'));
    const snapshot = fixtureSnapshot(fixture);
    writeFileSync(path.join(bundle, 'before-snapshot.json'), `${JSON.stringify(snapshot)}\n`, 'utf8');
    diagnoseBundle({ bundle, stage: 'before' }, context);
    writeFileSync(path.join(bundle, 'after-snapshot.json'), `${JSON.stringify(snapshot)}\n`, 'utf8');
    writeFileSync(path.join(bundle, 'k6-summary.json'), `${JSON.stringify(t5Summary(1))}\n`, 'utf8');
    writePortableT5CompletionArtifacts(bundle, rendered);
    diagnoseBundle({ bundle, stage: 'after' }, context);

    const executionPath = path.join(bundle, 'infra-execution.json');
    const finalResultPath = path.join(bundle, 'final-result.json');
    const afterDiagnosisPath = path.join(bundle, 'after-diagnosis.json');
    const summaryPath = path.join(bundle, 'k6-summary.json');
    const resourceSignalsPath = path.join(bundle, 'resource-signals.json');
    const originalSummary = readFileSync(summaryPath);
    const originalResourceSignals = readFileSync(resourceSignalsPath);
    const writeExecution = (failedPhase = null, exitCode = 0) => {
      writeFileSync(executionPath, `${JSON.stringify({
        schemaVersion: 1,
        runId: rendered.options.runId,
        fixtureId: rendered.fixtureId,
        phases: {
          prepare: { exitCode: failedPhase === 'prepare' ? exitCode : 0 },
          resourceQuery: { exitCode: failedPhase === 'resourceQuery' ? exitCode : 0 },
          beforeSnapshot: { exitCode: failedPhase === 'beforeSnapshot' ? exitCode : 0 },
          k6: { exitCode: failedPhase === 'k6' ? exitCode : 0 },
          afterSnapshot: { exitCode: failedPhase === 'afterSnapshot' ? exitCode : 0 },
        },
      })}\n`, 'utf8');
    };
    const aggregate = () => {
      rmSync(finalResultPath, { force: true });
      return aggregateBundle(bundle, context);
    };

    writeExecution();
    const passResult = aggregate();
    assert.equal(passResult.status, 'PASS');
    assert.equal(passResult.issues.length, 0);
    assert.deepEqual(readFileSync(summaryPath), originalSummary);

    rmSync(resourceSignalsPath);
    const missingCompletion = aggregate();
    assert.equal(missingCompletion.status, 'INVALID');
    assert.match(missingCompletion.issues.join('\n'), /resource-signals\.json/);
    writeFileSync(resourceSignalsPath, originalResourceSignals);

    for (const phaseName of ['prepare', 'resourceQuery', 'beforeSnapshot', 'k6', 'afterSnapshot']) {
      writeExecution(phaseName, 2);
      const phaseFailure = aggregate();
      assert.equal(phaseFailure.status, 'FAIL');
      assert.equal(phaseFailure.infraExecution.phases[phaseName].exitCode, 2);
    }

    writeExecution();
    const afterDiagnosis = JSON.parse(readFileSync(afterDiagnosisPath, 'utf8'));
    afterDiagnosis.status = 'FAIL';
    afterDiagnosis.failures = ['강제 FAIL'];
    writeFileSync(afterDiagnosisPath, `${JSON.stringify(afterDiagnosis)}\n`, 'utf8');
    const diagnosisFailure = aggregate();
    assert.equal(diagnosisFailure.status, 'FAIL');
    assert.equal(diagnosisFailure.afterDiagnosis.status, 'FAIL');

    afterDiagnosis.status = 'INVALID';
    afterDiagnosis.failures = ['강제 INVALID'];
    writeFileSync(afterDiagnosisPath, `${JSON.stringify(afterDiagnosis)}\n`, 'utf8');
    const invalidDiagnosis = aggregate();
    assert.equal(invalidDiagnosis.status, 'INVALID');
    assert.equal(invalidDiagnosis.afterDiagnosis.status, 'INVALID');

    afterDiagnosis.status = 'PASS';
    afterDiagnosis.failures = ['PASS와 모순되는 실패'];
    writeFileSync(afterDiagnosisPath, `${JSON.stringify(afterDiagnosis)}\n`, 'utf8');
    const contradictoryDiagnosis = aggregate();
    assert.equal(contradictoryDiagnosis.status, 'INVALID');
    assert.equal(contradictoryDiagnosis.afterDiagnosis, null);
    assert.match(contradictoryDiagnosis.issues[0], /after-diagnosis\.json/);

    delete afterDiagnosis.failures;
    writeFileSync(afterDiagnosisPath, `${JSON.stringify(afterDiagnosis)}\n`, 'utf8');
    const missingFailures = aggregate();
    assert.equal(missingFailures.status, 'INVALID');
    assert.equal(missingFailures.afterDiagnosis, null);

    afterDiagnosis.failures = [];
    writeFileSync(afterDiagnosisPath, `${JSON.stringify(afterDiagnosis)}\n`, 'utf8');
    writeExecution('k6', null);
    const incompleteMetadata = aggregate();
    assert.equal(incompleteMetadata.status, 'INVALID');
    assert.match(incompleteMetadata.issues[0], /phase exit code/);
    assert.equal(existsSync(finalResultPath), true);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
