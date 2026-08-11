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
    ['stress-hot-2', 'stress-spread-2', 'stress-hot-4', 'stress-spread-4',
      'stress-hot-8', 'stress-spread-8'],
  );
  for (const configuration of fixture.manifest.configurations) {
    assert.equal(configuration.loadProfile, 'stress');
    assert.equal(configuration.waveCount, 11);
    assert.equal(configuration.maxDurationSeconds, 73);
    assert.equal(configuration.targets.length, 11);
    assert.ok(configuration.targets.every((targets) => targets.length === configuration.vus));
  }
  const configurations = fixture.manifest.configurations;
  for (let index = 1; index < configurations.length; index += 1) {
    const previous = configurations[index - 1];
    assert.equal(
      configurations[index].startOffsetSeconds,
      previous.startOffsetSeconds + previous.maxDurationSeconds,
    );
  }

  const hotEight = fixture.manifest.configurations.find(
    (configuration) => configuration.id === 'stress-hot-8',
  );
  const spreadEight = fixture.manifest.configurations.find(
    (configuration) => configuration.id === 'stress-spread-8',
  );
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

test('01/02는 stress 필수와 spike 단일 burst를 profile별 manifest에 기록한다', () => {
  const cases = [
    { loadProfile: 'stress', warmupWaves: 1, measuredWaves: 10 },
    { loadProfile: 'spike', warmupWaves: 0, measuredWaves: 1 },
  ];

  for (const scenario of ['cancel-promotion', 'waitlist-registration']) {
    for (const expected of cases) {
      const fixture = buildFixture(options(
        scenario,
        '--seed', `${scenario}-${expected.loadProfile}`,
        '--levels', '2',
        '--modes', 'hot',
        '--load-profile', expected.loadProfile,
      ));
      const [configuration] = fixture.manifest.configurations;

      assert.equal(fixture.manifest.classification.category, 'write-contention');
      assert.equal(fixture.manifest.classification.loadProfiles.stress, 'required');
      assert.equal(fixture.manifest.classification.loadProfiles.spike, 'recommended');
      assert.equal(fixture.manifest.classification.loadProfiles.soak, 'excluded');
      assert.equal(configuration.loadProfile, expected.loadProfile);
      assert.equal(configuration.warmupWaves, expected.warmupWaves);
      assert.equal(configuration.measuredWaves, expected.measuredWaves);
      assert.equal(configuration.waveCount, expected.warmupWaves + expected.measuredWaves);
      assert.equal(configuration.id, `${expected.loadProfile}-hot-2`);
    }
  }
});

test('due backlog 조회는 저장 상태 보존과 Scheduler 격리 계약을 만든다', () => {
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
  assert.equal(fixture.manifest.configuration.controlRoomCount, 10);
  assert.equal(fixture.manifest.configuration.expectedWaitingWaitlistCount, 100);
  assert.equal(fixture.manifest.configuration.duration, '1m');
  assert.equal(fixture.manifest.configuration.thinkTimeSeconds, 1);
  assert.equal(fixture.manifest.configuration.schedulerLockName, 'room-status-correction');
  assert.equal(fixture.manifest.configuration.schedulerLockDurationSeconds, 300);
  assert.deepEqual(fixture.manifest.loginUserKeys, ['reader']);
  const prepareSql = renderPrepareSql(fixture);
  const verifySql = renderVerifySql(fixture);
  assert.match(prepareSql, /ROOM_K6_PREPARE_WAITING_COUNT_MISMATCH/);
  assert.match(prepareSql, /ROOM_K6_STATUS_CORRECTION_LOCK_NOT_ACQUIRED/);
  assert.ok(prepareSql.indexOf('ANALYZE room_waitlists;')
    < prepareSql.indexOf('ROOM_K6_STATUS_CORRECTION_LOCK_NOT_ACQUIRED'));
  assert.match(verifySql, /ROOM_K6_STATUS_CORRECTION_LOCK_LOST/);
  assert.match(verifySql, /ROOM_K6_DUE_BACKLOG_CHANGED/);
  assert.match(verifySql, /ROOM_K6_DUE_STORED_STATUS_CHANGED/);
  assert.match(verifySql, /ROOM_K6_DUE_ROOM_VERSION_CHANGED/);
  assert.match(verifySql, /ROOM_K6_DUE_EFFECTIVE_STATUS_MISMATCH/);
  assert.match(verifySql, /ROOM_K6_DUE_ROOM_TIMESTAMP_CHANGED/);
  assert.match(verifySql, /ROOM_K6_DUE_WAITLIST_CHANGED/);
  assert.match(verifySql, /ROOM_K6_DUE_WAITLIST_TIMESTAMP_CHANGED/);
  assert.match(verifySql, /ROOM_K6_DUE_CHAT_ROOM_COUNT_CHANGED/);
  assert.match(verifySql, /ROOM_K6_DUE_CHAT_RETENTION_CHANGED/);
  assert.match(verifySql, /ROOM_K6_STATUS_CORRECTION_LOCK_RELEASE_FAILED/);
});

