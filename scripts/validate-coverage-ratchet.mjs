#!/usr/bin/env node
// build.gradle의 gatedBranchCoverage 변경이 래칫 예외로 허용된 형태인지 판정한다.
// 허용되는 변경은 새 패키지 항목 추가와 기존 최소선 상향뿐이며, 하향·삭제와 맵 밖의
// build 변경은 저위험 전달의 예외가 아니라 full-delivery 재분류 신호다.
//
// 지금까지 이 판정은 사람이 `git diff HEAD -- build.gradle`을 읽고 내렸다. 조건이 여러 겹인
// 판정을 산문으로 두면 확인 여부를 검증할 수 없으므로 결정적 검사로 옮긴다.
// 회귀 입력은 scripts/validate-coverage-ratchet.test.mjs에 고정한다.
// 의존성 없이 Node.js 20 이상에서 실행한다.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const MAP_START = /^\s*def\s+gatedBranchCoverage\s*=\s*\[\s*$/u;
const MAP_END = /^\s*\]\s*$/u;
const MAP_ENTRY = /^\s*'([\w.]+)'\s*:\s*(\d+(?:\.\d+)?)\s*,?\s*$/u;
const HUNK_HEADER = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/u;

// 맵 블록의 1-based 줄 범위를 찾는다. 블록을 못 찾으면 어떤 변경도 래칫으로 인정하지 않는다.
export function mapBlockRange(buildFileText) {
    const lines = buildFileText.split('\n');
    const startIndex = lines.findIndex((line) => MAP_START.test(line));
    if (startIndex === -1) {
        return null;
    }
    for (let index = startIndex + 1; index < lines.length; index += 1) {
        if (MAP_END.test(lines[index])) {
            return { start: startIndex + 1, end: index + 1 };
        }
    }
    return null;
}

function addProblem(problems, detail) {
    problems.push(detail);
}

// Groovy 맵은 같은 키가 여러 번 나오면 뒤쪽 값이 유효값이 된다. 따라서 기존 패키지를 더 낮은
// 값으로 맵 아래쪽에 다시 추가하면 삭제 줄이 없어 추가·상향 판정만으로는 통과하는데 실제
// 최소선은 내려간다. post-image 맵 전체에서 중복 키를 거부해 이 우회를 막는다.
export function duplicateEntryKeys(buildFileText, range) {
    const lines = buildFileText.split('\n');
    const counts = new Map();
    const duplicates = [];
    for (let index = range.start; index < range.end - 1; index += 1) {
        const entry = MAP_ENTRY.exec(lines[index]);
        if (!entry) {
            continue;
        }
        const count = (counts.get(entry[1]) ?? 0) + 1;
        counts.set(entry[1], count);
        if (count === 2) {
            duplicates.push(entry[1]);
        }
    }
    return duplicates;
}

// diff의 추가·삭제 줄만 본다. 추가 줄은 post-image 줄 번호를 함께 모아 맵 블록 안인지 확인한다.
function collectChangedEntryLines(diffText, problems) {
    const added = [];
    const removed = [];
    let newLine = 0;
    let inHunk = false;

    for (const line of diffText.split('\n')) {
        if (line.startsWith('diff --git ')) {
            inHunk = false;
            if (!/ b\/build\.gradle$/u.test(line)) {
                addProblem(problems, `build.gradle 밖의 파일이 diff에 있습니다: ${line}`);
            }
            continue;
        }
        const hunk = HUNK_HEADER.exec(line);
        if (hunk) {
            newLine = Number(hunk[1]);
            inHunk = true;
            continue;
        }
        if (!inHunk || line.startsWith('\\')) {
            continue;
        }

        if (line.startsWith('+')) {
            added.push({ text: line.slice(1), line: newLine });
            newLine += 1;
            continue;
        }
        if (line.startsWith('-')) {
            removed.push({ text: line.slice(1) });
            continue;
        }
        newLine += 1;
    }

    return { added, removed };
}

function parseEntries(changedLines, kind, problems) {
    const entries = new Map();
    for (const changed of changedLines) {
        const entry = MAP_ENTRY.exec(changed.text);
        if (!entry) {
            addProblem(
                problems,
                `gatedBranchCoverage 항목이 아닌 ${kind} 줄이 있습니다: ${changed.text.trim()}`,
            );
            continue;
        }
        entries.set(entry[1], { minimum: Number(entry[2]), line: changed.line });
    }
    return entries;
}

