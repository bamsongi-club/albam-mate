#!/usr/bin/env node

// 게임 카탈로그 설명 필드를 한국어로 번역해 적재 입력 JSON을 만든다.
// 번역 대상은 BGG rank 상위 --limit 건이며, 결과는 prepare-game-catalog.mjs의 --games 입력으로 넣는다.
// Message Batches API를 사용해 표준 요금 대비 50% 저렴하게 처리한다.

import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

import { parseRankRows } from "./catalog-analysis.mjs";

const API_BASE = "https://api.anthropic.com";
const ANTHROPIC_VERSION = "2023-06-01";
const DEFAULT_MODEL = "claude-sonnet-5";
const DESCRIPTION_FIELDS = ["description", "detail_description"];
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

const SYSTEM_PROMPT = [
    "너는 보드게임 카탈로그의 영문 설명을 한국어로 옮기는 번역가다.",
    "",
    "규칙:",
    "- 원문의 사실·규칙·수치·승리 조건을 하나도 바꾸거나 빠뜨리지 않는다.",
    "- 게임 이름과 확장판 이름은 원문 표기를 그대로 둔다. 임의로 한글 이름을 지어내지 않는다.",
    "- 보드게임 용어는 국내에서 통용되는 표현을 쓴다(deck building → 덱 빌딩, worker placement → 일꾼 놓기).",
    "- 홍보 문구를 덧붙이거나 원문에 없는 평가를 넣지 않는다.",
    "- 영문 단어를 그대로 남기지 않는다. 고유명사가 아니면 한국어로 옮긴다.",
    "- 원문이 문장 하나면 번역도 문장 하나로 유지한다.",
].join("\n");

main().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
});

async function main() {
    const options = parseOptions(process.argv.slice(2));
    const games = readJson(options.games);
    const selected = selectTopRanked(games, options.ranks, options.limit);

    process.stderr.write(
        `번역 대상 ${selected.length}건 (전체 ${games.length}건 중 BGG rank 상위 ${options.limit})\n`,
    );
    process.stderr.write(`예상 입력 문자 수 ${countCharacters(selected).toLocaleString()}자\n`);

    if (options.dryRun) {
        process.stderr.write("상위 5건: ");
        process.stderr.write(
            `${selected.slice(0, 5).map(({ bgg_id, name }) => `${name}(${bgg_id})`).join(", ")}\n`,
        );
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

function selectTopRanked(games, ranksPath, limit) {
    const rankByBggId = new Map();
    for (const row of parseRankRows(readFileSync(ranksPath, "utf8"))) {
        const rank = Number(row.rank);
        if (Number.isSafeInteger(rank) && rank > 0) {
            rankByBggId.set(String(row.id), rank);
        }
    }

    return games
        .map((game) => ({ game, rank: rankByBggId.get(String(game.bgg_id)) }))
        .filter(({ rank }) => rank !== undefined)
        .sort((left, right) => left.rank - right.rank)
        .slice(0, limit)
        .map(({ game }) => game);
}

function buildRequest(game, model) {
    const source = Object.fromEntries(
        DESCRIPTION_FIELDS.map((field) => [field, String(game[field] ?? "")]),
    );
    const characters = Object.values(source).join("").length;
    return {
        custom_id: `game-${game.bgg_id}`,
        params: {
            model,
            max_tokens: Math.min(8000, Math.max(1024, Math.ceil(characters / 2))),
            system: SYSTEM_PROMPT,
            output_config: { format: { type: "json_schema", schema: TRANSLATION_SCHEMA } },
            messages: [
                {
                    role: "user",
                    content: [
                        `게임 이름: ${game.name} (${game.english_name})`,
                        "",
                        "아래 두 필드를 한국어로 옮겨라.",
                        "",
                        JSON.stringify(source, null, 2),
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

function mergeTranslations(games, translations) {
    const merged = [];
    const missing = [];
    for (const game of games) {
        const translation = translations.get(`game-${game.bgg_id}`);
        if (!translation) {
            missing.push(game.bgg_id);
            continue;
        }
        merged.push({
            ...game,
            description: translation.description,
            detail_description: translation.detail_description,
        });
    }
    if (missing.length > 0) {
        process.stderr.write(
            `번역이 없어 ${missing.length}건을 제외했습니다. 재실행하면 남은 건만 다시 시도합니다.\n`,
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
    return games.reduce(
        (total, game) =>
            total + DESCRIPTION_FIELDS.reduce((sum, field) => sum + String(game[field] ?? "").length, 0),
        0,
    );
}

function readJson(path) {
    return JSON.parse(readFileSync(path, "utf8"));
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

    if (!values.games || !values.ranks || !values.out) {
        failUsage();
    }

    return {
        games: resolve(values.games),
        ranks: resolve(values.ranks),
        out: resolve(values.out),
        limit: Number(values.limit ?? 5000),
        model: values.model ?? DEFAULT_MODEL,
        batchId: values["batch-id"] ?? null,
        dryRun: flags.has("dry-run"),
    };
}

function failUsage() {
    process.stderr.write(
        [
            "사용법: node translate-descriptions.mjs --games <json> --ranks <csv> --out <json>",
            "        [--limit 5000] [--model claude-sonnet-5] [--batch-id <id>] [--dry-run]",
            "",
            "ANTHROPIC_API_KEY 환경 변수가 필요하다. --dry-run은 대상 건수와 분량만 보고한다.",
            "",
        ].join("\n"),
    );
    process.exit(2);
}
