import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
    CANDIDATE_K_VALUES,
    QUERY_FIXTURES,
    RRF_K_VALUES,
    SELECTED_CANDIDATE_K,
    SELECTED_RRF_K,
    SELECTED_TIMEOUT_SECONDS,
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
    ];

    for (const [label, mutate] of mutations) {
        const evidence = copyEvidence();
        mutate(evidence);
        assert.throws(() => validateLiveEvidence(evidence), { name: "Error" }, label);
    }
});
