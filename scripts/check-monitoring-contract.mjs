import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const RUNBOOK_PATH = "docs/guides/MONITORING_OPERATIONS.md";
const CHAT_LISTENER_PATH =
  "src/main/java/cloud/bamsongi/albammate/chat/service/ChatMessageCommittedListener.java";

export function validateMonitoringContract({ runbookText, chatListenerText }) {
  const problems = [];
  const sourceEvent =
    /event=chat_realtime_publish_failed[^"\r\n]*exceptionType=\{\}/.test(
      chatListenerText,
    );
  if (!sourceEvent) {
    problems.push(
      `${CHAT_LISTENER_PATH}: chat_realtime_publish_failed는 exceptionType key를 기록해야 합니다.`,
    );
  }

  const inventoryRow = runbookText
    .split(/\r?\n/)
    .find((line) => line.startsWith("| `chat_realtime_publish_failed` |"));
  if (!inventoryRow) {
    problems.push(`${RUNBOOK_PATH}: chat_realtime_publish_failed 허용 행이 없습니다.`);
  } else {
    if (!inventoryRow.includes("`exceptionType`")) {
      problems.push(
        `${RUNBOOK_PATH}: chat_realtime_publish_failed 허용 field는 exceptionType이어야 합니다.`,
      );
    }
    if (inventoryRow.includes("`exceptionClass`")) {
      problems.push(
        `${RUNBOOK_PATH}: chat_realtime_publish_failed 행에 exceptionClass를 허용할 수 없습니다.`,
      );
    }
  }

  return problems;
}

export function runCheck(repoRoot) {
  return validateMonitoringContract({
    runbookText: fs.readFileSync(path.join(repoRoot, RUNBOOK_PATH), "utf8"),
    chatListenerText: fs.readFileSync(
      path.join(repoRoot, CHAT_LISTENER_PATH),
      "utf8",
    ),
  });
}

function main() {
  const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
  const repoRoot = path.resolve(scriptDirectory, "..");
  const problems = runCheck(repoRoot);
  if (problems.length > 0) {
    for (const problem of problems) {
      console.error(problem);
    }
    process.exitCode = 1;
    return;
  }
  console.log("운영 log 허용 field와 생산 event key가 일치한다.");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
