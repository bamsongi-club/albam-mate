#!/usr/bin/env node

// 승인된 범위의 BGG 영문 원문을 한국어로 번역해 검수 입력 JSON을 만든다.
// 승인 manifest와 description provenance가 없으면 API를 호출하지 않는다.
//
//   extract-bgg-descriptions.mjs → 이 스크립트 → export-korean-descriptions.mjs → UPSERT SQL
//
// Message Batches API를 사용해 표준 요금 대비 50% 저렴하게 처리한다.

import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { basename, resolve } from "node:path";

import { validateApprovedReleaseManifest } from "./catalog-release-manifest.mjs";

const API_BASE = "https://api.anthropic.com";
const ANTHROPIC_VERSION = "2023-06-01";
const DEFAULT_MODEL = "claude-sonnet-5";
const POLL_INTERVAL_MS = 30_000;

const TRANSLATION_SCHEMA = {
    type: "object",
    properties: {
        description: { type: "string" },
        detail_description: { type: "string" },
    },
    required: ["description", "detail_description"],
    additionalProperties: false,
};

// 승인된 범위의 원문을 사실 보존 방식으로 번역한다.
const SYSTEM_PROMPT = [
    "너는 승인된 보드게임 설명을 한국어로 옮기는 번역가다.",
    "원문의 사실, 규칙, 수치, 승리 조건을 바꾸거나 빠뜨리지 않는다.",
    "",
    "BGG 원문은 대개 세계관 설명이 길고 산만하며 출판사 홍보 문구와 수상 이력이 섞여 있다.",
    "원문에 없는 평가·추천·요약·재작성·홍보 문구를 추가하지 않는다.",
    "",
    "## description (간단설명)",
    "- 1~2문장.",
    "- 첫 문장은 플레이어가 무엇을 하는 게임인지로 시작한다. 배경이나 세계관으로 시작하지 않는다.",
    "- 구성 요소를 낱낱이 나열하지 않는다. '일곱 지형'처럼 묶어서 쓴다.",
    "",
    "## detail_description (상세설명)",
    "- 3~4문단. 문단 사이는 빈 줄 하나로 구분한다.",
    "- 자기 차례에 실제로 무엇을 하는지, 그리고 어떻게 이기는지를 구체적으로 쓴다.",
    "- 원문의 문단 순서와 사실 관계를 임의로 바꾸지 않는다.",
    "- 세계관과 배경은 첫 문단에 한두 문장이면 충분하다.",
    "",
    "## 용어",
    "- meeple → 말",
    "- worker placement → 일꾼 놓기",
    "- deck building → 덱 빌딩",
    "- tile placement → 타일 배치",
    "- victory point → 승점",
    "- faction → 실제로 종이 다르면 '종족', 이념이나 목적이 다르면 '세력'",
    "- 국가·민족 이름은 음역하지 말고 뜻으로 옮긴다(Dutch → 네덜란드).",
    "",
    "## 쓰지 않는 것",
    "- 출판사 홍보 문구, 수상 이력, 판본 발매 이력, 구성물 개수 나열",
    "- '—description from the publisher' 같은 출처 표기",
    "- 원문에 없는 평가나 추천",
    "- 게임 이름 뒤의 (2판) 같은 판본 표기",
    "",
    "## 문체 예시",
    "아래는 같은 기준으로 쓴 결과물이다. 길이와 어조를 여기에 맞춘다.",
    "",
    "[윙스팬]",
    "description: 새 카드를 자기 서식지에 놓아 먹이·알·카드를 끌어오는 연쇄를 만드는 엔진 빌딩 게임입니다. 4라운드 동안 가장 좋은 조합을 완성한 사람이 이깁니다.",
    "detail_description:",
    "플레이어는 자기 보호구역에 최고의 새들을 모으려는 조류 연구가가 됩니다. 개인 보드는 숲·초원·습지 세 서식지로 나뉘고, 매 턴 서식지 하나를 골라 그 줄의 행동을 실행합니다. 숲은 새 모이통 주사위를 굴려 먹이를 얻고, 초원은 알을 낳고, 습지는 새 카드를 뽑습니다.",
    "",
    "핵심은 이미 놓아 둔 새들이 그 줄의 행동을 강화한다는 점입니다. 서식지에 새를 많이 놓을수록 기본 산출량이 늘고, 각 새 카드에 적힌 능력이 줄줄이 발동해 한 번의 행동이 훨씬 큰 이득으로 돌아옵니다.",
    "",
    "게임은 4라운드로 진행되며 라운드마다 쓸 수 있는 행동 수가 줄어듭니다. 라운드가 끝날 때마다 공개된 라운드 목표를 비교해 점수를 나눠 갖습니다. 새 카드 점수, 낳은 알, 모아 둔 먹이와 카드, 보너스 카드 조건을 모두 합해 가장 높은 점수를 낸 사람이 승리합니다.",
    "",
    "[러브 레터]",
    "description: 손에 카드 한 장만 들고, 뽑은 카드와 둘 중 하나를 내며 상대를 탈락시키는 짧은 추리 게임입니다. 한 판이 몇 분이면 끝납니다.",
    "detail_description:",
    "공주에게 편지를 전하려는 인물들이 되어 서로를 밀어냅니다. 각자 카드 한 장만 손에 들고 시작합니다. 자기 차례에 덱에서 한 장을 뽑아 두 장이 되면 그중 하나를 내려놓고 그 카드의 효과를 실행합니다.",
    "",
    "카드에는 숫자와 능력이 있습니다. 병사는 상대의 손패를 맞히면 즉시 탈락시키고, 남작은 서로 손패를 비교해 낮은 쪽을 떨어뜨립니다. 어떤 카드가 몇 장씩 들어 있는지 다 알려져 있으므로, 나온 카드를 세는 것만으로 상대의 손패를 좁혀 갈 수 있습니다.",
    "",
    "한 명만 남거나 덱이 떨어지면 라운드가 끝나고, 마지막까지 남은 사람 중 가장 높은 숫자를 든 사람이 호감 토큰을 받습니다. 정해진 개수의 토큰을 먼저 모으면 승리합니다.",
].join("\n");

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split("/").pop())) {
    main().catch((error) => {
        process.stderr.write(`${error.message}\n`);
        process.exitCode = 1;
    });
}

