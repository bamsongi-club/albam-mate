// WebSocket 계약. K6_CHAT_CASE 로 팬아웃·유휴 유지·재연결 복구를 고른다.

import { check } from 'k6';
import { Rate } from 'k6/metrics';

import {
	FIXTURE,
	PRIMARY_ROOM_ID,
	WS_EVENT_TIMEOUT_MS,
	WS_GRACE_DURATION,
	closeWebSocket,
	createWebSocket,
	durationForMilliseconds,
	durationMilliseconds,
	durationWithGrace,
	equalNumberArrays,
	fanoutThresholds,
	immediateStopGate,
	isHealthyWebSocket,
	isMessageCreatedEvent,
	openFanoutWebSockets,
	parseWebSocketEvent,
	perVuOptions,
	profileUserForVu,
	readDuration,
	readEnum,
	readPositiveInteger,
	readVus,
	recordMissingWebSocketOpen,
	roomParticipants,
	setOf,
	validateCommonPrerequisites,
	validateFanoutProfile,
	validateProfileVuCount,
	webSocketThresholds,
	websocketEvents,
	websocketExpectedEvent,
	websocketSessionHealthy,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

const WS_IDLE_DURATION = readDuration('K6_WS_IDLE_DURATION', '10m');

const RECONNECT_TIMEOUT_MS = readPositiveInteger('K6_RECONNECT_TIMEOUT_MS', 120_000);

const reconnectRecovered = new Rate('chat_websocket_reconnect_recovered');

export function wsIdle(data) {
	const user = profileUserForVu(data.users);
	openIdleWebSocket(user, 'ws-idle', durationMilliseconds(WS_IDLE_DURATION));
}

export function wsFanout(data) {
	openFanoutWebSockets(roomParticipants(data.users, PRIMARY_ROOM_ID), data.runId);
}

export function reconnect(data) {
	const plan = resolvedReconnectPlan(data.users);
	beginReconnectLifecycle(plan.user, plan);
}

function openIdleWebSocket(user, mode, holdMilliseconds) {
	const connection = createWebSocket(user, mode);
	let invalidEvent = false;
	connection.socket.addEventListener('message', (event) => {
		const message = parseWebSocketEvent(event.data);
		websocketEvents.add(1);
		const expected = isMessageCreatedEvent(message, user.roomId);
		websocketExpectedEvent.add(expected);
		if (!expected) {
			invalidEvent = true;
		}
	});
	setTimeout(() => {
		recordMissingWebSocketOpen(connection);
		const expected = isHealthyWebSocket(connection) && !invalidEvent;
		websocketSessionHealthy.add(expected);
		check({ expected }, {
			'chat WebSocket remains open without transport errors': (value) => value.expected,
		});
		closeWebSocket(connection);
	}, holdMilliseconds);
}

function beginReconnectLifecycle(user, plan) {
	const state = {
		completed: false,
		deliveryTimer: null,
		expectedByMessageId: setOf(plan.expectedMessageIds),
		expectedMessageIds: plan.expectedMessageIds,
		initialConnection: null,
		initialClosed: false,
		invalidEvent: false,
		lifecycleTimer: null,
		reconnectStarted: false,
		recoveryConnection: null,
		receivedMessageIds: [],
		seenMessageIds: {},
		unexpectedMessage: false,
		user,
	};
	state.lifecycleTimer = setTimeout(() => finishReconnect(state), RECONNECT_TIMEOUT_MS);
	const initialConnection = createWebSocket(user, 'reconnect-initial');
	state.initialConnection = initialConnection;
	initialConnection.socket.addEventListener('open', () => {
		if (!state.completed) {
			setTimeout(() => closeWebSocket(initialConnection), 0);
		}
	});
	initialConnection.socket.addEventListener('close', () => {
		if (state.completed || !initialConnection.closeRequested) {
			return;
		}
		state.initialClosed = true;
		startReconnectRecovery(state, plan.afterMessageId);
	});
}

function startReconnectRecovery(state, afterMessageId) {
	if (state.completed || state.reconnectStarted) {
		return;
	}
	state.reconnectStarted = true;
	const recoveryConnection = createWebSocket(state.user, 'reconnect', afterMessageId);
	state.recoveryConnection = recoveryConnection;
	recoveryConnection.socket.addEventListener('open', () => {
		if (!state.completed) {
			state.deliveryTimer = setTimeout(() => finishReconnect(state), RECONNECT_TIMEOUT_MS);
		}
	});
	recoveryConnection.socket.addEventListener('message', (event) => {
		const message = parseWebSocketEvent(event.data);
		websocketEvents.add(1);
		const expectedEvent = isMessageCreatedEvent(message, state.user.roomId);
		websocketExpectedEvent.add(expectedEvent);
		if (!expectedEvent) {
			state.invalidEvent = true;
			return;
		}
		const messageId = message.message.messageId;
		if (messageId <= afterMessageId || !state.expectedByMessageId[messageId]) {
			state.unexpectedMessage = true;
			return;
		}
		if (state.seenMessageIds[messageId]) {
			state.invalidEvent = true;
			return;
		}
		state.seenMessageIds[messageId] = true;
		state.receivedMessageIds.push(messageId);
		if (state.receivedMessageIds.length === state.expectedMessageIds.length) {
			finishReconnect(state);
		}
	});
}

function finishReconnect(state) {
	if (state.completed) {
		return;
	}
	state.completed = true;
	clearTimeout(state.lifecycleTimer);
	clearTimeout(state.deliveryTimer);
	if (state.initialConnection) {
		recordMissingWebSocketOpen(state.initialConnection);
	}
	if (state.recoveryConnection) {
		recordMissingWebSocketOpen(state.recoveryConnection);
	}
	const expected = state.initialConnection !== null
		&& isHealthyWebSocket(state.initialConnection)
		&& state.initialClosed
		&& state.recoveryConnection !== null
		&& isHealthyWebSocket(state.recoveryConnection)
		&& !state.invalidEvent
		&& !state.unexpectedMessage
		&& equalNumberArrays(state.receivedMessageIds, state.expectedMessageIds);
	websocketSessionHealthy.add(expected);
	reconnectRecovered.add(expected);
	check({ expected }, {
		'chat reconnect recovers only the fixture backlog exactly once in ascending order': (value) => value.expected,
	});
	if (state.initialConnection) {
		closeWebSocket(state.initialConnection);
	}
	if (state.recoveryConnection) {
		closeWebSocket(state.recoveryConnection);
	}
}

function resolvedReconnectPlan(users) {
	const plan = FIXTURE.reconnect;
	const user = users.find((candidate) => candidate.label === plan.userLabel);
	if (!user) {
		throw new Error(`Reconnect fixture user is missing: ${plan.userLabel}`);
	}
	return { ...plan, user };
}

const CASE = readEnum('K6_CHAT_CASE', 'fanout', ['fanout', 'idle', 'reconnect']);

validateCommonPrerequisites();
if (CASE === 'fanout') {
	validateFanoutProfile();
}
if (CASE === 'reconnect' && FIXTURE.reconnect === null) {
	throw new Error('reconnect requires a fixture reconnect plan');
}

function contractOptions() {
	if (CASE === 'idle') {
		const vus = readVus();
		validateProfileVuCount(vus, 'ws-idle');
		return perVuOptions(
			'ws_idle',
			'wsIdle',
			vus,
			1,
			durationWithGrace(WS_IDLE_DURATION, WS_GRACE_DURATION),
			webSocketThresholds(),
		);
	}
	if (CASE === 'reconnect') {
		return perVuOptions(
			'reconnect',
			'reconnect',
			1,
			1,
			durationForMilliseconds(RECONNECT_TIMEOUT_MS + 30000),
			webSocketThresholds({
				chat_websocket_reconnect_recovered: [immediateStopGate('rate==1')],
			}),
		);
	}
	return perVuOptions(
		'ws_fanout',
		'wsFanout',
		1,
		1,
		durationForMilliseconds(WS_EVENT_TIMEOUT_MS * 3 + 10000),
		fanoutThresholds(),
	);
}

export const options = contractOptions();
