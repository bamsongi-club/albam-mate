import assert from "node:assert/strict";
import test from "node:test";

import {
  applyReproductionFields,
  assertReportMatchesDescriptor,
  canonicalSha256,
  describeFile,
  executionCommand,
  measurementSelector,
  measurementSystemProperty,
  spliceGeneratedSection,
  summarizeArm,
} from "./room09-measurement-report.mjs";

const DIRECT_COMPARISON_MEDIUM = { kind: "direct-comparison", profile: "medium", candidateLimit: 100 };

test("보고서 유형이 직접 비교면 후보 단독이 아니라 직접 비교 메서드를 가리킨다", () => {
  assert.match(measurementSelector(DIRECT_COMPARISON_MEDIUM), /승인_규모는_현행과_후보를_같은_세션에서_제한_ID별로_비교한다$/);
  assert.match(
    measurementSelector({ kind: "direct-comparison", profile: "small", candidateLimit: 10 }),
    /소형은_현행과_후보를_같은_세션에서_각각_warm_up_1회와_실측_5회로_비교한다$/,
  );
  assert.match(
    measurementSelector({ kind: "candidate", profile: "large", candidateLimit: 10 }),
    /승인_규모_후보는_명시적_속성에서만_10_100_1000_후보를_기록한다$/,
  );
  assert.match(
    measurementSelector({ kind: "waiting-queue", profile: "small", candidateLimit: 10 }),
    /CLOSED_due_ROOM마다_WAITING_10명을_둔_별도_fixture의_후보_종료_비용을_기록한다$/,
  );
});

test("소형과 대기열 fixture는 gate 속성 없이 기본 postgresTest에서 실행한다", () => {
  assert.equal(measurementSystemProperty({ kind: "direct-comparison", profile: "small" }), "");
  assert.equal(measurementSystemProperty({ kind: "waiting-queue", profile: "small" }), "");
  assert.equal(measurementSystemProperty(DIRECT_COMPARISON_MEDIUM), "issue390.measurement=true");
});

test("재현 명령은 원자료에 기록된 OS의 셸 문법으로 만든다", () => {
  const windows = executionCommand(DIRECT_COMPARISON_MEDIUM, "Windows 11");
  assert.ok(windows.startsWith("$env:JAVA_TOOL_OPTIONS = '-Dissue390.measurement=true'; .\\gradlew.bat"));

  const linux = executionCommand(DIRECT_COMPARISON_MEDIUM, "Linux");
  assert.ok(linux.startsWith("JAVA_TOOL_OPTIONS='-Dissue390.measurement=true' ./gradlew"));

  assert.equal(
    executionCommand({ kind: "direct-comparison", profile: "small", candidateLimit: 10 }, "Linux").includes(
      "JAVA_TOOL_OPTIONS",
    ),
    false,
  );
});

test("파일 이름에서 조합을 읽고 규칙에 어긋나면 실패한다", () => {
  assert.deepEqual(describeFile("room-09d-direct-comparison-large-limit-1000.json"), {
    kind: "direct-comparison",
    profile: "large",
    candidateLimit: 1000,
    fileName: "room-09d-direct-comparison-large-limit-1000.json",
  });
  assert.throws(() => describeFile("room-09d-unknown-kind-small-limit-10.json"), /규칙과 다릅니다/);
});

test("SHA-256은 CRLF와 LF에서 같은 값이 나온다", () => {
  assert.equal(canonicalSha256("a\r\nb\r\n"), canonicalSha256("a\nb\n"));
});

function rawDataText(command) {
  return [
    "{",
    '  "measurementStartEnvironment" : {',
    '    "operatingSystem" : "Windows 11",',
    '    "configuration" : {',
    `      "measurementSystemProperty" : "issue390.measurement=true",`,
    `      "executionCommand" : ${JSON.stringify(command)}`,
    "    }",
    "  },",
    '  "baseline" : { "measuredRuns" : [ { "callElapsedNanos" : 1000000 } ] }',
    "}",
    "",
  ].join("\n");
}

test("재현 메타데이터만 치환하고 나머지 바이트는 건드리지 않는다", () => {
  const before = rawDataText("틀린 명령");
  const after = applyReproductionFields(before, DIRECT_COMPARISON_MEDIUM);

  const changedLines = after
    .split("\n")
    .filter((line, index) => line !== before.split("\n")[index]);
  assert.equal(changedLines.length, 1);
  assert.match(changedLines[0], /승인_규모는_현행과_후보를_같은_세션에서_제한_ID별로_비교한다/);
  assert.equal(JSON.parse(after).baseline.measuredRuns[0].callElapsedNanos, 1000000);
});

test("이미 올바른 원자료는 한 바이트도 바뀌지 않는다", () => {
  const correct = rawDataText(executionCommand(DIRECT_COMPARISON_MEDIUM, "Windows 11"));
  assert.equal(applyReproductionFields(correct, DIRECT_COMPARISON_MEDIUM), correct);
});

function measuredRun(elapsedMs, throughput, { wholeTurnMs = elapsedMs, successCount = 20 } = {}) {
  return {
    candidateCount: 20,
    successCount,
    failureCount: 0,
    callElapsedNanos: elapsedMs * 1e6,
    wholeTurnElapsedNanos: wholeTurnMs * 1e6,
    throughputPerSecond: throughput,
    databaseCost: { calls: 53, totalExecutionTimeMs: 2 },
  };
}

test("실측 run에서 T2가 요구하는 최소·중앙·최대와 성공·실패 수를 모은다", () => {
  const summary = summarizeArm([measuredRun(30, 100), measuredRun(10, 300), measuredRun(20, 200)]);

  assert.equal(summary.minMs, 10);
  assert.equal(summary.medianMs, 20);
  assert.equal(summary.maxMs, 30);
  assert.equal(summary.medianThroughput, 200);
  assert.equal(summary.candidateCount, 20);
  assert.equal(summary.failureCount, 0);
});