export async function main() {
    const options = parseOptions(process.argv.slice(2));
    const sourceContents = readFileSync(options.source, "utf8");
    const source = JSON.parse(sourceContents);
    const manifest = readJson(options.manifest);
    assertTranslationApproval(manifest, {
        actualDescriptionInput: {
            fileName: basename(options.source),
            sha256: sha256(sourceContents),
            rows: source.length,
        },
    });
    const done = options.skip ? new Set(readJson(options.skip).map(({ bgg_id }) => Number(bgg_id))) : new Set();
    const selected = source
        .filter(({ bgg_id }) => !done.has(Number(bgg_id)))
        .slice(0, options.limit ?? undefined);

    process.stderr.write(
        `번역 대상 ${selected.length}건 (입력 ${source.length}건, 완료 제외 ${done.size}건)\n`,
    );
    process.stderr.write(`입력 원문 ${countCharacters(selected).toLocaleString()}자\n`);

    if (options.dryRun) {
        process.stderr.write(
            `앞 5건: ${selected.slice(0, 5).map(({ bgg_id, english_name }) => `${english_name}(${bgg_id})`).join(", ")}\n`,
        );
        if (selected.length > 0) {
            // 배치를 넣기 전에 실제 전송 본문을 눈으로 확인할 수 있게 첫 요청을 남긴다.
            const sample = buildRequest(selected[0], options.model);
            const samplePath = `${options.out}.request-sample.json`;
            writeFileSync(samplePath, `${JSON.stringify(sample, null, 2)}\n`, "utf8");
            process.stderr.write(`요청 예시를 ${samplePath} 에 저장했습니다.\n`);
        }
        process.stderr.write("--dry-run 이므로 API를 호출하지 않고 종료합니다.\n");
        return;
    }

    const apiKey = requireApiKey();
    const batchId = options.batchId ?? (await submitBatch(selected, options, apiKey));
    if (!options.batchId) {
        process.stderr.write(`배치를 만들었습니다: ${batchId}\n`);
        process.stderr.write(`중단되면 --batch-id ${batchId} 로 이어서 받을 수 있습니다.\n`);
    }

    const resultsUrl = await waitForBatch(batchId, apiKey);
    const translations = await collectResults(resultsUrl, apiKey);
    const merged = mergeTranslations(selected, translations);

    writeFileSync(options.out, `${JSON.stringify(merged, null, 2)}\n`, "utf8");
    process.stderr.write(`${merged.length}건을 ${options.out} 에 저장했습니다.\n`);
}