test('03은 endpoint × due 규모 × VU matrix와 stress/spike 실행 계약을 만든다', () => {
  const endpoints = ['room-list', 'my-rooms'];
  const dueRoomCounts = [0, 20, 2_000];
  const vusLevels = [2, 4, 8];
  const loadProfiles = ['stress', 'spike'];

  for (const endpoint of endpoints) {
    for (const dueRoomCount of dueRoomCounts) {
      for (const vus of vusLevels) {
        for (const loadProfile of loadProfiles) {
          const fixture = buildFixture(options(
            'due-backlog-read',
            '--seed', `due-${endpoint}-${dueRoomCount}-${vus}-${loadProfile}`,
            '--endpoint', endpoint,
            '--due-room-count', String(dueRoomCount),
            '--vus', String(vus),
            '--load-profile', loadProfile,
            '--duration', '45s',
            '--think-time-seconds', '2',
          ));
          const configuration = fixture.manifest.configuration;

          assert.equal(fixture.manifest.classification.loadProfiles.stress, 'required');
          assert.equal(fixture.manifest.classification.category, 'read-write-contention');
          assert.equal(fixture.manifest.classification.loadProfiles.spike, 'recommended');
          assert.equal(configuration.endpoint, endpoint);
          assert.equal(configuration.dueRoomCount, dueRoomCount);
          assert.equal(configuration.vus, vus);
          assert.equal(configuration.loadProfile, loadProfile);
          assert.equal(configuration.duration, '45s');
          assert.equal(configuration.thinkTimeSeconds, 2);
          assert.equal(configuration.spikeRampSeconds, loadProfile === 'spike' ? 1 : null);
          assert.equal(fixture.model.rooms.filter((room) => room.phase === 'measure').length, dueRoomCount);
          assert.equal(fixture.model.waitlists.length, configuration.expectedWaitingWaitlistCount);
          assert.deepEqual(fixture.manifest.loginUserKeys, endpoint === 'my-rooms' ? ['reader'] : []);
        }
      }
    }
  }

  const cleanFixture = buildFixture(options(
    'due-backlog-read', '--seed', 'due-clean-verify',
    '--endpoint', 'room-list', '--due-room-count', '0',
  ));
  assert.equal(cleanFixture.manifest.configuration.recruitingDueRoomCount, 0);
  assert.equal(cleanFixture.manifest.configuration.closedDueRoomCount, 0);
  assert.equal(cleanFixture.manifest.configuration.expectedWaitingWaitlistCount, 0);
  assert.match(renderPrepareSql(cleanFixture), /ROOM_K6_PREPARE_DUE_COUNT_MISMATCH/);
  assert.match(renderVerifySql(cleanFixture), /ROOM_K6_DUE_ROOM_COUNT_CHANGED/);
});

