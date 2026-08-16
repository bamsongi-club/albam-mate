// 채팅 부하테스트 시나리오가 공유하는 상수·지표·헬퍼.
// 시나리오 파일은 이 모듈만 import 하고 자기 options 와 exec 만 갖는다.

import http from 'k6/http';
import execution from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { WebSocket } from 'k6/experimental/websockets';

export const BASE_URL = readRequiredTargetUrl();

export const WS_BASE_URL = toWebSocketBaseUrl(BASE_URL);

export const ORIGIN = removeTrailingSlash(__ENV.K6_ORIGIN || BASE_URL);

// fixture 는 fixtures/rooms.sql 의 마지막 SELECT 가 만든다. 기본값을 두면 잘못된
// 계정으로 돌다 실패하므로 경로를 반드시 받는다.
export const FIXTURE_PATH = readRequiredString('K6_CHAT_FIXTURE');

export const FIXTURE = parseFixture(open(FIXTURE_PATH));

export const FIXTURE_USERS = FIXTURE.users;

export const PROFILE_NAME = readNonEmptyString('K6_CHAT_PROFILE', defaultProfileName(FIXTURE));

export const PROFILE = profileFor(FIXTURE, PROFILE_NAME);

export const PROFILE_ROOM_IDS = PROFILE.roomIds;

export const PROFILE_USERS = usersForRooms(FIXTURE_USERS, PROFILE_ROOM_IDS);

// 대상 서버의 app.security.auth-request.login-limit 과 같은 값을 준다. 측정 창에서만
// 서버 한도를 올렸다면 이 값도 함께 올려야 fixture 계정 수가 막히지 않는다.
export const LOGIN_LIMIT = readPositiveInteger('K6_LOGIN_LIMIT', 30);

// ---------------------------------------------------------------------------
// 부하 시나리오 공통 설정
//
// 계약 검증 mode는 실패 1건에 즉시 중단하지만, 부하 mode는 끝까지 돌면서 단계별로
// 어디서 무너지는지 기록해야 한다. 그래서 게이트를 중단형이 아닌 관찰형으로 두고,
// 단계마다 태그를 붙여 하위 지표로 남긴다.
//
// 시작점은 이미 통과가 확인된 수준에서 잡는다. 첫 단계부터 한계를 넘으면 계단이
// 의미를 잃는다.
// ---------------------------------------------------------------------------
export const LOAD_STEP_DURATION = readDuration('K6_LOAD_STEP_DURATION', '2m');

export const LOAD_WARMUP_DURATION = readDuration('K6_LOAD_WARMUP_DURATION', '30s');

export const LOAD_WARMUP_STAGE = 'warmup';

// 부하 시나리오가 쓰는 방당 수신자 수.
export const LOAD_SUBSCRIBERS_PER_ROOM = readPositiveInteger('K6_LOAD_SUBSCRIBERS_PER_ROOM', 6);

export const RATE_LIMIT_WINDOW_SECONDS = 10;

export const USER_RATE_LIMIT_PER_SECOND = 5;

export const ROOM_RATE_LIMIT_PER_SECOND = 10;

export const USER_RATE_LIMIT_PER_WINDOW = USER_RATE_LIMIT_PER_SECOND * RATE_LIMIT_WINDOW_SECONDS;

export const ROOM_RATE_LIMIT_PER_WINDOW = ROOM_RATE_LIMIT_PER_SECOND * RATE_LIMIT_WINDOW_SECONDS;

export const ROOM_PARTICIPANT_COUNT = readFixedPositiveInteger(
	'K6_ROOM_PARTICIPANT_COUNT',
	6,
	'the normal hot-room sender rotation',
);

export const MESSAGE_CHARS = readPositiveInteger('K6_MESSAGE_CHARS', 100);

export const HTTP_P95_MS = readPositiveNumber('K6_HTTP_P95_MS', 750);

export const HTTP_P99_MS = readPositiveNumber('K6_HTTP_P99_MS', 1_500);

export const WS_CONNECT_P95_MS = readPositiveNumber('K6_WS_CONNECT_P95_MS', 1_000);

export const WS_CONNECT_P99_MS = readPositiveNumber('K6_WS_CONNECT_P99_MS', 2_500);

export const WS_DELIVERY_P95_MS = readPositiveNumber('K6_WS_DELIVERY_P95_MS', 2_000);

export const WS_DELIVERY_P99_MS = readPositiveNumber('K6_WS_DELIVERY_P99_MS', 5_000);

export const WS_EVENT_TIMEOUT_MS = readPositiveInteger('K6_WS_EVENT_TIMEOUT_MS', 5_000);

export const WS_READY_DELAY = readDuration('K6_ROOM_WS_READY_DELAY', '30s');

export const WS_GRACE_DURATION = readDuration('K6_ROOM_WS_GRACE_DURATION', '30s');

// Correctness samples are irreversible and stop immediately. Percentiles need
// a representative sample, so their automatic stop gate starts after the
// agreed two-minute warm-up instead of reacting to a cold-start outlier.
export const PERFORMANCE_STOP_GATE_DELAY = '2m';

export const PRIMARY_ROOM_ID = readOptionalPositiveInteger('K6_PRIMARY_ROOM_ID') || PROFILE_ROOM_IDS[0];

export const PRIMARY_ROOM_USERS = usersForRoom(FIXTURE_USERS, PRIMARY_ROOM_ID);

export const FANOUT_SUBSCRIBER_COUNT = readPositiveInteger(
	'K6_FANOUT_SUBSCRIBER_COUNT',
	Math.min(ROOM_PARTICIPANT_COUNT, PRIMARY_ROOM_USERS.length),
);

export const FANOUT_MESSAGES = readPositiveInteger('K6_FANOUT_MESSAGES', 3);

export const HISTORY_PAGE_SIZE = 100;

export const sendCreated = new Counter('chat_send_created');

export const sendIdempotent = new Counter('chat_send_idempotent');

export const sendRateLimited = new Counter('chat_send_rate_limited');

export const sendExpectedStatus = new Rate('chat_send_expected_status');

export const httpRequestDurationMs = new Trend('chat_http_request_duration_ms', true);

export const websocketOpened = new Rate('chat_websocket_opened');

export const websocketConnectMs = new Trend('chat_websocket_connect_ms', true);

export const websocketDeliveryMs = new Trend('chat_websocket_delivery_ms', true);

