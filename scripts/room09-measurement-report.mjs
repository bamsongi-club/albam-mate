#!/usr/bin/env node
/**
 * ROOM-09d 측정의 보고 단계.
 *
 * 측정(`postgresTest`)과 보고(이 스크립트)를 나눈다. 대형 한 조합이 수십 분이라, 재현 명령이나
 * 대비 표 같은 파생물이 원자료와 어긋날 때 측정을 다시 돌릴 수 없다. 파생물은 보존 원자료만
 * 읽어 다시 만들므로 초 단위로 고친다.
 *
 * 측정값은 절대 바꾸지 않는다. 재현 메타데이터 필드만 문자열로 치환하고, 그 외 모든 값이
 * 같은지 재파싱해 확인한 뒤에만 파일을 쓴다.
 */

import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const RESULT_DIRECTORY = "docs/measurements/results/room-09d";
const REPORT_DOCUMENT = "docs/measurements/room-09-bounded-processing-baseline.md";
const TEST_CLASS =
  "cloud.bamsongi.albammate.room.measurement.RoomStatusCorrectionCandidateMeasurementPostgresTest";

/** 측정값과 달리 이 필드들은 원자료를 다시 만들지 않고 정정한다. */
const REPRODUCTION_FIELDS = ["executionCommand", "measurementSystemProperty"];

const PROFILE_ORDER = { small: 0, medium: 1, large: 2 };
const KIND_ORDER = { "direct-comparison": 0, candidate: 1, "waiting-queue": 2 };

const FILE_NAME_PATTERN =
  /^room-09d-(direct-comparison|candidate|waiting-queue)-(small|medium|large)-limit-(\d+)\.json$/;

/**
 * 보고서 유형별 측정 메서드. 이 스크립트가 재현 명령의 유일한 정본이며, 측정 테스트가 남긴
 * 값이 여기서 벗어나면 `--check`가 실패한다. 유형과 메서드가 어긋난 원자료를 보존한 적이 있어
 * 한곳으로 모았다.
 */
export function measurementSelector({ kind, profile }) {
  if (kind === "waiting-queue") {
    return `${TEST_CLASS}.CLOSED_due_ROOM마다_WAITING_10명을_둔_별도_fixture의_후보_종료_비용을_기록한다`;
  }
  if (kind === "direct-comparison") {
    return profile === "small"
      ? `${TEST_CLASS}.소형은_현행과_후보를_같은_세션에서_각각_warm_up_1회와_실측_5회로_비교한다`
      : `${TEST_CLASS}.승인_규모는_현행과_후보를_같은_세션에서_제한_ID별로_비교한다`;
  }
  return profile === "small"
    ? `${TEST_CLASS}.small_후보는_WAITING_없는_동일_fixture를_warm_up_1회와_실측_5회로_기록한다`
    : `${TEST_CLASS}.승인_규모_후보는_명시적_속성에서만_10_100_1000_후보를_기록한다`;
}

/** 소형과 대기열 fixture는 기본 `postgresTest`에서 돌아 gate 속성이 필요 없다. */
export function measurementSystemProperty({ kind, profile }) {
  return profile === "small" || kind === "waiting-queue" ? "" : "issue390.measurement=true";
}

/** 원자료에 기록된 OS의 셸 문법으로 만든다. 다른 OS에서 그대로 붙여 넣을 수 없기 때문이다. */
export function executionCommand(descriptor, operatingSystem) {
  const windows = operatingSystem.toLowerCase().includes("win");
  const wrapper = windows ? ".\\gradlew.bat" : "./gradlew";
  const selector = measurementSelector(descriptor);
  const gradleCommand = `${wrapper} postgresTest --no-daemon --tests "${selector}" --rerun --fail-fast`;
  const systemProperty = measurementSystemProperty(descriptor);
  if (systemProperty === "") {
    return gradleCommand;
  }
  return windows
    ? `$env:JAVA_TOOL_OPTIONS = '-D${systemProperty}'; ${gradleCommand}`
    : `JAVA_TOOL_OPTIONS='-D${systemProperty}' ${gradleCommand}`;
}

