import { check, sleep } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';
import { WebSocket } from 'k6/websockets';

import {
  CHAT_MESSAGE_INTERVAL_SECONDS,
  POLLING_INTERVAL_SECONDS,
  resolveProfile,
} from './lib/system-capacity-profile.mjs';
import {
  RUN_ID,
  TARGET_URL,
  cancelParticipation,
  createClient,
  createRoom,
  fetchCsrf,
  integerEnv,
  joinRoom,
  listNotifications,
  listRooms,
  loginFixture,
  requestJson,
  responseData,
  unreadNotificationCount,
  upstreamName,
} from './lib/albam.js';

const PROFILE = resolveProfile(__ENV);
const ACTIVE_LOAD_SECONDS = PROFILE.warmupSeconds + PROFILE.measurementSeconds;
const TOTAL_SECONDS = ACTIVE_LOAD_SECONDS + PROFILE.observationSeconds;
const ACTIVE_MAX_DURATION_SECONDS = ACTIVE_LOAD_SECONDS + 30;
const SETUP_LOGIN_INTERVAL_SECONDS = 0.15;
const EVENT_TIME_UNIT = '24m';
const FIXTURE_USER_COUNT = integerEnv(
  'LOAD_TEST_USER_COUNT',
  PROFILE.fixturePlan.requiredUsers,
  1,
  20_000,
);

if (FIXTURE_USER_COUNT < PROFILE.fixturePlan.requiredUsers) {
  throw new Error(
    `LOAD_TEST_USER_COUNT(${FIXTURE_USER_COUNT})는 ${PROFILE.activeCcu} CCU에 필요한 `
      + `${PROFILE.fixturePlan.requiredUsers} 이상이어야 합니다.`,
  );
}

const setupFailures = new Rate('system_setup_failures');
const activeStartedAt = new Gauge('system_active_started_at_ms');
const resolvedActiveVus = new Counter('system_resolved_active_vus');
const httpErrors = new Rate('system_http_errors');
const httpDuration = new Trend('system_http_duration_ms', true);
const chatHttpDuration = new Trend('system_chat_http_duration_ms', true);
const websocketOpened = new Rate('system_websocket_opened');
const websocketHealthy = new Rate('system_websocket_session_healthy');
const websocketDelivery = new Trend('system_websocket_delivery_ms', true);
const websocketDeliverySamples = new Counter('system_websocket_delivery_samples');
const recoveryErrors = new Rate('system_recovery_errors');
const recoveryDuration = new Trend('system_recovery_duration_ms', true);
const participationEvents = new Counter('system_participation_events');

function roleScenario(execName, vus) {
  return {
    executor: 'per-vu-iterations',
    exec: execName,
    vus,
    iterations: 1,
    maxDuration: `${ACTIVE_MAX_DURATION_SECONDS}s`,
    gracefulStop: '30s',
  };
}