export const websocketDeliverySampled = new Counter('chat_websocket_delivery_sampled');

export const websocketEvents = new Counter('chat_websocket_events');

export const websocketExpectedEvent = new Rate('chat_websocket_expected_event');

export const websocketSessionHealthy = new Rate('chat_websocket_session_healthy');

export const loadSubscriberReceivedMessages = new Counter('load_subscriber_received_messages');

export const loadSubscriberDeliveryComplete = new Rate('load_subscriber_delivery_complete');

export const fanoutDeliveredToEachSubscriber = new Rate('chat_websocket_fanout_delivered');

export const historyExpectedStatus = new Rate('chat_history_expected_status');

// 부하 시나리오의 단계별 지표. stage 태그로 나눠 담아 계단마다 따로 읽는다.
export const loadStageHttpMs = new Trend('load_stage_http_ms', true);

export const loadStageDeliveryMs = new Trend('load_stage_delivery_ms', true);

export const loadStageConnectMs = new Trend('load_stage_connect_ms', true);

export const loadStageSendOk = new Rate('load_stage_send_ok');

export const loadStageOpened = new Rate('load_stage_opened');

/**
 * fixture 계정으로 로그인해 세션을 얻는다. runId는 로그인보다 먼저 찍으면 단계 태그가
 * 그만큼 앞당겨지므로 로그인을 끝낸 뒤에 정한다.
 */
export function setup() {
	const users = FIXTURE_USERS.map(prepareFixtureUser);
	return { runId: String(Date.now()), users };
}

export function perVuOptions(name, exec, vus, iterations, maxDuration, thresholds) {
	return {
		thresholds,
		scenarios: {
			[name]: {
				executor: 'per-vu-iterations',
				exec,
				vus,
				iterations,
				maxDuration,
			},
		},
	};
}

/**
 * 부하 mode의 게이트. 전부 관찰형이라 위반해도 끝까지 돌며, 단계별 하위 지표로
 * 어디서 무너졌는지 읽는다. 중단형으로 두면 첫 실패에 멈춰 한계점을 못 찾는다.
 *
 * 단계별 하위 지표는 k6가 threshold를 선언한 태그 조합만 요약에 남기므로, 항상
 * 통과하는 조건을 붙여 값만 확보한다.
 */
export function loadThresholds(stepCount, includeSubscriberDelivery = false) {
	const thresholds = {
		chat_http_request_duration_ms: [
			observationGate(`p(95)<${HTTP_P95_MS}`),
			observationGate(`p(99)<${HTTP_P99_MS}`),
		],
		chat_send_expected_status: [observationGate('rate>=0.99')],
		chat_websocket_opened: [observationGate('rate>=0.99')],
		chat_websocket_session_healthy: [observationGate('rate>=0.99')],
		chat_websocket_delivery_ms: [
			observationGate(`p(95)<${WS_DELIVERY_P95_MS}`),
			observationGate(`p(99)<${WS_DELIVERY_P99_MS}`),
		],
		// 포화 지점에서는 발생기가 목표를 못 내는 것이 정상이다. 기록만 하고 해석할 때
		// 발생기 CPU·VU 포화 여부와 함께 본다.
		dropped_iterations: [observationGate('count==0')],
	};
	for (let step = 1; step <= stepCount; step++) {
		thresholds[`load_stage_http_ms{stage:${step}}`] = [observationGate('p(95)>=0')];
		thresholds[`load_stage_send_ok{stage:${step}}`] = [observationGate('rate>=0')];
		thresholds[`load_stage_delivery_ms{stage:${step}}`] = [observationGate('p(95)>=0')];
		thresholds[`load_stage_connect_ms{stage:${step}}`] = [observationGate('p(95)>=0')];
		thresholds[`load_stage_opened{stage:${step}}`] = [observationGate('rate>=0')];
		if (includeSubscriberDelivery) {
			thresholds[`load_subscriber_delivery_complete{stage:${step}}`] = [observationGate('rate==1')];
			thresholds[`load_subscriber_received_messages{stage:${step}}`] = [observationGate('count>0')];
		}
	}
	if (includeSubscriberDelivery) {
		thresholds.load_subscriber_delivery_complete = [observationGate('rate==1')];
	}
	return thresholds;
}

/**
 * 지금이 몇 번째 단계인지 돌려준다. setup이 남긴 시작 시각에서 경과를 재고, 워밍업과
 * 시나리오 지연을 뺀 뒤 단계 길이로 나눈다. 태그 값이라 문자열로 만든다.
 */
export function currentStage(data, stepCount, offsetMs) {
	const elapsed = Date.now() - Number(data.runId) - offsetMs - durationMilliseconds(LOAD_WARMUP_DURATION);
	if (elapsed < 0) {
		return LOAD_WARMUP_STAGE;
	}
	const index = Math.floor(elapsed / durationMilliseconds(LOAD_STEP_DURATION));
	return String(Math.min(Math.max(index, 0), stepCount - 1) + 1);
}

/** 워밍업 뒤 단계별 목표를 순서대로 밟고 마지막에 0으로 내린다. */
export function loadCountStages(steps) {
	const stages = [{ duration: LOAD_WARMUP_DURATION, target: Math.round(steps[0]) }];
	for (let index = 0; index < steps.length; index++) {
		stages.push({ duration: LOAD_STEP_DURATION, target: Math.round(steps[index]) });
	}
	stages.push({ duration: '30s', target: 0 });
	return stages;
}

/** 전송·조회가 시작되기 전 WebSocket 준비 시간을 구독자 워밍업에 포함한다. */
export function loadSubscriberStages(steps) {
	const warmupMilliseconds = durationMilliseconds(WS_READY_DELAY) + durationMilliseconds(LOAD_WARMUP_DURATION);
	const warmupDuration = `${warmupMilliseconds}ms`;
	const stages = [{ duration: warmupDuration, target: Math.round(steps[0]) }];
	for (let index = 0; index < steps.length; index++) {
		stages.push({ duration: LOAD_STEP_DURATION, target: Math.round(steps[index]) });
	}
	stages.push({ duration: '30s', target: 0 });
	return stages;
}

/** 방의 참가자를 번호로 돌려 고른다. 계단이 참가자 수를 넘어도 계정을 재사용한다. */
export function roomUserForVu(users, roomId, number) {
	const participants = usersForRoom(users, roomId);
	if (participants.length === 0) {
		throw new Error(`fixture has no user for room ${roomId}`);
	}
	return participants[(number - 1) % participants.length];
}