test('04는 role × active participants × load profile 응답 계약을 manifest에 만든다', () => {
  const roles = [
    { role: 'public', userKey: null, myRole: null },
    { role: 'host', userKey: 'host', myRole: 'HOST' },
    { role: 'participant', userKey: 'participant-0', myRole: 'JOINED' },
  ];
  const activeParticipantCounts = [1, 10];
  const loadProfiles = ['stress', 'spike', 'soak'];

  for (const expectedRole of roles) {
    for (const activeParticipantCount of activeParticipantCounts) {
      for (const loadProfile of loadProfiles) {
        const args = [
          'room-detail',
          '--seed', `detail-${expectedRole.role}-${activeParticipantCount}-${loadProfile}`,
          '--role', expectedRole.role,
          '--active-participant-count', String(activeParticipantCount),
          '--load-profile', loadProfile,
        ];
        if (loadProfile === 'soak') {
          args.push('--duration', '15m');
        }
        const fixture = buildFixture(options(...args));
        const configuration = fixture.manifest.configuration;

        assert.equal(fixture.manifest.classification.loadProfiles.stress, 'required');
        assert.equal(fixture.manifest.classification.category, 'read-load');
        assert.equal(fixture.manifest.classification.loadProfiles.spike, 'recommended');
        assert.equal(fixture.manifest.classification.loadProfiles.soak, 'future-recommended');
        assert.equal(configuration.expectedParticipantCount, activeParticipantCount + 1);
        assert.equal(configuration.expectedRemainingRecruitmentSeats, 10 - activeParticipantCount);
        assert.equal(configuration.expectedMyRole, expectedRole.myRole);
        assert.equal(configuration.expectedParticipantsLength, expectedRole.myRole
          ? activeParticipantCount + 1
          : null);
        assert.equal(configuration.loadProfile, loadProfile);
        assert.equal(configuration.spikeRampSeconds, loadProfile === 'spike' ? 1 : null);
        assert.equal(configuration.durationExplicit, loadProfile === 'soak');
        assert.deepEqual(fixture.manifest.loginUserKeys, expectedRole.userKey
          ? [expectedRole.userKey]
          : []);
        assert.equal(fixture.model.participations.length, activeParticipantCount);
      }
    }
  }
});

test('05는 data-scale 저경합 조건에서 head/middle/tail 순번과 VU 1을 고정한다', () => {
  const queueLengths = [10, 100, 1_000, 10_000];
  const positions = ['head', 'middle', 'tail'];

  for (const queueLength of queueLengths) {
    for (const position of positions) {
      const fixture = buildFixture(options(
        'waitlist-position',
        '--seed', `position-${queueLength}-${position}`,
        '--queue-length', String(queueLength),
        '--position', position,
      ));
      const configuration = fixture.manifest.configuration;
      const expectedPosition = position === 'head'
        ? 1
        : position === 'middle' ? Math.ceil(queueLength / 2) : queueLength;

      assert.equal(fixture.manifest.classification.category, 'data-scale-low-contention-comparison');
      assert.equal(fixture.manifest.classification.appliedLoadType, 'constant-vus-1');
      assert.equal(configuration.appliedLoadType, 'constant-vus-1');
      assert.equal(configuration.loadProfile, 'data-scale');
      assert.equal(configuration.vus, 1);
      assert.equal(configuration.expectedPosition, expectedPosition);
      assert.equal(fixture.model.waitlists.length, queueLength);
      const verifySql = renderVerifySql(fixture);
      assert.match(verifySql, /ROOM_K6_QUEUE_POSITION_CHANGED/);
      assert.match(verifySql, /EXPLAIN \(ANALYZE, BUFFERS, FORMAT JSON\)/);
      assert.match(verifySql, /WHERE preceding\.room_id = \d+/);
      assert.match(verifySql, /target\.user_id = \d+/);
    }
  }
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
    const manifest = JSON.parse(readFileSync(resolve(output, 'manifest.json'), 'utf8'));
    assert.equal(manifest.scenario, 'room-detail');
    assert.equal(manifest.classification.loadProfiles.soak, 'future-recommended');
    assert.equal(manifest.configuration.loadProfile, 'stress');
    assert.match(readFileSync(resolve(output, 'common.js'), 'utf8'),
      /room_measurement_check_rate/);
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
  assert.throws(() => options(
    'cancel-promotion', '--load-profile', 'spike', '--measured-waves', '2',
  ), /단일 동시 burst/);
  assert.throws(() => options('room-detail', '--load-profile', 'soak'), /--duration/);
  assert.throws(() => options('room-detail', '--duration', 'one-minute'), /duration 형식/);
  assert.throws(() => options('room-detail', '--duration', '0m'), /duration 형식/);
  assert.throws(() => options('due-backlog-read', '--due-room-count', '1'), /0, 20, 2000, 10000/);
  assert.doesNotThrow(() => options('due-backlog-read', '--due-room-count', '10000'));
  assert.throws(() => options('waitlist-position', '--queue-length', '10001'), /10000 이하여야/);
  assert.throws(() => options('waitlist-position', '--queue-length', '20'), /10, 100, 1000, 10000/);
  assert.throws(() => options('waitlist-position', '--vus', '2'), /1 이하여야/);
  assert.throws(() => options('due-backlog-read', '--unknown', 'value'), /지원하지 않는 옵션/);
});
