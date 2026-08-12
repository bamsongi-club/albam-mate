// 팬아웃. 전송률을 고정하고 방 하나의 구독자 수만 계단식으로 올린다.

import execution from 'k6/execution';

import {
	HTTP_P99_MS,
	LOAD_STEP_DURATION,
	LOAD_WARMUP_DURATION,
	PROFILE_ROOM_IDS,
	ROOM_RATE_LIMIT_PER_SECOND,
	WS_READY_DELAY,
	currentStage,
	durationForMilliseconds,
	durationMilliseconds,
	holdLoadSubscriber,
	loadCountStages,
	loadThresholds,
	postNewMessage,
	readPositiveInteger,
	readRateSteps,
	recordLoadSend,
	requiredArrivalVus,
	roomUserForVu,
	validateCommonPrerequisites,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

// 방 하나에 동시에 붙는 구독자 수. 전송률을 고정한 채 이 값만 올려 팬아웃 비용을 본다.
const LOAD_FANOUT_SUBSCRIBER_STEPS = readRateSteps('K6_LOAD_FANOUT_SUBSCRIBER_STEPS', '2,4,8,16,24');

// 팬아웃 측정 중 유지할 초당 전송 건수. 방 제한 3건/초 아래로 고정한다.
const LOAD_FANOUT_SEND_RATE = readPositiveInteger('K6_LOAD_FANOUT_SEND_RATE', 2);

/**
 * 팬아웃 수신자. 방 하나에 붙는 구독자 수를 계단식으로 올린다. 전송률은 고정이므로
 * 단계마다 달라지는 전달 지연은 구독자 수 때문이라고 읽을 수 있다.
 */
export function loadFanoutSubscriber(data) {
	const roomId = PROFILE_ROOM_IDS[0];
	const user = roomUserForVu(data.users, roomId, execution.vu.idInTest);
	holdLoadSubscriber(
		user,
		roomId,
		currentStage(data, LOAD_FANOUT_SUBSCRIBER_STEPS.length, 0),
		() => currentStage(data, LOAD_FANOUT_SUBSCRIBER_STEPS.length, 0),
		'load-fanout',
	);
}

/** 팬아웃 측정용 발신자. 구독자 수만 변수로 남기려고 전송률을 고정한다. */
export function loadFanoutSend(data) {
	const stage = currentStage(
		data,
		LOAD_FANOUT_SUBSCRIBER_STEPS.length,
		durationMilliseconds(WS_READY_DELAY),
	);
	const roomId = PROFILE_ROOM_IDS[0];
	const sequence = execution.scenario.iterationInTest;
	const user = roomUserForVu(data.users, roomId, sequence + 1);
	recordLoadSend(postNewMessage(user, data.runId, 'load-fanout', sequence), stage);
}

/** 팬아웃. 전송률을 고정하고 방 하나의 구독자 수만 계단식으로 올린다. */
function loadFanoutOptions() {
	const stepCount = LOAD_FANOUT_SUBSCRIBER_STEPS.length;
	const senderVus = requiredArrivalVus(LOAD_FANOUT_SEND_RATE, HTTP_P99_MS);
	return {
		thresholds: loadThresholds(stepCount, true),
		scenarios: {
			load_fanout_subscribers: {
				executor: 'ramping-vus',
				exec: 'loadFanoutSubscriber',
				startVUs: 0,
				stages: loadCountStages(LOAD_FANOUT_SUBSCRIBER_STEPS),
				gracefulRampDown: '15s',
				gracefulStop: '30s',
			},
			load_fanout_send: {
				executor: 'constant-arrival-rate',
				exec: 'loadFanoutSend',
				startTime: WS_READY_DELAY,
				rate: LOAD_FANOUT_SEND_RATE,
				timeUnit: '1s',
				duration: durationForMilliseconds(
					durationMilliseconds(LOAD_WARMUP_DURATION)
						+ durationMilliseconds(LOAD_STEP_DURATION) * stepCount,
				),
				preAllocatedVUs: senderVus,
				maxVUs: senderVus * 4,
				gracefulStop: '15s',
			},
		},
	};
}

/**
 * 팬아웃 부하는 전송률을 고정해 구독자 수만 변수로 남긴다. 전송률이 방 제한을 넘으면
 * 429가 섞여 구독자 수 때문에 느려진 것인지 구분할 수 없다.
 */
function validateLoadFanoutProfile() {
	if (LOAD_FANOUT_SEND_RATE >= ROOM_RATE_LIMIT_PER_SECOND) {
		throw new Error(
			`K6_LOAD_FANOUT_SEND_RATE must stay under the room limit of ${ROOM_RATE_LIMIT_PER_SECOND} per second`,
		);
	}
}

validateCommonPrerequisites();
validateLoadFanoutProfile();

export const options = loadFanoutOptions();