export const options = {
  setupTimeout: '10m',
  scenarios: {
    browsing: roleScenario('browsingSession', PROFILE.roles.browsing),
    chat: roleScenario('chatSession', PROFILE.roles.chat),
    waitlist: roleScenario('waitlistSession', PROFILE.roles.waitlist),
    notification_panel: roleScenario('notificationPanelSession', PROFILE.roles.notificationPanel),
    participation: {
      executor: 'constant-arrival-rate',
      exec: 'participationEvent',
      rate: PROFILE.activeCcu,
      timeUnit: EVENT_TIME_UNIT,
      duration: `${ACTIVE_LOAD_SECONDS}s`,
      preAllocatedVUs: Math.min(5, PROFILE.eventMaxVus),
      maxVUs: PROFILE.eventMaxVus,
      gracefulStop: '30s',
    },
    recovery: {
      executor: 'per-vu-iterations',
      exec: 'recoveryProbe',
      startTime: `${ACTIVE_LOAD_SECONDS}s`,
      vus: 1,
      iterations: 1,
      maxDuration: `${PROFILE.observationSeconds + 30}s`,
      gracefulStop: '15s',
    },
  },
  thresholds: {
    system_setup_failures: ['rate==0'],
    system_active_started_at_ms: ['value>0'],
    system_resolved_active_vus: [`count==${PROFILE.activeCcu}`],
    'system_http_errors{phase:measurement}': ['rate<0.01'],
    'system_http_duration_ms{phase:measurement}': ['p(95)<=1000'],
    'system_chat_http_duration_ms{phase:measurement}': ['p(95)<=750', 'p(99)<=1500'],
    system_websocket_opened: ['rate>=0.99'],
    system_websocket_session_healthy: ['rate>=0.99'],
    'system_websocket_delivery_ms{phase:measurement}': ['p(95)<=2000', 'p(99)<=5000'],
    system_websocket_delivery_samples: ['count>0'],
    dropped_iterations: ['count==0'],
    system_recovery_errors: ['rate==0'],
    system_recovery_duration_ms: ['p(95)<=1000'],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function cookieValue(client, name) {
  const cookies = client.jar.cookiesForURL(TARGET_URL);
  const values = cookies[name];
  if (Array.isArray(values)) {
    return values[values.length - 1] || null;
  }
  return typeof values === 'string' && values !== '' ? values : null;
}

function prepareUser(fixtureIndex, role) {
  const client = createClient();
  const result = loginFixture(client, fixtureIndex, {
    test_kind: 'system-capacity',
    run_kind: PROFILE.runKind,
    role,
    phase: 'setup',
  });
  if (result.csrf.status !== 200 || !result.login || result.login.status !== 200) {
    setupFailures.add(true, { role, stage: 'login' });
    throw new Error(`system capacity fixture 로그인 실패: role=${role} index=${fixtureIndex}`);
  }
  const csrf = fetchCsrf(client, { test_kind: 'system-capacity', role, phase: 'setup' });
  const sessionId = cookieValue(client, 'JSESSIONID');
  const csrfCookie = cookieValue(client, 'XSRF-TOKEN');
  if (csrf.status !== 200 || !client.csrf || !sessionId) {
    setupFailures.add(true, { role, stage: 'session' });
    throw new Error(`system capacity fixture 세션 준비 실패: role=${role} index=${fixtureIndex}`);
  }
  sleep(SETUP_LOGIN_INTERVAL_SECONDS);
  return {
    fixtureIndex,
    sessionId,
    csrfCookie,
    csrfHeaderName: client.csrf.headerName,
    csrfToken: client.csrf.token,
  };
}

function prepareUsers(indexes, role) {
  return indexes.map((fixtureIndex) => prepareUser(fixtureIndex, role));
}

function restoreClient(user) {
  const client = createClient();
  client.jar.set(TARGET_URL, 'JSESSIONID', user.sessionId);
  if (user.csrfCookie) {
    client.jar.set(TARGET_URL, 'XSRF-TOKEN', user.csrfCookie);
  }
  client.csrf = { headerName: user.csrfHeaderName, token: user.csrfToken };
  return client;
}

function requireResponse(response, expectedStatus, stage) {
  if (!response || response.status !== expectedStatus) {
    setupFailures.add(true, { stage });
    throw new Error(`system capacity fixture 준비 실패: stage=${stage} status=${response?.status ?? 0}`);
  }
}

function registerWaitlist(client, roomId) {
  return http.post(`${TARGET_URL}/api/rooms/${roomId}/waitlist`, null, {
    jar: client.jar,
    headers: { [client.csrf.headerName]: client.csrf.token },
    tags: { operation: 'waitlist-register', phase: 'setup', test_kind: 'system-capacity' },
  });
}

function fetchGameFixture() {
  const response = http.get(`${TARGET_URL}/api/games?page=0&size=100`, {
    tags: { operation: 'setup-game-list', phase: 'setup', test_kind: 'system-capacity' },
  });
  const games = responseData(response)?.content;
  if (response.status !== 200 || !Array.isArray(games) || games.length === 0) {
    setupFailures.add(true, { stage: 'games' });
    throw new Error('system capacity 게임 fixture를 준비하지 못했습니다.');
  }
  return games
    .filter((game) => Number.isInteger(game?.id) && typeof game?.name === 'string')
    .map((game) => ({ id: game.id, name: game.name }));
}

function prepareChatFixtures(activeUsers, hostUsers) {
  return activeUsers.map((user, index) => {
    const host = hostUsers[index];
    const hostClient = restoreClient(host);
    const room = createRoom(hostClient, `k6-system-chat-${RUN_ID}-${index + 1}`, {
      test_kind: 'system-capacity', role: 'chat-host', phase: 'setup',
    }, 1);
    const roomId = responseData(room)?.id;
    requireResponse(room, 201, 'chat-room-create');
    const participantClient = restoreClient(user);
    requireResponse(joinRoom(participantClient, roomId, {
      test_kind: 'system-capacity', role: 'chat', phase: 'setup',
    }), 201, 'chat-room-join');
    return { ...user, roomId };
  });
}

function prepareWaitlistFixtures(activeUsers, hostUsers, participantUsers) {
  return activeUsers.map((user, index) => {
    const hostClient = restoreClient(hostUsers[index]);
    const room = createRoom(hostClient, `k6-system-waitlist-${RUN_ID}-${index + 1}`, {
      test_kind: 'system-capacity', role: 'waitlist-host', phase: 'setup',
    }, 1);
    const roomId = responseData(room)?.id;
    requireResponse(room, 201, 'waitlist-room-create');
    requireResponse(joinRoom(restoreClient(participantUsers[index]), roomId, {
      test_kind: 'system-capacity', role: 'waitlist-participant', phase: 'setup',
    }), 201, 'waitlist-room-fill');
    requireResponse(registerWaitlist(restoreClient(user), roomId), 201, 'waitlist-register');
    return { ...user, roomId };
  });
}

function prepareEventFixtures(hostUsers, participantUsers) {
  return hostUsers.map((host, index) => {
    const room = createRoom(restoreClient(host), `k6-system-event-${RUN_ID}-${index + 1}`, {
      test_kind: 'system-capacity', role: 'event-host', phase: 'setup',
    }, 1);
    const roomId = responseData(room)?.id;
    requireResponse(room, 201, 'event-room-create');
    return { participant: participantUsers[index], roomId };
  });
}

export function setup() {
  const roles = PROFILE.roles;
  const plan = PROFILE.fixturePlan;
  let offset = 0;
  const browsingIndexes = plan.active.slice(offset, offset += roles.browsing);
  const chatIndexes = plan.active.slice(offset, offset += roles.chat);
  const waitlistIndexes = plan.active.slice(offset, offset += roles.waitlist);
  const notificationIndexes = plan.active.slice(offset);

  const browsing = prepareUsers(browsingIndexes, 'browsing');
  const chatUsers = prepareUsers(chatIndexes, 'chat');
  const waitlistUsers = prepareUsers(waitlistIndexes, 'waitlist');
  const notificationPanel = prepareUsers(notificationIndexes, 'notification-panel');
  const chatHosts = prepareUsers(plan.chatHosts, 'chat-host');
  const waitlistHosts = prepareUsers(plan.waitlistHosts, 'waitlist-host');
  const waitlistParticipants = prepareUsers(plan.waitlistParticipants, 'waitlist-participant');
  const eventHosts = prepareUsers(plan.eventHosts, 'event-host');
  const eventParticipants = prepareUsers(plan.eventParticipants, 'event-participant');

  const chat = prepareChatFixtures(chatUsers, chatHosts);
  const waitlist = prepareWaitlistFixtures(waitlistUsers, waitlistHosts, waitlistParticipants);
  const events = prepareEventFixtures(eventHosts, eventParticipants);
  const games = fetchGameFixture();
  const roomIds = [
    ...chat.map((user) => user.roomId),
    ...waitlist.map((user) => user.roomId),
    ...events.map((event) => event.roomId),
  ];
  setupFailures.add(false, { stage: 'complete' });
  const startedAt = Date.now();
  activeStartedAt.add(startedAt);
  return {
    activeCcu: PROFILE.activeCcu,
    browsing,
    chat,
    events,
    games,
    notificationPanel,
    profileVersion: 'system-active-ccu-v1',
    roles,
    roomIds,
    runKind: PROFILE.runKind,
    startedAt,
    waitlist,
  };
}

function phase(data) {
  const elapsedSeconds = (Date.now() - data.startedAt) / 1_000;
  if (elapsedSeconds < PROFILE.warmupSeconds) {
    return 'warmup';
  }
  if (elapsedSeconds < ACTIVE_LOAD_SECONDS) {
    return 'measurement';
  }
  return 'observation';
}

function active(data) {
  return (Date.now() - data.startedAt) / 1_000 < ACTIVE_LOAD_SECONDS;
}

function tagsFor(data, role, operation) {
  return {
    operation,
    phase: phase(data),
    role,
    run_kind: PROFILE.runKind,
    test_kind: 'system-capacity',
  };
}

function recordHttp(response, expectedStatus, data, role, operation, chatOperation = false) {
  const accepted = response?.status === expectedStatus;
  const tags = {
    ...tagsFor(data, role, operation),
    status: String(response?.status ?? 0),
    upstream: response ? upstreamName(response) : 'missing',
  };
  httpErrors.add(!accepted, tags);
  if (Number.isFinite(response?.timings?.duration)) {
    httpDuration.add(response.timings.duration, tags);
    if (chatOperation) {
      chatHttpDuration.add(response.timings.duration, tags);
    }
  }
  return accepted;
}

function authenticatedGet(client, path, tags) {
  return http.get(`${TARGET_URL}${path}`, { jar: client.jar, tags });
}

function pollUnread(client, data, role) {
  const tags = tagsFor(data, role, 'unread-count');
  recordHttp(unreadNotificationCount(client, tags), 200, data, role, 'unread-count');
}

function resolveRoleUser(users, role) {
  const index = exec.scenario.iterationInTest;
  const user = users[index];
  const resolved = Boolean(user);
  resolvedActiveVus.add(1, { role, outcome: resolved ? 'succeeded' : 'missing' });
  check({ resolved }, { [`${role} 활성 사용자가 fixture를 얻는다`]: (value) => value.resolved });
  if (!resolved) {
    throw new Error(`${role} fixture index가 없습니다: ${index}`);
  }
  return user;
}

function sleepRemainder(startedAt, intervalSeconds) {
  sleep(Math.max(0, intervalSeconds - (Date.now() - startedAt) / 1_000));
}

function browseOnce(client, data, roleIndex, sequence) {
  const operation = sequence % 5;
  if (operation === 0) {
    const tags = tagsFor(data, 'browsing', 'game-list');
    return recordHttp(authenticatedGet(client, '/api/games?page=0&size=20', tags), 200, data, 'browsing', 'game-list');
  }
  if (operation === 1) {
    const game = data.games[(roleIndex + sequence) % data.games.length];
    const keyword = encodeURIComponent(game.name.trim().slice(0, 2) || 'a');
    const tags = tagsFor(data, 'browsing', 'game-keyword');
    return recordHttp(authenticatedGet(client, `/api/games?keyword=${keyword}&size=20`, tags), 200, data, 'browsing', 'game-keyword');
  }
  if (operation === 2) {
    const tags = tagsFor(data, 'browsing', 'room-list');
    return recordHttp(listRooms(client, 0, 10, tags), 200, data, 'browsing', 'room-list');
  }
  if (operation === 3) {
    const roomId = data.roomIds[(roleIndex + sequence) % data.roomIds.length];
    const tags = tagsFor(data, 'browsing', 'room-detail');
    return recordHttp(authenticatedGet(client, `/api/rooms/${roomId}`, tags), 200, data, 'browsing', 'room-detail');
  }
  const game = data.games[(roleIndex + sequence) % data.games.length];
  const tags = tagsFor(data, 'browsing', 'game-detail');
  return recordHttp(authenticatedGet(client, `/api/games/${game.id}`, tags), 200, data, 'browsing', 'game-detail');
}

export function browsingSession(data) {
  const roleIndex = exec.scenario.iterationInTest;
  const user = resolveRoleUser(data.browsing, 'browsing');
  const client = restoreClient(user);
  let sequence = 0;
  while (active(data)) {
    const startedAt = Date.now();
    pollUnread(client, data, 'browsing');
    browseOnce(client, data, roleIndex, sequence);
    sleepRemainder(startedAt, 5 + ((roleIndex + sequence) % 11));
    sequence++;
  }
}

export function notificationPanelSession(data) {
  const user = resolveRoleUser(data.notificationPanel, 'notification-panel');
  const client = restoreClient(user);
  while (active(data)) {
    const startedAt = Date.now();
    pollUnread(client, data, 'notification-panel');
    const tags = tagsFor(data, 'notification-panel', 'notification-list');
    recordHttp(listNotifications(client, 0, 10, tags), 200, data, 'notification-panel', 'notification-list');
    sleepRemainder(startedAt, POLLING_INTERVAL_SECONDS);
  }
}

export function waitlistSession(data) {
  const user = resolveRoleUser(data.waitlist, 'waitlist');
  const client = restoreClient(user);
  while (active(data)) {
    const startedAt = Date.now();
    pollUnread(client, data, 'waitlist');
    const detailTags = tagsFor(data, 'waitlist', 'room-detail');
    recordHttp(authenticatedGet(client, `/api/rooms/${user.roomId}`, detailTags), 200, data, 'waitlist', 'room-detail');
    const waitlistTags = tagsFor(data, 'waitlist', 'waitlist-status');
    recordHttp(
      authenticatedGet(client, `/api/rooms/${user.roomId}/waitlist/me`, waitlistTags),
      200,
      data,
      'waitlist',
      'waitlist-status',
    );
    sleepRemainder(startedAt, POLLING_INTERVAL_SECONDS);
  }
}

function webSocketUrl(roomId) {
  const base = TARGET_URL.startsWith('https://')
    ? `wss://${TARGET_URL.slice('https://'.length)}`
    : `ws://${TARGET_URL.slice('http://'.length)}`;
  return `${base}/api/rooms/${roomId}/chat/ws`;
}

function isExpectedChatEvent(message, roomId) {
  return message
    && message.type === 'MESSAGE_CREATED'
    && Number.isInteger(message.eventId)
    && message.eventId > 0
    && message.message
    && message.message.messageId === message.eventId
    && message.message.roomId === roomId
    && typeof message.message.createdAt === 'string';
}

export function chatSession(data) {
  const user = resolveRoleUser(data.chat, 'chat');
  const client = restoreClient(user);
  const historyTags = tagsFor(data, 'chat', 'chat-history');
  recordHttp(
    authenticatedGet(client, `/api/rooms/${user.roomId}/chat/messages?size=100`, historyTags),
    200,
    data,
    'chat',
    'chat-history',
    true,
  );
  pollUnread(client, data, 'chat');

  const state = { closeRequested: false, error: false, openRecorded: false, opened: false, unexpectedClose: false };
  const socket = new WebSocket(webSocketUrl(user.roomId), null, {
    jar: client.jar,
    headers: { Origin: TARGET_URL },
    tags: { name: 'system_chat_websocket', role: 'chat', test_kind: 'system-capacity' },
  });
  socket.addEventListener('open', () => {
    state.opened = true;
    state.openRecorded = true;
    websocketOpened.add(true);
  });
  socket.addEventListener('message', (event) => {
    let message = null;
    try {
      message = JSON.parse(event.data);
    } catch (_) {
      return;
    }
    if (!isExpectedChatEvent(message, user.roomId)) {
      return;
    }
    const createdAt = Date.parse(message.message.createdAt);
    if (Number.isFinite(createdAt) && createdAt <= Date.now()) {
      const tags = tagsFor(data, 'chat', 'websocket-delivery');
      websocketDelivery.add(Date.now() - createdAt, tags);
      websocketDeliverySamples.add(1, tags);
    }
  });
  socket.addEventListener('error', () => {
    state.error = true;
    if (!state.openRecorded) {
      state.openRecorded = true;
      websocketOpened.add(false);
    }
  });
  socket.addEventListener('close', () => {
    if (!state.openRecorded) {
      state.openRecorded = true;
      websocketOpened.add(false);
    }
    if (!state.closeRequested) {
      state.unexpectedClose = true;
    }
  });

  let sequence = 0;
  const unreadTimer = setInterval(() => pollUnread(client, data, 'chat'), POLLING_INTERVAL_SECONDS * 1_000);
  const messageTimer = setInterval(() => {
    const tags = tagsFor(data, 'chat', 'chat-send');
    const response = requestJson(client, 'POST', `/api/rooms/${user.roomId}/chat/messages`, {
      clientMessageId: `k6-system-${RUN_ID}-${exec.vu.idInTest}-${sequence}`.slice(0, 100),
      content: `system capacity ${RUN_ID} ${sequence}`.slice(0, 500),
    }, tags);
    recordHttp(response, 201, data, 'chat', 'chat-send', true);
    sequence++;
  }, CHAT_MESSAGE_INTERVAL_SECONDS * 1_000);

  const remainingMilliseconds = Math.max(1, ACTIVE_LOAD_SECONDS * 1_000 - (Date.now() - data.startedAt));
  setTimeout(() => {
    clearInterval(unreadTimer);
    clearInterval(messageTimer);
    state.closeRequested = true;
    websocketHealthy.add(state.opened && !state.error && !state.unexpectedClose);
    socket.close();
  }, remainingMilliseconds);
}

export function participationEvent(data) {
  const fixture = data.events[exec.scenario.iterationInTest % data.events.length];
  const participant = restoreClient(fixture.participant);
  const tags = tagsFor(data, 'participation', 'room-join');
  const joined = joinRoom(participant, fixture.roomId, tags);
  if (!recordHttp(joined, 201, data, 'participation', 'room-join')) {
    return;
  }
  const canceled = cancelParticipation(participant, fixture.roomId, {
    ...tagsFor(data, 'participation', 'room-cancel'),
  });
  if (recordHttp(canceled, 200, data, 'participation', 'room-cancel')) {
    participationEvents.add(2, tagsFor(data, 'participation', 'notification-event'));
  }
}

export function recoveryProbe(data) {
  const deadline = data.startedAt + TOTAL_SECONDS * 1_000;
  while (Date.now() < deadline) {
    const startedAt = Date.now();
    const response = http.get(`${TARGET_URL}/api/games?page=0&size=1`, {
      tags: { operation: 'recovery-probe', phase: 'observation', test_kind: 'system-capacity' },
    });
    const accepted = response.status === 200;
    recoveryErrors.add(!accepted);
    if (Number.isFinite(response.timings?.duration)) {
      recoveryDuration.add(response.timings.duration);
    }
    sleepRemainder(startedAt, POLLING_INTERVAL_SECONDS);
  }
}
