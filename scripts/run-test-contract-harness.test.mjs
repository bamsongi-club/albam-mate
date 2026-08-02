import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

import {
    CANDIDATE_BLOB_PATHS,
    RV01_REQUIRED_CANDIDATE_BLOB_FIELDS,
    candidateBlobsMatch,
} from './run-test-contract-harness.mjs';
import {
    INLINE_SECTION_HEADERS,
    SUMMARY_HEADERS,
} from '../.agents/skills/review-code/scripts/validate-review-payload.mjs';

const targetBlobForPath = (relativePath) => `blob:${relativePath}`;

function rv01Candidate() {
    return Object.fromEntries(RV01_REQUIRED_CANDIDATE_BLOB_FIELDS.map((field) => [
        field,
        targetBlobForPath(CANDIDATE_BLOB_PATHS[field]),
    ]));
}

const generalMachineContract = fs.readFileSync(new URL('../.agents/skills/review-code/references/general-review-machine-output-contract.md', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const testContractVerifierOutput = fs.readFileSync(new URL('../.agents/skills/review-code/references/test-contract-verifier-output-contract.md', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const presentationContract = fs.readFileSync(new URL('../.agents/skills/review-code/references/presentation-contract.md', import.meta.url), 'utf8').replace(/\r\n/g, '\n');

test('RV-01 candidate는 T-ID reviewer가 받는 네 blob이 모두 대상과 일치해야 한다', () => {
    const result = candidateBlobsMatch(rv01Candidate(), ['RV-01'], targetBlobForPath);

    assert.equal(result.candidateInputMatch, true);
    assert.equal(result.comparedBlobCount, 4);
});

test('RV-01 candidate에서 T-ID verifier output contract blob이 빠지면 일치하지 않는다', () => {
    const candidate = rv01Candidate();
    delete candidate.reviewCodeTestContractVerifierOutputBlob;

    const result = candidateBlobsMatch(candidate, ['RV-01'], targetBlobForPath);

    assert.equal(result.candidateInputMatch, false);
    assert.equal(result.comparedBlobCount, 3);
});

test('일반 reviewer와 judge의 machine JSONL 키를 그대로 보존한다', () => {
    const records = [...generalMachineContract.matchAll(/~~~json\n([\s\S]*?)\n~~~/g)]
        .flatMap((match) => match[1].split('\n').map((line) => JSON.parse(line)));
    assert.equal(records.length, 3);
    assert.deepEqual(Object.keys(records[0]), ['type', 'shard', 'complete', 'checkedRiskIds', 'uncoveredRiskIds']);
    assert.deepEqual(Object.keys(records[1]), ['type', 'candidateId', 'dimension', 'severity', 'file', 'line', 'side', 'title', 'evidence', 'fix', 'confidence']);
    assert.deepEqual(Object.keys(records[2]), ['type', 'candidateId', 'accepted', 'finalSeverity', 'rationale', 'confidence']);
});

test('T-ID 전용 계약은 정확한 키 순서와 verdict 집계만 포함한다', () => {
    const records = [...testContractVerifierOutput.matchAll(/~~~json\n([\s\S]*?)\n~~~/g)]
        .flatMap((match) => match[1].split('\n').map((line) => JSON.parse(line)));
    assert.equal(records.length, 1);
    assert.deepEqual(Object.keys(records[0]), ['type', 'testId', 'verdict', 'evidence']);
    assert.match(testContractVerifierOutput, /`pass`, 직접 위반하면 `fail`, 증거가 부족하면 `unverified`/);
    assert.match(testContractVerifierOutput, /`fail`이면 `Changes Requested`.*`unverified`가 있으면 `Incomplete`.*모두 `pass`이면 `Approve`/s);
});

test('사용자 표시 계약의 고정 Markdown 형식을 보존한다', () => {
    assert.deepEqual([...presentationContract.matchAll(/^\| (🔴|🟠|🟡|⚪) \| `(critical|major|minor|nit)` \|/gm)].map((match) => match.slice(1, 3)), [['🔴', 'critical'], ['🟠', 'major'], ['🟡', 'minor'], ['⚪', 'nit']]);
    assert.match(presentationContract, /각 Finding은 아래 형식을 그대로 쓴다/);
    for (const header of Object.values(INLINE_SECTION_HEADERS)) {
        assert.match(presentationContract, new RegExp(header.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
    }
    for (const header of [SUMMARY_HEADERS.strengths, SUMMARY_HEADERS.findings, SUMMARY_HEADERS.actions]) {
        assert.match(presentationContract, new RegExp(header.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
    }
    assert.match(presentationContract, /## 판정: <Approve \| Changes Requested \| Blocked \| Incomplete>/);
    assert.match(presentationContract, /심각도: 🔴 <n>  🟠 <n>  🟡 <n>  ⚪ <n>/);
    assert.match(presentationContract, /미검토 범위가 있으면 `Incomplete`.*critical이 있으면 `Blocked`.*major가 있으면 `Changes Requested`.*critical·major가 없으면 `Approve`/s);
    const examples = [...presentationContract.matchAll(/~~~text\n([\s\S]*?)\n~~~/g)];
    assert.equal(examples.length, 2);
    assert.ok(examples.every((example) => example[1].trim() !== ''));
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
