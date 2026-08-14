import assert from "node:assert/strict";
import { test } from "node:test";

import { validateMonitoringContract } from "./check-monitoring-contract.mjs";

const validSource = `
log.warn(
  "event=chat_realtime_publish_failed eventType={} roomId={} messageId={} exceptionType={}",
  event.eventType(), event.roomId(), event.messageId(), exception.getClass().getName());
`;

const validRunbook = `
| event | level·허용 필드 | 중앙 전송 상태 |
| --- | --- | --- |
| \`chat_realtime_publish_failed\` | WARN; \`eventType\`, 단일 상관 키, \`exceptionType\` | 허용 |
`;

test("생산 event와 허용 목록이 exceptionType으로 일치하면 통과한다", () => {
  assert.deepEqual(
    validateMonitoringContract({
      runbookText: validRunbook,
      chatListenerText: validSource,
    }),
    [],
  );
});

test("허용 목록이 exceptionClass로 바뀌면 실패한다", () => {
  const problems = validateMonitoringContract({
    runbookText: validRunbook.replace("exceptionType", "exceptionClass"),
    chatListenerText: validSource,
  });

  assert.equal(problems.length, 2);
  assert.match(problems[0], /exceptionType/);
  assert.match(problems[1], /exceptionClass/);
});

test("생산 event가 exceptionType을 기록하지 않으면 실패한다", () => {
  const problems = validateMonitoringContract({
    runbookText: validRunbook,
    chatListenerText: validSource.replace("exceptionType", "exceptionClass"),
  });

  assert.equal(problems.length, 1);
  assert.match(problems[0], /exceptionType/);
});