/** 부하 mode의 전송 결과를 단계별 지표로 남긴다. */
export function recordLoadSend(result, stage) {
	const ok = result.response.status === 201;
	sendExpectedStatus.add(ok);
	loadStageSendOk.add(ok, { stage });
	loadStageHttpMs.add(result.response.timings.duration, { stage });
}

/** 이력 첫 페이지를 읽고 단계별 지표로 남긴다. */
export function readHistoryPage(user, stage) {
	const jar = installSession(user);
	const response = http.get(`${BASE_URL}/api/rooms/${user.roomId}/chat/messages?size=${HISTORY_PAGE_SIZE}`, {
		jar,
		tags: { name: 'chat_history', mode: execution.scenario.name, stage },
	});
	recordHttpResponse(response, 'history');
	const ok = response.status === 200;
	historyExpectedStatus.add(ok);
	loadStageSendOk.add(ok, { stage });
	loadStageHttpMs.add(response.timings.duration, { stage });
}

/**
 * 부하 mode의 수신자를 한 단계 길이만큼 붙여 둔다. 연결·open 지표는 연결 시작 단계를
 * 유지하고, 장기 연결로 받은 전달 지연은 수신 시점의 단계로 태그를 남긴다.
 */
export function holdLoadSubscriber(user, roomId, connectionStage, deliveryStage, mode) {
	const startedAt = Date.now();
	const receivedClientMessageIds = {};
	const connection = createWebSocket(user, mode);
	connection.socket.addEventListener('open', () => {
		loadStageConnectMs.add(Date.now() - startedAt, { stage: connectionStage });
	});
	connection.socket.addEventListener('message', (event) => {
		const message = parseWebSocketEvent(event.data);
		websocketEvents.add(1);
		const expected = isMessageCreatedEvent(message, roomId);
		websocketExpectedEvent.add(expected);
		if (!expected) {
			return;
		}
		const clientMessageId = message.message.clientMessageId;
		if (typeof clientMessageId === 'string' && clientMessageId !== '') {
			receivedClientMessageIds[clientMessageId] = true;
		}
		recordDeliveryFromCreatedAt(message.message.createdAt);
		loadStageDeliveryMs.add(Date.now() - Date.parse(message.message.createdAt), {
			stage: deliveryStage(),
		});
	});
	setTimeout(() => {
		recordMissingWebSocketOpen(connection);
		websocketSessionHealthy.add(isHealthyWebSocket(connection));
		loadStageOpened.add(connection.opened, { stage: connectionStage });
		const receivedMessageCount = Object.keys(receivedClientMessageIds).length;
		loadSubscriberReceivedMessages.add(receivedMessageCount, { stage: connectionStage });
		loadSubscriberDeliveryComplete.add(receivedMessageCount > 0, { stage: connectionStage });
		closeWebSocket(connection);
	}, durationMilliseconds(LOAD_STEP_DURATION));
}

/** 목표 도착률을 p99 안에서 유지하려면 필요한 VU 수. 부족하면 요청이 누락된다. */
export function requiredArrivalVus(ratePerSecond, latencyMs) {
	return Math.max(1, Math.ceil(ratePerSecond * latencyMs / 1000) + 1);
}

export function maxOf(values) {
	let widest = values[0];
	for (let index = 1; index < values.length; index++) {
		widest = Math.max(widest, values[index]);
	}
	return widest;
}

export function defaultThresholds() {
	return { checks: [immediateStopGate('rate==1')] };
}

export function normalHttpThresholds(extraThresholds = {}) {
	return {
		...defaultThresholds(),
		chat_http_request_duration_ms: [
			performanceStopGate(`p(95)<${HTTP_P95_MS}`),
			performanceStopGate(`p(99)<${HTTP_P99_MS}`),
		],
		...extraThresholds,
	};
}

export function successfulSendHttpThresholds(extraThresholds = {}) {
	return normalHttpThresholds({
		chat_send_expected_status: [immediateStopGate('rate==1')],
		...extraThresholds,
	});
}

export function webSocketThresholds(extraThresholds = {}) {
	return {
		...defaultThresholds(),
		chat_websocket_opened: [immediateStopGate('rate==1')],
		chat_websocket_connect_ms: [
			performanceStopGate(`p(95)<${WS_CONNECT_P95_MS}`),
			performanceStopGate(`p(99)<${WS_CONNECT_P99_MS}`),
		],
		chat_websocket_expected_event: [immediateStopGate('rate==1')],
		chat_websocket_session_healthy: [immediateStopGate('rate==1')],
		...extraThresholds,
	};
}

export function fanoutThresholds() {
	return {
		...successfulSendHttpThresholds(),
		...webSocketThresholds({
			chat_websocket_fanout_delivered: [immediateStopGate('rate==1')],
			chat_send_created: [`count==${FANOUT_MESSAGES}`],
			chat_websocket_delivery_ms: [
				performanceStopGate(`p(95)<${WS_DELIVERY_P95_MS}`),
				performanceStopGate(`p(99)<${WS_DELIVERY_P99_MS}`),
			],
			chat_websocket_delivery_sampled: ['count>0'],
		}),
	};
}

/** 부하 mode용. 위반해도 중단하지 않고 결과에만 남겨 한계점을 찾게 한다. */
export function observationGate(threshold) {
	return { threshold, abortOnFail: false };
}

export function immediateStopGate(threshold) {
	return { threshold, abortOnFail: true };
}

export function performanceStopGate(threshold) {
	return {
		threshold,
		abortOnFail: true,
		delayAbortEval: PERFORMANCE_STOP_GATE_DELAY,
	};
}

export function authenticateFixtureUser(user) {
	assertCredentialFixtureUser(user);
	const jar = http.cookieJar();
	jar.clear(BASE_URL);
	jar.delete(BASE_URL, 'JSESSIONID');
	jar.delete(BASE_URL, 'XSRF-TOKEN');
	const initialCsrf = requestCsrfToken(jar);
	const loginResponse = http.post(
		`${BASE_URL}/api/auth/login`,
		JSON.stringify({ email: user.email, password: user.password }),
		{ headers: jsonHeaders(initialCsrf), jar, tags: { name: 'chat_load_login' } },
	);
	const sessionId = responseCookie(loginResponse, 'JSESSIONID');
	const payload = parseApiPayload(loginResponse);
	const userId = payload && payload.data && payload.data.id;
	if (loginResponse.status !== 200 || sessionId === null || !Number.isInteger(userId) || userId < 1) {
		throw new Error(`Failed to authenticate chat load fixture user ${user.label}`);
	}
	const authenticatedCsrf = requestCsrfToken(jar);
	return {
		label: user.label,
		userId,
		roomId: user.roomId,
		sessionId,
		csrfHeaderName: authenticatedCsrf.headerName,
		csrfToken: authenticatedCsrf.token,
	};
}