function buildRequest(game, model) {
    return {
        custom_id: `game-${game.bgg_id}`,
        params: {
            model,
            // 출력은 원문 길이와 무관하게 한국어 600자 안팎이다. 넉넉히 잡아도 실제 출력만 과금된다.
            max_tokens: 4000,
            // 문체 예시가 매 요청 반복되므로 캐시해 입력 비용을 줄인다.
            system: [{ type: "text", text: SYSTEM_PROMPT, cache_control: { type: "ephemeral" } }],
            // 규칙 파악이 목적이라 긴 추론이 필요 없다. thinking을 끄면 출력 토큰이 절반이 된다.
            thinking: { type: "disabled" },
            output_config: {
                effort: "medium",
                format: { type: "json_schema", schema: TRANSLATION_SCHEMA },
            },
            messages: [
                {
                    role: "user",
                    content: [
                        `게임 이름: ${game.english_name}`,
                        "",
                        "아래 승인된 BGG 원문을 사실을 보존해 한국어로 번역하라.",
                        "",
                        game.source,
                    ].join("\n"),
                },
            ],
        },
    };
}

async function submitBatch(games, { model }, apiKey) {
    const response = await callApi("/v1/messages/batches", apiKey, {
        method: "POST",
        body: JSON.stringify({ requests: games.map((game) => buildRequest(game, model)) }),
    });
    return response.id;
}

async function waitForBatch(batchId, apiKey) {
    for (;;) {
        const batch = await callApi(`/v1/messages/batches/${batchId}`, apiKey);
        const counts = batch.request_counts ?? {};
        process.stderr.write(
            `상태 ${batch.processing_status} · 성공 ${counts.succeeded ?? 0} · 처리 중 ${counts.processing ?? 0} · 실패 ${counts.errored ?? 0}\n`,
        );
        if (batch.processing_status === "ended") {
            if (!batch.results_url) {
                throw new Error("배치가 끝났지만 results_url이 없습니다.");
            }
            return batch.results_url;
        }
        await sleep(POLL_INTERVAL_MS);
    }
}

async function collectResults(resultsUrl, apiKey) {
    const response = await fetch(resultsUrl, { headers: apiHeaders(apiKey) });
    if (!response.ok) {
        throw new Error(`배치 결과를 받지 못했습니다: HTTP ${response.status}`);
    }

    const translations = new Map();
    const failures = [];
    for (const line of (await response.text()).split("\n")) {
        if (!line.trim()) {
            continue;
        }
        const entry = JSON.parse(line);
        if (entry.result?.type !== "succeeded") {
            failures.push(`${entry.custom_id}: ${entry.result?.type ?? "unknown"}`);
            continue;
        }
        const message = entry.result.message;
        if (message.stop_reason === "refusal" || message.stop_reason === "max_tokens") {
            failures.push(`${entry.custom_id}: stop_reason=${message.stop_reason}`);
            continue;
        }
        const text = message.content.find(({ type }) => type === "text")?.text;
        if (!text) {
            failures.push(`${entry.custom_id}: 응답에 text 블록이 없습니다`);
            continue;
        }
        translations.set(entry.custom_id, JSON.parse(text));
    }

    if (failures.length > 0) {
        process.stderr.write(`실패 ${failures.length}건:\n`);
        for (const failure of failures.slice(0, 20)) {
            process.stderr.write(`  ${failure}\n`);
        }
    }
    return translations;
}

