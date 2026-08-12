// 이력 조회. 초당 조회 건수를 계단식으로 올린다.

import {
	HTTP_P99_MS,
	currentStage,
	loadCountStages,
	loadThresholds,
	maxOf,
	profileUserForVu,
	readHistoryPage,
	readRateSteps,
	requiredArrivalVus,
	validateCommonPrerequisites,
} from './lib/chat.js';

export { setup } from './lib/chat.js';

// 초당 이력 조회 건수.
const LOAD_HISTORY_RATES = readRateSteps('K6_LOAD_HISTORY_RATES', '1,2,4,8');

/** 이력 조회. 초당 조회 건수를 올리며 읽기 경로를 민다. */
export function loadHistoryRead(data) {
	const stage = currentStage(data, LOAD_HISTORY_RATES.length, 0);
	readHistoryPage(profileUserForVu(data.users), stage);
}

/** 이력 조회. 초당 조회 건수를 계단식으로 올린다. */
function loadHistoryOptions() {
	const peak = requiredArrivalVus(maxOf(LOAD_HISTORY_RATES), HTTP_P99_MS);
	return {
		thresholds: loadThresholds(LOAD_HISTORY_RATES.length),
		scenarios: {
			load_history: {
				executor: 'ramping-arrival-rate',
				exec: 'loadHistoryRead',
				startRate: 0,
				timeUnit: '1s',
				preAllocatedVUs: peak,
				maxVUs: peak * 4,
				stages: loadCountStages(LOAD_HISTORY_RATES),
				gracefulStop: '15s',
			},
		},
	};
}

validateCommonPrerequisites();

export const options = loadHistoryOptions();