export function prepareFixtureUser(user) {
	if (isPreparedSessionFixtureUser(user)) {
		return user;
	}
	return authenticateFixtureUser(user);
}

export function requestCsrfToken(jar = http.cookieJar()) {
	const response = http.get(`${BASE_URL}/api/auth/csrf`, {
		jar,
		tags: { name: 'chat_load_csrf' },
	});
	const payload = parseApiPayload(response);
	const csrf = payload && payload.data;
	if (response.status !== 200 || !csrf || !csrf.headerName || !csrf.token) {
		throw new Error('Could not obtain a CSRF token for the chat load fixture');
	}
	return csrf;
}

export function installSession(user, targetBaseUrl = BASE_URL) {
	// 한 VU가 여러 사용자의 WebSocket handshake를 동시에 시작할 수 있으므로 기본 jar를 공유하지 않는다.
	const jar = new http.CookieJar();
	jar.set(targetBaseUrl, 'JSESSIONID', user.sessionId);
	jar.set(targetBaseUrl, 'XSRF-TOKEN', user.csrfToken);
	return jar;
}

export function postNewMessage(user, runId, purpose, sequence, targetBaseUrl = BASE_URL) {
	return postMessage(
		user,
		clientMessageId(runId, purpose, execution.vu.idInTest, sequence),
		messageContent(runId, purpose, execution.vu.idInTest, sequence),
		targetBaseUrl,
	);
}

export function postMessage(user, clientMessageId, content, targetBaseUrl = BASE_URL, additionalHeaders = {}) {
	const jar = installSession(user, targetBaseUrl);
	const response = http.post(
		`${targetBaseUrl}/api/rooms/${user.roomId}/chat/messages`,
		JSON.stringify({ clientMessageId, content }),
		{
			headers: { ...jsonHeaders(user), ...additionalHeaders },
			jar,
			tags: { name: 'chat_send', mode: execution.scenario.name, room_id: String(user.roomId) },
		},
	);
	recordHttpResponse(response, 'message');
	const payload = parseApiPayload(response);
	if (response.status === 201) {
		sendCreated.add(1);
	} else if (response.status === 200) {
		sendIdempotent.add(1);
	} else if (response.status === 429) {
		sendRateLimited.add(1);
	}
	return { response, payload };
}

export function recordHttpResponse(response, operation) {
	if (response && response.timings && Number.isFinite(response.timings.duration)) {
		httpRequestDurationMs.add(response.timings.duration, { operation });
	}
}

export function verifyCrossInstanceRoute(user, targetBaseUrl, expectedRoute) {
	const response = http.get(`${targetBaseUrl}/api/users/me`, {
		headers: performanceRouteHeader(expectedRoute),
		jar: installSession(user, targetBaseUrl),
		tags: { name: 'chat_cross_instance_preflight', route: expectedRoute },
	});
	recordHttpResponse(response, 'cross-instance-preflight');
	const expected = response.status === 200 && selectedPerformanceRoute(response) === expectedRoute;
	sendExpectedStatus.add(expected);
	check(response, {
		'chat cross-instance route is pinned by the performance proxy': () => expected,
	});
	return expected;
}

export function failCrossInstancePreflight() {
	// A failed preflight must produce samples for every applicable threshold;
	// otherwise an empty custom metric could make the scenario look healthy.
	websocketOpened.add(0);
	websocketSessionHealthy.add(0);
	fanoutDeliveredToEachSubscriber.add(0);
}

export function performanceRouteHeader(route) {
	return route === null ? {} : { 'X-Albam-Mate-Performance-Upstream': route };
}

export function selectedPerformanceRoute(response) {
	const header = response && response.headers && (
		response.headers['X-Albam-Mate-Performance-Upstream']
		|| response.headers['x-albam-mate-performance-upstream']
	);
	return Array.isArray(header) ? header[0] : header;
}

export function openFanoutWebSockets(
	users,
	runId,
	sendBaseUrl = BASE_URL,
	receiveBaseUrl = BASE_URL,
	mode = 'ws-fanout',
	sendRoute = null,
	receiveRoute = null,
) {
	if (receiveRoute !== null && !verifyCrossInstanceRoute(users[0], receiveBaseUrl, receiveRoute)) {
		failCrossInstancePreflight();
		return;
	}
	const expectedClientMessageIds = expectedFanoutClientMessageIds(runId, mode);
	const state = {
		completed: false,
		connections: [],
		deliveryTimer: null,
		expectedByClientMessageId: setOf(expectedClientMessageIds),
		expectedClientMessageIds,
		invalidEvent: false,
		messagesCreated: false,
		openCount: 0,
		readinessTimedOut: false,
		readinessTimer: null,
		sending: false,
		sentAtByClientMessageId: {},
		subscribers: [],
		unexpectedMessage: false,
	};
	state.readinessTimer = setTimeout(() => {
		state.readinessTimedOut = true;
		finishFanout(state);
	}, WS_EVENT_TIMEOUT_MS);
	for (let index = 0; index < users.length; index++) {
		const user = users[index];
		const connection = createWebSocket(
			user,
			mode,
			undefined,
			receiveBaseUrl,
			performanceRouteHeader(receiveRoute),
		);
		const subscriber = { connection, receivedClientMessageIds: {}, receivedEventIds: {}, user };
		state.connections.push(connection);
		state.subscribers.push(subscriber);
		connection.socket.addEventListener('open', () => {
			state.openCount++;
			if (state.openCount === users.length && !state.completed && !state.sending) {
				clearTimeout(state.readinessTimer);
				setTimeout(() => startFanoutMessages(state, users, runId, sendBaseUrl, mode, sendRoute), 0);
			}
		});
		connection.socket.addEventListener('message', (event) => {
			const message = parseWebSocketEvent(event.data);
			websocketEvents.add(1);
			const expectedEvent = isMessageCreatedEvent(message, user.roomId);
			websocketExpectedEvent.add(expectedEvent);
			if (!expectedEvent) {
				state.invalidEvent = true;
				return;
			}
			const clientMessageId = message.message.clientMessageId;
			if (!state.expectedByClientMessageId[clientMessageId]) {
				state.unexpectedMessage = true;
				return;
			}
			if (subscriber.receivedClientMessageIds[clientMessageId] || subscriber.receivedEventIds[message.eventId]) {
				state.invalidEvent = true;
				return;
			}
			subscriber.receivedClientMessageIds[clientMessageId] = true;
			subscriber.receivedEventIds[message.eventId] = true;
			recordDeliveryFromSentAt(state.sentAtByClientMessageId[clientMessageId]);
		});
	}
}

