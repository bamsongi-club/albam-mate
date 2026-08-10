import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

import {
  buildFixture,
  parseArguments,
  renderPrepareSql,
  renderVerifySql,
  repositoryRoot,
  writeFixtureBundle,
} from './fixture.mjs';

function options(...args) {
  return { ...parseArguments(args), passwordOverride: 'fixture-password' };
}

function allEntityIds(fixture) {
  return [
    ...[...fixture.model.usersByKey.values()].map((user) => user.id),
    ...fixture.model.rooms.map((room) => room.id),
    ...fixture.model.participations.map((participation) => participation.id),
    ...fixture.model.chatRooms.map((chatRoom) => chatRoom.id),
  ];
}

test('취소-자동 승격은 hot/spread와 VU 2/4/8을 같은 wave 수로 만든다', () => {
  const fixtureOptions = options('cancel-promotion', '--seed', 'cancel-test');
  fixtureOptions.passwordOverride = "fixture'password";
  const fixture = buildFixture(fixtureOptions);

  assert.deepEqual(
    fixture.manifest.configurations.map((configuration) => configuration.id),
    ['hot-2', 'spread-2', 'hot-4', 'spread-4', 'hot-8', 'spread-8'],
  );
  for (const configuration of fixture.manifest.configurations) {
    assert.equal(configuration.waveCount, 11);
    assert.equal(configuration.targets.length, 11);
    assert.ok(configuration.targets.every((targets) => targets.length === configuration.vus));
  }

  const hotEight = fixture.manifest.configurations.find((configuration) => configuration.id === 'hot-8');
  const spreadEight = fixture.manifest.configurations.find((configuration) => configuration.id === 'spread-8');
  assert.equal(new Set(hotEight.targets[0].map((target) => target.roomId)).size, 1);
  assert.equal(new Set(spreadEight.targets[0].map((target) => target.roomId)).size, 8);
  assert.equal(Math.max(...fixture.model.rooms.map((room) => room.capacity)), 8);

  const ids = allEntityIds(fixture);
  assert.equal(new Set(ids).size, ids.length);
  assert.ok(ids.every((id) => Number.isSafeInteger(id) && id > 0));

  const prepareSql = renderPrepareSql(fixture);
  assert.match(prepareSql, /crypt\('fixture''password', gen_salt\('bf', 10\)\)/);
  assert.match(renderVerifySql(fixture), /ROOM_K6_PROMOTION_SUCCESS_MISMATCH/);
  assert.match(renderVerifySql(fixture), /ROOM_K6_DUPLICATE_ACTIVE_PARTICIPATION/);
});

test('대기 등록은 새 사용자만 대상으로 하고 초기 WAITING 행을 만들지 않는다', () => {
  const fixture = buildFixture(options(
    'waitlist-registration', '--seed', 'registration-test',
  ));

  assert.equal(fixture.model.waitlists.length, 0);
  assert.ok(fixture.model.rooms.every((room) => room.activeParticipantCount === room.capacity));
  assert.match(renderVerifySql(fixture), /ROOM_K6_WAITLIST_SUCCESS_MISMATCH/);
});

test('due backlog는 ROOM-09d의 CLOSED ROOM당 WAITING 10명을 재현한다', () => {
  const fixture = buildFixture(options(
    'due-backlog-read', '--seed', 'due-test',
    '--endpoint', 'my-rooms', '--due-room-count', '20', '--vus', '8',
  ));
  const measuredRooms = fixture.model.rooms.filter((room) => room.phase === 'measure');

  assert.equal(measuredRooms.length, 20);
  assert.equal(measuredRooms.filter((room) => room.status === 'RECRUITING').length, 10);
  assert.equal(measuredRooms.filter((room) => room.status === 'CLOSED').length, 10);
  assert.equal(fixture.model.waitlists.length, 100);
  assert.equal(fixture.model.rooms.filter((room) => room.phase === 'control').length, 10);
  assert.equal(fixture.manifest.configuration.endpoint, 'my-rooms');
  assert.equal(fixture.manifest.configuration.recruitingDueRoomCount, 10);
  assert.equal(fixture.manifest.configuration.closedDueRoomCount, 10);
  assert.equal(fixture.manifest.configuration.waitingPerClosedDueRoom, 10);
  assert.equal(fixture.manifest.configuration.expectedExpiredWaitlistCount, 100);
  assert.deepEqual(fixture.manifest.loginUserKeys, ['reader']);
  assert.match(renderPrepareSql(fixture), /ROOM_K6_PREPARE_WAITING_COUNT_MISMATCH/);
  assert.match(renderVerifySql(fixture), /ROOM_K6_DUE_WAITLIST_STATUS_MISMATCH/);
});

