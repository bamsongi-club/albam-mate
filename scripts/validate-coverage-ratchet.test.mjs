import { test } from 'node:test';
import assert from 'node:assert/strict';

import { mapBlockRange, validateCoverageRatchetDiff } from './validate-coverage-ratchet.mjs';

const buildFile = [
    "plugins {",
    "    id 'jacoco'",
    '}',
    '',
    'def gatedBranchCoverage = [',
    "    'cloud.bamsongi.albammate.auth.dto'    : 0.67,",
    "    'cloud.bamsongi.albammate.chat.service': 0.92,",
    ']',
    '',
    'def applyCoverageRules = { verification -> }',
].join('\n');

// 맵 항목은 6~7줄이므로 블록 범위는 5~8줄이다.
function diff(hunkHeader, ...lines) {
    return [
        'diff --git a/build.gradle b/build.gradle',
        'index 1111111..2222222 100644',
        '--- a/build.gradle',
        '+++ b/build.gradle',
        hunkHeader,
        ...lines,
    ].join('\n');
}

test('맵 블록 범위를 찾는다', () => {
    assert.deepEqual(mapBlockRange(buildFile), { start: 5, end: 8 });
});

test('맵 블록이 없으면 어떤 변경도 래칫으로 인정하지 않는다', () => {
    const problems = validateCoverageRatchetDiff(
        diff('@@ -5,2 +5,3 @@', " 'a'", "+    'cloud.x' : 0.10,"),
        'def other = []',
    );
    assert.equal(problems.length, 1);
    assert.match(problems[0], /맵 블록을 찾을 수 없습니다/u);
});

test('변경이 없으면 통과한다', () => {
    assert.deepEqual(validateCoverageRatchetDiff('', buildFile), []);
});

test('새 항목 추가를 허용한다', () => {
    const problems = validateCoverageRatchetDiff(
        diff(
            '@@ -5,3 +5,4 @@ plugins {',
            ' def gatedBranchCoverage = [',
            "     'cloud.bamsongi.albammate.auth.dto'    : 0.67,",
            "+    'cloud.bamsongi.albammate.chat.service': 0.92,",
            ' ]',
        ),
        buildFile,
    );
    assert.deepEqual(problems, []);
});

test('기존 최소선 상향을 허용한다', () => {
    const problems = validateCoverageRatchetDiff(
        diff(
            '@@ -5,3 +5,3 @@ plugins {',
            ' def gatedBranchCoverage = [',
            "-    'cloud.bamsongi.albammate.auth.dto'    : 0.67,",
            "+    'cloud.bamsongi.albammate.auth.dto'    : 0.70,",
            " ]",
        ),
        buildFile,
    );
    assert.deepEqual(problems, []);
});

test('최소선 하향을 거부한다', () => {
    const problems = validateCoverageRatchetDiff(
        diff(
            '@@ -5,3 +5,3 @@ plugins {',
            ' def gatedBranchCoverage = [',
            "-    'cloud.bamsongi.albammate.auth.dto'    : 0.67,",
            "+    'cloud.bamsongi.albammate.auth.dto'    : 0.50,",
            " ]",
        ),
        buildFile,
    );
    assert.equal(problems.length, 1);
    assert.match(problems[0], /최소선 상향이 아닙니다: cloud\.bamsongi\.albammate\.auth\.dto 0\.67 → 0\.5/u);
});

test('같은 값 재작성을 거부한다', () => {
    const problems = validateCoverageRatchetDiff(
        diff(
            '@@ -5,3 +5,3 @@ plugins {',
            ' def gatedBranchCoverage = [',
            "-    'cloud.bamsongi.albammate.auth.dto'    : 0.67,",
            "+    'cloud.bamsongi.albammate.auth.dto': 0.67,",
            " ]",
        ),
        buildFile,
    );
    assert.equal(problems.length, 1);
    assert.match(problems[0], /최소선 상향이 아닙니다/u);
});

test('항목 삭제를 거부한다', () => {
    const problems = validateCoverageRatchetDiff(
        diff(
            '@@ -5,3 +5,2 @@ plugins {',
            ' def gatedBranchCoverage = [',
            "-    'cloud.bamsongi.albammate.auth.dto'    : 0.67,",
            " ]",
        ),
        buildFile,
    );
    assert.equal(problems.length, 1);
    assert.match(problems[0], /래칫 항목을 삭제했습니다: cloud\.bamsongi\.albammate\.auth\.dto/u);
});

test('맵 항목이 아닌 build 변경을 거부한다', () => {
    const problems = validateCoverageRatchetDiff(
        diff(
            '@@ -1,3 +1,4 @@',
            ' plugins {',
            "+    id 'org.something'",
            "     id 'jacoco'",
            ' }',
        ),
        buildFile,
    );
    assert.equal(problems.length, 1);
    assert.match(problems[0], /항목이 아닌 추가 줄이 있습니다/u);
});

test('맵 블록 밖에 추가한 항목을 거부한다', () => {
    const problems = validateCoverageRatchetDiff(
        diff('@@ -10,1 +10,2 @@', ' def applyCoverageRules = { verification -> }', "+    'cloud.x' : 0.10,"),
        buildFile,
    );
    assert.equal(problems.length, 1);
    assert.match(problems[0], /맵 블록\(5~8줄\) 밖의 변경입니다: cloud\.x \(11줄\)/u);
});

test('build.gradle 밖의 파일이 섞이면 거부한다', () => {
    const problems = validateCoverageRatchetDiff(
        [
            'diff --git a/settings.gradle b/settings.gradle',
            '--- a/settings.gradle',
            '+++ b/settings.gradle',
            '@@ -1,1 +1,1 @@',
            "-rootProject.name = 'a'",
            "+rootProject.name = 'b'",
        ].join('\n'),
        buildFile,
    );
    assert.ok(problems.some((problem) => /build\.gradle 밖의 파일/u.test(problem)));
});