export function startFanoutMessages(state, users, runId, sendBaseUrl, mode, sendRoute) {
	if (state.completed || state.sending || state.openCount !== users.length) {
		return;
	}
	state.sending = true;
	const sender = users[0];
	let messagesCreated = true;
	for (let index = 0; index < FANOUT_MESSAGES; index++) {
		const clientId = clientMessageId(runId, mode, 0, index);
		state.sentAtByClientMessageId[clientId] = Date.now();
		const result = postMessage(
			sender,
			clientId,
			messageContent(runId, mode, 0, index),
			sendBaseUrl,
			performanceRouteHeader(sendRoute),
		);
		messagesCreated = messagesCreated
			&& result.response.status === 201
			&& hasMessageForRoom(result.payload, sender.roomId)
			&& (sendRoute === null || selectedPerformanceRoute(result.response) === sendRoute);
	}
	state.messagesCreated = messagesCreated;
	sendExpectedStatus.add(messagesCreated);
	check({ messagesCreated }, {
		'chat fan-out sender creates every expected room message': (value) => value.messagesCreated,
	});
	state.deliveryTimer = setTimeout(() => finishFanout(state), WS_EVENT_TIMEOUT_MS);
}

export function finishFanout(state) {
	if (state.completed) {
		return;
	}
	state.completed = true;
	clearTimeout(state.readinessTimer);
	clearTimeout(state.deliveryTimer);
	for (let index = 0; index < state.connections.length; index++) {
		recordMissingWebSocketOpen(state.connections[index]);
	}
	let everySubscriberReceivedExactlyOnce = true;
	for (let index = 0; index < state.subscribers.length; index++) {
		const subscriber = state.subscribers[index];
		const delivered = isHealthyWebSocket(subscriber.connection)
			&& !state.invalidEvent
			&& !state.unexpectedMessage
			&& hasExactlyKeys(subscriber.receivedClientMessageIds, state.expectedClientMessageIds);
		fanoutDeliveredToEachSubscriber.add(delivered);
		if (!delivered) {
			everySubscriberReceivedExactlyOnce = false;
		}
	}
	const expected = !state.readinessTimedOut
		&& state.openCount === state.connections.length
		&& state.messagesCreated
		&& everySubscriberReceivedExactlyOnce;
	websocketSessionHealthy.add(expected);
	check({ expected }, {
		'chat fan-out opens every subscriber before sending and delivers each message exactly once':
			(value) => value.expected,
	});
	for (let index = 0; index < state.connections.length; index++) {
		closeWebSocket(state.connections[index]);
	}
}

export function createWebSocket(user, mode, afterMessageId, targetBaseUrl = BASE_URL, additionalHeaders = {}) {
	const jar = installSession(user, targetBaseUrl);
	const query = afterMessageId === undefined ? '' : `?afterMessageId=${afterMessageId}`;
	const startedAt = Date.now();
	const connection = {
		closeRequested: false,
		error: false,
		opened: false,
		openResultRecorded: false,
		socket: null,
		unexpectedClose: false,
	};
	const socket = new WebSocket(`${toWebSocketBaseUrl(targetBaseUrl)}/api/rooms/${user.roomId}/chat/ws${query}`, null, {
		jar,
		headers: { Origin: ORIGIN, ...additionalHeaders },
		tags: { name: 'chat_websocket', mode, room_id: String(user.roomId) },
	});
	connection.socket = socket;
	socket.addEventListener('open', () => {
		connection.opened = true;
		connection.openResultRecorded = true;
		websocketOpened.add(1);
		websocketConnectMs.add(Date.now() - startedAt);
	});
	socket.addEventListener('error', () => {
		connection.error = true;
		if (!connection.openResultRecorded) {
			connection.openResultRecorded = true;
			websocketOpened.add(0);
		}
	});
	socket.addEventListener('close', () => {
		if (!connection.opened && !connection.openResultRecorded) {
			connection.openResultRecorded = true;
			websocketOpened.add(0);
		}
		if (!connection.closeRequested) {
			connection.unexpectedClose = true;
		}
	});
	return connection;
}

export function closeWebSocket(connection) {
	if (!connection.closeRequested) {
		connection.closeRequested = true;
		connection.socket.close();
	}
}

export function isHealthyWebSocket(connection) {
	return connection.opened && !connection.error && !connection.unexpectedClose;
}

export function recordMissingWebSocketOpen(connection) {
	if (!connection.openResultRecorded) {
		connection.openResultRecorded = true;
		websocketOpened.add(0);
	}
}

export function recordDeliveryFromSentAt(sentAt) {
	if (Number.isFinite(sentAt)) {
		websocketDeliveryMs.add(Math.max(0, Date.now() - sentAt));
		websocketDeliverySampled.add(1);
	}
}

export function recordDeliveryFromCreatedAt(createdAt) {
	const createdAtMilliseconds = Date.parse(createdAt);
	if (Number.isFinite(createdAtMilliseconds)) {
		const latency = Date.now() - createdAtMilliseconds;
		if (latency >= 0) {
			websocketDeliveryMs.add(latency);
			websocketDeliverySampled.add(1);
		}
	}
}

export function roomParticipants(users, roomId) {
	const roomUsers = usersForRoom(users, roomId);
	if (roomUsers.length < ROOM_PARTICIPANT_COUNT) {
		throw new Error(`Room ${roomId} has fewer than ${ROOM_PARTICIPANT_COUNT} fixture participants`);
	}
	return roomUsers.slice(0, ROOM_PARTICIPANT_COUNT);
}

export function fanoutParticipants(users, roomId) {
	return roomParticipants(users, roomId).slice(0, FANOUT_SUBSCRIBER_COUNT);
}

