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
writeFileSync(args[summaryIndex + 1], '{"metrics":{}}\\n', 'utf8');
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
writeFileSync(args[summaryIndex + 1], '{"metrics":{}}\\n', 'utf8');
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
