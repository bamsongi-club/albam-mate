// Redis 복구. 실행 중 Redis 를 내렸다 올려 전송이 되살아나는지 본다.

import execution from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

import {
	FIXTURE_USERS,
	PRIMARY_ROOM_ID,
	ROOM_PARTICIPANT_COUNT,
	clientMessageId,
	immediateStopGate,
	messageContent,
	normalHttpThresholds,
	postMessage,
	readDuration,
	readPositiveInteger,
	readPositiveNumber,
	roomParticipants,
	sendExpectedStatus,
	validateCommonPrerequisites,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

const REDIS_RECOVERY_DURATION = readDuration('K6_REDIS_RECOVERY_DURATION', '5m');

const REDIS_PROBE_INTERVAL_SECONDS = readPositiveNumber('K6_REDIS_PROBE_INTERVAL_SECONDS', 3);

const REDIS_MIN_UNAVAILABLE = readPositiveInteger('K6_REDIS_MIN_UNAVAILABLE', 1);

const REDIS_MIN_RECOVERED = readPositiveInteger('K6_REDIS_MIN_RECOVERED', 1);

const sendRedisUnavailable = new Counter('chat_send_redis_unavailable');

const sendRedisRecovered = new Counter('chat_send_redis_recovered');

let pendingRedisRetry = null;


/**
 * Redis stop/start는 runner에서 통제한다. 이 mode는 outage 중 503, 복구 뒤 동일
 * clientMessageId의 201을 관찰해 fail-closed와 재시도를 분리해 검증한다.
 */
export function redisRecovery(data) {
	const user = pendingRedisRetry === null
		? roomParticipants(data.users, PRIMARY_ROOM_ID)[execution.scenario.iterationInTest % ROOM_PARTICIPANT_COUNT]
		: pendingRedisRetry.user;
	const retry = pendingRedisRetry;
	const clientId = retry === null
		? clientMessageId(data.runId, 'redis-recovery', execution.vu.idInTest, execution.scenario.iterationInTest)
		: retry.clientMessageId;
	const content = retry === null
		? messageContent(data.runId, 'redis-recovery', execution.vu.idInTest, execution.scenario.iterationInTest)
		: retry.content;
	const result = postMessage(user, clientId, content);
	const expected = result.response.status === 201 || result.response.status === 503;
	sendExpectedStatus.add(expected);
	if (result.response.status === 503) {
		sendRedisUnavailable.add(1);
		pendingRedisRetry = { user, clientMessageId: clientId, content };
	} else if (result.response.status === 201) {
		if (retry !== null) {
			sendRedisRecovered.add(1);
		}
		pendingRedisRetry = null;
	}
	check(result.response, {
		'chat Redis recovery probe returns only persisted 201 or fail-closed 503': () => expected,
	});
	sleep(REDIS_PROBE_INTERVAL_SECONDS);
}

validateCommonPrerequisites();
// 기본 방에 참가자가 모자라면 시작 전에 멈춘다.
roomParticipants(FIXTURE_USERS, PRIMARY_ROOM_ID);

export const options = {
	thresholds: normalHttpThresholds({
		chat_send_expected_status: [immediateStopGate('rate==1')],
		chat_send_redis_unavailable: [`count>=${REDIS_MIN_UNAVAILABLE}`],
		chat_send_redis_recovered: [`count>=${REDIS_MIN_RECOVERED}`],
	}),
	scenarios: {
		redis_recovery: {
			executor: 'constant-vus',
			exec: 'redisRecovery',
			vus: 1,
			duration: REDIS_RECOVERY_DURATION,
			gracefulStop: '15s',
		},
	},
};