export function profileUserForVu(users) {
	const profileUsers = usersForRooms(users, PROFILE_ROOM_IDS);
	return profileUsers[(execution.vu.idInTest - 1) % profileUsers.length];
}

export function jsonHeaders(csrf) {
	const headers = { 'Content-Type': 'application/json' };
	headers[csrf.headerName || csrf.csrfHeaderName] = csrf.token || csrf.csrfToken;
	return headers;
}

export function parseFixture(serializedFixture) {
	let fixture;
	try {
		fixture = JSON.parse(serializedFixture);
	} catch (error) {
		throw new Error(`K6_CHAT_FIXTURE is not valid JSON: ${error}`);
	}
	if (!fixture || !Array.isArray(fixture.users) || fixture.users.length === 0) {
		throw new Error('K6_CHAT_FIXTURE must contain a non-empty users array');
	}
	const users = fixture.users.map((user, index) => validateFixtureUser(user, index));
	validateDistinctFixtureValues(users, 'label');
	validateDistinctFixtureValues(users, 'userId');
	return {
		users,
		profiles: parseProfiles(fixture.profiles, users),
		reconnect: parseReconnectPlan(fixture.reconnect, users),
	};
}

export function validateFixtureUser(user, index) {
	if (!user || typeof user.label !== 'string' || user.label.trim() === ''
		|| !Number.isInteger(user.roomId) || user.roomId < 1) {
		throw new Error(`K6_CHAT_FIXTURE user ${index} needs label and positive roomId`);
	}
	const hasPreparedSession = isPreparedSessionFixtureUser(user);
	const hasCredentials = typeof user.email === 'string' && user.email !== ''
		&& typeof user.password === 'string' && user.password !== '';
	if (!hasPreparedSession && !hasCredentials) {
		throw new Error(`K6_CHAT_FIXTURE user ${index} needs a prepared session or login credentials`);
	}
	return {
		label: user.label,
		userId: hasPreparedSession ? user.userId : null,
		roomId: user.roomId,
		sessionId: hasPreparedSession ? user.sessionId : null,
		csrfHeaderName: hasPreparedSession ? user.csrfHeaderName : null,
		csrfToken: hasPreparedSession ? user.csrfToken : null,
		email: hasCredentials ? user.email : null,
		password: hasCredentials ? user.password : null,
	};
}

export function isPreparedSessionFixtureUser(user) {
	return Number.isInteger(user.userId) && user.userId > 0
		&& typeof user.sessionId === 'string' && user.sessionId !== ''
		&& typeof user.csrfHeaderName === 'string' && user.csrfHeaderName !== ''
		&& typeof user.csrfToken === 'string' && user.csrfToken !== '';
}

export function parseProfiles(rawProfiles, users) {
	const roomIds = distinctRoomIds(users);
	if (rawProfiles === undefined) {
		return { default: { roomIds } };
	}
	if (!rawProfiles || typeof rawProfiles !== 'object' || Array.isArray(rawProfiles)) {
		throw new Error('fixture profiles must be an object of named roomId arrays');
	}
	const profiles = {};
	for (const profileName of Object.keys(rawProfiles)) {
		const profile = rawProfiles[profileName];
		if (!profile || !Array.isArray(profile.roomIds) || profile.roomIds.length === 0) {
			throw new Error(`fixture profile ${profileName} needs a non-empty roomIds array`);
		}
		const uniqueRoomIds = [];
		const known = {};
		for (let index = 0; index < profile.roomIds.length; index++) {
			const roomId = profile.roomIds[index];
			if (!Number.isInteger(roomId) || roomId < 1 || known[roomId] || usersForRoom(users, roomId).length === 0) {
				throw new Error(`fixture profile ${profileName} has an unknown or duplicate roomId`);
			}
			known[roomId] = true;
			uniqueRoomIds.push(roomId);
		}
		profiles[profileName] = { roomIds: uniqueRoomIds };
	}
	if (Object.keys(profiles).length === 0) {
		throw new Error('fixture profiles must not be empty');
	}
	return profiles;
}

export function parseReconnectPlan(rawPlan, users) {
	if (rawPlan === undefined) {
		return null;
	}
	if (!rawPlan || typeof rawPlan.userLabel !== 'string'
		|| !Number.isInteger(rawPlan.afterMessageId) || rawPlan.afterMessageId < 0
		|| !Array.isArray(rawPlan.expectedMessageIds) || rawPlan.expectedMessageIds.length === 0
		|| !users.some((user) => user.label === rawPlan.userLabel)) {
		throw new Error('fixture reconnect needs userLabel, afterMessageId, and expectedMessageIds');
	}
	let previous = rawPlan.afterMessageId;
	for (let index = 0; index < rawPlan.expectedMessageIds.length; index++) {
		const messageId = rawPlan.expectedMessageIds[index];
		if (!Number.isInteger(messageId) || messageId <= previous) {
			throw new Error('fixture reconnect expectedMessageIds must be strictly ascending after afterMessageId');
		}
		previous = messageId;
	}
	return {
		userLabel: rawPlan.userLabel,
		afterMessageId: rawPlan.afterMessageId,
		expectedMessageIds: rawPlan.expectedMessageIds,
	};
}

export function assertCredentialFixtureUser(user) {
	if (!user.email || !user.password || user.email.endsWith('.invalid') || user.password.includes('replace-with-')) {
		throw new Error(`Replace example credential values before running k6: ${user.label}`);
	}
}

export function parseApiPayload(response) {
	try {
		return JSON.parse(response.body);
	} catch (error) {
		return null;
	}
}

export function parseWebSocketEvent(serializedEvent) {
	try {
		return JSON.parse(serializedEvent);
	} catch (error) {
		return null;
	}
}

export function messageIdOf(payload) {
	return payload && payload.data && Number.isInteger(payload.data.messageId) ? payload.data.messageId : 0;
}

export function hasMessageForRoom(payload, expectedRoomId) {
	return messageIdOf(payload) > 0 && payload.data.roomId === expectedRoomId;
}

export function isMessageCreatedEvent(message, expectedRoomId) {
	return message
		&& message.type === 'MESSAGE_CREATED'
		&& Number.isInteger(message.eventId)
		&& message.eventId > 0
		&& message.message
		&& Number.isInteger(message.message.messageId)
		&& message.message.messageId === message.eventId
		&& Number.isInteger(message.message.roomId)
		&& message.message.roomId === expectedRoomId
		&& typeof message.message.clientMessageId === 'string'
		&& typeof message.message.createdAt === 'string';
}

