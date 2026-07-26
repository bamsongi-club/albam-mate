#!/usr/bin/env node

import { createHash, randomUUID } from "node:crypto";
import {
  appendFile,
  mkdir,
  open,
  readFile,
  realpath,
  rename,
  stat,
  unlink,
  writeFile,
} from "node:fs/promises";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const MINIMUM_NODE_MAJOR = 20;
const MEMBER_ENV = "BAMSONGI_MEMBER";
const BRAIN_ROOT_ENV = "BAMSONGI_BRAIN_ROOT";
const SUPPORTED_TOOLS = new Set(["codex", "claude"]);
const LOCK_WAIT_MS = 3_000;
const LOCK_STALE_MS = 15_000;
const CODEX_INTERNAL_SUGGESTION_MARKERS = [
  "Generate 0 to 3 hyperpersonalized suggestions for what this user can do with Codex",
  "Recent Codex tasks in this project:",
  "# Response format",
];

function warning(message) {
  process.stdout.write(
    JSON.stringify({
      systemMessage: `[bamsongi prompt logger] ${message}`,
    }),
  );
}

function assertSupportedNode() {
  const major = Number.parseInt(process.versions.node.split(".")[0], 10);
  if (!Number.isInteger(major) || major < MINIMUM_NODE_MAJOR) {
    throw new Error(`Node.js ${MINIMUM_NODE_MAJOR} 이상이 필요합니다.`);
  }
}

function getTool(argv) {
  const optionIndex = argv.indexOf("--tool");
  const tool = optionIndex >= 0 ? argv[optionIndex + 1] : undefined;

  if (!SUPPORTED_TOOLS.has(tool)) {
    throw new Error("--tool은 codex 또는 claude여야 합니다.");
  }

  return tool;
}

function getMember() {
  const member = process.env[MEMBER_ENV];

  if (!member) {
    throw new Error(`${MEMBER_ENV} 환경변수를 먼저 설정해 주세요.`);
  }

  if (!/^[\p{L}\p{N}][\p{L}\p{N}._-]{0,63}$/u.test(member)) {
    throw new Error(
      `${MEMBER_ENV}에는 1~64자의 이름(문자, 숫자, 점, 밑줄, 하이픈)만 사용할 수 있습니다.`,
    );
  }

  if (/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/iu.test(member)) {
    throw new Error(`${MEMBER_ENV} 값은 운영체제 예약 이름일 수 없습니다.`);
  }

  return member;
}

function isGeneratedAgentPrompt(event) {
  return typeof event?.agent_id === "string" && event.agent_id.length > 0;
}

function isCodexInternalSuggestionPrompt(tool, prompt) {
  if (tool !== "codex") {
    return false;
  }

  const normalized = prompt.replace(/^\uFEFF/u, "").replace(/\r\n?|\r/gu, "\n").trimStart();
  return (
    normalized.startsWith("# Overview") &&
    CODEX_INTERNAL_SUGGESTION_MARKERS.every((marker) => normalized.includes(marker))
  );
}

function shouldIgnorePrompt(tool, event) {
  return (
    isGeneratedAgentPrompt(event) ||
    isCodexInternalSuggestionPrompt(tool, event.prompt)
  );
}

async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString("utf8");
}

function isInside(parent, child) {
  const relative = path.relative(parent, child);
  return (
    relative === "" ||
    (!relative.startsWith(`..${path.sep}`) &&
      relative !== ".." &&
      !path.isAbsolute(relative))
  );
}

