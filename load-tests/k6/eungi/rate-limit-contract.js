// 전송 제한 계약. K6_CHAT_CASE 로 사용자 제한과 방 제한을 고른다.

import execution from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

import {
	sendRateLimited,
	FIXTURE_USERS,
	PRIMARY_ROOM_ID,
	ROOM_RATE_LIMIT_PER_WINDOW,
	USER_RATE_LIMIT_PER_WINDOW,
	defaultThresholds,
	immediateStopGate,
	perVuOptions,
	postNewMessage,
	readEnum,
	readFixedPositiveInteger,
	readNonNegativeNumber,
	readPositiveInteger,
	sendExpectedStatus,
	usersForRoom,
	validateCommonPrerequisites,
	validateDistinctPrincipals,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

// The dedicated room-limit proof sends 101 requests from three distinct users.
// The first hundred exercise the room bucket, and the 101st is room-throttled
// while every sender remains below the 50-message user bucket.
const ROOM_RATE_LIMIT_PARTICIPANT_COUNT = 3;

const RATE_LIMIT_ATTEMPTS = readFixedPositiveInteger(
	'K6_RATE_LIMIT_ATTEMPTS',
	51,
	'the exact user limiter proof',
);

const ROOM_RATE_LIMIT_ATTEMPTS = readFixedPositiveInteger(
	'K6_ROOM_RATE_LIMIT_ATTEMPTS',
	34,
	'the exact room limiter proof',
);

const RATE_LIMIT_COOLDOWN_SECONDS = readNonNegativeNumber('K6_RATE_LIMIT_COOLDOWN_SECONDS', 11);


export function rateLimitUser(data) {
	waitForRateLimitCooldown();
	const user = data.users[0];
	let created = 0;
	let throttled = 0;
	let unexpected = 0;
	for (let attempt = 0; attempt < RATE_LIMIT_ATTEMPTS; attempt++) {
		const result = postNewMessage(user, data.runId, 'rate-limit-user', attempt);
		if (result.response.status === 201) {
			created++;
		} else if (result.response.status === 429 && hasRetryAfter(result.response)) {
			throttled++;
		} else {
			unexpected++;
		}
	}
	const expectedCreated = Math.min(RATE_LIMIT_ATTEMPTS, USER_RATE_LIMIT_PER_WINDOW);
	const expectedThrottled = Math.max(0, RATE_LIMIT_ATTEMPTS - USER_RATE_LIMIT_PER_WINDOW);
	const expected = created === expectedCreated && throttled === expectedThrottled && unexpected === 0;
	sendExpectedStatus.add(expected);
	check({ expected }, {
		'chat user rate limit has the expected 201/429 split': (value) => value.expected,
	});
}

export function rateLimitRoom(data) {
	// 쿨다운은 VU마다 첫 iteration에서만 기다린다. 매 iteration마다 자면 101건이
	// 흩뿌려져 방 버킷이 차지 않아 100건 통과·101번째 차단이 나오지 않는다.
	if (execution.vu.iterationInScenario === 0) {
		waitForRateLimitCooldown();
	}
	const users = rateLimitRoomUsers(data.users);
	// Three VU x 34 iterations is 102. 마지막 VU의 마지막 iteration을 건너뛰어
	// 정확히 101회를 보내고, 각 sender는 34회 이하로 user bucket에 닿지 않는다.
	if (execution.vu.idInTest === ROOM_RATE_LIMIT_PARTICIPANT_COUNT
		&& execution.vu.iterationInScenario === ROOM_RATE_LIMIT_ATTEMPTS - 1) {
		return;
	}
	const user = users[(execution.vu.idInTest - 1) % users.length];
	const result = postNewMessage(
		user,
		data.runId,
		'rate-limit-room',
		execution.scenario.iterationInTest,
	);
	const expected = result.response.status === 201
		|| (result.response.status === 429 && hasRetryAfter(result.response));
	sendExpectedStatus.add(expected);
	check(result.response, {
		'chat room rate limit returns only 201 or 429': () => expected,
	});
}

function rateLimitThresholds(expectedCreated, expectedRateLimited) {
	return {
		...defaultThresholds(),
		chat_send_created: [`count==${expectedCreated}`],
		chat_send_rate_limited: [`count==${expectedRateLimited}`],
		chat_send_expected_status: [immediateStopGate('rate==1')],
	};
}

/**
 * 기본값인 FIXTURE_USERS는 login mode에서 세션이 없다. 실행 중에는 setup이 인증한
 * data.users를 넘겨야 하고, init 검증에서만 fixture 원본을 그대로 쓴다.
 */
function rateLimitRoomUsers(users = FIXTURE_USERS) {
	const roomUsers = usersForRoom(users, PRIMARY_ROOM_ID);
	if (roomUsers.length < ROOM_RATE_LIMIT_PARTICIPANT_COUNT) {
		throw new Error(
			`rate-limit-room needs ${ROOM_RATE_LIMIT_PARTICIPANT_COUNT} distinct users in room ${PRIMARY_ROOM_ID}`,
		);
	}
	return roomUsers.slice(0, ROOM_RATE_LIMIT_PARTICIPANT_COUNT);
}

function waitForRateLimitCooldown() {
	if (RATE_LIMIT_COOLDOWN_SECONDS > 0) {
		sleep(RATE_LIMIT_COOLDOWN_SECONDS);
	}
}

function hasRetryAfter(response) {
	return response.headers['Retry-After'] !== undefined || response.headers['retry-after'] !== undefined;
}

function validateRateLimitRoomProfile() {
	const users = rateLimitRoomUsers();
	validateDistinctPrincipals(users, 'rate-limit-room');
}

const CASE = readEnum('K6_CHAT_CASE', 'user', ['user', 'room']);

validateCommonPrerequisites();
if (CASE === 'room') {
	validateRateLimitRoomProfile();
}

export const options = CASE === 'user'
	? perVuOptions(
		'rate_limit_user',
		'rateLimitUser',
		1,
		1,
		'1m',
		rateLimitThresholds(
			Math.min(RATE_LIMIT_ATTEMPTS, USER_RATE_LIMIT_PER_WINDOW),
			Math.max(0, RATE_LIMIT_ATTEMPTS - USER_RATE_LIMIT_PER_WINDOW),
		),
	)
	: perVuOptions(
		'rate_limit_room',
		'rateLimitRoom',
		ROOM_RATE_LIMIT_PARTICIPANT_COUNT,
		ROOM_RATE_LIMIT_ATTEMPTS,
		'1m',
		rateLimitThresholds(ROOM_RATE_LIMIT_PER_WINDOW, 1),
	);
