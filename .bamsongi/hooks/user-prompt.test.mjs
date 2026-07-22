import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, readdir, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, test } from "node:test";
import { fileURLToPath } from "node:url";

const LOGGER_PATH = fileURLToPath(new URL("./user-prompt.mjs", import.meta.url));
const temporaryRoots = [];

afterEach(async () => {
  await Promise.all(
    temporaryRoots.splice(0).map((root) => rm(root, { recursive: true, force: true })),
  );
});

async function createBrainRoot() {
  const brainRoot = await mkdtemp(path.join(os.tmpdir(), "albam-mate-prompt-logger-"));
  temporaryRoots.push(brainRoot);
  await mkdir(path.join(brainRoot, "prompts"), { recursive: true });
  return brainRoot;
}

function runLogger({ brainRoot, prompt, tool = "codex", event = {} }) {
  return spawnSync(process.execPath, [LOGGER_PATH, "--tool", tool], {
    encoding: "utf8",
    env: {
      ...process.env,
      BAMSONGI_BRAIN_ROOT: brainRoot,
      BAMSONGI_MEMBER: "test-member",
    },
    input: JSON.stringify({
      hook_event_name: "UserPromptSubmit",
      prompt,
      session_id: "test-session",
      turn_id: "test-turn",
      ...event,
    }),
  });
}

async function savedMarkdown(brainRoot) {
  const memberRoot = path.join(brainRoot, "prompts", "test-member");
  const files = await readdir(memberRoot);
  assert.equal(files.length, 1);
  return readFile(path.join(memberRoot, files[0]), "utf8");
}

async function assertNothingSaved(brainRoot) {
  const promptsRoot = path.join(brainRoot, "prompts");
  assert.deepEqual(await readdir(promptsRoot), []);
}

test("사용자가 직접 입력한 프롬프트는 저장한다", async () => {
  const brainRoot = await createBrainRoot();
  const prompt = "회원가입 실패 원인을 확인해줘";

  const result = runLogger({ brainRoot, prompt });

  assert.equal(result.status, 0, result.stderr);
  assert.match(await savedMarkdown(brainRoot), new RegExp(prompt));
});

test("Codex 내부 추천 생성 프롬프트는 저장하지 않는다", async () => {
  const brainRoot = await createBrainRoot();
  const prompt = `# Overview

Generate 0 to 3 hyperpersonalized suggestions for what this user can do with Codex in this local project: C:\\repo

# Rules

Recent Codex tasks in this project:
[]

# Response format
Return JSON.`;

  const result = runLogger({ brainRoot, prompt });

  assert.equal(result.status, 0, result.stderr);
  await assertNothingSaved(brainRoot);
});

test("서브에이전트가 만든 프롬프트는 저장하지 않는다", async () => {
  const brainRoot = await createBrainRoot();

  const result = runLogger({
    brainRoot,
    prompt: "모델이 만든 하위 작업 지시",
    event: {
      agent_id: "agent-1",
      agent_type: "worker",
    },
  });

  assert.equal(result.status, 0, result.stderr);
  await assertNothingSaved(brainRoot);
});
