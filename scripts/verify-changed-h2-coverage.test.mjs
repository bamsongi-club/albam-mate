import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
    coverageFromJacocoXml,
    coverageRulesFromBuildFile,
    verifyChangedH2Coverage,
} from './verify-changed-h2-coverage.mjs';

const scriptPath = fileURLToPath(new URL('./verify-changed-h2-coverage.mjs', import.meta.url));

const buildFile = [
    'def gatedBranchCoverage = [',
    "    'example.dto': 0.80,",
    "    'example.database': 0.90,",
    ']',
    '',
    'def applyCoverageRules = { verification ->',
    '    verification.violationRules { rules ->',
    '        rules.rule {',
    '            limit {',
    "                counter = 'BRANCH'",
    "                value = 'COVEREDRATIO'",
    '                minimum = 0.72',
    '            }',
    '            limit {',
    "                counter = 'LINE'",
    "                value = 'COVEREDRATIO'",
    '                minimum = 0.92',
    '            }',
    '        }',
    '        gatedBranchCoverage.each { packageName, packageMinimum ->',
    '        }',
    '    }',
    '}',
].join('\n');

function packageXml(name, { branchMissed, branchCovered, lineMissed = 1, lineCovered = 9 }) {
    return [
        `<package name="${name.replaceAll('.', '/')}">`,
        `<counter type="BRANCH" missed="${branchMissed}" covered="${branchCovered}"/>`,
        `<counter type="LINE" missed="${lineMissed}" covered="${lineCovered}"/>`,
        '</package>',
    ].join('');
}

function reportXml({ globalBranch = [10, 90], globalLine = [5, 95] } = {}) {
    return [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<report name="test">',
        packageXml('example.dto', { branchMissed: 1, branchCovered: 9 }),
        packageXml('example.database', { branchMissed: 9, branchCovered: 1 }),
        `<counter type="BRANCH" missed="${globalBranch[0]}" covered="${globalBranch[1]}"/>`,
        `<counter type="LINE" missed="${globalLine[0]}" covered="${globalLine[1]}"/>`,
        '</report>',
    ].join('');
}

test('build.gradle의 전체 및 패키지 최소선을 읽는다', () => {
    const rules = coverageRulesFromBuildFile(buildFile);
    assert.deepEqual(Object.fromEntries(rules.globalMinimums), { BRANCH: 0.72, LINE: 0.92 });
    assert.deepEqual(Object.fromEntries(rules.packageMinimums), {
        'example.dto': 0.8,
        'example.database': 0.9,
    });
});

test('JaCoCo 리포트의 마지막 counter를 패키지와 전체 합계로 읽는다', () => {
    const coverage = coverageFromJacocoXml(reportXml());
    assert.deepEqual(coverage.packages.get('example.dto').branch, { missed: 1, covered: 9 });
    assert.deepEqual(coverage.totals.branch, { missed: 10, covered: 90 });
    assert.deepEqual(coverage.totals.line, { missed: 5, covered: 95 });
});

test('변경하지 않은 PostgreSQL 의존 패키지의 낮은 H2 비율은 안전 경로를 막지 않는다', () => {
    const result = verifyChangedH2Coverage({
        buildFileText: buildFile,
        reportXml: reportXml(),
        changedPackages: ['example.dto'],
    });

    assert.deepEqual(result.problems, []);
    assert.deepEqual(result.checkedPackages.map((entry) => entry.packageName), ['example.dto']);
});

test('실제로 변경한 패키지의 H2 최소선 미달은 실패한다', () => {
    const result = verifyChangedH2Coverage({
        buildFileText: buildFile,
        reportXml: reportXml(),
        changedPackages: ['example.database'],
    });

    assert.equal(result.problems.length, 1);
    assert.match(result.problems[0], /example\.database.*10\.00%.*90\.00%/u);
});

test('전체 branch 또는 line 최소선 미달은 변경 패키지와 무관하게 실패한다', () => {
    const result = verifyChangedH2Coverage({
        buildFileText: buildFile,
        reportXml: reportXml({ globalBranch: [30, 70], globalLine: [10, 90] }),
        changedPackages: ['example.dto'],
    });

    assert.equal(result.problems.length, 2);
    assert.match(result.problems[0], /전체 BRANCH/u);
    assert.match(result.problems[1], /전체 LINE/u);
});

test('CLI는 git diff에서 변경 Java 패키지를 읽어 H2 게이트를 적용한다', (t) => {
    const worktree = fs.mkdtempSync(path.join(os.tmpdir(), 'changed-h2-coverage-'));
    t.after(() => fs.rmSync(worktree, { recursive: true, force: true }));
    const sourcePath = path.join(worktree, 'src/main/java/example/dto/Response.java');
    const reportPath = path.join(worktree, 'jacoco.xml');
    fs.mkdirSync(path.dirname(sourcePath), { recursive: true });
    fs.writeFileSync(path.join(worktree, 'build.gradle'), buildFile, 'utf8');
    fs.writeFileSync(sourcePath, 'package example.dto;\npublic record Response(long id) {}\n', 'utf8');
    fs.writeFileSync(reportPath, reportXml(), 'utf8');

    const git = (...args) =>
        spawnSync('git', ['-C', worktree, ...args], { encoding: 'utf8', windowsHide: true });
    git('init', '--quiet');
    git('add', '--all');
    git('-c', 'user.name=test', '-c', 'user.email=test@example.com', 'commit', '--quiet', '-m', 'base');
    fs.writeFileSync(
        sourcePath,
        'package example.dto;\npublic record Response(long id, String name) {}\n',
        'utf8',
    );

    const result = spawnSync(
        process.execPath,
        [scriptPath, '--report', reportPath, '--worktree', worktree, '--base', 'HEAD'],
        { encoding: 'utf8' },
    );

    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /example\.dto/u);
});
