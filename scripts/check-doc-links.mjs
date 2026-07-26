#!/usr/bin/env node
// 저장소 Markdown 문서의 상대 링크와 앵커가 실제로 존재하는지 검사한다.
// 정본 문서가 서로를 링크로 참조하므로 깨진 경로와 앵커를 빌드 실패와 같은 급으로 다룬다.
// 외부 링크(http, https, mailto)는 검사하지 않는다. 의존성 없이 Node.js 20 이상에서 실행한다.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const repoRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], {
    encoding: 'utf8',
}).trim();

function listMarkdownFiles() {
    const output = execFileSync(
        'git',
        ['ls-files', '-z', '--cached', '--others', '--exclude-standard', '*.md'],
        { cwd: repoRoot, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 },
    );
    return [...new Set(output.split('\0').filter(Boolean))].sort();
}

// 코드 블록 안의 링크와 주석을 문서 내용으로 오인하지 않도록 펜스 구간을 빈 줄로 바꾼다.
function linesOutsideCodeFences(text) {
    let openFence = null;
    return text.split('\n').map((line) => {
        const fence = /^\s*(```+|~~~+)/.exec(line);
        if (fence) {
            const marker = fence[1][0];
            if (openFence === null) openFence = marker;
            else if (openFence === marker) openFence = null;
            return '';
        }
        return openFence === null ? line : '';
    });
}

// GitHub이 제목에 부여하는 앵커 규칙을 따른다. 같은 제목이 반복되면 -1, -2를 덧붙인다.
function headingAnchor(heading) {
    return heading
        .trim()
        .toLowerCase()
        .replace(/[`*_~]/g, '')
        .replace(/[^\p{L}\p{N}\s-]/gu, '')
        .replace(/\s+/g, '-');
}

const anchorCache = new Map();

function anchorsOf(absolutePath) {
    const cached = anchorCache.get(absolutePath);
    if (cached) return cached;

    const anchors = new Set();
    const seenCounts = new Map();
    for (const line of linesOutsideCodeFences(fs.readFileSync(absolutePath, 'utf8'))) {
        const heading = /^#{1,6}\s+(.+?)\s*$/.exec(line);
        if (!heading) continue;
        const base = headingAnchor(heading[1]);
        const seen = seenCounts.get(base) ?? 0;
        seenCounts.set(base, seen + 1);
        anchors.add(seen === 0 ? base : `${base}-${seen}`);
    }

    anchorCache.set(absolutePath, anchors);
    return anchors;
}

const INLINE_LINK = /\[[^\]]*\]\(([^)\s]+)\)/g;
const REFERENCE_LINK = /^\s{0,3}\[[^\]]+\]:\s*(\S+)/;

function targetsIn(line) {
    const targets = [...line.matchAll(INLINE_LINK)].map((match) => match[1]);
    const reference = REFERENCE_LINK.exec(line);
    if (reference) targets.push(reference[1]);
    return targets;
}

const files = listMarkdownFiles();
const problems = [];
let checkedLinks = 0;

for (const file of files) {
    const absoluteFile = path.join(repoRoot, file);
    linesOutsideCodeFences(fs.readFileSync(absoluteFile, 'utf8')).forEach((line, index) => {
        for (const target of targetsIn(line)) {
            if (/^(https?:|mailto:|#!)/i.test(target)) continue;

            const hashAt = target.indexOf('#');
            const rawPath = hashAt === -1 ? target : target.slice(0, hashAt);
            const rawHash = hashAt === -1 ? '' : target.slice(hashAt + 1);
            if (rawPath === '' && rawHash === '') continue;

            checkedLinks += 1;
            const location = `${file}:${index + 1}`;

            let absoluteTarget = absoluteFile;
            if (rawPath !== '') {
                absoluteTarget = path.resolve(
                    path.dirname(absoluteFile),
                    decodeURIComponent(rawPath),
                );
                if (!fs.existsSync(absoluteTarget)) {
                    problems.push(`${location}  없는 파일    ${target}`);
                    continue;
                }
            }

            if (rawHash === '' || !absoluteTarget.toLowerCase().endsWith('.md')) continue;
            if (!anchorsOf(absoluteTarget).has(decodeURIComponent(rawHash).toLowerCase())) {
                problems.push(`${location}  없는 앵커    ${target}`);
            }
        }
    });
}

console.log(`Markdown 파일 ${files.length}개에서 내부 링크 ${checkedLinks}개를 검사했다.`);

if (problems.length === 0) {
    console.log('깨진 상대 링크와 앵커가 없다.');
    process.exit(0);
}

console.error(`\n깨진 링크 ${problems.length}개를 찾았다.`);
for (const problem of problems) console.error(`  ${problem}`);
console.error('\n링크 대상 경로와 제목 앵커를 확인한 뒤 다시 실행한다.');
process.exit(1);