export function responseCookie(response, name) {
	const cookies = response.cookies[name];
	return cookies && cookies.length > 0 ? cookies[cookies.length - 1].value : null;
}

export function clientMessageId(runId, purpose, vu, sequence) {
	return `k6-${purpose}-${runId}-${vu}-${sequence}`.slice(0, 100);
}

export function messageContent(runId, purpose, vu, sequence) {
	const prefix = `k6:${runId}:${purpose}:${vu}:${sequence}:`;
	return `${prefix}${'가'.repeat(Math.max(0, MESSAGE_CHARS - prefix.length))}`.slice(0, MESSAGE_CHARS);
}

export function expectedFanoutClientMessageIds(runId, mode) {
	const clientMessageIds = [];
	for (let index = 0; index < FANOUT_MESSAGES; index++) {
		clientMessageIds.push(clientMessageId(runId, mode, 0, index));
	}
	return clientMessageIds;
}

export function setOf(values) {
	const set = {};
	for (let index = 0; index < values.length; index++) {
		set[values[index]] = true;
	}
	return set;
}

export function hasExactlyKeys(actual, expectedKeys) {
	const actualKeys = Object.keys(actual);
	if (actualKeys.length !== expectedKeys.length) {
		return false;
	}
	for (let index = 0; index < expectedKeys.length; index++) {
		if (!actual[expectedKeys[index]]) {
			return false;
		}
	}
	return true;
}

export function equalNumberArrays(actual, expected) {
	if (actual.length !== expected.length) {
		return false;
	}
	for (let index = 0; index < expected.length; index++) {
		if (actual[index] !== expected[index]) {
			return false;
		}
	}
	return true;
}

export function usersForRoom(users, roomId) {
	return users.filter((user) => user.roomId === roomId);
}

export function usersForRooms(users, roomIds) {
	const roomIdSet = setOf(roomIds);
	return users.filter((user) => roomIdSet[user.roomId]);
}

export function distinctRoomIds(users) {
	const roomIds = [];
	const known = {};
	for (let index = 0; index < users.length; index++) {
		const roomId = users[index].roomId;
		if (!known[roomId]) {
			known[roomId] = true;
			roomIds.push(roomId);
		}
	}
	return roomIds;
}

export function validateDistinctFixtureValues(users, field) {
	const values = {};
	for (let index = 0; index < users.length; index++) {
		const value = users[index][field];
		if (value !== null && values[value]) {
			throw new Error(`fixture user ${field} must be unique: ${value}`);
		}
		if (value !== null) {
			values[value] = true;
		}
	}
}

export function defaultProfileName(fixture) {
	const names = Object.keys(fixture.profiles);
	return fixture.profiles['hot-room'] ? 'hot-room' : names[0];
}

export function profileFor(fixture, name) {
	const profile = fixture.profiles[name];
	if (!profile) {
		throw new Error(`K6_CHAT_PROFILE does not exist in fixture: ${name}`);
	}
	return profile;
}

export function subscriberScenarioName(roomId, userIndex) {
	return `room_subscriber_${roomId}_${userIndex}`;
}

export function subscriptionFromScenario(name, users) {
	const match = /^room_subscriber_(\d+)_(\d+)$/.exec(name);
	if (!match) {
		throw new Error(`Cannot resolve room subscriber scenario: ${name}`);
	}
	const roomId = Number(match[1]);
	const userIndex = Number(match[2]);
	// 팬아웃 부하는 방을 정원까지 채우므로 6명 로테이션 범위를 넘는 인덱스가 나온다.
	// 방 전체 참가자에서 찾고, 부족할 때만 오류로 본다.
	const participants = usersForRoom(users, roomId);
	if (!Number.isInteger(userIndex) || userIndex < 0 || userIndex >= participants.length) {
		throw new Error(`Cannot resolve room subscriber user index: ${name}`);
	}
	return { roomId, user: participants[userIndex] };
}

export function validateThresholdBounds() {
	if (HTTP_P99_MS < HTTP_P95_MS || WS_CONNECT_P99_MS < WS_CONNECT_P95_MS || WS_DELIVERY_P99_MS < WS_DELIVERY_P95_MS) {
		throw new Error('Every p99 threshold must be greater than or equal to its p95 threshold');
	}
}

export function validateProfileVuCount(vus, mode) {
	if (vus > PROFILE_USERS.length) {
		throw new Error(`${mode} has ${vus} VUs but profile ${PROFILE_NAME} has only ${PROFILE_USERS.length} users`);
	}
}

export function validateBurstMapping(vus, messagesPerUser, mode) {
	validateProfileVuCount(vus, mode);
	if (messagesPerUser > USER_RATE_LIMIT_PER_WINDOW) {
		throw new Error(
			`${mode} creates more than ${USER_RATE_LIMIT_PER_WINDOW} messages per user inside one 10-second quota window`,
		);
	}
	const users = PROFILE_USERS.slice(0, vus);
	const roomCounts = roomUserCounts(users);
	for (let index = 0; index < PROFILE_ROOM_IDS.length; index++) {
		const roomId = PROFILE_ROOM_IDS[index];
		if ((roomCounts[roomId] || 0) * messagesPerUser > ROOM_RATE_LIMIT_PER_WINDOW) {
			throw new Error(`${mode} exceeds the room ${ROOM_RATE_LIMIT_PER_WINDOW}/10s quota for room ${roomId}`);
		}
	}
}

export function validateFanoutProfile() {
	const subscribers = roomParticipants(FIXTURE_USERS, PRIMARY_ROOM_ID);
	if (FANOUT_SUBSCRIBER_COUNT < 2 || FANOUT_SUBSCRIBER_COUNT > subscribers.length) {
		throw new Error('K6_FANOUT_SUBSCRIBER_COUNT must select 2 through all primary-room participants');
	}
	if (FANOUT_MESSAGES > USER_RATE_LIMIT_PER_WINDOW) {
		throw new Error(
			`K6_FANOUT_MESSAGES must not exceed the sender ${USER_RATE_LIMIT_PER_WINDOW}/10s quota`,
		);
	}
	validateDistinctPrincipals(subscribers.slice(0, FANOUT_SUBSCRIBER_COUNT), 'fanout subscribers');
}