test('상세 조회 역할에 따라 인증 사용자를 정확히 선택한다', () => {
  const publicFixture = buildFixture(options(
    'room-detail', '--seed', 'detail-public', '--role', 'public',
  ));
  const hostFixture = buildFixture(options(
    'room-detail', '--seed', 'detail-host', '--role', 'host',
  ));
  const participantFixture = buildFixture(options(
    'room-detail', '--seed', 'detail-participant',
    '--role', 'participant', '--active-participant-count', '10',
  ));

  assert.deepEqual(publicFixture.manifest.loginUserKeys, []);
  assert.deepEqual(hostFixture.manifest.loginUserKeys, ['host']);
  assert.deepEqual(participantFixture.manifest.loginUserKeys, ['participant-0']);
  assert.equal(participantFixture.model.participations.length, 10);
});

test('대기 순번 head/tail fixture의 기대 순번이 큐 길이와 일치한다', () => {
  const head = buildFixture(options(
    'waitlist-position', '--seed', 'position-head',
    '--queue-length', '1000', '--position', 'head',
  ));
  const tail = buildFixture(options(
    'waitlist-position', '--seed', 'position-tail',
    '--queue-length', '1000', '--position', 'tail',
  ));

  assert.equal(head.manifest.configuration.expectedPosition, 1);
  assert.equal(tail.manifest.configuration.expectedPosition, 1000);
  assert.equal(head.model.waitlists.length, 1000);
  assert.match(renderVerifySql(tail), /ROOM_K6_QUEUE_POSITION_CHANGED/);
});

test('bundle은 marker가 있는 ignored output만 교체하고 metadata에 비밀번호를 남기지 않는다', () => {
  const output = resolve(repositoryRoot(), 'build/k6/room/fixture-node-test');
  const unsafeOutput = resolve(repositoryRoot(), 'build/k6/room/fixture-node-test-unsafe');
  rmSync(output, { recursive: true, force: true });
  rmSync(unsafeOutput, { recursive: true, force: true });

  const fixtureOptions = options(
    'room-detail', '--seed', 'bundle-test',
    '--role', 'host', '--output', output,
  );
  fixtureOptions.passwordOverride = 'do-not-copy-this-password';
  const fixture = buildFixture(fixtureOptions);

  try {
    writeFixtureBundle(fixtureOptions, fixture);
    assert.ok(existsSync(resolve(output, '.room-k6-fixture-bundle')));
    assert.ok(existsSync(resolve(output, 'scenario.js')));
    assert.match(readFileSync(resolve(output, 'users.json'), 'utf8'), /do-not-copy-this-password/);
    assert.doesNotMatch(readFileSync(resolve(output, 'source-metadata.json'), 'utf8'),
      /do-not-copy-this-password/);

    writeFixtureBundle(fixtureOptions, fixture);
    mkdirSync(unsafeOutput, { recursive: true });
    writeFileSync(resolve(unsafeOutput, 'owned-by-user.txt'), 'keep');
    const unsafeOptions = { ...fixtureOptions, output: unsafeOutput };
    assert.throws(() => writeFixtureBundle(unsafeOptions, fixture),
      /room-k6-fixture-bundle.*없어 삭제하지 않습니다/);
    assert.ok(existsSync(resolve(unsafeOutput, 'owned-by-user.txt')));
  } finally {
    rmSync(output, { recursive: true, force: true });
    rmSync(unsafeOutput, { recursive: true, force: true });
  }
});

test('위험하거나 잘못된 입력을 거부한다', () => {
  assert.throws(() => options('cancel-promotion', '--levels', '16'), /10 이하여야/);
  assert.throws(() => options('waitlist-registration', '--levels', '16', '--modes', 'hot,spread'),
    /최대 로그인 수\(32\).*제한\(30\)/);
  assert.doesNotThrow(() => options('waitlist-registration', '--levels', '16', '--modes', 'hot'));
  assert.throws(() => options('room-detail', '--duration', 'one-minute'), /duration 형식/);
  assert.throws(() => options('room-detail', '--duration', '0m'), /duration 형식/);
  assert.throws(() => options('waitlist-position', '--queue-length', '10001'), /10000 이하여야/);
  assert.throws(() => options('due-backlog-read', '--unknown', 'value'), /지원하지 않는 옵션/);
});
