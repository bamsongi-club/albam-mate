// CHAT-03 접근 회수 계약. 제어 HTTP는 app-a, WebSocket은 app-b에 고정한다.
// 기존 부하·계약 시나리오와 분리해 참가 취소·방 취소의 관찰 가능한 동작만 확인한다.

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

import {
	BASE_URL,
	FIXTURE_USERS,
	ORIGIN,
	PROFILE_ROOM_IDS,
	clientMessageId,
	closeWebSocket,
	createWebSocket,
	durationForMilliseconds,
	hasMessageForRoom,
	immediateStopGate,
	installSession,
	jsonHeaders,
	messageContent,
	parseApiPayload,
	parseWebSocketEvent,
	performanceRouteHeader,
	perVuOptions,
	postMessage,
	readPositiveInteger,
	recordHttpResponse,
	selectedPerformanceRoute,
	usersForRoom,
	validateCommonPrerequisites,
	verifyCrossInstanceRoute,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

const CONTROL_BASE_URL = BASE_URL;

const WEBSOCKET_BASE_URL = BASE_URL;

const CONTROL_ROUTE = 'app-a';

const WEBSOCKET_ROUTE = 'app-b';

const ACCESS_INVALIDATION_TIMEOUT_MS = readPositiveInteger(
	'K6_ACCESS_INVALIDATION_TIMEOUT_MS',
	10_000,
);

const ACCESS_INVALIDATION_SETTLE_MS = readPositiveInteger(
	'K6_ACCESS_INVALIDATION_SETTLE_MS',
	1_000,
);

const routePreflightExpected = new Rate('chat_access_invalidation_route_pinned');

const historyExpected = new Rate('chat_access_invalidation_history_expected');

const actionExpected = new Rate('chat_access_invalidation_action_expected');

const websocketClosedExpected = new Rate('chat_access_invalidation_closed');

const noMessageAfterInvalidationExpected = new Rate('chat_access_invalidation_no_message');

const roomIsolationExpected = new Rate('chat_access_invalidation_room_isolation');

const participantMessageCreatedExpected = new Rate('chat_access_invalidation_participant_message_created');

const terminalMessageRejectedExpected = new Rate('chat_access_invalidation_terminal_message_rejected');

function validateAccessInvalidationTarget() {
	if (!BASE_URL.startsWith('https://') || ORIGIN !== BASE_URL) {
		throw new Error('room-access-invalidation requires HTTPS K6_BASE_URL and the same K6_ORIGIN');
	}
}

function accessInvalidationThresholds() {
	return {
		chat_access_invalidation_route_pinned: [immediateStopGate('rate==1')],
		chat_access_invalidation_history_expected: [immediateStopGate('rate==1')],
		chat_access_invalidation_action_expected: [immediateStopGate('rate==1')],
		chat_access_invalidation_closed: [immediateStopGate('rate==1')],
		chat_access_invalidation_no_message: [immediateStopGate('rate==1')],
		chat_access_invalidation_room_isolation: [immediateStopGate('rate==1')],
		chat_access_invalidation_participant_message_created: [immediateStopGate('rate==1')],
		chat_access_invalidation_terminal_message_rejected: [immediateStopGate('rate==1')],
		chat_websocket_opened: [immediateStopGate('rate==1')],
		dropped_iterations: [immediateStopGate('count==0')],
	};
}

function userForLabel(users, roomId, suffix) {
	const match = usersForRoom(users, roomId).find((user) => user.label.endsWith(suffix));
	if (!match) {
		throw new Error(`fixture user ${suffix} is missing for room ${roomId}`);
	}
	return match;
}

function latestMessageId(user) {
	const response = http.get(
		`${CONTROL_BASE_URL}/api/rooms/${user.roomId}/chat/messages?size=1`,
		{
			headers: performanceRouteHeader(CONTROL_ROUTE),
			jar: installSession(user, CONTROL_BASE_URL),
			tags: { name: 'chat_access_invalidation_history', room_id: String(user.roomId) },
		},
	);
	recordHttpResponse(response, 'access-invalidation-history');
	const payload = parseApiPayload(response);
	const messages = payload && payload.data && payload.data.messages;
	const message = Array.isArray(messages) ? messages[0] : null;
	const expected = response.status === 200
		&& message !== null
		&& Number.isInteger(message.messageId)
		&& message.messageId > 0
		&& message.roomId === user.roomId
		&& selectedPerformanceRoute(response) === CONTROL_ROUTE;
	historyExpected.add(expected);
	check({ expected }, {
		'chat access invalidation gets the latest in-room message on the control route':
			(value) => value.expected,
	});
	return expected ? message.messageId : 0;
}

function cancelParticipation(user) {
	const response = http.del(
		`${CONTROL_BASE_URL}/api/rooms/${user.roomId}/participants/me`,
		null,
		{
			headers: { ...jsonHeaders(user), ...performanceRouteHeader(CONTROL_ROUTE) },
			jar: installSession(user, CONTROL_BASE_URL),
			tags: { name: 'chat_access_invalidation_participant_cancel', room_id: String(user.roomId) },
		},
	);
	recordHttpResponse(response, 'access-invalidation-participant-cancel');
	const expected = response.status === 200
		&& selectedPerformanceRoute(response) === CONTROL_ROUTE;
	actionExpected.add(expected);
	check({ expected }, {
		'chat participant cancellation succeeds on the control route': (value) => value.expected,
	});
	return expected;
}

function cancelRoom(user) {
	const response = http.del(
		`${CONTROL_BASE_URL}/api/rooms/${user.roomId}`,
		null,
		{
			headers: { ...jsonHeaders(user), ...performanceRouteHeader(CONTROL_ROUTE) },
			jar: installSession(user, CONTROL_BASE_URL),
			tags: { name: 'chat_access_invalidation_room_cancel', room_id: String(user.roomId) },
		},
	);
	recordHttpResponse(response, 'access-invalidation-room-cancel');
	const expected = response.status === 200
		&& selectedPerformanceRoute(response) === CONTROL_ROUTE;
	actionExpected.add(expected);
	check({ expected }, {
		'chat room cancellation succeeds on the control route': (value) => value.expected,
	});
	return expected;
}

function sendAfterParticipantCancellation(user, data) {
	const result = postMessage(
		user,
		clientMessageId(data.runId, 'access-invalidation-after-cancel', 0, 0),
		messageContent(data.runId, 'access-invalidation-after-cancel', 0, 0),
		CONTROL_BASE_URL,
		performanceRouteHeader(CONTROL_ROUTE),
	);
	const expected = result.response.status === 201
		&& hasMessageForRoom(result.payload, user.roomId)
		&& selectedPerformanceRoute(result.response) === CONTROL_ROUTE;
	participantMessageCreatedExpected.add(expected);
	check({ expected }, {
		'chat host can create a message after another participant cancels': (value) => value.expected,
	});
}

function sendAfterRoomCancellation(user, data) {
	const result = postMessage(
		user,
		clientMessageId(data.runId, 'access-invalidation-after-room-cancel', 0, 0),
		messageContent(data.runId, 'access-invalidation-after-room-cancel', 0, 0),
		CONTROL_BASE_URL,
		performanceRouteHeader(CONTROL_ROUTE),
	);
	const expected = result.response.status === 403
		&& selectedPerformanceRoute(result.response) === CONTROL_ROUTE;
	terminalMessageRejectedExpected.add(expected);
	check({ expected }, {
		'chat message creation is rejected after the room reaches a terminal state':
			(value) => value.expected,
	});
}

function addCloseListener(caseState, state, phase) {
	caseState.connection.socket.addEventListener('close', (event) => {
		caseState.closed = true;
		caseState.closeCode = event.code;
		if (phase === 'participant' && state.phase === 'participant') {
			completeParticipantCancellation(state);
		}
		if (phase === 'terminal' && state.phase === 'terminal') {
			completeRoomCancellation(state);
		}
	});
}

function addMessageListener(caseState) {
	caseState.connection.socket.addEventListener('message', (event) => {
		const message = parseWebSocketEvent(event.data);
		caseState.messageCount++;
		if (caseState.invalidationStartedAt !== null
			&& Date.now() >= caseState.invalidationStartedAt
			&& message !== null) {
			caseState.messageAfterInvalidation = true;
		}
	});
}

function startParticipantCancellation(state, data) {
	if (state.completed || state.phase !== 'waiting-open') {
		return;
	}
	state.phase = 'participant';
	setTimeout(() => {
		if (state.completed || state.phase !== 'participant') {
			return;
		}
		state.participant.invalidationStartedAt = Date.now();
		state.participant.closeTimer = setTimeout(
			() => completeParticipantCancellation(state),
			ACCESS_INVALIDATION_TIMEOUT_MS,
		);
		state.participant.actionSucceeded = cancelParticipation(state.participant.user);
		if (state.participant.actionSucceeded) {
			sendAfterParticipantCancellation(state.participant.hostUser, data);
		}
	}, ACCESS_INVALIDATION_SETTLE_MS);
}

function completeParticipantCancellation(state) {
	if (state.participant.completed) {
		return;
	}
	state.participant.completed = true;
	if (state.participant.closeTimer !== null) {
		clearTimeout(state.participant.closeTimer);
	}
	const closedWithPolicy = state.participant.closed && state.participant.closeCode === 1008;
	const closedExpected = state.participant.actionSucceeded && closedWithPolicy;
	const noMessageExpected = !state.participant.messageAfterInvalidation;
	const otherRoomUnaffected = state.terminal.opened && !state.terminal.closed;
	websocketClosedExpected.add(closedExpected);
	noMessageAfterInvalidationExpected.add(noMessageExpected);
	roomIsolationExpected.add(otherRoomUnaffected);
	check({ closedExpected }, {
		'chat canceled participant WebSocket closes with POLICY_VIOLATION':
			(value) => value.closedExpected,
	});
	check({ noMessageExpected }, {
		'chat canceled participant receives no message after invalidation':
			(value) => value.noMessageExpected,
	});
	check({ otherRoomUnaffected }, {
		'chat participant cancellation leaves another room connection open':
			(value) => value.otherRoomUnaffected,
	});
	if (!state.participant.closed) {
		closeWebSocket(state.participant.connection);
	}
	setTimeout(() => startRoomCancellation(state, state.data), 0);
}

function startRoomCancellation(state, data) {
	if (state.completed || state.phase !== 'participant') {
		return;
	}
	state.phase = 'terminal';
	state.terminal.invalidationStartedAt = Date.now();
	state.terminal.closeTimer = setTimeout(
		() => completeRoomCancellation(state),
		ACCESS_INVALIDATION_TIMEOUT_MS,
	);
	state.terminal.actionSucceeded = cancelRoom(state.terminal.user);
	if (state.terminal.actionSucceeded) {
		sendAfterRoomCancellation(state.terminal.user, data);
	}
}

function completeRoomCancellation(state) {
	if (state.completed || state.terminal.completed) {
		return;
	}
	state.terminal.completed = true;
	if (state.terminal.closeTimer !== null) {
		clearTimeout(state.terminal.closeTimer);
	}
	const closedWithPolicy = state.terminal.closed && state.terminal.closeCode === 1008;
	const closedExpected = state.terminal.actionSucceeded && closedWithPolicy;
	const noMessageExpected = !state.terminal.messageAfterInvalidation;
	websocketClosedExpected.add(closedExpected);
	noMessageAfterInvalidationExpected.add(noMessageExpected);
	check({ closedExpected }, {
		'chat terminal room WebSocket closes with POLICY_VIOLATION': (value) => value.closedExpected,
	});
	check({ noMessageExpected }, {
		'chat terminal room receives no message after invalidation': (value) => value.noMessageExpected,
	});
	finish(state);
}

function finish(state) {
	if (state.completed) {
		return;
	}
	state.completed = true;
	if (!state.participant.closed) {
		closeWebSocket(state.participant.connection);
	}
	if (!state.terminal.closed) {
		closeWebSocket(state.terminal.connection);
	}
}

export function accessInvalidation(data) {
	const roomIds = PROFILE_ROOM_IDS.slice(0, 2);
	const participantRoomId = roomIds[0];
	const terminalRoomId = roomIds[1];
	const participant = userForLabel(data.users, participantRoomId, '-participant-1');
	const participantHost = userForLabel(data.users, participantRoomId, '-host');
	const terminalHost = userForLabel(data.users, terminalRoomId, '-host');

	const controlRouteOk = verifyCrossInstanceRoute(participant, CONTROL_BASE_URL, CONTROL_ROUTE);
	const websocketRouteOk = verifyCrossInstanceRoute(participant, WEBSOCKET_BASE_URL, WEBSOCKET_ROUTE);
	const routesExpected = controlRouteOk && websocketRouteOk;
	routePreflightExpected.add(routesExpected);
	check({ routesExpected }, {
		'chat access invalidation pins control and WebSocket to different app routes':
			(value) => value.routesExpected,
	});
	if (!routesExpected) {
		throw new Error('access invalidation route preflight failed');
	}

	const participantBaseline = latestMessageId(participant);
	const terminalBaseline = latestMessageId(terminalHost);
	if (participantBaseline < 1 || terminalBaseline < 1) {
		throw new Error('access invalidation requires a latest message in both fixture rooms');
	}

	const state = {
		completed: false,
		data,
		phase: 'waiting-open',
		participant: {
			actionSucceeded: false,
			closeCode: null,
			closeTimer: null,
			closed: false,
			completed: false,
			connection: null,
			hostUser: participantHost,
			invalidationStartedAt: null,
			messageAfterInvalidation: false,
			messageCount: 0,
			opened: false,
			user: participant,
		},
		terminal: {
			actionSucceeded: false,
			closeCode: null,
			closeTimer: null,
			closed: false,
			completed: false,
			connection: null,
			invalidationStartedAt: null,
			messageAfterInvalidation: false,
			messageCount: 0,
			opened: false,
			user: terminalHost,
		},
	};

	state.participant.connection = createWebSocket(
		participant,
		'access-invalidation-participant',
		participantBaseline,
		WEBSOCKET_BASE_URL,
		performanceRouteHeader(WEBSOCKET_ROUTE),
	);
	state.terminal.connection = createWebSocket(
		terminalHost,
		'access-invalidation-terminal',
		terminalBaseline,
		WEBSOCKET_BASE_URL,
		performanceRouteHeader(WEBSOCKET_ROUTE),
	);
	state.participant.connection.socket.addEventListener('open', () => {
		state.participant.opened = true;
		if (state.terminal.opened) {
			startParticipantCancellation(state, data);
		}
	});
	state.terminal.connection.socket.addEventListener('open', () => {
		state.terminal.opened = true;
		if (state.participant.opened) {
			startParticipantCancellation(state, data);
		}
	});
	addMessageListener(state.participant);
	addMessageListener(state.terminal);
	addCloseListener(state.participant, state, 'participant');
	addCloseListener(state.terminal, state, 'terminal');
	setTimeout(() => {
		if (state.phase === 'waiting-open') {
			startParticipantCancellation(state, data);
		}
	}, ACCESS_INVALIDATION_TIMEOUT_MS);
}

validateCommonPrerequisites();
validateAccessInvalidationTarget();
if (PROFILE_ROOM_IDS.length < 2) {
	throw new Error('room-access-invalidation requires at least two fixture rooms');
}
if (FIXTURE_USERS.filter((user) => user.roomId === PROFILE_ROOM_IDS[0]).length < 2
	|| FIXTURE_USERS.filter((user) => user.roomId === PROFILE_ROOM_IDS[1]).length < 1) {
	throw new Error('room-access-invalidation requires a participant and host in two fixture rooms');
}

export const options = perVuOptions(
	'access_invalidation',
	'accessInvalidation',
	1,
	1,
	durationForMilliseconds(ACCESS_INVALIDATION_TIMEOUT_MS * 3 + ACCESS_INVALIDATION_SETTLE_MS + 30_000),
	accessInvalidationThresholds(),
);
