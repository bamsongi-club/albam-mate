import assert from "node:assert/strict";
import test from "node:test";

import {
  applyReproductionFields,
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

test("실측 run에서 T2가 요구하는 최소·중앙·최대와 성공·실패 수를 모은다", () => {
  const run = (elapsedMs, throughput, calls) => ({
    candidateCount: 20,
    successCount: 20,
    failureCount: 0,
    callElapsedNanos: elapsedMs * 1e6,
    wholeTurnElapsedNanos: elapsedMs * 1e6,
    throughputPerSecond: throughput,
    databaseCost: { calls, totalExecutionTimeMs: 2 },
  });
  const summary = summarizeArm([run(30, 100, 53), run(10, 300, 53), run(20, 200, 53)]);

  assert.equal(summary.minMs, 10);
  assert.equal(summary.medianMs, 20);
  assert.equal(summary.maxMs, 30);
  assert.equal(summary.medianThroughput, 200);
  assert.equal(summary.candidateCount, 20);
  assert.equal(summary.failureCount, 0);
  assert.equal(summary.medianWholeTurnMs, summary.medianMs);
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
