import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildCleanupSql,
  buildPrepareSql,
  buildResourceQuery,
  buildSnapshotQuery,
  createFixturePlan,
  evaluateFixture,
  hydrateFixture,
  normalizeRoomSummary,
  roomRequestDurationMetricName,
} from '../tools/fixture-model.mjs';

const PREPARE_OWNERSHIP = 'a'.repeat(32);

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
  return { plan, fixture: hydrateFixture(plan, resourcesFor(plan), PREPARE_OWNERSHIP) };
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
    metrics[name] = { values: { count: counts[name] ?? 0 } };
  });

  const requestCount = counts.room_requests ?? 0;
  const successCount = counts.room_success ?? 0;
  const businessCount = counts.room_business_failures ?? 0;
  const concurrencyCount = counts.room_concurrent_failures ?? 0;
  const unexpectedCount = counts.room_unexpected_outcome
    ?? Math.max(0, requestCount - successCount - businessCount - concurrencyCount);
  const outcomeCounts = {
    success: successCount,
    business: businessCount,
    concurrency: concurrencyCount,
    unexpected: unexpectedCount,
  };
  for (const [category, count] of Object.entries(outcomeCounts)) {
    metrics[roomRequestDurationMetricName(category)] = {
      values: {
        p50: count > 0 ? 10 : null,
        p95: count > 0 ? 20 : null,
        p99: count > 0 ? 30 : null,
        max: count > 0 ? 40 : null,
        count,
      },
    };
  }
  return { metrics };
}

function summaryWithTopLevelCounts(counts = {}) {
  const summary = summaryWith(counts);
  Object.entries(summary.metrics).forEach(([name, metric]) => {
    if (name.startsWith('room_request_duration{outcome:')) {
      return;
    }
    summary.metrics[name] = { count: metric.values.count };
  });
  return summary;
}

test('outcome별 duration은 표본 유무와 관계없이 p50·p95·p99·max·count 구조를 유지한다', () => {
  const successMetric = roomRequestDurationMetricName('success');
  const concurrencyMetric = roomRequestDurationMetricName('concurrency');
  const normalized = normalizeRoomSummary({
    metrics: {
      [successMetric]: {
        type: 'trend',
        contains: 'time',
        values: { med: 12, 'p(95)': 18, 'p(99)': 20, max: 25, count: 2 },
      },
      [concurrencyMetric]: {
        type: 'trend',
        contains: 'time',
        values: { med: 0, 'p(95)': 0, 'p(99)': 0, max: 0, count: 0 },
      },
    },
  });

  assert.deepEqual(normalized.metrics[successMetric].values, {
    p50: 12,
    p95: 18,
    p99: 20,
    max: 25,
    count: 2,
  });
  for (const category of ['business', 'concurrency', 'unexpected']) {
    assert.deepEqual(normalized.metrics[roomRequestDurationMetricName(category)].values, {
      p50: null,
      p95: null,
      p99: null,
      max: null,
      count: 0,
    });
  }
});

test('T2 outcome별 count 합은 전체 room_requests와 일치해야 한다', () => {
  const summary = summaryWith({
    room_requests: 4,
    room_success: 1,
    room_business_failures: 1,
    room_concurrent_failures: 1,
    room_unexpected_outcome: 1,
  });
  const counts = ['success', 'business', 'concurrency', 'unexpected']
    .map((category) => summary.metrics[roomRequestDurationMetricName(category)].values.count);

  assert.equal(counts.reduce((total, count) => total + count, 0), summary.metrics.room_requests.values.count);
});

test('기존 응답 분류 counter와 outcome별 count가 다르면 사후 판정은 실패한다', () => {
  const { fixture } = fixtureFor({
    scenario: 't5',
    runId: 'fixture-outcome-counter-mismatch',
    t5Role: 'public',
    t5Scale: 1,
  });
  const snapshot = initialSnapshot(fixture);
  fixture.baselineSnapshot = structuredClone(snapshot);
  const summary = summaryWith({ room_requests: 1, room_success: 1 });
  summary.metrics[roomRequestDurationMetricName('success')].values = {
    p50: null,
    p95: null,
    p99: null,
    max: null,
    count: 0,
  };
  summary.metrics[roomRequestDurationMetricName('unexpected')].values = {
    p50: 10,
    p95: 20,
    p99: 30,
    max: 40,
    count: 1,
  };

  const result = evaluateFixture(fixture, snapshot, 'after', summary);
  assert.equal(result.status, 'FAIL');
  assert.match(result.failures.join('\n'), /success outcome count와 기존 counter/);
});

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