function localDate(now) {
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function promptMarker(tool, sessionId, turnId, prompt) {
  const digest = createHash("sha256")
    .update(JSON.stringify([tool, sessionId, String(turnId), prompt]), "utf8")
    .digest("hex")
    .slice(0, 20);
  return `<!-- bamsongi-prompt:${digest} -->`;
}

function nextSequence(markdown) {
  let maximum = 0;
  for (const match of markdown.matchAll(/^##[ \t]+(\d+)[ \t]*$/gmu)) {
    maximum = Math.max(maximum, Number.parseInt(match[1], 10));
  }
  return maximum + 1;
}

function blockquote(prompt) {
  return prompt
    .split(/\r\n|\n|\r/u)
    .map((line) => (line ? `> ${line}` : ">"))
    .join("\n");
}

async function resolvePromptsRoot(projectRoot) {
  const configuredRoot = process.env[BRAIN_ROOT_ENV]?.trim();
  const candidate = configuredRoot
    ? path.resolve(projectRoot, configuredRoot)
    : path.resolve(projectRoot, "..", "bamsongi-brain");

  let brainRoot;
  try {
    brainRoot = await realpath(candidate);
  } catch {
    throw new Error(
      `bamsongi-brain을 찾을 수 없습니다: ${candidate}. 기본 위치가 아니면 ${BRAIN_ROOT_ENV}를 설정해 주세요.`,
    );
  }

  const promptsRoot = await realpath(path.join(brainRoot, "prompts")).catch(
    () => null,
  );
  if (!promptsRoot) {
    throw new Error(`저장 폴더가 없습니다: ${path.join(brainRoot, "prompts")}`);
  }

  const promptsRootStat = await stat(promptsRoot);
  if (!promptsRootStat.isDirectory() || !isInside(brainRoot, promptsRoot)) {
    throw new Error("prompts 경로가 bamsongi-brain 내부의 폴더가 아닙니다.");
  }

  return promptsRoot;
}

async function resolveMemberDirectory(promptsRoot, member) {
  const memberDirectory = path.join(promptsRoot, member);
  await mkdir(memberDirectory, { recursive: true });
  const resolvedMemberDirectory = await realpath(memberDirectory);

  if (!isInside(promptsRoot, resolvedMemberDirectory)) {
    throw new Error("팀원 저장 경로가 prompts 바깥을 가리킵니다.");
  }

  return resolvedMemberDirectory;
}

async function recoverStaleLock(lockPath) {
  const lockStat = await stat(lockPath).catch(() => null);
  if (!lockStat || Date.now() - lockStat.mtimeMs <= LOCK_STALE_MS) {
    return false;
  }

  const stalePath = `${lockPath}.stale-${randomUUID()}`;
  try {
    await rename(lockPath, stalePath);
    await unlink(stalePath).catch(() => undefined);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT" || error?.code === "EACCES" || error?.code === "EPERM") {
      return false;
    }
    throw error;
  }
}

async function withDailyLock(memberDirectory, date, callback) {
  const lockPath = path.join(memberDirectory, `.${date}.prompt-logger.lock`);
  const owner = randomUUID();
  const deadline = Date.now() + LOCK_WAIT_MS;
  let lockHandle;

  while (!lockHandle) {
    let candidateHandle;
    try {
      candidateHandle = await open(lockPath, "wx");
    } catch (error) {
      if (error?.code !== "EEXIST") {
        throw error;
      }

      if (await recoverStaleLock(lockPath)) {
        continue;
      }

      if (Date.now() >= deadline) {
        throw new Error("일별 프롬프트 파일 잠금을 기다리다 시간이 초과되었습니다.");
      }
      await delay(50);
      continue;
    }

    try {
      await candidateHandle.writeFile(owner, "utf8");
      lockHandle = candidateHandle;
    } catch (error) {
      await candidateHandle.close().catch(() => undefined);
      await unlink(lockPath).catch(() => undefined);
      throw error;
    }
  }

  try {
    return await callback();
  } finally {
    await lockHandle.close();
    const currentOwner = await readFile(lockPath, "utf8").catch(() => null);
    if (currentOwner === owner) {
      await unlink(lockPath).catch(() => undefined);
    }
  }
}

async function readMarkdown(dailyPath) {
  try {
    return await readFile(dailyPath, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") {
      return null;
    }
    throw error;
  }
}

function entryMarkdown(sequence, marker, prompt) {
  const label = String(sequence).padStart(3, "0");
  return `${marker}\n## ${label}\n\n${blockquote(prompt)}\n`;
}

async function appendPrompt(dailyPath, date, marker, prompt) {
  let markdown = await readMarkdown(dailyPath);
  if (markdown?.includes(marker)) {
    return;
  }

  if (markdown === null || markdown.length === 0) {
    const initial = `# ${date}\n\n---\n\n${entryMarkdown(1, marker, prompt)}`;
    if (markdown === "") {
      await appendFile(dailyPath, initial, "utf8");
      return;
    }

    try {
      await writeFile(dailyPath, initial, { encoding: "utf8", flag: "wx" });
      return;
    } catch (error) {
      if (error?.code !== "EEXIST") {
        throw error;
      }
      markdown = await readFile(dailyPath, "utf8");
      if (markdown.includes(marker)) {
        return;
      }
    }
  }

  const separator = markdown.endsWith("\n\n")
    ? ""
    : markdown.endsWith("\n")
      ? "\n"
      : "\n\n";
  await appendFile(
    dailyPath,
    `${separator}${entryMarkdown(nextSequence(markdown), marker, prompt)}`,
    "utf8",
  );
}

async function main() {
  assertSupportedNode();
  const tool = getTool(process.argv.slice(2));
  const rawInput = await readStdin();

  let event;
  try {
    event = JSON.parse(rawInput);
  } catch {
    throw new Error("훅 입력이 올바른 JSON이 아닙니다.");
  }

  if (event?.hook_event_name !== "UserPromptSubmit" || typeof event.prompt !== "string") {
    throw new Error("UserPromptSubmit 프롬프트 입력을 찾을 수 없습니다.");
  }

  if (typeof event.session_id !== "string" || !event.session_id) {
    throw new Error("세션 ID를 찾을 수 없습니다.");
  }

  if (shouldIgnorePrompt(tool, event)) {
    return;
  }

  const member = getMember();

  const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
  const projectRoot = path.resolve(scriptDirectory, "..", "..");
  const promptsRoot = await resolvePromptsRoot(projectRoot);
  const memberDirectory = await resolveMemberDirectory(promptsRoot, member);
  const date = localDate(new Date());
  const dailyPath = path.join(memberDirectory, `${date}.md`);

  if (!isInside(promptsRoot, dailyPath)) {
    throw new Error("최종 저장 경로가 prompts 바깥을 가리킵니다.");
  }

  const turnId = event.turn_id ?? event.prompt_id ?? randomUUID();
  const marker = promptMarker(tool, event.session_id, turnId, event.prompt);
  await withDailyLock(memberDirectory, date, () =>
    appendPrompt(dailyPath, date, marker, event.prompt),
  );
}

try {
  await main();
} catch (error) {
  warning(error instanceof Error ? error.message : String(error));
}
