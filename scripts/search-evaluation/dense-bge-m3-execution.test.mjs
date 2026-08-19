import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { buildGoldJudgementPacket } from "./build-gold-judgement-packet.mjs";
import {
    APPROVAL_REFERENCE,
    APPROVED_INPUT_GIT_HEAD,
    APPROVED_MODEL_ARTIFACT_FILES,
    MODEL_DESCRIPTOR,
    PLAY_INTENT_QUERIES,
    assembleDenseExecutionFiles,
    buildBlindJudgementExport,
    buildCandidatePool,
    calculateGradedMetrics,
    sha256,
    validateBlindJudgementExport,
    validateCandidatePool,
    validateDenseExecutionManifest,
    validateDenseInputArtifacts,
    validateDenseQueries,
    validateDenseResults,
    validateGoldJudgementPacket,
} from "./dense-bge-m3-execution.mjs";

function fixture() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "search-04-dense-"));
    const dense = path.join(root, "dense");
    fs.mkdirSync(dense);
    const queries = Object.entries(PLAY_INTENT_QUERIES).map(([id, query]) => ({
        id,
        query,
        cohorts: ["intent/description"],
        hardFilters: {},
        evaluationStatus: "development",
        labelStatus: "unjudged",
        judgementRubric: {
            relevant: "검수 기준에 맞는 플레이 경험을 핵심으로 제공",
            borderline: "일부는 맞지만 핵심 근거가 약함",
            irrelevant: "플레이 경험 근거가 없음",
        },
    }));
    const results = {
        schemaVersion: 1,
        kind: "search-04-dense-execution",
        sourceGitHead: "592de01644e33554dcce5a13bfcb5e9d5bfac882",
        model: MODEL_DESCRIPTOR,
        inputs: {},
        runtime: {
            python: "3.14.6",
            sentenceTransformers: "6.0.0",
            torch: "2.13.0",
            numpy: "2.5.2",
            device: "mps",
        },
        topK: 20,
        queries: queries.map((query) => ({
            id: query.id,
            query: query.query,
            hardFilterViolationGameIds: [],
            ranked: Array.from({ length: 20 }, (_, index) => ({
                rank: index + 1,
                gameId: index + 1,
                score: 1 - index / 100,
                name: `게임 ${index + 1}`,
                englishName: `Game ${index + 1}`,
            })),
        })),
    };
    const qualityCorpus = JSON.stringify({ members: Array.from({ length: 1000 }, (_, index) => ({ gameId: index + 1 })) });
    const searchText = JSON.stringify({ gameCount: 1000, games: Array.from({ length: 1000 }, (_, index) => ({ gameId: index + 1, searchText: `game ${index + 1}` })) });
    const displayMap = JSON.stringify({ gameCount: 1000, games: Array.from({ length: 1000 }, (_, index) => ({ gameId: index + 1, name: `게임 ${index + 1}`, englishName: `Game ${index + 1}` })) });
    const modelArtifactManifest = JSON.stringify({
        schemaVersion: 1,
        kind: "search-04-local-model-artifact",
        modelId: MODEL_DESCRIPTOR.modelId,
        revision: MODEL_DESCRIPTOR.revision,
        requiredFiles: Object.entries(APPROVED_MODEL_ARTIFACT_FILES).map(([path, sha256]) => ({ path, sha256 })),
    });
    const files = {
        qualityCorpus: path.join(dense, "quality-corpus.json"),
        searchText: path.join(dense, "search-text.json"),
        queries: path.join(dense, "queries.json"),
        displayMap: path.join(dense, "display-map.json"),
        modelArtifactManifest: path.join(dense, "model-artifact-manifest.json"),
        results: path.join(dense, "results.json"),
    };
    fs.writeFileSync(files.qualityCorpus, qualityCorpus);
    fs.writeFileSync(files.searchText, searchText);
    fs.writeFileSync(files.queries, JSON.stringify(queries));
    fs.writeFileSync(files.displayMap, displayMap);
    fs.writeFileSync(files.modelArtifactManifest, modelArtifactManifest);
    results.inputs = {
        qualityCorpusSha256: sha256(Buffer.from(qualityCorpus)),
        searchTextSha256: sha256(Buffer.from(searchText)),
        querySha256: sha256(Buffer.from(JSON.stringify(queries))),
        displayMapSha256: sha256(Buffer.from(displayMap)),
        modelArtifactManifestSha256: sha256(Buffer.from(modelArtifactManifest)),
        corpusRows: 1000,
    };
    fs.writeFileSync(files.results, JSON.stringify(results));
    const candidatePool = buildCandidatePool(results);
    const blind = buildBlindJudgementExport({ queries, candidatePool });
    const candidatePath = path.join(dense, "candidate-pool.json");
    const blindPath = path.join(dense, "blind.json");
    const goldPath = path.join(dense, "gold.json");
    fs.writeFileSync(candidatePath, JSON.stringify(candidatePool));
    fs.writeFileSync(blindPath, JSON.stringify(blind));
    const descriptor = (filePath, rows) => ({
        path: path.relative(dense, filePath),
        sha256: sha256(fs.readFileSync(filePath)),
        rows,
    });
    const gold = buildGoldJudgementPacket({
        blind,
        searchText: JSON.parse(searchText),
        sources: {
            blindJudgement: { path: path.relative(dense, blindPath), sha256: sha256(fs.readFileSync(blindPath)) },
            searchText: descriptor(files.searchText, 1000),
        },
    });
    fs.writeFileSync(goldPath, JSON.stringify(gold));
    const manifest = {
        schemaVersion: 1,
        kind: "search-04-dense-execution",
        featureId: "SEARCH-04",
        evaluationProfile: "development-seed",
        status: "completed",
        approvalReference: APPROVAL_REFERENCE,
        branch: "feature/issue-868-dense-hybrid-evaluation",
        inputGitHead: APPROVED_INPUT_GIT_HEAD,
        execution: {
            mode: "offline",
            network: "disabled",
            externalApi: false,
            secretRequired: false,
            provider: "local",
            runtime: results.runtime,
        },
        model: MODEL_DESCRIPTOR,
        sources: {
            qualityCorpus: descriptor(files.qualityCorpus, 1000),
            searchText: descriptor(files.searchText, 1000),
            queries: descriptor(files.queries, 3),
            displayMap: descriptor(files.displayMap, 1000),
            modelArtifactManifest: descriptor(files.modelArtifactManifest, 1),
        },
        outputs: {
            results: { ...descriptor(files.results, 3), topK: 20 },
            candidatePool: descriptor(candidatePath, 3),
            blindJudgement: descriptor(blindPath, 3),
            goldJudgementPacket: descriptor(goldPath, 3),
        },
        hybrid: { status: "deferred", reason: "#866 lexical/Sparse 결과가 아직 없습니다." },
        quality: { status: "unjudged", qualityReady: false },
    };
    const manifestPath = path.join(dense, "manifest.json");
    fs.writeFileSync(manifestPath, JSON.stringify(manifest));
    return {
        root,
        dense,
        files,
        queries,
        results,
        candidatePool,
        blind,
        gold,
        manifest,
        manifestPath,
        inputArtifacts: {
            qualityCorpus: JSON.parse(qualityCorpus),
            searchText: JSON.parse(searchText),
            displayMap: JSON.parse(displayMap),
            queries,
        },
    };
}

