import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
    CANDIDATE_BLOB_PATHS,
    RV01_REQUIRED_CANDIDATE_BLOB_FIELDS,
    candidateBlobsMatch,
} from './run-test-contract-harness.mjs';

const targetBlobForPath = (relativePath) => `blob:${relativePath}`;

function rv01Candidate() {
    return Object.fromEntries(RV01_REQUIRED_CANDIDATE_BLOB_FIELDS.map((field) => [
        field,
        targetBlobForPath(CANDIDATE_BLOB_PATHS[field]),
    ]));
}

test('RV-01 candidate는 review-code 네 blob이 모두 대상과 일치해야 한다', () => {
    const result = candidateBlobsMatch(rv01Candidate(), ['RV-01'], targetBlobForPath);

    assert.equal(result.candidateInputMatch, true);
    assert.equal(result.comparedBlobCount, 4);
});

test('RV-01 candidate에서 output contract blob이 빠지면 일치하지 않는다', () => {
    const candidate = rv01Candidate();
    delete candidate.reviewCodeOutputContractBlob;

    const result = candidateBlobsMatch(candidate, ['RV-01'], targetBlobForPath);

    assert.equal(result.candidateInputMatch, false);
    assert.equal(result.comparedBlobCount, 3);
});

test('RV-01 target commit에 새 reference가 없으면 안전하게 불일치 처리한다', () => {
    const missingPath = CANDIDATE_BLOB_PATHS.reviewCodeScopeAndRoutingBlob;
    const result = candidateBlobsMatch(
        rv01Candidate(),
        ['RV-01'],
        (relativePath) => relativePath === missingPath ? null : targetBlobForPath(relativePath),
    );

    assert.equal(result.candidateInputMatch, false);
    assert.equal(result.comparedBlobCount, 4);
});

test('RV-01이 아닌 기존 run은 기록한 blob만 대상과 일치하면 된다', () => {
    const candidate = {
        validatorBlob: targetBlobForPath(CANDIDATE_BLOB_PATHS.validatorBlob),
    };

    const result = candidateBlobsMatch(candidate, ['PV-01'], targetBlobForPath);

    assert.equal(result.candidateInputMatch, true);
    assert.equal(result.comparedBlobCount, 1);
});