test('fixture SQL은 필수 users timestamp와 정확한 ID 기반 cleanup을 만들고 prefix 전체 삭제를 쓰지 않는다', () => {
  const { plan, fixture } = fixtureFor({
    scenario: 't2',
    runId: 'fixture-cleanup',
    mode: 'spread',
    subcase: 'distinct',
    concurrency: 2,
  });

  const prepareSql = buildPrepareSql(
    plan,
    '{bcrypt}$2y$10$PzJpRRDVEB/jtl2uSy8vZuLyskdxt1Jg6BZ23PQqlQLvm7kB0EAem',
    PREPARE_OWNERSHIP,
  );
  const resourceQuery = buildResourceQuery(plan, PREPARE_OWNERSHIP);
  const cleanupSql = buildCleanupSql(fixture);
  assert.equal(plan.schemaVersion, 2);
  assert.equal(fixture.schemaVersion, 2);
  assert.match(prepareSql, /pg_advisory_xact_lock/);
  const usersSql = prepareSql.slice(
    prepareSql.indexOf('INSERT INTO users'),
    prepareSql.indexOf('INSERT INTO rooms'),
  );
  const userTimestampPairs = usersSql.match(/clock_timestamp\(\),\s*clock_timestamp\(\)/g) ?? [];
  assert.equal(userTimestampPairs.length, plan.users.length);
  assert.match(cleanupSql, /room_k6_cleanup_users/);
  assert.match(cleanupSql, /room_k6_cleanup_rooms/);
  assert.match(prepareSql, /ROOM k6 fixture a{32}/);
  assert.match(resourceQuery, /description = 'ROOM k6 fixture a{32}'/);
  assert.match(cleanupSql, /description text NOT NULL/);
  assert.match(cleanupSql, /f\.description = r\.description/);
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
  assert.match(cleanupSql, /JOIN notification_outbox_events source_event ON source_event.id = n.source_event_id/);
  assert.match(cleanupSql, /WHERE source_event.room_id <> n.room_id/);
  assert.match(cleanupSql, /fixture ROOM has notification from another ROOM outbox event/);
  assert.ok(
    cleanupSql.indexOf('fixture ROOM has notification from another ROOM outbox event')
      < cleanupSql.indexOf('DELETE FROM notifications'),
  );
  assert.doesNotMatch(cleanupSql, /LEFT JOIN notification_outbox_events source_event/);
  assert.match(cleanupSql, /fixture ROOM has outbox recipient outside fixture users/);
  assert.match(cleanupSql, /fixture ROOM has chat message by non-fixture user/);
  assert.doesNotMatch(cleanupSql, /\bLIKE\b/i);
  assert.doesNotMatch(cleanupSql, /TRUNCATE/i);
});

test('snapshot SQL은 파생 테이블의 quoted camelCase alias로 정렬한다', () => {
  const { fixture } = fixtureFor({
    scenario: 't2',
    runId: 'fixture-snapshot-aliases',
    mode: 'hot',
    subcase: 'distinct',
    concurrency: 2,
  });

  const snapshotSql = buildSnapshotQuery(fixture);

  assert.match(snapshotSql, /ORDER BY participation_row\."roomId", participation_row\."userId"/);
  assert.match(snapshotSql, /ORDER BY waitlist_row\."roomId", waitlist_row\."queueOrder", waitlist_row\."userId"/);
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

  assert.deepEqual(evaluateFixture(fixture, snapshot, 'after', summaryWithTopLevelCounts({
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

test('summary metric count는 0 이상 safe integer가 아니면 PASS가 될 수 없다', () => {
  const { fixture } = fixtureFor({
    scenario: 't5',
    runId: 'fixture-invalid-metric-count',
    t5Role: 'public',
    t5Scale: 1,
  });
  const snapshot = initialSnapshot(fixture);
  fixture.baselineSnapshot = structuredClone(snapshot);

  for (const count of [0.5, -1, Number.MAX_SAFE_INTEGER + 1, Number.NaN, Number.POSITIVE_INFINITY]) {
    const summary = summaryWith({ room_requests: 1, room_success: 1 });
    summary.metrics.room_requests.values.count = count;
    summary.metrics.room_success.values.count = count;
    const result = evaluateFixture(fixture, snapshot, 'after', summary);
    assert.equal(result.status, 'FAIL', `invalid metric count ${count}가 PASS가 되었습니다.`);
    assert.match(result.failures.join('\n'), /metric이 부족합니다/);
  }
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
