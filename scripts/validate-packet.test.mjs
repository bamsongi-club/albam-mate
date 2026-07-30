import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import { DEFAULT_SCHEMA_PATH, validatePacket } from './validate-packet.mjs';

const schema = JSON.parse(fs.readFileSync(DEFAULT_SCHEMA_PATH, 'utf8'));
const scriptPath = fileURLToPath(new URL('./validate-packet.mjs', import.meta.url));
const approvedComment = 'https://github.com/bamsongi-club/albam-mate/issues/14#issuecomment-123456789';

function validPacket() {
    return {
        schemaVersion: 2,
        workItem: {
            kind: 'issue',
            id: '#14',
            featureId: 'AUTH-02',
            summary: '회원가입 HTTP 경계를 구현한다',
        },
        canonicalSources: [
            {
                path: 'docs/p0/authentication.md',
                section: 'AUTH-02',
            },
        ],
        allowedPaths: ['src/main/java/cloud/bamsongi/albammate/auth/'],
        forbiddenPaths: ['frontend/'],
        completionCriteria: ['승인된 회원가입 요청은 201을 반환한다'],
        requiredTests: [
            {
                id: 'T1',
                intent: '비로그인 사용자가 유효한 회원가입 요청을 보내면 201을 반환한다',
                sourceRef: approvedComment,
            },
            {
                id: 'T2',
                intent: '중복 이메일로 가입하면 충돌 응답을 반환한다',
                sourceRef: approvedComment,
            },
        ],
        testContractApproval: {
            issueNumber: 14,
            commentUrl: approvedComment,
        },
        validation: {
            targetedTests: ['./gradlew.bat test --tests "*Signup*"'],
            finalCommands: ['git diff --check'],
        },
        confirmedDecisions: ['테스트 계약은 이슈 코멘트를 정본으로 사용한다'],
    };
}

const keywords = (errors) => errors.map((error) => error.keyword);

test('schemaVersion 2 패킷과 승인된 연속 T-ID는 실제 스키마를 통과한다', () => {
    assert.deepEqual(validatePacket(validPacket(), schema), []);
});

test('이슈 없는 feature 작업은 위임 패킷으로 거부한다', () => {
    const packet = validPacket();
    packet.workItem.kind = 'feature';
    packet.workItem.id = 'AUTH-02';

    const errors = validatePacket(packet, schema);

    assert.ok(errors.some((error) => error.instancePath === '$.workItem.kind' && error.keyword === 'const'));
    assert.ok(keywords(errors).includes('workItemIssueNumber'));
});

test('PR 피드백이 기존 T-ID 범위 안이면 원래 이슈 승인 참조를 재사용할 수 있다', () => {
    const packet = validPacket();
    packet.workItem.summary = 'PR 리뷰에서 확인된 회원가입 오류 응답 누락을 수정한다';
    packet.completionCriteria = ['기존 T2의 충돌 응답을 구현한다'];

    assert.deepEqual(validatePacket(packet, schema), []);
});

test('이전 스키마 버전과 새 필수 필드 누락을 거부한다', () => {
    const packet = validPacket();
    packet.schemaVersion = 1;
    delete packet.requiredTests;
    delete packet.testContractApproval;

    const errors = validatePacket(packet, schema);

    assert.ok(errors.some((error) => error.instancePath === '$.schemaVersion' && error.keyword === 'const'));
    assert.ok(errors.some((error) => error.instancePath === '$.requiredTests' && error.keyword === 'required'));
    assert.ok(errors.some((error) => error.instancePath === '$.testContractApproval' && error.keyword === 'required'));
});

test('스키마에 없는 패킷 속성을 거부한다', () => {
    const packet = validPacket();
    packet.unreviewedNote = '모델이 임의로 추가한 필드';

    const errors = validatePacket(packet, schema);

    assert.ok(errors.some((error) => error.instancePath === '$.unreviewedNote' && error.keyword === 'additionalProperties'));
});

test('T-ID 중복과 배열 순서의 불연속을 함께 거부한다', () => {
    const packet = validPacket();
    packet.requiredTests[1].id = 'T1';

    const errors = validatePacket(packet, schema);

    assert.ok(keywords(errors).includes('uniqueTId'));
    assert.ok(keywords(errors).includes('continuousTId'));
});

test('중복이 없어도 T1부터 연속하지 않는 T-ID를 거부한다', () => {
    const packet = validPacket();
    packet.requiredTests[1].id = 'T3';

    const errors = validatePacket(packet, schema);

    assert.ok(keywords(errors).includes('continuousTId'));
});

test('승인 이슈 번호와 코멘트 URL의 이슈 번호가 다르면 거부한다', () => {
    const packet = validPacket();
    packet.testContractApproval.issueNumber = 15;
    packet.workItem.id = '#15';

    const errors = validatePacket(packet, schema);

    assert.ok(keywords(errors).includes('approvalIssueNumber'));
});

test('각 T-ID의 sourceRef가 승인된 정본 코멘트와 다르면 거부한다', () => {
    const packet = validPacket();
    packet.requiredTests[1].sourceRef =
        'https://github.com/bamsongi-club/albam-mate/issues/14#issuecomment-987654321';

    const errors = validatePacket(packet, schema);

    assert.ok(keywords(errors).includes('testSourceApproval'));
});

test('issue 작업 번호와 승인 이슈 번호가 다르면 거부한다', () => {
    const packet = validPacket();
    packet.workItem.id = '#15';

    const errors = validatePacket(packet, schema);

    assert.ok(keywords(errors).includes('workItemIssueNumber'));
});

test('CLI는 실제 스키마를 읽어 유효 패킷은 0, 무효 패킷은 1로 종료한다', (t) => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'validate-packet-'));
    t.after(() => fs.rmSync(tempDir, { recursive: true, force: true }));
    const packetPath = path.join(tempDir, 'packet.json');

    fs.writeFileSync(packetPath, JSON.stringify(validPacket()), 'utf8');
    const valid = spawnSync(process.execPath, [scriptPath, packetPath], { encoding: 'utf8' });

    assert.equal(valid.status, 0, valid.stderr);
    assert.match(valid.stdout, /패킷 검증 통과/);

    const packet = validPacket();
    packet.requiredTests[1].id = 'T3';
    fs.writeFileSync(packetPath, JSON.stringify(packet), 'utf8');
    const invalid = spawnSync(process.execPath, [scriptPath, packetPath], { encoding: 'utf8' });

    assert.equal(invalid.status, 1);
    assert.match(invalid.stderr, /continuousTId/);
});
