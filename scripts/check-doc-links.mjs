#!/usr/bin/env node
// 저장소 Markdown 문서의 상대 링크와 앵커가 실제로 존재하는지 검사한다.
// 정본 문서가 서로를 링크로 참조하므로 깨진 경로와 앵커를 빌드 실패와 같은 급으로 다룬다.
// 외부 링크(http, https, mailto)는 검사하지 않는다. 의존성 없이 Node.js 20 이상에서 실행한다.
//
// destination을 뽑지 못한 링크는 건너뛰지 않고 `파싱 실패`로 보고한다. 검사기가 조용히
// 지나친 링크가 통과로 보이면 게이트의 의미가 없기 때문이다.
// 회귀 입력은 scripts/check-doc-links.test.mjs에 고정한다.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export function repoRootOf(cwd = process.cwd()) {
    return execFileSync('git', ['rev-parse', '--show-toplevel'], {
        cwd,
        encoding: 'utf8',
    }).trim();
}

export function listMarkdownFiles(repoRoot) {
    const output = execFileSync(
        'git',
        ['ls-files', '-z', '--cached', '--others', '--exclude-standard', '*.md'],
        { cwd: repoRoot, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 },
    );
    return [...new Set(output.split('\0').filter(Boolean))].sort();
}

// 코드 블록 안의 링크와 주석을 문서 내용으로 오인하지 않도록 펜스 구간을 빈 줄로 바꾼다.
export function linesOutsideCodeFences(text) {
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

// 인라인 코드 스팬을 같은 길이의 공백으로 덮는다. 여는 백틱 런과 길이가 같은 런만
// 닫는 런으로 인정하며, 백슬래시로 이스케이프한 백틱은 구분자로 보지 않는다.
export function maskInlineCode(line) {
    const runs = [];
    for (const match of line.matchAll(/`+/g)) {
        if (match.index > 0 && line[match.index - 1] === '\\') continue;
        runs.push({ start: match.index, length: match[0].length });
    }

    let masked = line;
    let index = 0;
    while (index < runs.length) {
        const open = runs[index];
        const closeIndex = runs.findIndex(
            (run, position) => position > index && run.length === open.length,
        );
        if (closeIndex === -1) {
            index += 1;
            continue;
        }
        const close = runs[closeIndex];
        const end = close.start + close.length;
        masked = masked.slice(0, open.start) + ' '.repeat(end - open.start) + masked.slice(end);
        index = closeIndex + 1;
    }
    return masked;
}

// CommonMark 인라인 링크의 destination만 뽑는다. `start`는 여는 괄호 다음 위치다.
// `<...>` 형식, 균형 잡힌 괄호, 백슬래시 이스케이프와 선택 title을 처리한다.
// 문법에 맞지 않으면 null을 반환해 호출자가 파싱 실패로 보고하게 한다.
export function parseLinkDestination(text, start) {
    let cursor = start;
    const skipSpaces = () => {
        while (cursor < text.length && /\s/.test(text[cursor])) cursor += 1;
    };

    skipSpaces();

    let raw = '';
    if (text[cursor] === '<') {
        cursor += 1;
        while (cursor < text.length && text[cursor] !== '>') {
            if (text[cursor] === '\\' && cursor + 1 < text.length) {
                raw += text[cursor] + text[cursor + 1];
                cursor += 2;
                continue;
            }
            raw += text[cursor];
            cursor += 1;
        }
        if (text[cursor] !== '>') return null;
        cursor += 1;
    } else {
        let depth = 0;
        while (cursor < text.length) {
            const character = text[cursor];
            if (character === '\\' && cursor + 1 < text.length) {
                raw += character + text[cursor + 1];
                cursor += 2;
                continue;
            }
            if (/\s/.test(character)) break;
            if (character === '(') depth += 1;
            if (character === ')') {
                if (depth === 0) break;
                depth -= 1;
            }
            raw += character;
            cursor += 1;
        }
        if (depth !== 0) return null;
    }

    skipSpaces();

    const titleOpen = text[cursor];
    if (titleOpen === '"' || titleOpen === "'" || titleOpen === '(') {
        const titleClose = titleOpen === '(' ? ')' : titleOpen;
        cursor += 1;
        while (cursor < text.length && text[cursor] !== titleClose) {
            cursor += text[cursor] === '\\' && cursor + 1 < text.length ? 2 : 1;
        }
        if (text[cursor] !== titleClose) return null;
        cursor += 1;
        skipSpaces();
    }

    if (text[cursor] !== ')') return null;
    return { destination: raw.replace(/\\(.)/g, '$1'), end: cursor + 1 };
}

const REFERENCE_DEFINITION = /^\s{0,3}\[[^\]]+\]:\s*(\S+)/;

// 한 줄에서 검사할 링크 destination과 파싱 실패 조각을 함께 반환한다.
export function linksIn(line) {
    const masked = maskInlineCode(line);
    const targets = [];
    const failures = [];

    for (let index = 0; index + 1 < masked.length; index += 1) {
        if (masked[index] !== ']' || masked[index + 1] !== '(') continue;
        if (index > 0 && masked[index - 1] === '\\') continue;

        const parsed = parseLinkDestination(masked, index + 2);
        if (parsed === null) {
            failures.push(masked.slice(index, index + 60).trimEnd());
            continue;
        }
        targets.push(parsed.destination);
        index = parsed.end - 1;
    }

    const definition = REFERENCE_DEFINITION.exec(masked);
    if (definition) targets.push(definition[1].replace(/^<(.*)>$/, '$1'));

    return { targets, failures };
}

// GitHub이 제목에 부여하는 앵커 규칙을 따른다. 같은 제목이 반복되면 -1, -2를 덧붙인다.
export function headingAnchor(heading) {
    return heading
        .trim()
        .toLowerCase()
        .replace(/[`*_~]/g, '')
        .replace(/[^\p{L}\p{N}\s-]/gu, '')
        .replace(/\s+/g, '-');
}

export function anchorsIn(text) {
    const anchors = new Set();
    const seenCounts = new Map();
    for (const line of linesOutsideCodeFences(text)) {
        const heading = /^#{1,6}\s+(.+?)\s*$/.exec(line);
        if (!heading) continue;
        const base = headingAnchor(heading[1]);
        const seen = seenCounts.get(base) ?? 0;
        seenCounts.set(base, seen + 1);
        anchors.add(seen === 0 ? base : `${base}-${seen}`);
    }
    return anchors;
}

function isExistingFile(absolutePath) {
    return fs.statSync(absolutePath, { throwIfNoEntry: false })?.isFile() ?? false;
}

// 인덱스에는 남아 있지만 작업 트리에서 사라진 경로를 검사 원본에서 제외한다.
// 일반 `mv`·`rm` 뒤 스테이징 전에도 남은 문서의 깨진 참조를 정상 보고하기 위한 것이다.
export function runCheck({ repoRoot, files }) {
    const problems = [];
    const anchorCache = new Map();
    let checkedLinks = 0;

    const sources = files.filter((file) => isExistingFile(path.join(repoRoot, file)));

    const anchorsOf = (absolutePath) => {
        const cached = anchorCache.get(absolutePath);
        if (cached) return cached;
        const anchors = anchorsIn(fs.readFileSync(absolutePath, 'utf8'));
        anchorCache.set(absolutePath, anchors);
        return anchors;
    };

    for (const file of sources) {
        const absoluteFile = path.join(repoRoot, file);
        const lines = linesOutsideCodeFences(fs.readFileSync(absoluteFile, 'utf8'));

        lines.forEach((line, lineIndex) => {
            const location = `${file}:${lineIndex + 1}`;
            const { targets, failures } = linksIn(line);

            for (const failure of failures) {
                problems.push({ location, kind: '파싱 실패', detail: failure });
            }

            for (const target of targets) {
                if (/^(https?:|mailto:|#!)/i.test(target)) continue;

                const hashAt = target.indexOf('#');
                const rawPath = hashAt === -1 ? target : target.slice(0, hashAt);
                const rawHash = hashAt === -1 ? '' : target.slice(hashAt + 1);
                if (rawPath === '' && rawHash === '') continue;

                checkedLinks += 1;

                let absoluteTarget = absoluteFile;
                if (rawPath !== '') {
                    absoluteTarget = path.resolve(
                        path.dirname(absoluteFile),
                        decodeURIComponent(rawPath),
                    );
                    if (!fs.existsSync(absoluteTarget)) {
                        problems.push({ location, kind: '없는 파일', detail: target });
                        continue;
                    }
                }

                if (rawHash === '' || !absoluteTarget.toLowerCase().endsWith('.md')) continue;
                if (!anchorsOf(absoluteTarget).has(decodeURIComponent(rawHash).toLowerCase())) {
                    problems.push({ location, kind: '없는 앵커', detail: target });
                }
            }
        });
    }

    return { sources, checkedLinks, problems };
}

const invokedDirectly =
    process.argv[1] !== undefined && pathToFileURL(process.argv[1]).href === import.meta.url;

if (invokedDirectly) {
    const repoRoot = repoRootOf();
    const { sources, checkedLinks, problems } = runCheck({
        repoRoot,
        files: listMarkdownFiles(repoRoot),
    });

    console.log(`Markdown 파일 ${sources.length}개에서 내부 링크 ${checkedLinks}개를 검사했다.`);

    if (problems.length === 0) {
        console.log('깨진 상대 링크와 앵커가 없다.');
    } else {
        console.error(`\n문제 ${problems.length}개를 찾았다.`);
        for (const problem of problems) {
            console.error(`  ${problem.location}  ${problem.kind}    ${problem.detail}`);
        }
        console.error('\n링크 대상 경로와 제목 앵커를 확인한 뒤 다시 실행한다.');
        process.exitCode = 1;
    }
}
