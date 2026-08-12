// 동시 접속. 동시에 유지하는 WebSocket 수를 계단식으로 올린다.

import {
	LOAD_STEP_DURATION,
	closeWebSocket,
	createWebSocket,
	currentStage,
	durationMilliseconds,
	isHealthyWebSocket,
	loadCountStages,
	loadStageConnectMs,
	loadStageOpened,
	loadThresholds,
	profileUserForVu,
	readRateSteps,
	recordMissingWebSocketOpen,
	validateCommonPrerequisites,
	websocketSessionHealthy,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

// 동시에 유지할 WebSocket 수.
const LOAD_CONNECTION_STEPS = readRateSteps('K6_LOAD_CONNECTION_STEPS', '5,10,20,40,80');

/** 동시 접속. VU 수가 곧 동시 연결 수이며 단계마다 그 수를 올린다. */
export function loadConnection(data) {
	const stage = currentStage(data, LOAD_CONNECTION_STEPS.length, 0);
	const user = profileUserForVu(data.users);
	const startedAt = Date.now();
	const connection = createWebSocket(user, 'load-connections');
	connection.socket.addEventListener('open', () => {
		loadStageConnectMs.add(Date.now() - startedAt, { stage });
	});
	setTimeout(() => {
		recordMissingWebSocketOpen(connection);
		const healthy = isHealthyWebSocket(connection);
		websocketSessionHealthy.add(healthy);
		loadStageOpened.add(connection.opened, { stage });
		closeWebSocket(connection);
	}, durationMilliseconds(LOAD_STEP_DURATION));
}

/** 동시 접속. VU 수가 곧 동시 연결 수다. */
function loadConnectionsOptions() {
	return {
		thresholds: loadThresholds(LOAD_CONNECTION_STEPS.length),
		scenarios: {
			load_connections: {
				executor: 'ramping-vus',
				exec: 'loadConnection',
				startVUs: 0,
				stages: loadCountStages(LOAD_CONNECTION_STEPS),
				gracefulRampDown: '15s',
				gracefulStop: '30s',
			},
		},
	};
}

validateCommonPrerequisites();

export const options = loadConnectionsOptions();
