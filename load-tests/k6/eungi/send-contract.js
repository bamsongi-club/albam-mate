// 전송 계약. K6_CHAT_CASE 로 단건 전송과 멱등 재전송을 고른다.

import execution from 'k6/execution';
import { check } from 'k6';

import {
	clientMessageId,
	hasMessageForRoom,
	messageContent,
	messageIdOf,
	perVuOptions,
	postMessage,
	postNewMessage,
	profileUserForVu,
	readEnum,
	readIterations,
	readVus,
	sendExpectedStatus,
	successfulSendHttpThresholds,
	validateBurstMapping,
	validateCommonPrerequisites,
	validateProfileVuCount,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

export function send(data) {
	const user = profileUserForVu(data.users);
	const result = postNewMessage(user, data.runId, 'send', execution.scenario.iterationInTest);
	const expected = result.response.status === 201 && hasMessageForRoom(result.payload, user.roomId);
	sendExpectedStatus.add(expected);
	check(result.response, {
		'chat smoke send creates one message': () => expected,
	});
}

export function idempotentRetry(data) {
	const user = profileUserForVu(data.users);
	const sequence = execution.scenario.iterationInTest;
	const retryClientMessageId = clientMessageId(data.runId, 'idempotent', execution.vu.idInTest, sequence);
	const content = messageContent(data.runId, 'idempotent', execution.vu.idInTest, sequence);
	const first = postMessage(user, retryClientMessageId, content);
	const second = postMessage(user, retryClientMessageId, content);
	const expected = first.response.status === 201
		&& second.response.status === 200
		&& hasMessageForRoom(first.payload, user.roomId)
		&& hasMessageForRoom(second.payload, user.roomId)
		&& messageIdOf(first.payload) === messageIdOf(second.payload);
	sendExpectedStatus.add(expected);
	check({ expected }, {
		'chat idempotent retry returns the original message': (value) => value.expected,
	});
}

const CASE = readEnum('K6_CHAT_CASE', 'send', ['send', 'idempotent-retry']);
const VUS = readVus();
const ITERATIONS = readIterations(CASE === 'send' ? 3 : 1);

validateCommonPrerequisites();
if (CASE === 'send') {
	validateProfileVuCount(VUS, CASE);
} else {
	// 재전송은 iteration 마다 두 건을 보내므로 방 10초 할당량 계산도 두 배로 본다.
	validateBurstMapping(VUS, ITERATIONS * 2, CASE);
}

export const options = CASE === 'send'
	? perVuOptions('send', 'send', VUS, ITERATIONS, '2m', successfulSendHttpThresholds())
	: perVuOptions('idempotent_retry', 'idempotentRetry', VUS, ITERATIONS, '2m', successfulSendHttpThresholds());