test("승인된 로컬 BGE-M3 실행 manifest와 모든 checksum을 검증한다", () => {
    const value = fixture();
    try {
        assert.equal(validateDenseExecutionManifest(value.manifest, { baseDir: value.dense, verifyFiles: true }).status, "completed");
        assert.equal(validateDenseQueries(value.queries).length, 3);
        assert.equal(validateDenseResults(value.results).queries.length, 3);
        assert.deepEqual(validateCandidatePool(value.candidatePool, value.results), value.candidatePool);
        assert.deepEqual(validateBlindJudgementExport(value.blind, { queries: value.queries, candidatePool: value.candidatePool }), value.blind);
        assert.deepEqual(
            validateGoldJudgementPacket(value.gold, {
                queries: value.queries,
                candidatePool: value.candidatePool,
                blindJudgement: value.blind,
                searchText: value.inputArtifacts.searchText,
                sources: {
                    blindJudgement: {
                        path: value.manifest.outputs.blindJudgement.path,
                        sha256: value.manifest.outputs.blindJudgement.sha256,
                    },
                    searchText: value.manifest.sources.searchText,
                },
            }),
            value.gold,
        );
        const tamperedGold = structuredClone(value.gold);
        tamperedGold.queries[0].candidates[0].grade = 2;
        assert.throws(
            () => validateGoldJudgementPacket(tamperedGold, {
                queries: value.queries,
                candidatePool: value.candidatePool,
                blindJudgement: value.blind,
                searchText: value.inputArtifacts.searchText,
                sources: {
                    blindJudgement: {
                        path: value.manifest.outputs.blindJudgement.path,
                        sha256: value.manifest.outputs.blindJudgement.sha256,
                    },
                    searchText: value.manifest.sources.searchText,
                },
            }),
            /goldJudgementPacket/u,
        );
    } finally {
        fs.rmSync(value.root, { recursive: true, force: true });
    }
});

test("외부 API·잘못된 모델 revision·quality-ready 전환을 차단한다", () => {
    const value = fixture();
    try {
        const external = structuredClone(value.manifest);
        external.execution.externalApi = true;
        assert.throws(() => validateDenseExecutionManifest(external), /외부 Embedding API/u);

        const wrongRevision = structuredClone(value.manifest);
        wrongRevision.model.revision = "main";
        assert.throws(() => validateDenseExecutionManifest(wrongRevision), /model.revision/u);

        const qualityReady = structuredClone(value.manifest);
        qualityReady.quality.status = "quality-ready";
        assert.throws(() => validateDenseExecutionManifest(qualityReady), /unjudged/u);

        const unapprovedComment = structuredClone(value.manifest);
        unapprovedComment.approvalReference = "https://github.com/bamsongi-club/albam-mate/issues/868#issuecomment-9999999999";
        assert.throws(() => validateDenseExecutionManifest(unapprovedComment), /승인된/u);
    } finally {
        fs.rmSync(value.root, { recursive: true, force: true });
    }
});