/** `room-09d-<kind>-<profile>-limit-<n>.json`에서 조합을 읽는다. */
export function describeFile(fileName) {
  const matched = FILE_NAME_PATTERN.exec(fileName);
  if (matched === null) {
    throw new Error(`원자료 파일 이름이 규칙과 다릅니다: ${fileName}`);
  }
  return { kind: matched[1], profile: matched[2], candidateLimit: Number(matched[3]), fileName };
}

const WARM_UP_RUN_COUNT = 1;
const MEASURED_RUN_COUNT = 5;
const REQUIRED_CHANGE_METRICS = [
  "medianCallElapsedNanos",
  "medianThroughputPerSecond",
  "medianDatabaseExecutionTimeMs",
];

/**
 * 파일명에서 읽은 조합이 원자료 내용과 같은지 대조한다. 파일명만 믿으면 limit-10 원자료를
 * limit-100 이름으로 복사해도 표와 재현 명령이 100으로 생성되고 다음 `--check`까지 통과한다.
 * 가장 흔한 복사 오류라 `--check`와 `--write` 양쪽에서 실패시킨다.
 */
export function assertReportMatchesDescriptor(report, descriptor) {
  const fail = (reason) => {
    throw new Error(`${descriptor.fileName}: ${reason}`);
  };
  const equals = (label, actual, expected) => {
    if (actual !== expected) {
      fail(`${label}이 파일명과 다릅니다. 원자료 ${JSON.stringify(actual)}, 파일명 ${JSON.stringify(expected)}`);
    }
  };

  if (report.outcome !== "SUCCESS") {
    fail(`정상 표본이 아닙니다. outcome ${JSON.stringify(report.outcome)}`);
  }
  equals("profile", report.fixture?.profile?.name, descriptor.profile);
  equals("candidateLimit", report.candidateLimit, descriptor.candidateLimit);

  const waitingPerRoom = report.fixture?.waitingPerClosedDueRoom;
  if (descriptor.kind === "waiting-queue" ? !(waitingPerRoom > 0) : waitingPerRoom !== 0) {
    fail(`fixture 유형이 파일명과 다릅니다. waitingPerClosedDueRoom ${waitingPerRoom}`);
  }

  const checkArm = (arm, label, expectedPath, expectedLimit) => {
    if (arm === undefined) {
      fail(`${label} 경로 원자료가 없습니다.`);
    }
    if (expectedPath !== null && arm.path !== expectedPath) {
      fail(`${label} 경로가 ${JSON.stringify(arm.path)}입니다. ${JSON.stringify(expectedPath)}이어야 합니다.`);
    }
    if (expectedLimit !== undefined && arm.candidateLimit !== expectedLimit) {
      fail(`${label} 경로의 candidateLimit이 ${arm.candidateLimit}입니다. ${expectedLimit}이어야 합니다.`);
    }
    if (arm.warmUpRuns?.length !== WARM_UP_RUN_COUNT || arm.measuredRuns?.length !== MEASURED_RUN_COUNT) {
      fail(
        `${label} 경로의 run 수가 warm-up ${arm.warmUpRuns?.length}·실측 ${arm.measuredRuns?.length}입니다.` +
          ` warm-up ${WARM_UP_RUN_COUNT}·실측 ${MEASURED_RUN_COUNT}이어야 합니다.`,
      );
    }
    for (const run of arm.measuredRuns) {
      if (run.failureCount !== 0 || run.successCount !== report.fixture.dueRoomCount) {
        fail(
          `${label} 경로에 초기 due 집합을 끝까지 처리하지 않은 run이 있습니다.` +
            ` 성공 ${run.successCount}, 실패 ${run.failureCount}, due ${report.fixture.dueRoomCount}`,
        );
      }
    }
  };

  if (descriptor.kind === "direct-comparison") {
    checkArm(report.baseline, "현행", "current-baseline", null);
    checkArm(report.candidate, "후보", "bounded-candidate", descriptor.candidateLimit);
    const recorded = (report.observedChanges ?? []).map((entry) => entry.metric);
    const missing = REQUIRED_CHANGE_METRICS.filter((metric) => !recorded.includes(metric));
    if (missing.length > 0) {
      fail(`현행 대비 변화가 빠졌습니다: ${missing.join(", ")}`);
    }
    return;
  }
  checkArm(report, "후보", undefined, undefined);
}

