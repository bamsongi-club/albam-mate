// 이력 조회 계약. 커서 페이징이 정합한지 확인한다.

import http from 'k6/http';
import execution from 'k6/execution';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

import {
	BASE_URL,
	HISTORY_PAGE_SIZE,
	historyExpectedStatus,
	immediateStopGate,
	installSession,
	normalHttpThresholds,
	parseApiPayload,
	perVuOptions,
	profileUserForVu,
	readIterations,
	readNonNegativeInteger,
	readPositiveInteger,
	readVus,
	recordHttpResponse,
	validateCommonPrerequisites,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

const HISTORY_MAX_PAGES = readPositiveInteger('K6_HISTORY_MAX_PAGES', 10);

const HISTORY_MIN_MESSAGES = readNonNegativeInteger('K6_HISTORY_MIN_MESSAGES', 101);

const historyPrerequisites = new Rate('chat_history_prerequisites');

export function history(data) {
	const user = profileUserForVu(data.users);
	let beforeMessageId = null;
	let pageCount = 0;
	let messageCount = 0;
	let previousPageOldestMessageId = null;
	const seenMessageIds = {};
	while (pageCount < HISTORY_MAX_PAGES) {
		const query = beforeMessageId === null
			? `?size=${HISTORY_PAGE_SIZE}`
			: `?size=${HISTORY_PAGE_SIZE}&beforeMessageId=${beforeMessageId}`;
		const jar = installSession(user);
		const response = http.get(`${BASE_URL}/api/rooms/${user.roomId}/chat/messages${query}`, {
			jar,
			tags: { name: 'chat_history', mode: execution.scenario.name, room_id: String(user.roomId) },
		});
		recordHttpResponse(response, 'history');
		const payload = parseApiPayload(response);
		const expected = response.status === 200
			&& isValidHistoryPage(payload, user.roomId, previousPageOldestMessageId, seenMessageIds);
		historyExpectedStatus.add(expected);
		check(response, {
			'chat history returns an in-room cursor page of at most 100 messages': () => expected,
		});
		if (!expected) {
			historyPrerequisites.add(false);
			return;
		}
		const messages = payload.data.messages;
		pageCount++;
		messageCount += messages.length;
		if (messages.length > 0) {
			previousPageOldestMessageId = messages[messages.length - 1].messageId;
		}
		if (!payload.data.hasNext) {
			const prerequisitesMet = messageCount >= HISTORY_MIN_MESSAGES
				&& pageCount >= minimumHistoryPages();
			historyPrerequisites.add(prerequisitesMet);
			check({ prerequisitesMet }, {
				'chat history fixture has the configured minimum messages and cursor pages':
					(value) => value.prerequisitesMet,
			});
			return;
		}
		beforeMessageId = payload.data.nextBeforeMessageId;
	}
	historyExpectedStatus.add(false);
	historyPrerequisites.add(false);
	check({ completed: false }, {
		'chat history completes before the configured page cap': (value) => value.completed,
	});
}

function historyThresholds() {
	return {
		...normalHttpThresholds(),
		chat_history_expected_status: [immediateStopGate('rate==1')],
		chat_history_prerequisites: [immediateStopGate('rate==1')],
	};
}

function isValidHistoryPage(payload, expectedRoomId, previousPageOldestMessageId, seenMessageIds) {
	if (!payload || !payload.data || !Array.isArray(payload.data.messages)
		|| payload.data.messages.length > HISTORY_PAGE_SIZE
		|| typeof payload.data.hasNext !== 'boolean') {
		return false;
	}
	const messages = payload.data.messages;
	let previousMessageId = previousPageOldestMessageId;
	for (let index = 0; index < messages.length; index++) {
		const message = messages[index];
		const messageId = message && message.messageId;
		if (!message || !Number.isInteger(messageId) || messageId < 1
			|| !Number.isInteger(message.roomId) || message.roomId !== expectedRoomId
			|| (previousMessageId !== null && messageId >= previousMessageId)
			|| seenMessageIds[messageId]) {
			return false;
		}
		seenMessageIds[messageId] = true;
		previousMessageId = messageId;
	}
	if (!payload.data.hasNext) {
		return payload.data.nextBeforeMessageId === null || payload.data.nextBeforeMessageId === undefined;
	}
	return messages.length > 0
		&& payload.data.nextBeforeMessageId === messages[messages.length - 1].messageId;
}

function minimumHistoryPages() {
	return Math.max(1, Math.ceil(HISTORY_MIN_MESSAGES / HISTORY_PAGE_SIZE));
}

const VUS = readVus();
const ITERATIONS = readIterations();

validateCommonPrerequisites();
if (HISTORY_MIN_MESSAGES > HISTORY_MAX_PAGES * HISTORY_PAGE_SIZE) {
	throw new Error('K6_HISTORY_MIN_MESSAGES exceeds the messages reachable within K6_HISTORY_MAX_PAGES');
}

export const options = perVuOptions('history', 'history', VUS, ITERATIONS, '2m', historyThresholds());