export function validateCoverageRatchetDiff(diffText, buildFileText) {
    const problems = [];
    if (diffText.trim() === '') {
        return problems;
    }

    const range = mapBlockRange(buildFileText);
    if (range === null) {
        addProblem(problems, 'build.gradle에서 gatedBranchCoverage 맵 블록을 찾을 수 없습니다.');
        return problems;
    }

    for (const packageName of duplicateEntryKeys(buildFileText, range)) {
        addProblem(
            problems,
            `gatedBranchCoverage에 같은 패키지 키가 두 번 있습니다. 뒤쪽 값이 유효값이 되므로 최소선을 낮출 수 있습니다: ${packageName}`,
        );
    }

    const { added, removed } = collectChangedEntryLines(diffText, problems);
    const addedEntries = parseEntries(added, '추가', problems);
    const removedEntries = parseEntries(removed, '삭제', problems);

    for (const [packageName, entry] of addedEntries) {
        if (entry.line < range.start || entry.line > range.end) {
            addProblem(
                problems,
                `gatedBranchCoverage 맵 블록(${range.start}~${range.end}줄) 밖의 변경입니다: ${packageName} (${entry.line}줄)`,
            );
        }
    }

    for (const [packageName, entry] of addedEntries) {
        const previous = removedEntries.get(packageName);
        if (previous && entry.minimum <= previous.minimum) {
            addProblem(
                problems,
                `최소선 상향이 아닙니다: ${packageName} ${previous.minimum} → ${entry.minimum}`,
            );
        }
    }

    for (const packageName of removedEntries.keys()) {
        if (!addedEntries.has(packageName)) {
            addProblem(problems, `래칫 항목을 삭제했습니다: ${packageName}`);
        }
    }

    return problems;
}

export function repoRootOf(cwd = process.cwd()) {
    return execFileSync('git', ['rev-parse', '--show-toplevel'], { cwd, encoding: 'utf8' }).trim();
}

// base를 주지 않으면 worktree와 HEAD를 비교한다. 커밋을 만든 뒤에는 그 diff가 비므로 고정한
// Draft head를 검증할 때는 base를 함께 넘겨 두 커밋 사이의 래칫 변경을 검사한다.
export function validateCoverageRatchetInRepo(repoRoot, { base = null, head = null } = {}) {
    const git = (args) =>
        execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
    const diffArgs =
        base === null
            ? ['diff', head ?? 'HEAD', '--', 'build.gradle']
            : ['diff', base, head ?? 'HEAD', '--', 'build.gradle'];
    const diffText = git(diffArgs);
    const buildFileText =
        base === null && head === null
            ? fs.readFileSync(path.join(repoRoot, 'build.gradle'), 'utf8')
            : git(['show', `${head ?? 'HEAD'}:build.gradle`]);
    return { diffText, problems: validateCoverageRatchetDiff(diffText, buildFileText) };
}

function parseArguments(argv) {
    const values = { base: null, head: null };
    const allowed = new Set(['--base', '--head']);
    for (let index = 0; index < argv.length; index += 2) {
        const option = argv[index];
        const value = argv[index + 1];
        if (!allowed.has(option) || value === undefined || value.startsWith('--')) {
            return null;
        }
        values[option.slice(2)] = value;
    }
    return values;
}

function runCli() {
    const args = parseArguments(process.argv.slice(2));
    if (args === null) {
        console.error('사용법: node scripts/validate-coverage-ratchet.mjs [--base <ref>] [--head <ref>]');
        process.exitCode = 2;
        return;
    }

    try {
        const repoRoot = repoRootOf();
        const { diffText, problems } = validateCoverageRatchetInRepo(repoRoot, args);
        if (problems.length > 0) {
            console.error('커버리지 래칫 예외로 허용할 수 없는 build.gradle 변경이 있습니다.');
            for (const problem of problems) {
                console.error(`- ${problem}`);
            }
            console.error('\n이 변경은 full-delivery 범위로 다시 분류한다.');
            process.exitCode = 1;
            return;
        }
        console.log(
            diffText.trim() === ''
                ? 'build.gradle 변경이 없다.'
                : 'build.gradle 변경이 gatedBranchCoverage 항목 추가와 최소선 상향뿐이다.',
        );
    } catch (error) {
        console.error(`커버리지 래칫 검증 실패: ${error.message}`);
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : null;
if (entryPoint === import.meta.url) {
    runCli();
}
