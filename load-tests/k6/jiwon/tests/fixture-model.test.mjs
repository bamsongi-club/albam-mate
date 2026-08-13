import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildCleanupSql,
  buildPrepareSql,
  createFixturePlan,
  evaluateFixture,
  hydrateFixture,
} from '../tools/fixture-model.mjs';

function resourcesFor(plan) {
  const users = {};
  const rooms = {};
  let nextUserId = 100;
  let nextRoomId = 1000;
  for (const user of plan.users) {
    users[user.email] = nextUserId;
    nextUserId += 1;
  }
  for (const room of plan.rooms) {
    rooms[room.title] = nextRoomId;
    nextRoomId += 1;
  }
  return { users, rooms };
}

function initialSnapshot(fixture) {
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
    room.activeKeys.forEach((userKey, index) => {
      participations.push({
        roomId: room.id,
        userId: fixture.users[userKey].id,
        status: 'ACTIVE',
        joinedAt: `2030-01-01T00:00:0${index}Z`,
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

function fixtureFor(input) {
  const plan = createFixturePlan(input);
  return { plan, fixture: hydrateFixture(plan, resourcesFor(plan)) };
}

function summaryWith(counts = {}) {
  const metricNames = [
    'room_success',
    'room_created',
    'room_requests',
    'room_business_failures',
    'room_concurrent_failures',
    'room_contract_failures',
    'room_unexpected_4xx',
    'room_server_failures',
    'room_waitlist_position_1',
    'room_waitlist_position_2',
    'room_waitlist_position_3',
    'room_waitlist_position_4',
    'room_waitlist_position_5',
    'room_waitlist_position_6',
    'room_waitlist_position_7',
    'room_waitlist_position_8',
  ];
  const metrics = {};
  metricNames.forEach((name) => {
    metrics[name] = { values: { count: counts[name] || 0 } };
  });
  return { metrics };
}

test('T1 hot stress fixture는 8명 취소·9명 FIFO 대기자를 round마다 분리한다', () => {
  const { plan, fixture } = fixtureFor({
    scenario: 't1',
    runId: 'fixture-t1-hot',
    profile: 'stress',
    mode: 'hot',
    concurrency: 8,
  });

  assert.equal(plan.options.rounds, 5);
  assert.equal(plan.targets.length, 40);
  assert.equal(plan.users.length, 18);
  assert.equal(plan.rooms.length, 5);
  assert.ok(plan.rooms.every((room) => room.capacity === 8 && room.activeKeys.length === 8));
  assert.ok(plan.rooms.every((room) => room.waiterKeys.length === 9));
  assert.deepEqual(evaluateFixture(fixture, initialSnapshot(fixture), 'before'), {
    status: 'PASS',
    failures: [],
  });
});

test('T2 duplicate는 같은 ROOM·같은 사용자 요청 두 개만 만든다', () => {
  const { plan, fixture } = fixtureFor({
    scenario: 't2',
    runId: 'fixture-t2-duplicate',
    profile: 'spike',
    mode: 'hot',
    subcase: 'duplicate',
    concurrency: 2,
  });

  assert.equal(plan.options.rounds, 1);
  assert.equal(plan.targets.length, 2);
  assert.equal(plan.targets[0].roomKey, plan.targets[1].roomKey);
  assert.equal(plan.targets[0].actorKey, plan.targets[1].actorKey);
  assert.equal(plan.rooms[0].waiterKeys.length, 0);
  assert.deepEqual(evaluateFixture(fixture, initialSnapshot(fixture), 'before'), {
    status: 'PASS',
    failures: [],
  });
});

test('T3는 round별 독립 ROOM에 취소자와 신청자 한 명씩을 만든다', () => {
  const { plan, fixture } = fixtureFor({
    scenario: 't3',
    runId: 'fixture-t3-race',
    profile: 'stress',
    t3Mode: 'race',
  });

  assert.equal(plan.rooms.length, 5);
  assert.equal(plan.users.length, 3);
  assert.deepEqual(plan.targets.map((target) => target.round), [0, 1, 2, 3, 4]);
  assert.equal(new Set(plan.targets.map((target) => target.roomKey)).size, 5);
  assert.ok(plan.rooms.every((room) => room.capacity === 1 && room.status === 'CLOSED'));
  assert.ok(plan.rooms.every((room) => room.activeKeys.length === 1 && room.waiterKeys.length === 0));
  assert.deepEqual(evaluateFixture(fixture, initialSnapshot(fixture), 'before'), {
    status: 'PASS',
    failures: [],
  });
});

test('T4는 마지막 자리 후보를 대기 fixture 없이 만든다', () => {
  const { plan, fixture } = fixtureFor({
    scenario: 't4',
    runId: 'fixture-t4-seat',
    profile: 'spike',
    concurrency: 8,
  });

  assert.equal(plan.rooms.length, 1);
  assert.equal(plan.targets.length, 8);
  assert.equal(plan.rooms[0].status, 'RECRUITING');
  assert.equal(plan.rooms[0].candidateKeys.length, 8);
  assert.deepEqual(evaluateFixture(fixture, initialSnapshot(fixture), 'before'), {
    status: 'PASS',
    failures: [],
  });
});

test('T5 scale=10은 정원 상한에 맞춘 CLOSED 만석 fixture다', () => {
  const { plan, fixture } = fixtureFor({
    scenario: 't5',
    runId: 'fixture-t5-scale-ten',
    t5Role: 'participant',
    t5Scale: 10,
  });

  assert.equal(plan.rooms.length, 1);
  assert.equal(plan.rooms[0].capacity, 10);
  assert.equal(plan.rooms[0].activeKeys.length, 10);
  assert.equal(plan.rooms[0].status, 'CLOSED');
  assert.equal(plan.targets[0].role, 'participant');
  assert.deepEqual(evaluateFixture(fixture, initialSnapshot(fixture), 'before'), {
    status: 'PASS',
    failures: [],
  });
});

test('fixture SQL은 정확한 ID 기반 cleanup을 만들고 prefix 전체 삭제를 쓰지 않는다', () => {
  const { plan, fixture } = fixtureFor({
    scenario: 't2',
    runId: 'fixture-cleanup',
    mode: 'spread',
    subcase: 'distinct',
    concurrency: 2,
  });

  const prepareSql = buildPrepareSql(plan, '{bcrypt}$2y$10$PzJpRRDVEB/jtl2uSy8vZuLyskdxt1Jg6BZ23PQqlQLvm7kB0EAem');
  const cleanupSql = buildCleanupSql(fixture);
  assert.match(prepareSql, /pg_advisory_xact_lock/);
  assert.match(cleanupSql, /room_k6_cleanup_users/);
  assert.match(cleanupSql, /room_k6_cleanup_rooms/);
  assert.match(cleanupSql, /FOR UPDATE OF room/);
  assert.match(cleanupSql, /FOR UPDATE OF event/);
  assert.match(cleanupSql, /FOR UPDATE OF chat_room/);
  assert.ok(
    cleanupSql.indexOf('FOR UPDATE OF room')
      < cleanupSql.indexOf('fixture ROOM has participation by non-fixture user'),
  );
  assert.ok(
    cleanupSql.indexOf('FOR UPDATE OF room')
      < cleanupSql.indexOf('fixture ROOM has waitlist by non-fixture user'),
  );
  assert.ok(
    cleanupSql.indexOf('FOR UPDATE OF room')
      < cleanupSql.indexOf('fixture ROOM has notification for non-fixture user'),
  );
  assert.ok(
    cleanupSql.indexOf('FOR UPDATE OF event')
      < cleanupSql.indexOf('fixture ROOM has outbox recipient outside fixture users'),
  );
  assert.ok(
    cleanupSql.indexOf('FOR UPDATE OF chat_room')
      < cleanupSql.indexOf('fixture ROOM has chat message by non-fixture user'),
  );
  assert.match(cleanupSql, /fixture ROOM has participation by non-fixture user/);
  assert.match(cleanupSql, /fixture ROOM has waitlist by non-fixture user/);
  assert.match(cleanupSql, /fixture ROOM has notification for non-fixture user/);
  assert.match(cleanupSql, /fixture ROOM has outbox recipient outside fixture users/);
  assert.match(cleanupSql, /fixture ROOM has chat message by non-fixture user/);
  assert.doesNotMatch(cleanupSql, /\bLIKE\b/i);
  assert.doesNotMatch(cleanupSql, /TRUNCATE/i);
});

test('T2 duplicate는 동시성 2 이외의 입력을 거절한다', () => {
  assert.throws(
    () => createFixturePlan({
      scenario: 't2',
      runId: 'fixture-invalid-duplicate',
      mode: 'hot',
      subcase: 'duplicate',
      concurrency: 4,
    }),
    /concurrency=2/,
  );
});

test('fixture 입력은 오타와 다른 시나리오 전용 옵션을 거절한다', () => {
  assert.throws(
    () => createFixturePlan({ scenario: 't1', runId: 'fixture-invalid-typo', concurency: 4 }),
    /허용되지 않는 옵션/,
  );
  assert.throws(
    () => createFixturePlan({ scenario: 't1', runId: 'fixture-invalid-cross-scenario', t3Mode: 'race' }),
    /허용되지 않는 옵션/,
  );
});

test('T1 사후 검증은 성공한 취소 수와 FIFO PROMOTED 수를 함께 확인한다', () => {
  const { fixture } = fixtureFor({
    scenario: 't1',
    runId: 'fixture-t1-after',
    profile: 'spike',
    mode: 'hot',
    concurrency: 2,
  });
  const snapshot = initialSnapshot(fixture);
  const room = Object.values(fixture.rooms)[0];
  room.cancelKeys.forEach((userKey) => {
    const participation = snapshot.participations.find((entry) => entry.roomId === room.id
      && entry.userId === fixture.users[userKey].id);
    participation.status = 'CANCELED';
    participation.canceledAt = '2030-01-01T00:01:00Z';
  });
  room.waiterKeys.slice(0, 2).forEach((userKey) => {
    const waitlist = snapshot.waitlists.find((entry) => entry.roomId === room.id
      && entry.userId === fixture.users[userKey].id);
    waitlist.status = 'PROMOTED';
    snapshot.participations.push({
      roomId: room.id,
      userId: fixture.users[userKey].id,
      status: 'ACTIVE',
      joinedAt: '2030-01-01T00:01:00Z',
      canceledAt: null,
    });
  });

  assert.deepEqual(evaluateFixture(fixture, snapshot, 'after', summaryWith({
    room_requests: 2,
    room_success: 2,
  })), {
    status: 'PASS',
    failures: [],
  });
});

test('T2 distinct 사후 검증은 201 수와 새 WAITING 행 수를 대조한다', () => {
  const { fixture } = fixtureFor({
    scenario: 't2',
    runId: 'fixture-t2-after',
    profile: 'spike',
    mode: 'hot',
    subcase: 'distinct',
    concurrency: 2,
  });
  const snapshot = initialSnapshot(fixture);
  fixture.targets.forEach((target, index) => {
    const room = fixture.rooms[target.roomKey];
    snapshot.waitlists.push({
      roomId: room.id,
      userId: fixture.users[target.actorKey].id,
      status: 'WAITING',
      queueOrder: index + 1,
      queuedAt: '2030-01-01T00:01:00Z',
    });
  });

  const summary = summaryWith({
    room_requests: 2,
    room_success: 2,
    room_created: 2,
    room_waitlist_position_1: 1,
    room_waitlist_position_2: 1,
  });

  assert.deepEqual(evaluateFixture(fixture, snapshot, 'after', summary), {
    status: 'PASS',
    failures: [],
  });

  delete summary.metrics.room_created;
  assert.equal(evaluateFixture(fixture, snapshot, 'after', summary).status, 'FAIL');
});

test('T3 wait-first 사후 검증은 PROMOTED + ACTIVE 종단만 통과시킨다', () => {
  const { fixture } = fixtureFor({
    scenario: 't3',
    runId: 'fixture-t3-after',
    profile: 'spike',
    t3Mode: 'wait-first',
  });
  const snapshot = initialSnapshot(fixture);
  const room = Object.values(fixture.rooms)[0];
  const cancelKey = room.cancelKeys[0];
  const waitKey = room.raceWaitKey;
  const canceled = snapshot.participations.find((entry) => entry.roomId === room.id
    && entry.userId === fixture.users[cancelKey].id);
  canceled.status = 'CANCELED';
  canceled.canceledAt = '2030-01-01T00:01:00Z';
  snapshot.participations.push({
    roomId: room.id,
    userId: fixture.users[waitKey].id,
    status: 'ACTIVE',
    joinedAt: '2030-01-01T00:01:00Z',
    canceledAt: null,
  });
  snapshot.waitlists.push({
    roomId: room.id,
    userId: fixture.users[waitKey].id,
    status: 'PROMOTED',
    queueOrder: 1,
    queuedAt: '2030-01-01T00:01:00Z',
  });

  assert.deepEqual(evaluateFixture(fixture, snapshot, 'after', summaryWith({
    room_requests: 2,
    room_success: 2,
    room_created: 1,
  })), {
    status: 'PASS',
    failures: [],
  });
});

test('T4 사후 검증은 ACTIVE 한 명과 대기 행 0만 통과시킨다', () => {
  const { fixture } = fixtureFor({
    scenario: 't4',
    runId: 'fixture-t4-after',
    profile: 'spike',
    concurrency: 2,
  });
  const snapshot = initialSnapshot(fixture);
  const room = Object.values(fixture.rooms)[0];
  const winnerKey = room.candidateKeys[0];
  snapshot.rooms[0].status = 'CLOSED';
  snapshot.rooms[0].activeParticipantCount = 1;
  snapshot.participations.push({
    roomId: room.id,
    userId: fixture.users[winnerKey].id,
    status: 'ACTIVE',
    joinedAt: '2030-01-01T00:01:00Z',
    canceledAt: null,
  });

  assert.deepEqual(evaluateFixture(fixture, snapshot, 'after', summaryWith({
    room_requests: 2,
    room_success: 1,
    room_created: 1,
    room_business_failures: 1,
  })), {
    status: 'PASS',
    failures: [],
  });
});

test('T5 사후 검증은 조회 전후 snapshot이 같을 때만 통과한다', () => {
  const { fixture } = fixtureFor({
    scenario: 't5',
    runId: 'fixture-t5-after',
    t5Role: 'public',
    t5Scale: 1,
  });
  const snapshot = initialSnapshot(fixture);
  fixture.baselineSnapshot = structuredClone(snapshot);

  assert.deepEqual(evaluateFixture(fixture, snapshot, 'after', summaryWith({
    room_requests: 1,
    room_success: 1,
  })), {
    status: 'PASS',
    failures: [],
  });

  snapshot.rooms[0].updatedAt = '2030-01-01T00:01:00Z';
  assert.equal(evaluateFixture(fixture, snapshot, 'after', summaryWith({
    room_requests: 1,
    room_success: 1,
  })).status, 'FAIL');
});

test('요청을 관측하지 못한 after summary는 PASS가 될 수 없다', () => {
  const { fixture } = fixtureFor({
    scenario: 't3',
    runId: 'fixture-no-requests',
    profile: 'spike',
    t3Mode: 'race',
  });

  assert.equal(
    evaluateFixture(fixture, initialSnapshot(fixture), 'after', summaryWith()).status,
    'FAIL',
  );
});
