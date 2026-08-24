// 전송 처리량. 방 전체를 합친 초당 전송 건수를 계단식으로 올린다.

import execution from 'k6/execution';

import {
	FIXTURE_USERS,
	HTTP_P99_MS,
	LOAD_STEP_DURATION,
	LOAD_SUBSCRIBERS_PER_ROOM,
	LOAD_WARMUP_DURATION,
	PROFILE_ROOM_IDS,
	WS_GRACE_DURATION,
	WS_READY_DELAY,
	closeWebSocket,
	createWebSocket,
	currentStage,
	durationForMilliseconds,
	durationMilliseconds,
	isHealthyWebSocket,
	isMessageCreatedEvent,
	loadCountStages,
	loadStageDeliveryMs,
	loadThresholds,
	maxOf,
	parseWebSocketEvent,
	postNewMessage,
	readPositiveInteger,
	readRateSteps,
	recordDeliveryFromCreatedAt,
	recordLoadSend,
	recordMissingWebSocketOpen,
	requiredArrivalVus,
	roomUserForVu,
	subscriberScenarioName,
	subscriptionFromScenario,
	usersForRoom,
	validateCommonPrerequisites,
	websocketEvents,
	websocketExpectedEvent,
	websocketSessionHealthy,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

// 초당 전송 건수. 방 전체를 합친 값이다.
const LOAD_SEND_RATES = readRateSteps('K6_LOAD_SEND_RATES', '1,2,3,4,5');

// 전송 처리량이 쓰는 방 수. fixture 방이 늘어도 여기에 묶어 두지 않으면 수신자가 함께
// 늘어 팬아웃 부하가 전송 처리량 축에 섞인다.
const LOAD_THROUGHPUT_ROOMS = readPositiveInteger('K6_LOAD_THROUGHPUT_ROOMS', 3);

/** 전송 처리량. 방과 발신자를 돌려가며 초당 전송 건수를 계단식으로 올린다. */
export function loadThroughputSend(data) {
	const stage = currentStage(data, LOAD_SEND_RATES.length, durationMilliseconds(WS_READY_DELAY));
	const sequence = execution.scenario.iterationInTest;
	const roomIds = throughputRoomIds();
	const roomId = roomIds[sequence % roomIds.length];
	const user = roomUserForVu(data.users, roomId, throughputSenderForSequence(sequence, roomIds.length));
	recordLoadSend(postNewMessage(user, data.runId, 'load-throughput', sequence), stage);
}

/** 전송 처리량의 수신자. 측정 구간 내내 붙어 전달 지연을 단계별로 남긴다. */
export function loadSubscriber(data) {
	const subscription = subscriptionFromScenario(execution.scenario.name, data.users);
	const connection = createWebSocket(subscription.user, 'load');
	connection.socket.addEventListener('message', (event) => {
		const message = parseWebSocketEvent(event.data);
		websocketEvents.add(1);
		const expected = isMessageCreatedEvent(message, subscription.user.roomId);
		websocketExpectedEvent.add(expected);
		if (!expected) {
			return;
		}
		recordDeliveryFromCreatedAt(message.message.createdAt);
		const stage = currentStage(data, LOAD_SEND_RATES.length, durationMilliseconds(WS_READY_DELAY));
		loadStageDeliveryMs.add(Date.now() - Date.parse(message.message.createdAt), { stage });
	});
	setTimeout(() => {
		recordMissingWebSocketOpen(connection);
		websocketSessionHealthy.add(isHealthyWebSocket(connection));
		closeWebSocket(connection);
	}, loadSubscriberHoldMilliseconds());
}

/** 방 전체를 합친 초당 전송 건수를 계단식으로 올린다. 수신자는 방마다 붙어 있다. */
function loadThroughputOptions() {
	const scenarios = {};
	const roomIds = throughputRoomIds();
	for (let roomIndex = 0; roomIndex < roomIds.length; roomIndex++) {
		const roomId = roomIds[roomIndex];
		const participants = usersForRoom(FIXTURE_USERS, roomId).slice(0, LOAD_SUBSCRIBERS_PER_ROOM);
		for (let userIndex = 0; userIndex < participants.length; userIndex++) {
			scenarios[subscriberScenarioName(roomId, userIndex)] = {
				executor: 'per-vu-iterations',
				exec: 'loadSubscriber',
				vus: 1,
				iterations: 1,
				maxDuration: durationForMilliseconds(loadSubscriberHoldMilliseconds() + 15000),
			};
		}
	}
	scenarios.load_throughput_send = {
		executor: 'ramping-arrival-rate',
		exec: 'loadThroughputSend',
		startTime: WS_READY_DELAY,
		startRate: 0,
		timeUnit: '1s',
		preAllocatedVUs: requiredArrivalVus(maxOf(LOAD_SEND_RATES), HTTP_P99_MS),
		maxVUs: requiredArrivalVus(maxOf(LOAD_SEND_RATES), HTTP_P99_MS) * 4,
		stages: loadCountStages(LOAD_SEND_RATES),
		gracefulStop: '15s',
	};
	return { thresholds: loadThresholds(LOAD_SEND_RATES.length), scenarios };
}

function loadSubscriberHoldMilliseconds(stepCount = LOAD_SEND_RATES.length) {
	return durationMilliseconds(WS_READY_DELAY)
		+ durationMilliseconds(LOAD_WARMUP_DURATION)
		+ durationMilliseconds(LOAD_STEP_DURATION) * stepCount
		+ 30000
		+ durationMilliseconds(WS_GRACE_DURATION);
}

/** 전송 처리량이 쓰는 방. fixture 방이 더 많아도 고정 수만 쓴다. */
function throughputRoomIds() {
	return PROFILE_ROOM_IDS.slice(0, LOAD_THROUGHPUT_ROOMS);
}

/** 방 순환 횟수마다 발신자만 한 칸 이동해 각 방의 계정을 고르게 쓴다. */
export function throughputSenderForSequence(sequence, roomCount) {
	return Math.floor(sequence / roomCount) + 1;
}

/** fixture가 작으면 처리량 축 자체가 바뀌므로 시작 전에 거부한다. */
export function validateThroughputFixture() {
	if (PROFILE_ROOM_IDS.length < LOAD_THROUGHPUT_ROOMS) {
		throw new Error(
			`load-throughput needs at least ${LOAD_THROUGHPUT_ROOMS} rooms in the fixture but the profile has ${PROFILE_ROOM_IDS.length}`,
		);
	}
	for (const roomId of throughputRoomIds()) {
		const users = usersForRoom(FIXTURE_USERS, roomId);
		if (users.length < LOAD_SUBSCRIBERS_PER_ROOM) {
			throw new Error(
				`load-throughput needs at least ${LOAD_SUBSCRIBERS_PER_ROOM} users in room ${roomId} but the fixture has ${users.length}`,
			);
		}
	}
}

validateCommonPrerequisites();
validateThroughputFixture();

export const options = loadThroughputOptions();
