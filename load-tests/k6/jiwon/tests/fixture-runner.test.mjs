import assert from 'node:assert/strict';
import {
  chmodSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { createHash } from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(testDirectory, '../../../..');
const fixtureTool = path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon', 'tools', 'fixture.mjs');
const fixtureBuildRoot = path.join(repositoryRoot, 'build', 'k6', 'room');

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
writeFileSync(args[summaryIndex + 1], JSON.stringify(summary) + '\\n', 'utf8');
process.exit(Number.parseInt(process.env.FAKE_K6_EXIT || '0', 10));
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
writeFileSync(args[summaryIndex + 1], JSON.stringify(summary) + '\\n', 'utf8');
process.exit(Number.parseInt(process.env.FAKE_K6_EXIT || '0', 10));
`, 'utf8');

  const executablePath = path.join(binDirectory, 'k6');
  writeFileSync(executablePath, `#!/usr/bin/env node\nimport '${programPath.replaceAll('\\\\', '\\\\\\')}';\n`, 'utf8');
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
  }, null, 2)}\n`, 'utf8');
  return fixturePath;
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

function verifyAfter(fixturePath) {
  return spawnSync(process.execPath, [fixtureTool, 'verify', '--fixture', fixturePath, '--stage', 'after'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: process.env,
  });
}

function compareT5(runId) {
  return spawnSync(process.execPath, [fixtureTool, 'compare-t5', '--run-id', runId], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: process.env,
  });
}

function sha256(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function createTestDirectory() {
  mkdirSync(fixtureBuildRoot, { recursive: true });
  return mkdtempSync(path.join(fixtureBuildRoot, 'fixture-runner-test-'));
}

test('run은 성공한 k6 실행의 provenance manifest와 summary를 같은 fixture에 남긴다', () => {
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

test('T5 run은 유효 VU·duration·think time을 manifest에 기록한다', () => {
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

test('T5 비교는 여섯 역할·규모 실행의 read profile 불일치를 거절한다', () => {
  const runId = `runner-t5-compare-${process.pid}`;
  const comparisonDirectory = path.join(fixtureBuildRoot, runId);
  const binDirectory = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bin-'));
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

test('after 검증은 manifest와 다른 k6 summary를 INVALID로 거절한다', () => {
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

test('run은 k6 비정상 종료에도 종료 시각과 exit code를 보존한다', () => {
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
