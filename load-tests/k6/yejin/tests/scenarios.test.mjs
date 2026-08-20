import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import {
  chmodSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import test from 'node:test';

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const gameDirectory = path.resolve(testDirectory, '..');
const fakeApi = path.join(testDirectory, 'fake-game-api.mjs');

function bashPath(value) {
  if (process.platform !== 'win32') {
    return value;
  }

  return value
    .replaceAll('\\', '/')
    .replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);
}

async function startFakeApi(mode, requestLog) {
  const child = spawn(process.execPath, [fakeApi, mode, requestLog], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let stderr = '';
  child.stderr.on('data', (chunk) => {
    stderr += chunk;
  });

  const port = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error(`fake API did not start: ${stderr}`));
    }, 5_000);

    child.once('exit', (code) => {
      clearTimeout(timeout);
      reject(new Error(`fake API exited with ${code}: ${stderr}`));
    });
    child.stdout.once('data', (chunk) => {
      clearTimeout(timeout);
      resolve(Number.parseInt(String(chunk).trim(), 10));
    });
  });

  return {
    baseUrl: `http://127.0.0.1:${port}`,
    stop: () => child.kill('SIGTERM'),
  };
}

async function runScenario(scenario, mode, extraEnvironment = {}) {
  const temporaryDirectory = mkdtempSync(path.join(os.tmpdir(), 'game-k6-api-'));
  const requestLog = path.join(temporaryDirectory, 'requests.log');
  writeFileSync(requestLog, '');
  const server = await startFakeApi(mode, requestLog);

  try {
    const result = spawnSync(
      'k6',
      [
        'run',
        '--quiet',
        '--vus',
        '1',
        '--iterations',
        '1',
        '-e',
        `BASE_URL=${server.baseUrl}`,
        ...Object.entries(extraEnvironment).flatMap(([key, value]) => [
          '-e',
          `${key}=${value}`,
        ]),
        path.join(gameDirectory, scenario),
      ],
      { encoding: 'utf8' }
    );

    return {
      ...result,
      requests: readFileSync(requestLog, 'utf8'),
    };
  } finally {
    server.stop();
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
}

function createFakeK6(binDirectory) {
  const executable = path.join(binDirectory, 'k6');
  writeFileSync(
    executable,
    `#!/usr/bin/env bash
set -euo pipefail
if [[ "\${1:-}" == version ]]; then
  echo 'k6 v2.1.0'
  exit 0
fi
summary=''
script=''
while (($#)); do
  if [[ "$1" == --summary-export ]]; then
    summary="$2"
    shift 2
    continue
  fi
  script="$1"
  shift
done
printf '{"marker":"%s","metrics":{}}' "\${FAKE_K6_MARKER:-default}" > "$summary"
echo "fake k6 run \${FAKE_K6_MARKER:-default}"
if [[ "$script" == *00-game-keyword-contract.js ]]; then
  exit 0
fi
exit "\${FAKE_K6_EXIT:-0}"
`
  );
  chmodSync(executable, 0o755);
}

for (const scenario of ['02-game-keyword.js', '08-game-realistic.js', '09-game-list-concurrency.js']) {
  test(`${scenario} is a valid k6 bundle`, () => {
    const result = spawnSync('k6', ['inspect', path.join(gameDirectory, scenario)], {
      encoding: 'utf8',
    });

    assert.equal(
      result.status,
      0,
      result.stderr || result.stdout || `k6 inspect failed for ${scenario}`
    );
  });
}

test('index comparison runner is valid Bash and requires an index state', () => {
  const runner = path.join(gameDirectory, 'run-index-comparison.sh');
  const syntax = spawnSync('bash', ['-n', runner], { encoding: 'utf8' });
  assert.equal(syntax.status, 0, syntax.stderr || syntax.stdout);

  const result = spawnSync('bash', [runner], { encoding: 'utf8' });
  assert.equal(result.status, 2, result.stderr || result.stdout);
  assert.match(result.stderr, /INDEX_STATE must be no-pg-trgm or pg-trgm-gin/);
});

test('a non-200 keyword response fails the k6 run', async () => {
  const result = await runScenario('02-game-keyword.js', 'keyword-204', {
    KEYWORD: '누스피요르드',
  });

  assert.notEqual(result.status, 0, result.stderr || result.stdout);
  assert.match(`${result.stdout}\n${result.stderr}`, /checks/);
});

test('a non-200 realistic workload response fails the k6 run', async () => {
  const result = await runScenario('08-game-realistic.js', 'workload-204', {
    KEYWORD: '누스피요르드',
  });

  assert.notEqual(result.status, 0, result.stderr || result.stdout);
  assert.match(`${result.stdout}\n${result.stderr}`, /checks/);
});

test('the keyword scenario sends the configured fixed keyword', async () => {
  const result = await runScenario('02-game-keyword.js', 'success', {
    KEYWORD: '누스피요르드',
    EXPECTED_TOTAL_ELEMENTS: '1',
  });

  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(result.requests, /keyword=%EB%88%84%EC%8A%A4%ED%94%BC%EC%9A%94%EB%A5%B4%EB%93%9C/);
});

test('an unexpected fixed keyword total fails the contract preflight', async () => {
  const result = await runScenario('00-game-keyword-contract.js', 'keyword-wrong-total', {
    KEYWORD: '누스피요르드',
    EXPECTED_TOTAL_ELEMENTS: '1',
  });

  assert.notEqual(result.status, 0, result.stderr || result.stdout);
  assert.match(`${result.stdout}\n${result.stderr}`, /totalElements/);
});

test('a malformed keyword content fails the contract preflight', async () => {
  const result = await runScenario('00-game-keyword-contract.js', 'keyword-malformed', {
    KEYWORD: '누스피요르드',
    EXPECTED_TOTAL_ELEMENTS: '1',
  });

  assert.notEqual(result.status, 0, result.stderr || result.stdout);
  assert.match(`${result.stdout}\n${result.stderr}`, /data.content array/);
});

test('metadata API failure aborts realistic scenario setup', async () => {
  const result = await runScenario('08-game-realistic.js', 'metadata-503');

  assert.notEqual(result.status, 0, result.stderr || result.stdout);
  assert.match(`${result.stdout}\n${result.stderr}`, /game-categories/);
});

test('empty metadata codes abort realistic scenario setup', async () => {
  const result = await runScenario('08-game-realistic.js', 'metadata-empty');

  assert.notEqual(result.status, 0, result.stderr || result.stdout);
  assert.match(`${result.stdout}\n${result.stderr}`, /at least one code is required/);
});

test('malformed metadata data aborts realistic scenario setup', async () => {
  const result = await runScenario('08-game-realistic.js', 'metadata-malformed');

  assert.notEqual(result.status, 0, result.stderr || result.stdout);
  assert.match(`${result.stdout}\n${result.stderr}`, /data must be an array/);
});

test('index comparison runner requires reproducibility inputs', () => {
  const temporaryDirectory = mkdtempSync(path.join(os.tmpdir(), 'game-k6-runner-'));
  createFakeK6(temporaryDirectory);

  try {
    const runner = path.join(gameDirectory, 'run-index-comparison.sh');
    const result = spawnSync('bash', [runner], {
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${temporaryDirectory}${path.delimiter}${process.env.PATH}`,
        INDEX_STATE: 'no-pg-trgm',
        RESULT_DIR: bashPath(temporaryDirectory),
        KEYWORD: '누스피요르드',
        EXPECTED_TOTAL_ELEMENTS: '1',
        BENCHMARK_ID: 'review-591-inputs',
        RELEASE_SHA: 'a'.repeat(40),
        FIXTURE_SHA256: 'b'.repeat(64),
      },
    });

    assert.equal(result.status, 2, result.stderr || result.stdout);
    assert.match(result.stderr, /FIXTURE_ID is required/);
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test('index comparison runner requires the expected keyword total', () => {
  const temporaryDirectory = mkdtempSync(path.join(os.tmpdir(), 'game-k6-runner-'));
  createFakeK6(temporaryDirectory);

  try {
    const runner = path.join(gameDirectory, 'run-index-comparison.sh');
    const result = spawnSync('bash', [runner], {
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${temporaryDirectory}${path.delimiter}${process.env.PATH}`,
        INDEX_STATE: 'no-pg-trgm',
        RESULT_DIR: bashPath(temporaryDirectory),
        KEYWORD: '누스피요르드',
        BENCHMARK_ID: 'review-591-expected-total',
        RELEASE_SHA: 'a'.repeat(40),
        FIXTURE_ID: 'catalog-170k-v1',
        FIXTURE_SHA256: 'b'.repeat(64),
      },
    });

    assert.equal(result.status, 2, result.stderr || result.stdout);
    assert.match(result.stderr, /EXPECTED_TOTAL_ELEMENTS is required/);
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test('paired index runs share a manifest and preserve logs', () => {
  const temporaryDirectory = mkdtempSync(path.join(os.tmpdir(), 'game-k6-pair-'));
  const binDirectory = path.join(temporaryDirectory, 'bin');
  const resultDirectory = path.join(temporaryDirectory, 'results');
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);
  const runner = path.join(gameDirectory, 'run-index-comparison.sh');
  const environment = {
    ...process.env,
    PATH: `${binDirectory}${path.delimiter}${process.env.PATH}`,
    RESULT_DIR: bashPath(resultDirectory),
    BENCHMARK_ID: 'review-591-pair',
    KEYWORD: '누스피요르드',
    EXPECTED_TOTAL_ELEMENTS: '1',
    RELEASE_SHA: 'a'.repeat(40),
    FIXTURE_ID: 'catalog-170k-v1',
    FIXTURE_SHA256: 'b'.repeat(64),
  };

  try {
    for (const indexState of ['no-pg-trgm', 'pg-trgm-gin']) {
      const result = spawnSync('bash', [runner], {
        encoding: 'utf8',
        env: { ...environment, INDEX_STATE: indexState },
      });
      assert.equal(result.status, 0, result.stderr || result.stdout);
    }

    const manifest = JSON.parse(
      readFileSync(path.join(resultDirectory, 'review-591-pair.manifest.json'), 'utf8')
    );
    assert.equal(manifest.version, 2);
    assert.equal(manifest.invariants.keyword, '누스피요르드');
    assert.equal(manifest.invariants.expectedTotalElements, '1');
    assert.match(manifest.invariants.contractSha256, /^[0-9a-f]{64}$/);
    assert.equal(manifest.invariants.fixtureId, 'catalog-170k-v1');
    assert.deepEqual(Object.keys(manifest.runs).sort(), [
      'no-pg-trgm',
      'pg-trgm-gin',
    ]);

    for (const indexState of ['no-pg-trgm', 'pg-trgm-gin']) {
      for (const scenario of ['02-game-keyword', '08-game-realistic']) {
        const log = path.join(
          resultDirectory,
          `${indexState}-review-591-pair-${scenario}-load.log`
        );
        assert.match(readFileSync(log, 'utf8'), /fake k6 run/);
      }
    }
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test('an existing benchmark index state rejects rerun without overwriting artifacts', () => {
  const temporaryDirectory = mkdtempSync(path.join(os.tmpdir(), 'game-k6-rerun-'));
  const binDirectory = path.join(temporaryDirectory, 'bin');
  const resultDirectory = path.join(temporaryDirectory, 'results');
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);
  const runner = path.join(gameDirectory, 'run-index-comparison.sh');
  const environment = {
    ...process.env,
    PATH: `${binDirectory}${path.delimiter}${process.env.PATH}`,
    RESULT_DIR: bashPath(resultDirectory),
    INDEX_STATE: 'no-pg-trgm',
    BENCHMARK_ID: 'review-591-rerun',
    KEYWORD: '누스피요르드',
    EXPECTED_TOTAL_ELEMENTS: '1',
    RELEASE_SHA: 'a'.repeat(40),
    FIXTURE_ID: 'catalog-170k-v1',
    FIXTURE_SHA256: 'b'.repeat(64),
  };

  try {
    const first = spawnSync('bash', [runner], {
      encoding: 'utf8',
      env: { ...environment, FAKE_K6_MARKER: 'first' },
    });
    assert.equal(first.status, 0, first.stderr || first.stdout);

    const summary = path.join(
      resultDirectory,
      'no-pg-trgm-review-591-rerun-02-game-keyword-load.summary.json'
    );
    const before = readFileSync(summary, 'utf8');

    const rerun = spawnSync('bash', [runner], {
      encoding: 'utf8',
      env: { ...environment, FAKE_K6_MARKER: 'second' },
    });
    assert.equal(rerun.status, 2, rerun.stderr || rerun.stdout);
    assert.match(rerun.stderr, /no-pg-trgm is already recorded/);
    assert.equal(readFileSync(summary, 'utf8'), before);
    assert.match(before, /first/);
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test('threshold failures are recorded before the runner returns non-zero', () => {
  const temporaryDirectory = mkdtempSync(path.join(os.tmpdir(), 'game-k6-failure-'));
  const binDirectory = path.join(temporaryDirectory, 'bin');
  const resultDirectory = path.join(temporaryDirectory, 'results');
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);

  try {
    const runner = path.join(gameDirectory, 'run-index-comparison.sh');
    const result = spawnSync('bash', [runner], {
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${binDirectory}${path.delimiter}${process.env.PATH}`,
        RESULT_DIR: bashPath(resultDirectory),
        INDEX_STATE: 'no-pg-trgm',
        BENCHMARK_ID: 'review-591-threshold-failure',
        KEYWORD: '누스피요르드',
        EXPECTED_TOTAL_ELEMENTS: '1',
        RELEASE_SHA: 'a'.repeat(40),
        FIXTURE_ID: 'catalog-170k-v1',
        FIXTURE_SHA256: 'b'.repeat(64),
        FAKE_K6_EXIT: '99',
      },
    });

    assert.equal(result.status, 99, result.stderr || result.stdout);
    const manifest = JSON.parse(
      readFileSync(
        path.join(resultDirectory, 'review-591-threshold-failure.manifest.json'),
        'utf8'
      )
    );
    assert.equal(manifest.runs['no-pg-trgm'].scenarios.length, 3);
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test('paired index runs reject changed fixture provenance', () => {
  const temporaryDirectory = mkdtempSync(path.join(os.tmpdir(), 'game-k6-pair-'));
  const binDirectory = path.join(temporaryDirectory, 'bin');
  const resultDirectory = path.join(temporaryDirectory, 'results');
  mkdirSync(binDirectory, { recursive: true });
  createFakeK6(binDirectory);
  const runner = path.join(gameDirectory, 'run-index-comparison.sh');
  const environment = {
    ...process.env,
    PATH: `${binDirectory}${path.delimiter}${process.env.PATH}`,
    RESULT_DIR: bashPath(resultDirectory),
    BENCHMARK_ID: 'review-591-mismatch',
    KEYWORD: '누스피요르드',
    EXPECTED_TOTAL_ELEMENTS: '1',
    RELEASE_SHA: 'a'.repeat(40),
    FIXTURE_ID: 'catalog-170k-v1',
  };

  try {
    const baseline = spawnSync('bash', [runner], {
      encoding: 'utf8',
      env: {
        ...environment,
        INDEX_STATE: 'no-pg-trgm',
        FIXTURE_SHA256: 'b'.repeat(64),
      },
    });
    assert.equal(baseline.status, 0, baseline.stderr || baseline.stdout);

    const changed = spawnSync('bash', [runner], {
      encoding: 'utf8',
      env: {
        ...environment,
        INDEX_STATE: 'pg-trgm-gin',
        FIXTURE_SHA256: 'c'.repeat(64),
      },
    });
    assert.equal(changed.status, 2, changed.stderr || changed.stdout);
    assert.match(changed.stderr, /fixtureSha256 does not match/);
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});