test("새 play-intent query에 provisional expected ID가 섞이면 차단한다", () => {
    const value = fixture();
    try {
        const invalid = structuredClone(value.queries);
        invalid[0].expectedGameIds = [1];
        assert.throws(() => validateDenseQueries(invalid), /새 gold/u);
    } finally {
        fs.rmSync(value.root, { recursive: true, force: true });
    }
});

test("결과 rank·중복 gameId·NaN·hard-filter 위반을 차단한다", () => {
    const value = fixture();
    try {
        const duplicate = structuredClone(value.results);
        duplicate.queries[0].ranked[1].gameId = 1;
        assert.throws(() => validateDenseResults(duplicate), /중복/u);

        const invalidOrder = structuredClone(value.results);
        invalidOrder.queries[0].ranked[1].score = 2;
        assert.throws(() => validateDenseResults(invalidOrder), /score DESC/u);

        const violation = structuredClone(value.results);
        violation.queries[0].hardFilterViolationGameIds = [1];
        assert.throws(() => validateDenseResults(violation), /hard-filter/u);
    } finally {
        fs.rmSync(value.root, { recursive: true, force: true });
    }
});

test("results를 고정 query·corpus membership·display map에 결속한다", () => {
    const value = fixture();
    try {
        const inputs = validateDenseInputArtifacts(value.inputArtifacts);
        assert.doesNotThrow(() => validateDenseResults(value.results, { expectedInputs: inputs }));

        const wrongQuery = structuredClone(value.results);
        wrongQuery.queries[0].query = "변조된 query";
        assert.throws(() => validateDenseResults(wrongQuery, { expectedInputs: inputs }), /query 문구/u);

        const outsideCorpus = structuredClone(value.results);
        outsideCorpus.queries[0].ranked[0].gameId = 1001;
        assert.throws(() => validateDenseResults(outsideCorpus, { expectedInputs: inputs }), /membership/u);

        const wrongDisplay = structuredClone(value.results);
        wrongDisplay.queries[0].ranked[0].name = "변조된 이름";
        assert.throws(() => validateDenseResults(wrongDisplay, { expectedInputs: inputs }), /display 값/u);
    } finally {
        fs.rmSync(value.root, { recursive: true, force: true });
    }
});

test("candidate pool은 provenance를 보존하고 blind export는 score와 model을 숨긴다", () => {
    const value = fixture();
    const blind = value.blind.queries[0];
    assert.equal(value.candidatePool.queries[0].candidates[0].sources[0].modelId, MODEL_DESCRIPTOR.modelId);
    assert.equal(Object.hasOwn(blind.candidates[0], "score"), false);
    assert.equal(Object.hasOwn(blind.candidates[0], "modelId"), false);
    assert.equal(blind.candidates.length, 20);

    const tamperedPool = structuredClone(value.candidatePool);
    tamperedPool.queries[0].candidates[0].gameId = 9999;
    assert.throws(() => validateCandidatePool(tamperedPool, value.results), /재구성한 후보/u);

    const tamperedBlind = structuredClone(value.blind);
    tamperedBlind.queries[0].candidates[0].grade = 2;
    assert.throws(() => validateBlindJudgementExport(tamperedBlind, { queries: value.queries, candidatePool: value.candidatePool }), /재구성한 결과/u);
});

test("새 results에서 candidate·blind·gold와 manifest checksum을 함께 조립한다", () => {
    const value = fixture();
    try {
        const rerun = structuredClone(value.results);
        rerun.queries[0].ranked[0].score = 0.999;
        const nextManifest = assembleDenseExecutionFiles({
            manifest: value.manifest,
            baseDir: value.dense,
            manifestPath: value.manifestPath,
            results: rerun,
        });

        assert.notEqual(nextManifest.outputs.results.sha256, value.manifest.outputs.results.sha256);
        assert.equal(validateDenseExecutionManifest(nextManifest, { baseDir: value.dense, verifyFiles: true }).status, "completed");
        assert.deepEqual(JSON.parse(fs.readFileSync(path.join(value.dense, "results.json"), "utf8")), rerun);
    } finally {
        fs.rmSync(value.root, { recursive: true, force: true });
    }
});

test("graded qrels는 grade 2만 Recall·MRR relevant로 세고 nDCG는 2^grade-1 gain을 사용한다", () => {
    const metrics = calculateGradedMetrics({
        grades: { "1": 2, "2": 1, "3": 0 },
        rankedGameIds: [2, 1, 3],
        k: 3,
    });
    assert.equal(metrics.recallAtK, 1);
    assert.equal(metrics.mrrAtK, 1 / 2);
    assert.equal(metrics.ndcgAtK, (1 + 3 / Math.log2(3)) / (3 + 1 / Math.log2(3)));
    assert.throws(() => calculateGradedMetrics({ grades: { "1": 2 }, rankedGameIds: [1, 1], k: 2 }), /중복/u);
});
