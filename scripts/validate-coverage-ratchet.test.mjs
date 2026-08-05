import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

import {
    duplicateEntryKeys,
    mapBlockRange,
    validateCoverageRatchetDiff,
    validateCoverageRatchetInRepo,
} from './validate-coverage-ratchet.mjs';

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
        buildFile,
    );
    assert.equal(problems.length, 1);
    assert.match(problems[0], /맵 블록을 찾을 수 없습니다/u);
});

test('변경이 없으면 통과한다', () => {
    assert.deepEqual(validateCoverageRatchetDiff('', buildFile, buildFile), []);
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
        buildFile,
    );
    assert.equal(problems.length, 1);
    assert.match(problems[0], /항목이 아닌 추가 줄이 있습니다/u);
});

test('맵 블록 밖에 추가한 항목을 거부한다', () => {
    const problems = validateCoverageRatchetDiff(
        diff('@@ -10,1 +10,2 @@', ' def applyCoverageRules = { verification -> }', "+    'cloud.x' : 0.10,"),
        buildFile,
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
        buildFile,
    );
    assert.ok(problems.some((problem) => /build\.gradle 밖의 파일/u.test(problem)));
});

test('기존 키를 낮은 값으로 중복 추가하는 우회를 거부한다', () => {
    // Groovy 맵은 뒤쪽 값이 유효값이므로 실제 최소선은 0.90 -> 0.10으로 내려간다.
    const duplicated = [
        'def gatedBranchCoverage = [',
        "    'cloud.bamsongi.albammate.auth.dto'    : 0.90,",
        "    'cloud.bamsongi.albammate.chat.service': 0.80,",
        "    'cloud.bamsongi.albammate.auth.dto'    : 0.10,",
        ']',
    ].join('\n');
    const problems = validateCoverageRatchetDiff(
        diff(
            '@@ -3,1 +3,2 @@',
            "     'cloud.bamsongi.albammate.chat.service': 0.80,",
            "+    'cloud.bamsongi.albammate.auth.dto'    : 0.10,",
        ),
        duplicated,
        duplicated,
    );

    assert.equal(problems.length, 1);
    assert.match(problems[0], /같은 패키지 키가 두 번 있습니다.*auth\.dto/u);
});

test('중복 키가 상향이어도 거부한다', () => {
    const duplicated = [
        'def gatedBranchCoverage = [',
        "    'cloud.a' : 0.10,",
        "    'cloud.a' : 0.90,",
        ']',
    ].join('\n');
    const problems = validateCoverageRatchetDiff(
        diff('@@ -2,1 +2,2 @@', "     'cloud.a' : 0.10,", "+    'cloud.a' : 0.90,"),
        duplicated,
        duplicated,
    );

    assert.ok(problems.some((problem) => /같은 패키지 키가 두 번/u.test(problem)));
});

test('중복 키 없는 맵은 통과한다', () => {
    assert.deepEqual(duplicateEntryKeys(buildFile, mapBlockRange(buildFile)), []);
});

// 커밋을 만든 뒤에는 worktree diff가 비므로, 고정한 head 검증에서는 base를 넘겨야 한다.
function createRepo(t, mapLines) {
    const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'coverage-ratchet-'));
    t.after(() => fs.rmSync(repo, { recursive: true, force: true }));
    const git = (...args) =>
        spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8', windowsHide: true });
    const write = (lines) =>
        fs.writeFileSync(
            path.join(repo, 'build.gradle'),
            ['def gatedBranchCoverage = [', ...lines, ']', ''].join('\n'),
            'utf8',
        );
    git('init', '--quiet');
    write(mapLines);
    git('add', '--all');
    git('-c', 'user.name=t', '-c', 'user.email=t@e.com', 'commit', '--quiet', '-m', 'base');
    return { repo, git, write };
}

test('커밋된 head의 최소선 하향을 base 비교로 검출한다', (t) => {
    const { repo, git, write } = createRepo(t, ["    'cloud.a' : 0.90,"]);
    write(["    'cloud.a' : 0.10,"]);
    git('add', '--all');
    git('-c', 'user.name=t', '-c', 'user.email=t@e.com', 'commit', '--quiet', '-m', 'lower');

    // base 없이 실행하면 커밋된 변경이 빈 diff가 되어 통과한다.
    assert.deepEqual(validateCoverageRatchetInRepo(repo).problems, []);

    const withBase = validateCoverageRatchetInRepo(repo, { base: 'HEAD~1' });
    assert.equal(withBase.problems.length, 1);
    assert.match(withBase.problems[0], /최소선 상향이 아닙니다: cloud\.a 0\.9 → 0\.1/u);
});

test('커밋된 head의 정상 상향은 base 비교에서도 통과한다', (t) => {
    const { repo, git, write } = createRepo(t, ["    'cloud.a' : 0.50,"]);
    write(["    'cloud.a' : 0.60,", "    'cloud.b' : 0.70,"]);
    git('add', '--all');
    git('-c', 'user.name=t', '-c', 'user.email=t@e.com', 'commit', '--quiet', '-m', 'raise');

    assert.deepEqual(validateCoverageRatchetInRepo(repo, { base: 'HEAD~1' }).problems, []);
});

test('맵 밖에서 같은 키를 삭제하고 맵 안에 상향 추가하는 위장을 거부한다', () => {
    // 다른 맵의 'cloud.a' : 0.90을 지우고 래칫 맵에 0.95로 추가하면 키·값만 보면 상향으로 보인다.
    const preImage = [
        'def otherThresholds = [',
        "    'cloud.a' : 0.90,",
        ']',
        '',
        'def gatedBranchCoverage = [',
        "    'cloud.b' : 0.80,",
        ']',
    ].join('\n');
    const postImage = [
        'def otherThresholds = [',
        ']',
        '',
        'def gatedBranchCoverage = [',
        "    'cloud.b' : 0.80,",
        "    'cloud.a' : 0.95,",
        ']',
    ].join('\n');
    const problems = validateCoverageRatchetDiff(
        [
            'diff --git a/build.gradle b/build.gradle',
            '--- a/build.gradle',
            '+++ b/build.gradle',
            '@@ -1,3 +1,2 @@',
            ' def otherThresholds = [',
            "-    'cloud.a' : 0.90,",
            ' ]',
            '@@ -5,2 +4,3 @@',
            ' def gatedBranchCoverage = [',
            "     'cloud.b' : 0.80,",
            "+    'cloud.a' : 0.95,",
        ].join('\n'),
        postImage,
        preImage,
    );

    assert.equal(problems.length, 1);
    assert.match(problems[0], /base의 gatedBranchCoverage 맵 블록\(5~7줄\) 밖에서 삭제한 줄입니다: cloud\.a \(2줄\)/u);
});
