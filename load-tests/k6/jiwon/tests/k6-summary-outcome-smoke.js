import { Trend } from 'k6/metrics';

import { outcomeDurationThresholds } from '../lib/write-options.mjs';

const roomRequestDuration = new Trend('room_request_duration', true);

export const options = {
  vus: 1,
  iterations: 1,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)', 'count'],
  thresholds: outcomeDurationThresholds(),
};

export default function () {
  roomRequestDuration.add(12, { outcome: 'success' });
  roomRequestDuration.add(24, { outcome: 'unexpected' });
}