/** 문서가 고정하는 SHA-256은 OS 줄바꿈 차이를 없앤 Git canonical blob bytes 기준이다. */
export function canonicalSha256(text) {
  return createHash("sha256")
    .update(Buffer.from(text.replaceAll("\r\n", "\n"), "utf8"))
    .digest("hex")
    .toUpperCase();
}

/**
 * 재현 메타데이터만 치환한다. JSON을 다시 직렬화하면 측정 테스트의 포맷과 어긋나 diff가 파일
 * 전체로 번지므로, 해당 필드의 값 문자열만 바꾸고 나머지 바이트는 그대로 둔다.
 */
export function applyReproductionFields(text, descriptor) {
  const operatingSystem = JSON.parse(text).measurementStartEnvironment?.operatingSystem ?? "";
  const desired = {
    executionCommand: executionCommand(descriptor, operatingSystem),
    measurementSystemProperty: measurementSystemProperty(descriptor),
  };
  let applied = text;
  for (const field of REPRODUCTION_FIELDS) {
    const pattern = new RegExp(`("${field}"\\s*:\\s*)"(?:[^"\\\\]|\\\\.)*"`);
    if (!pattern.test(applied)) {
      throw new Error(`${descriptor.fileName}에 ${field} 필드가 없습니다.`);
    }
    applied = applied.replace(pattern, `$1${JSON.stringify(desired[field])}`);
  }
  assertOnlyReproductionFieldsChanged(text, applied, descriptor.fileName);
  return applied;
}

/** 재현 메타데이터를 뺀 나머지가 완전히 같은지 확인한다. 측정값이 바뀌지 않았다는 근거다. */
function assertOnlyReproductionFieldsChanged(before, after, fileName) {
  const strip = (value) => {
    if (Array.isArray(value)) {
      return value.map(strip);
    }
    if (value === null || typeof value !== "object") {
      return value;
    }
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key]) => !REPRODUCTION_FIELDS.includes(key))
        .map(([key, nested]) => [key, strip(nested)]),
    );
  };
  if (JSON.stringify(strip(JSON.parse(before))) !== JSON.stringify(strip(JSON.parse(after)))) {
    throw new Error(`${fileName}의 측정값이 바뀌었습니다. 보고 단계는 측정값을 바꾸지 않아야 합니다.`);
  }
}

const median = (values) => {
  const sorted = [...values].sort((left, right) => left - right);
  const { length } = sorted;
  return length % 2 === 1 ? sorted[(length - 1) / 2] : (sorted[length / 2 - 1] + sorted[length / 2]) / 2;
};
const decimal = (value) =>
  value.toLocaleString("en-US", { minimumFractionDigits: 4, maximumFractionDigits: 4 });
const integer = (value) => value.toLocaleString("en-US");
const percent = (value) => `${value >= 0 ? "+" : "−"}${Math.abs(value).toFixed(2)}%`;
const range = (low, middle, high) => `${decimal(low)} / **${decimal(middle)}** / ${decimal(high)}`;

/**
 * 실측 run에서 `ROOM-09d-T2`가 요구하는 지표를 모은다. 호출별 시간과 전체 순회 완료 시간은
 * 현재 fixture에서 값이 같지만 각각 최소·중앙·최대를 따로 낸다. 한 쪽만 내면 두 값이 갈리는
 * fixture가 생겼을 때 표와 `--check`가 그 차이를 놓친다.
 */
export function summarizeArm(runs) {
  const elapsedMs = runs.map((run) => run.callElapsedNanos / 1e6);
  const wholeTurnMs = runs.map((run) => run.wholeTurnElapsedNanos / 1e6);
  const throughput = runs.map((run) => run.throughputPerSecond);
  return {
    candidateCount: runs[0].candidateCount,
    successCount: median(runs.map((run) => run.successCount)),
    failureCount: median(runs.map((run) => run.failureCount)),
    minMs: Math.min(...elapsedMs),
    medianMs: median(elapsedMs),
    maxMs: Math.max(...elapsedMs),
    minWholeTurnMs: Math.min(...wholeTurnMs),
    medianWholeTurnMs: median(wholeTurnMs),
    maxWholeTurnMs: Math.max(...wholeTurnMs),
    minThroughput: Math.min(...throughput),
    medianThroughput: median(throughput),
    maxThroughput: Math.max(...throughput),
    databaseCalls: median(runs.map((run) => run.databaseCost.calls)),
    databaseMs: median(runs.map((run) => run.databaseCost.totalExecutionTimeMs)),
  };
}

