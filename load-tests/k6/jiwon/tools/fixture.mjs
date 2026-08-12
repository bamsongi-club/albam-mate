#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
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
const COMMAND_OPTION_KEYS = {
  prepare: new Set([
    'scenario', 'runId', 'profile', 'rounds', 'mode', 'concurrency', 'subcase', 't3Mode', 't5Role', 't5Scale',
  ]),
  verify: new Set(['fixture', 'stage', 'summary']),
  cleanup: new Set(['fixture']),
};

function usage() {
  return `사용법:
  node load-tests/k6/jiwon/tools/fixture.mjs prepare --scenario t1 --run-id <run-id> [옵션]
  node load-tests/k6/jiwon/tools/fixture.mjs verify --fixture <fixture.json> --stage before|after [--summary <summary.json>]
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

function verify(values) {
  const stage = String(values.stage || '').trim();
  if (stage !== 'before' && stage !== 'after') {
    fail('--stage는 before 또는 after여야 합니다.');
  }
  const { fixturePath, fixture } = readFixture(values.fixture);
  let summary = null;
  if (stage === 'after') {
    if (!values.summary) {
      fail('after 검증에는 --summary <k6 summary JSON>이 필요합니다.');
    }
    try {
      summary = JSON.parse(readFileSync(path.resolve(values.summary), 'utf8'));
    } catch (_) {
      fail(`k6 summary를 읽을 수 없습니다: ${values.summary}`);
    }
  }

  const snapshot = queryJson(buildSnapshotQuery(fixture));
  const result = {
    fixtureId: fixture.fixtureId,
    scenario: fixture.options.scenario,
    stage,
    ...evaluateFixture(fixture, snapshot, stage, summary),
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
