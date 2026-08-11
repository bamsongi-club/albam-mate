// 인스턴스 간 전달 계약. 발신과 수신을 서로 다른 App 에 고정한다.

import {
	BASE_URL,
	ORIGIN,
	PRIMARY_ROOM_ID,
	WS_EVENT_TIMEOUT_MS,
	durationForMilliseconds,
	fanoutThresholds,
	openFanoutWebSockets,
	perVuOptions,
	removeTrailingSlash,
	roomParticipants,
	validateCommonPrerequisites,
	validateFanoutProfile,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

const CROSS_INSTANCE_SEND_BASE_URL = readOptionalHttpBaseUrl('K6_CROSS_INSTANCE_SEND_BASE_URL');

const CROSS_INSTANCE_RECEIVE_BASE_URL = readOptionalHttpBaseUrl('K6_CROSS_INSTANCE_RECEIVE_BASE_URL');

const CROSS_INSTANCE_SEND_ROUTE = readOptionalCrossInstanceRoute('K6_CROSS_INSTANCE_SEND_ROUTE');

const CROSS_INSTANCE_RECEIVE_ROUTE = readOptionalCrossInstanceRoute('K6_CROSS_INSTANCE_RECEIVE_ROUTE');

export function crossInstance(data) {
	openFanoutWebSockets(
		roomParticipants(data.users, PRIMARY_ROOM_ID),
		data.runId,
		CROSS_INSTANCE_SEND_BASE_URL,
		CROSS_INSTANCE_RECEIVE_BASE_URL,
		'cross-instance',
		CROSS_INSTANCE_SEND_ROUTE,
		CROSS_INSTANCE_RECEIVE_ROUTE,
	);
}

function readOptionalHttpBaseUrl(name) {
	const raw = __ENV[name];
	if (raw === undefined || raw === '') {
		return null;
	}
	const value = removeTrailingSlash(raw);
	if (!value.startsWith('http://') && !value.startsWith('https://')) {
		throw new Error(`${name} must start with http:// or https://`);
	}
	return value;
}

function readOptionalCrossInstanceRoute(name) {
	const raw = __ENV[name];
	if (raw === undefined || raw === '') {
		return null;
	}
	if (raw !== 'app-a' && raw !== 'app-b') {
		throw new Error(`${name} must be app-a or app-b`);
	}
	return raw;
}

validateCommonPrerequisites();
validateFanoutProfile();
if (!CROSS_INSTANCE_SEND_BASE_URL || !CROSS_INSTANCE_RECEIVE_BASE_URL
	|| !CROSS_INSTANCE_SEND_ROUTE || !CROSS_INSTANCE_RECEIVE_ROUTE) {
	throw new Error('cross-instance requires send/receive base URLs and pinned routes');
}
if (!BASE_URL.startsWith('https://') || ORIGIN !== BASE_URL
	|| CROSS_INSTANCE_SEND_BASE_URL !== BASE_URL
	|| CROSS_INSTANCE_RECEIVE_BASE_URL !== BASE_URL) {
	throw new Error('cross-instance requires the same HTTPS K6_BASE_URL, Origin, send base URL, and receive base URL');
}
if (CROSS_INSTANCE_SEND_ROUTE === CROSS_INSTANCE_RECEIVE_ROUTE) {
	throw new Error('cross-instance send and receive routes must select different app instances');
}

export const options = perVuOptions(
	'cross_instance',
	'crossInstance',
	1,
	1,
	durationForMilliseconds(WS_EVENT_TIMEOUT_MS * 3 + 10000),
	fanoutThresholds(),
);