function comparisonTable(entries) {
  const rows = [];
  for (const { descriptor, report } of entries) {
    const change = (metric) =>
      report.observedChanges.find((entry) => entry.metric === metric).percentChange;
    const arms = [
      { label: "현행(단일)", summary: summarizeArm(report.baseline.measuredRuns), baseline: true },
      { label: "후보(분할)", summary: summarizeArm(report.candidate.measuredRuns), baseline: false },
    ];
    for (const { label, summary, baseline } of arms) {
      rows.push(
        `| ${[
          descriptor.profile,
          descriptor.candidateLimit,
          label,
          integer(summary.candidateCount),
          integer(summary.successCount),
          summary.failureCount,
          range(summary.minMs, summary.medianMs, summary.maxMs),
          range(summary.minWholeTurnMs, summary.medianWholeTurnMs, summary.maxWholeTurnMs),
          range(summary.minThroughput, summary.medianThroughput, summary.maxThroughput),
          integer(summary.databaseCalls),
          decimal(summary.databaseMs),
          baseline ? "기준" : percent(change("medianCallElapsedNanos")),
          baseline ? "기준" : percent(change("medianThroughputPerSecond")),
          baseline ? "기준" : percent(change("medianDatabaseExecutionTimeMs")),
        ].join(" | ")} |`,
      );
    }
  }
  return [
    "| 규모 | 제한 ID | 경로 | 후보 수 | 성공 | 실패 |" +
      " 호출 시간 최소/**중앙**/최대 (ms) | 전체 순회 최소/**중앙**/최대 (ms) |" +
      " 처리량 최소/**중앙**/최대 (ROOM/s) |" +
      " DB 호출 수 | DB 실행시간 중앙 (ms) | 현행 대비 시간 | 현행 대비 처리량 | 현행 대비 DB 시간 |",
    "| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ...rows,
  ].join("\n");
}

function waitingQueueTable(entries) {
  const rows = entries.map(({ descriptor, report }) => {
    const summary = summarizeArm(report.measuredRuns);
    return `| ${[
      descriptor.profile,
      descriptor.candidateLimit,
      report.fixture.waitingPerClosedDueRoom,
      integer(summary.candidateCount),
      integer(summary.successCount),
      summary.failureCount,
      range(summary.minMs, summary.medianMs, summary.maxMs),
      range(summary.minWholeTurnMs, summary.medianWholeTurnMs, summary.maxWholeTurnMs),
      range(summary.minThroughput, summary.medianThroughput, summary.maxThroughput),
      integer(summary.databaseCalls),
      decimal(summary.databaseMs),
    ].join(" | ")} |`;
  });
  return [
    "| 규모 | 제한 ID | ROOM당 WAITING | 후보 수 | 성공 | 실패 |" +
      " 호출 시간 최소/**중앙**/최대 (ms) | 전체 순회 최소/**중앙**/최대 (ms) |" +
      " 처리량 최소/**중앙**/최대 (ROOM/s) | DB 호출 수 | DB 실행시간 중앙 (ms) |",
    "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ...rows,
  ].join("\n");
}

function preservedDataTable(files) {
  const rows = files.map(
    ({ descriptor, text }) =>
      `| [\`${descriptor.fileName}\`](results/room-09d/${descriptor.fileName})` +
      ` | \`${canonicalSha256(text)}\` |`,
  );
  return ["| 파일 | SHA-256 |", "| --- | --- |", ...rows].join("\n");
}

/**
 * 문서의 생성 구역을 표시로 찾아 바꾼다. 표시가 없으면 어느 구역인지 알 수 없어 실패시킨다.
 * 생성 구역만 줄바꿈이 다르면 CRLF 체크아웃에서 `--check`가 늘 실패하므로 문서 쪽에 맞춘다.
 */
