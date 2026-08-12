// 전송 제한 계약. K6_CHAT_CASE 로 사용자 제한과 방 제한을 고른다.

import execution from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

import {
	sendRateLimited,
	FIXTURE_USERS,
	PRIMARY_ROOM_ID,
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

// The dedicated room-limit proof needs 35 attempts: seven distinct users send
// five messages each.  The first thirty exercise the room bucket; the last
// five must be room-throttled without hitting a user bucket first.  This is
// deliberately separate from the six-member normal hot-room geometry.
const ROOM_RATE_LIMIT_PARTICIPANT_COUNT = 7;

const RATE_LIMIT_ATTEMPTS = readPositiveInteger('K6_RATE_LIMIT_ATTEMPTS', 6);

const ROOM_RATE_LIMIT_ATTEMPTS = readFixedPositiveInteger(
	'K6_ROOM_RATE_LIMIT_ATTEMPTS',
	5,
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
	const expectedCreated = Math.min(RATE_LIMIT_ATTEMPTS, 5);
	const expectedThrottled = Math.max(0, RATE_LIMIT_ATTEMPTS - 5);
	const expected = created === expectedCreated && throttled === expectedThrottled && unexpected === 0;
	sendExpectedStatus.add(expected);
	check({ expected }, {
		'chat user rate limit has the expected 201/429 split': (value) => value.expected,
	});
}

export function rateLimitRoom(data) {
	// 쿨다운은 VU마다 첫 iteration에서만 기다린다. 매 iteration마다 자면 35건이
	// 55초에 흩뿌려져 방 버킷(3/s)이 차지 않아 30건 통과·5건 차단이 나오지 않는다.
	if (execution.vu.iterationInScenario === 0) {
		waitForRateLimitCooldown();
	}
	const users = rateLimitRoomUsers(data.users);
	// Each VU owns one sender so that five attempts per VU cannot accidentally
	// consume another sender's five-message user quota and hide the room limit.
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
		rateLimitThresholds(Math.min(RATE_LIMIT_ATTEMPTS, 5), Math.max(0, RATE_LIMIT_ATTEMPTS - 5)),
	)
	: perVuOptions(
		'rate_limit_room',
		'rateLimitRoom',
		ROOM_RATE_LIMIT_PARTICIPANT_COUNT,
		ROOM_RATE_LIMIT_ATTEMPTS,
		'1m',
		rateLimitThresholds(30, 5),
	);
