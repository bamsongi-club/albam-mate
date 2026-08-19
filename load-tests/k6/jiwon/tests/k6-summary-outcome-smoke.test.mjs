import assert from 'node:assert/strict';
import {
  chmodSync,
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
} from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(testDirectory, '../../../..');
const fixturePath = path.join(testDirectory, 'k6-summary-outcome-smoke.js');
const k6Image = 'grafana/k6:1.3.0';
const outcomeMetricNames = [
  'room_request_duration{outcome:success}',
  'room_request_duration{outcome:business}',
  'room_request_duration{outcome:concurrency}',
  'room_request_duration{outcome:unexpected}',
];

function runK6Container(arguments_) {
  return spawnSync('docker', [
    'run',
    '--rm',
    '--network',
    'none',
    '--volume',
    `${repositoryRoot}:/work`,
    k6Image,
    ...arguments_,
  ], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    timeout: 120_000,
    windowsHide: true,
  });
}

function commandOutput(result) {
  return [result.stdout, result.stderr, result.error?.message]
    .filter(Boolean)
    .join('\n');
}

function assertCommandSucceeded(result, label) {
  assert.equal(
    result.error,
    undefined,
    `${label} 실행 실패:\n${commandOutput(result)}`,
  );
  assert.equal(
    result.status,
    0,
    `${label} exit code가 0이 아닙니다:\n${commandOutput(result)}`,
  );
}

test('k6 1.3.0 summary-export는 관측·무표본 outcome을 모두 생성하고 성공한다', (t) => {
  const smokeParent = path.join(repositoryRoot, 'build', 'k6', 'room');
  mkdirSync(smokeParent, { recursive: true });
  const smokeDirectory = mkdtempSync(path.join(smokeParent, 'summary-outcome-smoke-'));
  chmodSync(smokeDirectory, 0o777);
  const summaryPath = path.join(smokeDirectory, 'summary.json');
  const containerSummaryPath = `/work/${path.relative(repositoryRoot, summaryPath).replaceAll(path.sep, '/')}`;
  const containerFixturePath = `/work/${path.relative(repositoryRoot, fixturePath).replaceAll(path.sep, '/')}`;
  t.after(() => rmSync(smokeDirectory, { recursive: true, force: true }));

  const version = runK6Container(['version']);
  assertCommandSucceeded(version, `${k6Image} version`);
  assert.match(commandOutput(version), /k6 v1\.3\.0/);

  const run = runK6Container([
    'run',
    '--summary-export',
    containerSummaryPath,
    containerFixturePath,
  ]);
  assertCommandSucceeded(run, `${k6Image} summary-export smoke`);
  assert.equal(
    existsSync(summaryPath),
    true,
    `k6 summary-export 파일이 생성되어야 합니다.\n${commandOutput(run)}`,
  );

  const rawSummary = JSON.parse(readFileSync(summaryPath, 'utf8'));
  const metrics = rawSummary.metrics;
  assert.ok(metrics && typeof metrics === 'object', 'raw summary에 metrics가 있어야 합니다.');

  const actualOutcomeMetricNames = Object.keys(metrics)
    .filter((name) => name.startsWith('room_request_duration{outcome:'))
    .sort();
  assert.deepEqual(actualOutcomeMetricNames, [...outcomeMetricNames].sort());

  assert.equal(metrics['room_request_duration{outcome:success}'].count, 1);
  assert.equal(metrics['room_request_duration{outcome:unexpected}'].count, 1);
  assert.equal(metrics['room_request_duration{outcome:business}'].count, 0);
  assert.equal(metrics['room_request_duration{outcome:concurrency}'].count, 0);
});
