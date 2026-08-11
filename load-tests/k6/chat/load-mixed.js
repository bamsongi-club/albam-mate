// 혼합. 전송·조회·연결을 같은 배수로 함께 올린다.

import execution from 'k6/execution';

import {
	HTTP_P99_MS,
	PROFILE_ROOM_IDS,
	WS_READY_DELAY,
	currentStage,
	durationMilliseconds,
	holdLoadSubscriber,
	loadCountStages,
	loadThresholds,
	maxOf,
	postNewMessage,
	profileUserForVu,
	readHistoryPage,
	readPositiveInteger,
	readRateSteps,
	recordLoadSend,
	requiredArrivalVus,
	roomUserForVu,
	validateCommonPrerequisites,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

// 혼합 부하의 배수. 1배는 전송 1건/초 + 조회 2건/초 + 연결 5개다.
const LOAD_MIXED_SCALES = readRateSteps('K6_LOAD_MIXED_SCALES', '1,2,3,4');

const LOAD_MIXED_SEND_RATE = readPositiveInteger('K6_LOAD_MIXED_SEND_RATE', 1);

const LOAD_MIXED_HISTORY_RATE = readPositiveInteger('K6_LOAD_MIXED_HISTORY_RATE', 2);

const LOAD_MIXED_CONNECTIONS = readPositiveInteger('K6_LOAD_MIXED_CONNECTIONS', 5);

/** 혼합 부하의 전송. 조회·연결과 같은 배수로 함께 올라간다. */
export function loadMixedSend(data) {
	const stage = currentStage(data, LOAD_MIXED_SCALES.length, durationMilliseconds(WS_READY_DELAY));
	const sequence = execution.scenario.iterationInTest;
	const roomId = PROFILE_ROOM_IDS[sequence % PROFILE_ROOM_IDS.length];
	const user = roomUserForVu(data.users, roomId, sequence + 1);
	recordLoadSend(postNewMessage(user, data.runId, 'load-mixed', sequence), stage);
}

/** 혼합 부하의 이력 조회. */
export function loadMixedHistory(data) {
	const stage = currentStage(data, LOAD_MIXED_SCALES.length, durationMilliseconds(WS_READY_DELAY));
	readHistoryPage(profileUserForVu(data.users), stage);
}

/** 혼합 부하의 구독. 전송·조회가 도는 동안 연결을 유지한다. */
export function loadMixedConnection(data) {
	const stage = currentStage(data, LOAD_MIXED_SCALES.length, 0);
	const roomIndex = (execution.vu.idInTest - 1) % PROFILE_ROOM_IDS.length;
	const roomId = PROFILE_ROOM_IDS[roomIndex];
	const user = roomUserForVu(data.users, roomId, execution.vu.idInTest);
	holdLoadSubscriber(user, roomId, stage, 'load-mixed');
}

/** 혼합. 전송·조회·연결을 같은 배수로 함께 올려 실사용에 가까운 형태로 민다. */
function loadMixedOptions() {
	const stepCount = LOAD_MIXED_SCALES.length;
	const sendRates = LOAD_MIXED_SCALES.map((scale) => scale * LOAD_MIXED_SEND_RATE);
	const historyRates = LOAD_MIXED_SCALES.map((scale) => scale * LOAD_MIXED_HISTORY_RATE);
	const sendVus = requiredArrivalVus(maxOf(sendRates), HTTP_P99_MS);
	const historyVus = requiredArrivalVus(maxOf(historyRates), HTTP_P99_MS);
	return {
		thresholds: loadThresholds(stepCount),
		scenarios: {
			load_mixed_connections: {
				executor: 'ramping-vus',
				exec: 'loadMixedConnection',
				startVUs: 0,
				stages: loadCountStages(LOAD_MIXED_SCALES.map((scale) => scale * LOAD_MIXED_CONNECTIONS)),
				gracefulRampDown: '15s',
				gracefulStop: '30s',
			},
			load_mixed_send: {
				executor: 'ramping-arrival-rate',
				exec: 'loadMixedSend',
				startTime: WS_READY_DELAY,
				startRate: 0,
				timeUnit: '1s',
				preAllocatedVUs: sendVus,
				maxVUs: sendVus * 4,
				stages: loadCountStages(sendRates),
				gracefulStop: '15s',
			},
			load_mixed_history: {
				executor: 'ramping-arrival-rate',
				exec: 'loadMixedHistory',
				startTime: WS_READY_DELAY,
				startRate: 0,
				timeUnit: '1s',
				preAllocatedVUs: historyVus,
				maxVUs: historyVus * 4,
				stages: loadCountStages(historyRates),
				gracefulStop: '15s',
			},
		},
	};
}

validateCommonPrerequisites();

export const options = loadMixedOptions();