/** 서로 다른 계정인지 확인한다. login fixture에는 userId가 없으므로 email을 본다. */
export function validateDistinctPrincipals(users, name) {
	const field = 'email';
	const seen = {};
	for (let index = 0; index < users.length; index++) {
		const value = users[index][field];
		if (value === null || value === undefined || seen[value]) {
			throw new Error(`${name} must have distinct fixture ${field} values`);
		}
		seen[value] = true;
	}
}

export function roomUserCounts(users) {
	const counts = {};
	for (let index = 0; index < users.length; index++) {
		const roomId = users[index].roomId;
		counts[roomId] = (counts[roomId] || 0) + 1;
	}
	return counts;
}

export function readPositiveInteger(name, defaultValue) {
	const raw = __ENV[name];
	if (raw === undefined || raw === '') {
		return defaultValue;
	}
	const parsed = Number(raw);
	if (!Number.isInteger(parsed) || parsed < 1) {
		throw new Error(`${name} must be a positive integer`);
	}
	return parsed;
}

export function readFixedPositiveInteger(name, fixedValue, reason) {
	const value = readPositiveInteger(name, fixedValue);
	if (value !== fixedValue) {
		throw new Error(`${name} is fixed at ${fixedValue} for ${reason}`);
	}
	return fixedValue;
}

export function readNonNegativeInteger(name, defaultValue) {
	const raw = __ENV[name];
	if (raw === undefined || raw === '') {
		return defaultValue;
	}
	const parsed = Number(raw);
	if (!Number.isInteger(parsed) || parsed < 0) {
		throw new Error(`${name} must be a non-negative integer`);
	}
	return parsed;
}

export function readPositiveNumber(name, defaultValue) {
	const raw = __ENV[name];
	if (raw === undefined || raw === '') {
		return defaultValue;
	}
	const parsed = Number(raw);
	if (!Number.isFinite(parsed) || parsed <= 0) {
		throw new Error(`${name} must be a positive number`);
	}
	return parsed;
}

export function readNonNegativeNumber(name, defaultValue) {
	const raw = __ENV[name];
	if (raw === undefined || raw === '') {
		return defaultValue;
	}
	const parsed = Number(raw);
	if (!Number.isFinite(parsed) || parsed < 0) {
		throw new Error(`${name} must be a non-negative number`);
	}
	return parsed;
}

export function readRequiredString(name) {
	const raw = __ENV[name];
	if (typeof raw !== 'string' || raw.trim() === '') {
		throw new Error(`${name} is required`);
	}
	return raw;
}

/** 외부 runner의 기존 별칭을 유지하되 대상 없이 localhost로 요청하지 않는다. */
export function readRequiredTargetUrl() {
	const value = __ENV.K6_BASE_URL || __ENV.ALBAM_MATE_TARGET_URL;
	if (typeof value !== 'string' || value.trim() === '') {
		throw new Error('K6_BASE_URL or ALBAM_MATE_TARGET_URL is required');
	}
	return removeTrailingSlash(value.trim());
}

export function readNonEmptyString(name, defaultValue) {
	const raw = __ENV[name];
	if (raw === undefined || raw === '') {
		return defaultValue;
	}
	if (typeof raw !== 'string' || raw.trim() === '') {
		throw new Error(`${name} must be a non-empty string`);
	}
	return raw;
}

export function readEnum(name, defaultValue, values) {
	const value = readNonEmptyString(name, defaultValue);
	if (!values.includes(value)) {
		throw new Error(`${name} must be one of: ${values.join(', ')}`);
	}
	return value;
}


export function readOptionalPositiveInteger(name) {
	const raw = __ENV[name];
	return raw === undefined || raw === '' ? null : readPositiveInteger(name, 1);
}

export function readRateSteps(name, defaultValue) {
	const raw = readNonEmptyString(name, defaultValue);
	const values = raw.split(',').map((value) => Number(value.trim()));
	if (values.length === 0 || values.some((value) => !Number.isFinite(value) || value <= 0)) {
		throw new Error(`${name} must be a comma-separated list of positive rates`);
	}
	return values;
}

export function readDuration(name, defaultValue) {
	const value = readNonEmptyString(name, defaultValue);
	durationMilliseconds(value);
	return value;
}

export function durationMilliseconds(value) {
	const match = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(value);
	if (!match) {
		throw new Error(`Duration must use a single ms, s, m, or h unit: ${value}`);
	}
	const amount = Number(match[1]);
	const multiplier = { ms: 1, s: 1_000, m: 60_000, h: 3_600_000 }[match[2]];
	return amount * multiplier;
}

export function durationForMilliseconds(milliseconds) {
	return `${Math.ceil(milliseconds / 1_000)}s`;
}

export function durationWithGrace(duration, grace) {
	return durationForMilliseconds(durationMilliseconds(duration) + durationMilliseconds(grace));
}

export function removeTrailingSlash(value) {
	return value.replace(/\/+$/, '');
}

export function toWebSocketBaseUrl(httpBaseUrl) {
	if (httpBaseUrl.startsWith('https://')) {
		return `wss://${httpBaseUrl.slice('https://'.length)}`;
	}
	if (httpBaseUrl.startsWith('http://')) {
		return `ws://${httpBaseUrl.slice('http://'.length)}`;
	}
	throw new Error('K6_BASE_URL must start with http:// or https://');
}

/** mode 와 무관하게 모든 시나리오가 같이 지켜야 하는 전제. */
export function validateCommonPrerequisites() {
	validateThresholdBounds();
	if (__ENV.K6_WS_BASE_URL !== undefined && __ENV.K6_WS_BASE_URL !== '') {
		throw new Error('K6_WS_BASE_URL is unsupported: WebSocket URLs derive from K6_BASE_URL');
	}
	if (MESSAGE_CHARS > 500) {
		throw new Error('K6_MESSAGE_CHARS must not exceed the chat 500-character limit');
	}
	if (FIXTURE_USERS.length > LOGIN_LIMIT) {
		throw new Error(
			`fixture cannot exceed the remote-IP login limit of ${LOGIN_LIMIT} users per 10 minutes`,
		);
	}
}

/** 계약 시나리오의 VU 수. 지정하지 않으면 profile 계정 수를 따른다. */
export function readVus(defaultValue = PROFILE_USERS.length) {
	return readPositiveInteger('K6_CHAT_VUS', defaultValue);
}

export function readIterations(defaultValue = 1) {
	return readPositiveInteger('K6_CHAT_ITERATIONS', defaultValue);
}
