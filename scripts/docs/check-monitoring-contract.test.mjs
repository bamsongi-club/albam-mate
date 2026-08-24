import assert from "node:assert/strict";
import { test } from "node:test";

import { validateMonitoringContract } from "./check-monitoring-contract.mjs";

const validSource = `
log.warn(
  "event=chat_realtime_publish_failed eventType={} roomId={} messageId={} exceptionType={}",
  event.eventType(), event.roomId(), event.messageId(), exception.getClass().getName());
`;

const validFluentSource = `
log.atWarn().addKeyValue("event", "chat_realtime_publish_failed")
  .addKeyValue("eventType", event.eventType()).addKeyValue("roomId", event.roomId())
  .addKeyValue("messageId", event.messageId()).addKeyValue("exceptionType", exception.getClass().getName())
  .log("chat realtime publish failed");
`;

const fluentEventChainEndedBeforeExceptionType = `
log.atWarn().addKeyValue("event", "chat_realtime_publish_failed")
  .log("chat realtime publish failed");
log.atWarn().addKeyValue("exceptionType", exception.getClass().getName())
  .log("another event");
`;

const fluentExceptionTypeBeforeEvent = `
log.atWarn().addKeyValue("exceptionType", exception.getClass().getName())
  .addKeyValue("event", "chat_realtime_publish_failed")
  .log("chat realtime publish failed");
`;

const fluentEventWithExceptionClassAndSeparateExceptionType = `
log.atWarn().addKeyValue("event", "chat_realtime_publish_failed")
  .addKeyValue("exceptionClass", exception.getClass().getName())
  .log("chat realtime publish failed");
log.atWarn().addKeyValue("exceptionType", exception.getClass().getName())
  .log("another event");
`;

const fluentEventWithExceptionClassAndExceptionType = `
log.atWarn().addKeyValue("event", "chat_realtime_publish_failed")
  .addKeyValue("exceptionClass", exception.getClass().getName())
  .addKeyValue("exceptionType", exception.getClass().getName())
  .log("chat realtime publish failed");
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

test("fluent structured event와 exceptionType도 허용한다", () => {
  assert.deepEqual(
    validateMonitoringContract({
      runbookText: validRunbook,
      chatListenerText: validFluentSource,
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

test("fluent structured event에서 exceptionType이 빠지면 실패한다", () => {
	const problems = validateMonitoringContract({
		runbookText: validRunbook,
		chatListenerText: validFluentSource.replace("exceptionType", "failureCode"),
  });

  assert.equal(problems.length, 1);
  assert.match(problems[0], /exceptionType/);
});

test("fluent event 체인이 끝난 뒤 별도 exceptionType 체인을 결합하지 않는다", () => {
	const problems = validateMonitoringContract({
		runbookText: validRunbook,
		chatListenerText: fluentEventChainEndedBeforeExceptionType,
	});

	assert.equal(problems.length, 1);
	assert.match(problems[0], /exceptionType/);
});

test("fluent exceptionType이 event보다 앞선 체인은 허용하지 않는다", () => {
	const problems = validateMonitoringContract({
		runbookText: validRunbook,
		chatListenerText: fluentExceptionTypeBeforeEvent,
	});

	assert.equal(problems.length, 1);
	assert.match(problems[0], /exceptionType/);
});

test("fluent event 체인의 exceptionClass와 별도 exceptionType 체인을 결합하지 않는다", () => {
	const problems = validateMonitoringContract({
		runbookText: validRunbook,
		chatListenerText: fluentEventWithExceptionClassAndSeparateExceptionType,
	});

	assert.equal(problems.length, 2);
	assert.match(problems[0], /exceptionType/);
	assert.match(problems[1], /exceptionClass/);
});

test("fluent event 체인에 exceptionClass와 exceptionType을 함께 기록하지 않는다", () => {
	const problems = validateMonitoringContract({
		runbookText: validRunbook,
		chatListenerText: fluentEventWithExceptionClassAndExceptionType,
	});

	assert.equal(problems.length, 1);
	assert.match(problems[0], /exceptionClass/);
});

test("생산 event가 exceptionType을 기록하지 않으면 실패한다", () => {
  const problems = validateMonitoringContract({
    runbookText: validRunbook,
    chatListenerText: validSource.replace("exceptionType", "exceptionClass"),
  });

  assert.equal(problems.length, 1);
  assert.match(problems[0], /exceptionType/);
});
