#!/usr/bin/env node

import fs from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import process from "node:process";

const REQUIRED_QUERY_IDS = new Set(["Q-010", "Q-011", "Q-012"]);
const REQUIRED_HIDDEN_FIELDS = Object.freeze(["modelId", "score", "sourceRank", "sourceModel"]);

export function buildGoldJudgementPacket({ blind, searchText, sources = undefined }) {
    assertObject(blind, "blindJudgement");
    if (blind.schemaVersion !== 1 || blind.kind !== "search-04-blind-judgement") {
        fail("blindJudgement 형식이 올바르지 않습니다.");
    }
    if (!Array.isArray(blind.queries) || blind.queries.length !== REQUIRED_QUERY_IDS.size) {
        fail("blindJudgement는 Q-010~Q-012 세 query를 가져야 합니다.");
    }
    assertObject(searchText, "searchText");
    if (!Array.isArray(searchText.games) || searchText.games.length !== 1000) {
        fail("searchText는 Top 1,000 games를 가져야 합니다.");
    }

    const gameById = new Map();
    for (const game of searchText.games) {
        if (!Number.isSafeInteger(game.gameId) || game.gameId < 1 || typeof game.searchText !== "string") {
            fail("searchText game row가 올바르지 않습니다.");
        }
        if (gameById.has(game.gameId)) fail(`searchText gameId가 중복되었습니다: ${game.gameId}`);
        gameById.set(game.gameId, game.searchText);
    }

    const seenQueryIds = new Set();
    for (const query of blind.queries) {
        if (!REQUIRED_QUERY_IDS.has(query.id) || seenQueryIds.has(query.id)) {
            fail(`blindJudgement query ID가 올바르지 않거나 중복되었습니다: ${query.id}`);
        }
        seenQueryIds.add(query.id);
        if (!Array.isArray(query.candidates) || query.candidates.length !== 20) {
            fail(`${query.id}는 Top 20 후보를 가져야 합니다.`);
        }
        const candidateIds = new Set();
        for (const candidate of query.candidates) {
            if (!Number.isSafeInteger(candidate.gameId) || candidate.gameId < 1 || candidateIds.has(candidate.gameId)) {
                fail(`${query.id} 후보 gameId가 없거나 중복되었습니다.`);
            }
            candidateIds.add(candidate.gameId);
        }
    }

    const queries = blind.queries.map((query) => ({
        id: query.id,
        query: query.query,
        judgementRubric: query.judgementRubric,
        candidates: query.candidates.map((candidate, index) => {
            const evidenceText = gameById.get(candidate.gameId);
            if (evidenceText === undefined) fail(`${query.id} 후보 ${candidate.gameId}가 searchText에 없습니다.`);
            return {
                order: index + 1,
                gameId: candidate.gameId,
                name: candidate.name,
                englishName: candidate.englishName,
                evidenceText,
                grade: null,
                rationale: null,
            };
        }),
    }));

    const packet = {
        schemaVersion: 1,
        kind: "search-04-gold-judgement-packet",
        status: "pending-independent-human-judgement",
        hides: REQUIRED_HIDDEN_FIELDS,
        gradeScale: blind.gradeScale,
        judgementContract: {
            requiredIndependentJudges: 2,
            thirdJudgeRequiredOnDisagreement: true,
            gradeMeaning: "2=relevant, 1=borderline, 0=irrelevant",
        },
        annotationRule: "query와 후보 evidenceText만 읽고 관련도 0·1·2와 짧은 근거를 독립적으로 기록한다. 모델 점수·원래 순위·모델명은 판정에 사용하지 않는다.",
        sources,
        queries,
    };
    return packet;
}

function readJson(filePath) {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function sha256File(filePath) {
    return createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

function parseArgs(argv) {
    if (argv.length !== 6 || argv[0] !== "--blind" || argv[2] !== "--search-text" || argv[4] !== "--out") {
        return null;
    }
    return { blind: argv[1], searchText: argv[3], out: argv[5] };
}

function assertObject(value, field) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) fail(`${field}는 object여야 합니다.`);
}

function fail(message) {
    throw new Error(message);
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (!args) {
        console.error("사용법: node scripts/search-evaluation/build-gold-judgement-packet.mjs --blind <blind.json> --search-text <search-text.json> --out <packet.json>");
        process.exitCode = 2;
        return;
    }
    try {
        const packet = buildGoldJudgementPacket({
            blind: readJson(args.blind),
            searchText: readJson(args.searchText),
            sources: {
                blindJudgement: { path: args.blind, sha256: sha256File(args.blind) },
                searchText: { path: args.searchText, sha256: sha256File(args.searchText), rows: 1000 },
            },
        });
        fs.mkdirSync(path.dirname(args.out), { recursive: true });
        fs.writeFileSync(args.out, `${JSON.stringify(packet, null, 2)}\n`, "utf8");
        console.log(`gold judgement packet 생성: ${args.out} (query=${packet.queries.length}, candidates=${packet.queries.reduce((sum, query) => sum + query.candidates.length, 0)})`);
    } catch (error) {
        console.error(`gold judgement packet 생성 실패: ${error.message}`);
        process.exitCode = 1;
    }
}

if (process.argv[1]?.endsWith("build-gold-judgement-packet.mjs")) main();
