// 전송 제한 계약. K6_CHAT_CASE 로 사용자 제한과 방 제한을 고른다.

import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

import {
	FIXTURE_USERS,
	PRIMARY_ROOM_ID,
	RATE_LIMIT_WINDOW_SECONDS,
	ROOM_RATE_LIMIT_PER_WINDOW,
	USER_RATE_LIMIT_PER_WINDOW,
	defaultThresholds,
	immediateStopGate,
	perVuOptions,
	postNewMessage,
	postNewMessagesBatch,
	readEnum,
	readFixedPositiveInteger,
	readPositiveNumber,
	readPositiveInteger,
	sendExpectedStatus,
	usersForRoom,
	validateCommonPrerequisites,
	validateDistinctPrincipals,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

// The dedicated room-limit proof sends 101 requests in one HTTP batch from three
// distinct users. The first hundred exercise the room bucket, and the 101st is
// room-throttled while every sender remains below the 50-message user bucket.
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

const ROOM_RATE_LIMIT_MESSAGES = ROOM_RATE_LIMIT_ATTEMPTS * ROOM_RATE_LIMIT_PARTICIPANT_COUNT - 1;

const RATE_LIMIT_WINDOW_MILLISECONDS = RATE_LIMIT_WINDOW_SECONDS * 1_000;

const RATE_LIMIT_COOLDOWN_SECONDS = readPositiveNumber(
	'K6_RATE_LIMIT_COOLDOWN_SECONDS',
	RATE_LIMIT_WINDOW_SECONDS + 1,
);

const RATE_LIMIT_BOUNDARY_TARGET_SECONDS = RATE_LIMIT_WINDOW_SECONDS - 1;

const RATE_LIMIT_RECOVERY_WAIT_SECONDS = RATE_LIMIT_WINDOW_SECONDS - RATE_LIMIT_BOUNDARY_TARGET_SECONDS + 1;

const RATE_LIMIT_RECOVERY_TARGET_MILLISECONDS = (
	RATE_LIMIT_BOUNDARY_TARGET_SECONDS + RATE_LIMIT_RECOVERY_WAIT_SECONDS
) * 1_000;

const RATE_LIMIT_RECOVERY_LATEST_MILLISECONDS = (RATE_LIMIT_WINDOW_SECONDS + 2) * 1_000;

if (RATE_LIMIT_COOLDOWN_SECONDS <= RATE_LIMIT_WINDOW_SECONDS) {
	throw new Error(
		`K6_RATE_LIMIT_COOLDOWN_SECONDS must be greater than ${RATE_LIMIT_WINDOW_SECONDS} seconds`,
	);
}

const rateLimitWindowValid = new Rate('chat_rate_limit_window_valid');

const rateLimitWindowElapsedMs = new Trend('chat_rate_limit_window_elapsed_ms', true);

const rateLimitWindowStillLimited = new Rate('chat_rate_limit_window_still_limited');

const rateLimitWindowRecovered = new Rate('chat_rate_limit_window_recovered');

const rateLimitWindowRecoveryTiming = new Rate('chat_rate_limit_window_recovery_timing');

const rateLimitWindowRecoveryElapsedMs = new Trend('chat_rate_limit_window_recovery_elapsed_ms', true);

export function rateLimitUser(data) {
	waitForRateLimitCooldown();
	const user = data.users[0];
	const messages = Array.from({ length: RATE_LIMIT_ATTEMPTS }, (_, sequence) => ({ user, sequence }));
	const { results, elapsedMs, startedAt } = sendRateLimitBatch(messages, data.runId, 'rate-limit-user');
	const { created, throttled, unexpected } = summarizeRateLimitResults(results);
	const expectedCreated = Math.min(RATE_LIMIT_ATTEMPTS, USER_RATE_LIMIT_PER_WINDOW);
	const expectedThrottled = Math.max(0, RATE_LIMIT_ATTEMPTS - USER_RATE_LIMIT_PER_WINDOW);
	const initialExpected = created === expectedCreated && throttled === expectedThrottled && unexpected === 0;
	const stillLimited = verifyRateLimitWindowStillLimited(
		user,
		data.runId,
		'rate-limit-user-boundary',
		RATE_LIMIT_ATTEMPTS,
		elapsedMs,
	);
	const recovered = verifyRateLimitWindowRecovery(
		user,
		data.runId,
		'rate-limit-user-recovery',
		RATE_LIMIT_ATTEMPTS + 1,
		startedAt,
	);
	const expected = initialExpected && stillLimited && recovered;
	sendExpectedStatus.add(expected);
	check({ expected }, {
		'chat user rate limit has the expected 201/429 split': (value) => value.expected,
	});
}

export function rateLimitRoom(data) {
	waitForRateLimitCooldown();
	const users = rateLimitRoomUsers(data.users);
	// 34·34·33건을 한 batch로 보내 정확히 101회만 요청하고, 각 sender는
	// 사용자 50건 window에 닿지 않는다.
	const messages = Array.from({ length: ROOM_RATE_LIMIT_MESSAGES }, (_, sequence) => ({
		user: users[sequence % users.length],
		sequence,
	}));
	const { results, elapsedMs, startedAt } = sendRateLimitBatch(messages, data.runId, 'rate-limit-room');
	const { created, throttled, unexpected } = summarizeRateLimitResults(results);
	const expectedCreated = Math.min(ROOM_RATE_LIMIT_MESSAGES, ROOM_RATE_LIMIT_PER_WINDOW);
	const expectedThrottled = Math.max(0, ROOM_RATE_LIMIT_MESSAGES - ROOM_RATE_LIMIT_PER_WINDOW);
	const initialExpected = created === expectedCreated && throttled === expectedThrottled && unexpected === 0;
	const stillLimited = verifyRateLimitWindowStillLimited(
		users[0],
		data.runId,
		'rate-limit-room-boundary',
		ROOM_RATE_LIMIT_MESSAGES,
		elapsedMs,
	);
	const recovered = verifyRateLimitWindowRecovery(
		users[0],
		data.runId,
		'rate-limit-room-recovery',
		ROOM_RATE_LIMIT_MESSAGES + 1,
		startedAt,
	);
	const expected = initialExpected && stillLimited && recovered;
	sendExpectedStatus.add(expected);
	check({ expected }, {
		'chat room rate limit has the expected 201/429 split': (value) => value.expected,
	});
}

function rateLimitThresholds(expectedCreated, expectedRateLimited) {
	return {
		...defaultThresholds(),
		chat_send_created: [`count==${expectedCreated + 1}`],
		chat_send_rate_limited: [`count==${expectedRateLimited + 1}`],
		chat_rate_limit_window_valid: [immediateStopGate('rate==1')],
		chat_rate_limit_window_elapsed_ms: [
			immediateStopGate(`max<${RATE_LIMIT_WINDOW_MILLISECONDS}`),
		],
		chat_rate_limit_window_still_limited: [immediateStopGate('rate==1')],
		chat_rate_limit_window_recovered: [immediateStopGate('rate==1')],
		chat_rate_limit_window_recovery_timing: [immediateStopGate('rate==1')],
		chat_rate_limit_window_recovery_elapsed_ms: [
			immediateStopGate(`max<${RATE_LIMIT_RECOVERY_LATEST_MILLISECONDS}`),
		],
		chat_send_expected_status: [immediateStopGate('rate==1')],
	};
}

function rateLimitOptions(name, exec, batchSize, thresholds) {
	const options = perVuOptions(name, exec, 1, 1, '1m', thresholds);
	return { ...options, batch: batchSize, batchPerHost: batchSize };
}

function sendRateLimitBatch(messages, runId, purpose) {
	const startedAt = Date.now();
	const results = postNewMessagesBatch(messages, runId, purpose);
	const elapsedMs = Date.now() - startedAt;
	const withinWindow = elapsedMs < RATE_LIMIT_WINDOW_MILLISECONDS;
	rateLimitWindowElapsedMs.add(elapsedMs);
	rateLimitWindowValid.add(withinWindow);
	check({ withinWindow }, {
		'chat rate limit requests complete inside one fixed window': (value) => value.withinWindow,
	});
	return { results, elapsedMs, startedAt };
}

function verifyRateLimitWindowStillLimited(user, runId, purpose, sequence, elapsedMs) {
	const remainingSeconds = RATE_LIMIT_BOUNDARY_TARGET_SECONDS - elapsedMs / 1_000;
	if (remainingSeconds > 0) {
		sleep(remainingSeconds);
	}
	const result = postNewMessage(user, runId, purpose, sequence);
	const stillLimited = result.response.status === 429 && hasRetryAfter(result.response);
	rateLimitWindowStillLimited.add(stillLimited);
	check({ stillLimited }, {
		'chat rate limit remains active before the fixed window expires': (value) => value.stillLimited,
	});
	return stillLimited;
}

function verifyRateLimitWindowRecovery(user, runId, purpose, sequence, startedAt) {
	const elapsedBeforeRecoveryMs = Date.now() - startedAt;
	const remainingSeconds = (RATE_LIMIT_RECOVERY_TARGET_MILLISECONDS - elapsedBeforeRecoveryMs) / 1_000;
	if (remainingSeconds > 0) {
		sleep(remainingSeconds);
	}
	const dispatchElapsedMs = Date.now() - startedAt;
	const result = postNewMessage(user, runId, purpose, sequence);
	const completionElapsedMs = Date.now() - startedAt;
	const recoveryTimingValid = dispatchElapsedMs >= RATE_LIMIT_WINDOW_MILLISECONDS
		&& dispatchElapsedMs < RATE_LIMIT_RECOVERY_LATEST_MILLISECONDS
		&& completionElapsedMs >= RATE_LIMIT_WINDOW_MILLISECONDS
		&& completionElapsedMs < RATE_LIMIT_RECOVERY_LATEST_MILLISECONDS;
	rateLimitWindowRecoveryElapsedMs.add(completionElapsedMs);
	rateLimitWindowRecoveryTiming.add(recoveryTimingValid);
	const recovered = recoveryTimingValid && result.response.status === 201;
	check({ recoveryTimingValid }, {
		'chat rate limit recovery is dispatched and completed near the fixed-window boundary': (value) => value.recoveryTimingValid,
	});
	rateLimitWindowRecovered.add(recovered);
	check({ recovered }, {
		'chat rate limit recovers after the fixed window': (value) => value.recovered,
	});
	return recovered;
}

function summarizeRateLimitResults(results) {
	return results.reduce((counts, result) => {
		if (result.response.status === 201) {
			counts.created++;
		} else if (result.response.status === 429 && hasRetryAfter(result.response)) {
			counts.throttled++;
		} else {
			counts.unexpected++;
		}
		return counts;
	}, { created: 0, throttled: 0, unexpected: 0 });
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

function validateRateLimitFixtureIsolation() {
	const roomId = CASE === 'room' ? PRIMARY_ROOM_ID : FIXTURE_USERS[0].roomId;
	const users = usersForRoom(FIXTURE_USERS, roomId);
	const runIds = users.map((user) => rateLimitFixtureRunId(user.email));
	if (new Set(runIds).size !== 1) {
		throw new Error('rate-limit-contract requires one run-scoped fixture room without mixed run ids');
	}
}

function rateLimitFixtureRunId(email) {
	const match = /^k6\.([a-z0-9][a-z0-9._-]{0,63})\.chat\.r\d+\.u\d+@example\.com$/.exec(email || '');
	if (!match) {
		throw new Error('rate-limit-contract requires credential fixture emails generated by rooms.sql');
	}
	return match[1];
}

const CASE = readEnum('K6_CHAT_CASE', 'user', ['user', 'room']);

validateCommonPrerequisites();
validateRateLimitFixtureIsolation();
if (CASE === 'room') {
	validateRateLimitRoomProfile();
}

export const options = CASE === 'user'
	? rateLimitOptions(
		'rate_limit_user',
		'rateLimitUser',
		RATE_LIMIT_ATTEMPTS,
		rateLimitThresholds(
			Math.min(RATE_LIMIT_ATTEMPTS, USER_RATE_LIMIT_PER_WINDOW),
			Math.max(0, RATE_LIMIT_ATTEMPTS - USER_RATE_LIMIT_PER_WINDOW),
		),
	)
	: rateLimitOptions(
		'rate_limit_room',
		'rateLimitRoom',
		ROOM_RATE_LIMIT_MESSAGES,
		rateLimitThresholds(
			Math.min(ROOM_RATE_LIMIT_MESSAGES, ROOM_RATE_LIMIT_PER_WINDOW),
			Math.max(0, ROOM_RATE_LIMIT_MESSAGES - ROOM_RATE_LIMIT_PER_WINDOW),
		),
	);