export function spliceGeneratedSection(document, name, content) {
  const start = `<!-- room09-report:${name}:start -->`;
  const end = `<!-- room09-report:${name}:end -->`;
  const pattern = new RegExp(`${start}[\\s\\S]*?${end}`);
  if (!pattern.test(document)) {
    throw new Error(`${REPORT_DOCUMENT}에 ${name} 생성 구역 표시가 없습니다.`);
  }
  const lineEnding = document.includes("\r\n") ? "\r\n" : "\n";
  const body = content.replaceAll("\n", lineEnding);
  return document.replace(pattern, `${start}${lineEnding}${body}${lineEnding}${end}`);
}

function loadPreservedData(rootDirectory) {
  const directory = path.join(rootDirectory, RESULT_DIRECTORY);
  return fs
    .readdirSync(directory)
    .filter((fileName) => fileName.endsWith(".json"))
    .map((fileName) => {
      const descriptor = describeFile(fileName);
      const filePath = path.join(directory, fileName);
      const original = fs.readFileSync(filePath, "utf8");
      const report = JSON.parse(original);
      assertReportMatchesDescriptor(report, descriptor);
      return {
        descriptor,
        filePath,
        original,
        text: applyReproductionFields(original, descriptor),
        report,
      };
    })
    .sort(
      (left, right) =>
        KIND_ORDER[left.descriptor.kind] - KIND_ORDER[right.descriptor.kind] ||
        PROFILE_ORDER[left.descriptor.profile] - PROFILE_ORDER[right.descriptor.profile] ||
        left.descriptor.candidateLimit - right.descriptor.candidateLimit,
    );
}

export function buildReport(rootDirectory) {
  const files = loadPreservedData(rootDirectory);
  const byKind = (kind) => files.filter((file) => file.descriptor.kind === kind);
  const documentPath = path.join(rootDirectory, REPORT_DOCUMENT);
  const originalDocument = fs.readFileSync(documentPath, "utf8");
  let document = originalDocument;
  document = spliceGeneratedSection(document, "comparison-table", comparisonTable(byKind("direct-comparison")));
  document = spliceGeneratedSection(document, "waiting-queue-table", waitingQueueTable(byKind("waiting-queue")));
  document = spliceGeneratedSection(document, "preserved-data-table", preservedDataTable(files));
  return { files, documentPath, originalDocument, document };
}

function main(argv) {
  const write = argv.includes("--write");
  if (!write && !argv.includes("--check")) {
    console.error("사용법: node scripts/room09-measurement-report.mjs [--check | --write]");
    return 2;
  }
  let built;
  try {
    built = buildReport(process.cwd());
  } catch (error) {
    // 원자료가 파일명과 다르면 파생물을 만들 근거가 없다. --check와 --write 모두 여기서 멈춘다.
    console.error(`보고 단계를 중단합니다: ${error.message}`);
    return 1;
  }
  const { files, documentPath, originalDocument, document } = built;
  const drifted = files.filter((file) => file.text !== file.original);
  const documentDrifted = document !== originalDocument;

  if (!write) {
    for (const file of drifted) {
      console.error(`원자료 재현 메타데이터가 어긋납니다: ${file.descriptor.fileName}`);
    }
    if (documentDrifted) {
      console.error(`문서의 생성 표가 원자료와 어긋납니다: ${REPORT_DOCUMENT}`);
    }
    if (drifted.length === 0 && !documentDrifted) {
      console.log(`원자료 ${files.length}개와 문서 생성 표가 모두 일치합니다.`);
      return 0;
    }
    return 1;
  }

  for (const file of drifted) {
    fs.writeFileSync(file.filePath, file.text);
    console.log(`재현 메타데이터 정정: ${file.descriptor.fileName}`);
  }
  if (documentDrifted) {
    fs.writeFileSync(documentPath, document);
    console.log(`생성 표 갱신: ${REPORT_DOCUMENT}`);
  }
  console.log(`원자료 ${files.length}개 기준으로 파생물을 다시 만들었습니다. 측정값은 바꾸지 않았습니다.`);
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main(process.argv.slice(2));
}
