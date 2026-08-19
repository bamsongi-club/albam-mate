import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const script = await readFile(
  new URL('../system-active-ccu-capacity.js', import.meta.url),
  'utf8',
);

test('T2 대표 혼합 스크립트는 네 활성 역할과 독립 쓰기 도착률을 함께 실행한다', () => {
  for (const scenario of ['browsing', 'chat', 'waitlist', 'notification_panel', 'participation', 'recovery']) {
    assert.match(script, new RegExp(`\\b${scenario}:`));
  }
  assert.match(script, /executor: 'per-vu-iterations'/);
  assert.match(script, /executor: 'constant-arrival-rate'/);
  assert.match(script, /const EVENT_TIME_UNIT = '24m'/);
  assert.match(script, /POLLING_INTERVAL_SECONDS/);
  assert.match(script, /CHAT_MESSAGE_INTERVAL_SECONDS/);
});

test('T3 공식 Run 임계는 HTTP 채팅 WebSocket drop과 회복을 모두 판정한다', () => {
  for (const metric of [
    'system_resolved_active_vus',
    'system_active_started_at_ms',
    'system_http_errors{phase:measurement}',
    'system_http_duration_ms{phase:measurement}',
    'system_chat_http_duration_ms{phase:measurement}',
    'system_websocket_opened',
    'system_websocket_session_healthy',
    'system_websocket_delivery_ms{phase:measurement}',
    'dropped_iterations',
    'system_recovery_errors',
  ]) {
    assert.ok(script.includes(metric), `missing threshold ${metric}`);
  }
  assert.match(script, /setupTimeout: '10m'/);
  assert.match(script, /startTime: `\$\{ACTIVE_LOAD_SECONDS\}s`/);
});