test("호출 시간과 전체 순회 시간이 갈리면 각각의 최소·중앙·최대를 따로 낸다", () => {
  const summary = summarizeArm([
    measuredRun(10, 300, { wholeTurnMs: 40 }),
    measuredRun(20, 200, { wholeTurnMs: 50 }),
    measuredRun(30, 100, { wholeTurnMs: 90 }),
  ]);

  assert.deepEqual([summary.minMs, summary.medianMs, summary.maxMs], [10, 20, 30]);
  assert.deepEqual([summary.minWholeTurnMs, summary.medianWholeTurnMs, summary.maxWholeTurnMs], [40, 50, 90]);
});

function directComparisonReport({ profile = "small", candidateLimit = 10, waitingPerClosedDueRoom = 0 } = {}) {
  const arm = (path, limit) => ({
    path,
    candidateLimit: limit,
    warmUpRuns: [measuredRun(10, 300)],
    measuredRuns: [1, 2, 3, 4, 5].map(() => measuredRun(10, 300)),
  });
  return {
    outcome: "SUCCESS",
    candidateLimit,
    fixture: { profile: { name: profile }, dueRoomCount: 20, waitingPerClosedDueRoom },
    baseline: arm("current-baseline", null),
    candidate: arm("bounded-candidate", candidateLimit),
    observedChanges: [
      { metric: "medianCallElapsedNanos", percentChange: 0 },
      { metric: "medianThroughputPerSecond", percentChange: 0 },
      { metric: "medianDatabaseExecutionTimeMs", percentChange: 0 },
    ],
  };
}

test("파일명과 원자료 조건이 같으면 통과한다", () => {
  assert.doesNotThrow(() =>
    assertReportMatchesDescriptor(directComparisonReport(), describeFile("room-09d-direct-comparison-small-limit-10.json")),
  );
});

test("limit이 다른 원자료를 다른 이름으로 복사하면 실패한다", () => {
  assert.throws(
    () =>
      assertReportMatchesDescriptor(
        directComparisonReport({ candidateLimit: 10 }),
        describeFile("room-09d-direct-comparison-small-limit-100.json"),
      ),
    /candidateLimit이 파일명과 다릅니다/,
  );
});

test("profile이 다른 원자료를 다른 이름으로 복사하면 실패한다", () => {
  assert.throws(
    () =>
      assertReportMatchesDescriptor(
        directComparisonReport({ profile: "medium" }),
        describeFile("room-09d-direct-comparison-small-limit-10.json"),
      ),
    /profile이 파일명과 다릅니다/,
  );
});

test("대기열 fixture를 직접 비교 이름으로 두면 실패한다", () => {
  assert.throws(
    () =>
      assertReportMatchesDescriptor(
        directComparisonReport({ waitingPerClosedDueRoom: 10 }),
        describeFile("room-09d-direct-comparison-small-limit-10.json"),
      ),
    /fixture 유형이 파일명과 다릅니다/,
  );
});

test("정상 표본이 아니거나 run 수·경로·변화율이 계약과 다르면 실패한다", () => {
  const descriptor = describeFile("room-09d-direct-comparison-small-limit-10.json");

  assert.throws(
    () => assertReportMatchesDescriptor({ ...directComparisonReport(), outcome: "RUN_FAILURE" }, descriptor),
    /정상 표본이 아닙니다/,
  );

  const shortRuns = directComparisonReport();
  shortRuns.candidate.measuredRuns = shortRuns.candidate.measuredRuns.slice(0, 3);
  assert.throws(() => assertReportMatchesDescriptor(shortRuns, descriptor), /run 수가/);

  const swappedPath = directComparisonReport();
  swappedPath.baseline.path = "bounded-candidate";
  assert.throws(() => assertReportMatchesDescriptor(swappedPath, descriptor), /현행 경로가/);

  const missingChange = directComparisonReport();
  missingChange.observedChanges = missingChange.observedChanges.slice(0, 1);
  assert.throws(() => assertReportMatchesDescriptor(missingChange, descriptor), /현행 대비 변화가 빠졌습니다/);

  const unfinished = directComparisonReport();
  unfinished.candidate.measuredRuns[0] = measuredRun(10, 300, { successCount: 19 });
  assert.throws(() => assertReportMatchesDescriptor(unfinished, descriptor), /끝까지 처리하지 않은 run/);
});

test("생성 구역 표시가 없는 문서는 조용히 넘어가지 않고 실패한다", () => {
  const document = "앞\n<!-- room09-report:comparison-table:start -->\n낡은 표\n<!-- room09-report:comparison-table:end -->\n뒤\n";
  assert.equal(
    spliceGeneratedSection(document, "comparison-table", "새 표"),
    "앞\n<!-- room09-report:comparison-table:start -->\n새 표\n<!-- room09-report:comparison-table:end -->\n뒤\n",
  );
  assert.throws(() => spliceGeneratedSection("표시 없음", "comparison-table", "새 표"), /생성 구역 표시가 없습니다/);
});

test("CRLF 문서는 생성 구역도 CRLF로 넣어 줄바꿈만으로 어긋나지 않는다", () => {
  const document =
    "앞\r\n<!-- room09-report:comparison-table:start -->\r\n낡은 표\r\n<!-- room09-report:comparison-table:end -->\r\n";
  const spliced = spliceGeneratedSection(document, "comparison-table", "머리\n본문");

  assert.ok(spliced.includes("머리\r\n본문"));
  assert.equal(spliced.includes("머리\n본문"), false);
});