// 결과는 export-korean-descriptions.mjs 가 그대로 받는 형태로 낸다.
function mergeTranslations(games, translations) {
    const merged = [];
    const missing = [];
    for (const game of games) {
        const written = translations.get(`game-${game.bgg_id}`);
        if (!written) {
            missing.push(game.bgg_id);
            continue;
        }
        merged.push({
            bgg_id: game.bgg_id,
            english_name: game.english_name,
            description: written.description,
            detail_description: written.detail_description,
        });
    }
    if (missing.length > 0) {
        process.stderr.write(
            `결과가 없어 ${missing.length}건을 제외했습니다. --skip 에 이 결과를 넣고 다시 실행하면 남은 건만 시도합니다.\n`,
        );
    }
    return merged;
}

async function callApi(path, apiKey, init = {}) {
    const response = await fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...apiHeaders(apiKey), "content-type": "application/json" },
    });
    if (!response.ok) {
        throw new Error(`${path} 요청이 실패했습니다: HTTP ${response.status} ${await response.text()}`);
    }
    return response.json();
}

function apiHeaders(apiKey) {
    return { "x-api-key": apiKey, "anthropic-version": ANTHROPIC_VERSION };
}

function requireApiKey() {
    const apiKey = process.env.ANTHROPIC_API_KEY;
    if (!apiKey) {
        throw new Error("ANTHROPIC_API_KEY 환경 변수가 필요합니다.");
    }
    return apiKey;
}

function countCharacters(games) {
    return games.reduce((total, game) => total + String(game.source ?? "").length, 0);
}

export function assertTranslationApproval(manifest, { actualDescriptionInput } = {}) {
    try {
        validateApprovedReleaseManifest(manifest, {
            actualDescriptionInput,
            requiredProcessingScopes: ['description-translation'],
        });
    } catch (error) {
        throw new Error(
            `승인된 description-translation release manifest가 필요하다: ${error.message}`,
            { cause: error },
        );
    }
    if (!manifest.approvedFields?.includes("description") ||
        !manifest.approvedFields?.includes("detail_description")) {
        throw new Error("description·detail_description이 approvedFields에 없습니다.");
    }
}

function readJson(path) {
    return JSON.parse(readFileSync(path, "utf8"));
}

function sha256(value) {
    return createHash("sha256").update(value).digest("hex");
}

function sleep(milliseconds) {
    return new Promise((done) => setTimeout(done, milliseconds));
}

function parseOptions(args) {
    const values = {};
    const flags = new Set();
    for (let index = 0; index < args.length; index += 1) {
        const key = args[index];
        if (key === "--dry-run") {
            flags.add("dry-run");
            continue;
        }
        const value = args[index + 1];
        if (!key?.startsWith("--") || !value) {
            failUsage();
        }
        values[key.slice(2)] = value;
        index += 1;
    }

    if (!values.source || !values.manifest || !values.out) {
        failUsage();
    }

    return {
        source: resolve(values.source),
        manifest: resolve(values.manifest),
        out: resolve(values.out),
        skip: values.skip ? resolve(values.skip) : null,
        limit: values.limit ? Number(values.limit) : null,
        model: values.model ?? DEFAULT_MODEL,
        batchId: values["batch-id"] ?? null,
        dryRun: flags.has("dry-run"),
    };
}

function failUsage() {
    process.stderr.write(
        [
            "사용법: node translate-descriptions.mjs --source <json> --manifest <json> --out <json>",
            "        [--skip <json>] [--limit N] [--model claude-sonnet-5] [--batch-id <id>] [--dry-run]",
            "",
            "--source 는 extract-bgg-descriptions.mjs 가 만든 무손상 원문 JSON이다.",
            "--manifest 는 description-translation 처리가 명시적으로 승인된 release manifest이다.",
            "--skip 에 이미 작성한 결과 JSON을 주면 그 bgg_id를 건너뛴다.",
            "ANTHROPIC_API_KEY 환경 변수가 필요하다. --dry-run은 대상 건수와 분량만 보고한다.",
            "",
        ].join("\n"),
    );
    process.exit(2);
}
