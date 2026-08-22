import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
    CANDIDATE_K_VALUES,
    COMMON_DEADLINE_MS,
    DENSE_CANDIDATE_LIMIT,
    DENSE_QUERY_SOURCE,
    PROVIDER_TIMEOUT_MS,
    QUERY_FIXTURES,
    RRF_K_VALUES,
    SERVING_SPARSE_CANDIDATE_LIMIT,
    SELECTED_CANDIDATE_K,
    SELECTED_RRF_K,
    SELECTED_TIMEOUT_SECONDS,
    denseSql,
    sparseSql,
    validateLiveEvidence,
} from "./search-04e-hybrid-rrf-live.mjs";

const REPOSITORY_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const EVIDENCE_PATH = path.join(REPOSITORY_ROOT, "docs/measurements/search-04e-hybrid-rrf-live.json");

function readEvidence() {
    return JSON.parse(fs.readFileSync(EVIDENCE_PATH, "utf8"));
}

function copyEvidence() {
    return structuredClone(readEvidence());
}

test("SEARCH-04e live evidence는 동일 corpus의 승인된 T fixture 8개를 모두 고정한다", () => {
    const evidence = readEvidence();
    assert.deepEqual(validateLiveEvidence(evidence), { queryCount: 8, commonQueryCount: 4 });
    assert.equal(evidence.parameters.selected.candidateK, SELECTED_CANDIDATE_K);
    assert.equal(evidence.parameters.selected.rrfK, SELECTED_RRF_K);
    assert.equal(evidence.parameters.selected.timeoutSeconds, SELECTED_TIMEOUT_SECONDS);
    assert.equal(evidence.execution.parallel.providerTimeoutMs, PROVIDER_TIMEOUT_MS);
    assert.equal(evidence.execution.parallel.commonDeadlineMs, COMMON_DEADLINE_MS);
    assert.equal(evidence.execution.dense.candidateQueries.source, DENSE_QUERY_SOURCE);
    assert.equal(evidence.execution.dense.candidateQueries.candidateLimit, DENSE_CANDIDATE_LIMIT);
    assert.equal(evidence.execution.sparse.serving.candidateLimit, SERVING_SPARSE_CANDIDATE_LIMIT);
    assert.equal(evidence.execution.requests.length, 40);

    const queries = evidence.execution.queries;
    assert.deepEqual(queries.map((query) => query.id), QUERY_FIXTURES.map((fixture) => fixture.id));
    for (const query of queries) {
        assert.ok(query.sparseFullCount > 200, `${query.id}는 K=200 truncation 비교 대상이어야 합니다.`);
        assert.deepEqual(
            query.candidateKComparison.map((row) => row.candidateK),
            CANDIDATE_K_VALUES,
        );
        assert.deepEqual(
            query.rrfKComparison.map((row) => row.rrfK),
            RRF_K_VALUES,
        );
        assert.ok(query.servingSparseCount <= SERVING_SPARSE_CANDIDATE_LIMIT);
    }
});

test("SEARCH-04e live evidence는 mock Dense와 corpus 불일치를 거부한다", () => {
    const mutations = [
        ["BGG membership", (evidence) => {
            evidence.input.index.bggGameIdMembershipSha256 = "0".repeat(64);
        }],
        ["Dense source", (evidence) => {
            evidence.execution.dense.source = "MockitoDenseCandidateSource";
        }],
        ["query fixture", (evidence) => {
            evidence.execution.queries[0].querySha256 = "0".repeat(64);
        }],
        ["common query", (evidence) => {
            for (const query of evidence.execution.queries) query.sparseFullCount = 200;
        }],
        ["index row count", (evidence) => {
            evidence.input.index.rowCount = 999;
        }],
        ["derived result", (evidence) => {
            evidence.execution.queries[0].denseTop20[0] += 1;
        }],
        ["latency summary", (evidence) => {
            evidence.execution.phaseLatency.parallel.p95 += 1;
        }],
        ["derived latency", (evidence) => {
            evidence.parameters.observedParallelP95Ms += 1;
        }],
        ["request status", (evidence) => {
            evidence.execution.requests[0].status = "timeout";
        }],
        ["request completion", (evidence) => {
            evidence.execution.requests[0].completedWithinDeadline = false;
        }],
        ["runner.fileSha256 변경", (evidence) => {
            evidence.runner.fileSha256 = "0".repeat(64);
        }],
        ["runner.snapshotPath 변경", (evidence) => {
            evidence.runner.snapshotPath = "docs/measurements/results/other-snapshot.mjs";
        }],
        ["sourceGitHead 불일치", (evidence) => {
            evidence.sourceGitHead = "0".repeat(40);
        }],
        ["sourceClean 변경", (evidence) => {
            evidence.runner.sourceClean = !evidence.runner.sourceClean;
        }],
    ];

    for (const [label, mutate] of mutations) {
        const evidence = copyEvidence();
        mutate(evidence);
        assert.throws(() => validateLiveEvidence(evidence), { name: "Error" }, label);
    }
});

test("SEARCH-04e live evidence는 snapshot 파일이 변조되면 실패한다", () => {
    const evidence = copyEvidence();
    const snapshotPath = path.join(REPOSITORY_ROOT, evidence.runner.snapshotPath);
    const originalBytes = fs.readFileSync(snapshotPath);
    try {
        const mutatedBytes = Buffer.from(originalBytes);
        mutatedBytes[0] ^= 0xFF; // 변조
        fs.writeFileSync(snapshotPath, mutatedBytes);
        assert.throws(() => validateLiveEvidence(evidence), { name: "Error" }, "snapshot bytes 변조 시 실패해야 합니다");
    } finally {
        fs.writeFileSync(snapshotPath, originalBytes);
    }
});

test("SEARCH-04e 측정 SQL은 serving 전체 범위와 approved corpus 범위를 분리한다", () => {
    const serving = sparseSql("게임", null, SERVING_SPARSE_CANDIDATE_LIMIT);
    const corpus = sparseSql("게임", [101, 202], 1000);
    assert.match(serving, /from games g/);
    assert.doesNotMatch(serving, /with approved\(game_id\)/);
    assert.match(serving, /limit 200/);
    assert.match(corpus, /with approved\(game_id\) as \(values \(101\),\(202\)\)/);
    assert.match(corpus, /limit 1000/);

    const dense = denseSql([1, 0], {
        releaseId: "release",
        fieldVersion: "fields",
        manifestSha256: "a".repeat(64),
        searchTextChecksum: "b".repeat(64),
        provider: "cloudflare-workers-ai",
        model: "@cf/baai/bge-m3",
        embeddingMode: "text",
        dimension: 1024,
        l2Normalized: true,
    }, DENSE_CANDIDATE_LIMIT);
    assert.match(dense, /matching_active_version/);
    assert.match(dense, /limit 1000/);
});
