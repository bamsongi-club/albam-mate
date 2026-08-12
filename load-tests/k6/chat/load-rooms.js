// 활성 방 수. 방마다 구독자와 전송을 고정하고 방 수만 계단식으로 올린다.

import execution from 'k6/execution';

import {
	HTTP_P99_MS,
	LOAD_WARMUP_STAGE,
	PROFILE_ROOM_IDS,
	WS_READY_DELAY,
	currentStage,
	durationMilliseconds,
	holdLoadSubscriber,
	loadCountStages,
	loadThresholds,
	maxOf,
	postNewMessage,
	readPositiveInteger,
	readRateSteps,
	recordLoadSend,
	requiredArrivalVus,
	roomUserForVu,
	validateCommonPrerequisites,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

// 동시에 활성인 방 수. fixture의 방 수가 마지막 계단 이상이어야 한다.
const LOAD_ROOM_STEPS = readRateSteps('K6_LOAD_ROOM_STEPS', '1,2,4,8');

// 활성 방 하나에 붙이는 구독자 수. 방 수만 변수로 남기려고 고정한다.
const LOAD_ROOM_SUBSCRIBERS = readPositiveInteger('K6_LOAD_ROOM_SUBSCRIBERS', 3);

// 활성 방 하나가 받는 초당 전송 건수. 방 수에 곱해 전체 전송량이 정해진다.
const LOAD_ROOM_SEND_RATE = readPositiveInteger('K6_LOAD_ROOM_SEND_RATE', 1);

/**
 * 활성 방 수 수신자. 방마다 같은 수의 구독자를 붙이고 방 수를 계단식으로 올린다.
 * VU 번호를 구독자 수로 나눠 방을 정하므로 VU가 늘면 새 방이 켜진다.
 */
export function loadRoomsSubscriber(data) {
	const roomIndex = Math.floor((execution.vu.idInTest - 1) / LOAD_ROOM_SUBSCRIBERS);
	const roomId = PROFILE_ROOM_IDS[roomIndex % PROFILE_ROOM_IDS.length];
	const user = roomUserForVu(data.users, roomId, execution.vu.idInTest);
	holdLoadSubscriber(
		user,
		roomId,
		currentStage(data, LOAD_ROOM_STEPS.length, 0),
		() => currentStage(data, LOAD_ROOM_STEPS.length, 0),
		'load-rooms',
	);
}

/** 활성 방 수 발신자. 그 단계에서 켜져 있는 방에만 돌아가며 보낸다. */
export function loadRoomsSend(data) {
	const stage = currentStage(data, LOAD_ROOM_STEPS.length, durationMilliseconds(WS_READY_DELAY));
	const activeRooms = activeRoomCount(stage);
	const sequence = execution.scenario.iterationInTest;
	const roomId = PROFILE_ROOM_IDS[sequence % activeRooms];
	const user = roomUserForVu(data.users, roomId, sequence + 1);
	recordLoadSend(postNewMessage(user, data.runId, 'load-rooms', sequence), stage);
}

/** 활성 방 수. 방마다 구독자와 전송을 같은 비율로 붙이고 방 수만 올린다. */
function loadRoomsOptions() {
	const stepCount = LOAD_ROOM_STEPS.length;
	const sendRates = LOAD_ROOM_STEPS.map((rooms) => rooms * LOAD_ROOM_SEND_RATE);
	const peak = requiredArrivalVus(maxOf(sendRates), HTTP_P99_MS);
	return {
		thresholds: loadThresholds(stepCount, true),
		scenarios: {
			load_rooms_subscribers: {
				executor: 'ramping-vus',
				exec: 'loadRoomsSubscriber',
				startVUs: 0,
				stages: loadCountStages(LOAD_ROOM_STEPS.map((rooms) => rooms * LOAD_ROOM_SUBSCRIBERS)),
				gracefulRampDown: '15s',
				gracefulStop: '30s',
			},
			load_rooms_send: {
				executor: 'ramping-arrival-rate',
				exec: 'loadRoomsSend',
				startTime: WS_READY_DELAY,
				startRate: 0,
				timeUnit: '1s',
				preAllocatedVUs: peak,
				maxVUs: peak * 4,
				stages: loadCountStages(sendRates),
				gracefulStop: '15s',
			},
		},
	};
}

/** 그 단계에서 켜져 있는 방 수. fixture가 가진 방 수를 넘지 않는다. */
function activeRoomCount(stage) {
	const stageIndex = stage === LOAD_WARMUP_STAGE ? 0 : Number(stage) - 1;
	const target = Math.round(LOAD_ROOM_STEPS[stageIndex]);
	return Math.max(1, Math.min(target, PROFILE_ROOM_IDS.length));
}

/** 활성 방 수 부하는 마지막 계단만큼 방이 있어야 한다. 모자라면 계단이 겹쳐 무의미해진다. */
function validateLoadRoomsProfile() {
	const widest = Math.round(maxOf(LOAD_ROOM_STEPS));
	if (PROFILE_ROOM_IDS.length < widest) {
		throw new Error(
			`load-rooms needs at least ${widest} rooms in the fixture but the profile has ${PROFILE_ROOM_IDS.length}`,
		);
	}
}

validateCommonPrerequisites();
validateLoadRoomsProfile();

export const options = loadRoomsOptions();
