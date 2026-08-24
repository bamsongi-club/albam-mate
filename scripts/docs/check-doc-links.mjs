#!/usr/bin/env node
// 저장소 Markdown 문서의 상대 링크와 앵커가 실제로 존재하는지 검사한다.
// 정본 문서가 서로를 링크로 참조하므로 깨진 경로와 앵커를 빌드 실패와 같은 급으로 다룬다.
// 외부 링크(http, https, mailto)는 검사하지 않는다. 의존성 없이 Node.js 20 이상에서 실행한다.
//
// Markdown은 블록 단위 문법이므로 링크 추출도 줄이 아니라 문서 단위로 수행한다. 코드 펜스와
// 들여쓰기 코드 블록, 인라인 코드 스팬을 먼저 덮은 뒤 남은 본문에서 destination을 뽑는다.
// destination을 뽑지 못한 링크는 건너뛰지 않고 `파싱 실패`로 보고한다. 검사기가 조용히
// 지나친 링크가 통과로 보이면 게이트의 의미가 없기 때문이다.
// 회귀 입력은 scripts/docs/check-doc-links.test.mjs에 고정한다.

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

const LIST_MARKER = /^ {0,3}([-*+]|\d{1,9}[.)])\s/;

// 코드 블록 안의 링크와 제목을 본문으로 오인하지 않도록 해당 줄을 빈 줄로 바꾼다.
// 들여쓰기 코드 블록은 빈 줄 뒤에서만 시작하며, 리스트 안의 들여쓰기는 continuation이므로
// 코드로 보지 않는다. 리스트 항목의 링크를 조용히 건너뛰면 오탐보다 나쁜 누락이 된다.
export function linesOutsideCodeBlocks(text) {
    let openFence = null;
    let previousBlank = true;
    let inIndentedCode = false;
    let inList = false;

    return text.split('\n').map((line) => {
        const fence = /^\s*(```+|~~~+)/.exec(line);
        if (fence) {
            const marker = fence[1][0];
            if (openFence === null) openFence = marker;
            else if (openFence === marker) openFence = null;
            previousBlank = false;
            inIndentedCode = false;
            return '';
        }
        if (openFence !== null) return '';

        if (line.trim() === '') {
            previousBlank = true;
            return '';
        }

        const indent = /^ */.exec(line)[0].length;
        if (LIST_MARKER.test(line)) inList = true;
        else if (indent === 0) inList = false;

        if (indent >= 4 && !inList && (previousBlank || inIndentedCode)) {
            inIndentedCode = true;
            previousBlank = false;
            return '';
        }

        inIndentedCode = false;
        previousBlank = false;
        return line;
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
// `<...>` 형식, 균형 잡힌 괄호, 백슬래시 이스케이프와 선택 title을 처리하고, 공백에는
// 줄바꿈을 최대 하나만 허용한다. 문법에 맞지 않으면 null을 반환해 파싱 실패로 보고하게 한다.
export function parseLinkDestination(text, start) {
    let cursor = start;

    const skipWhitespace = () => {
        let newlines = 0;
        while (cursor < text.length && /\s/.test(text[cursor])) {
            if (text[cursor] === '\n') {
                newlines += 1;
                if (newlines > 1) return false;
            }
            cursor += 1;
        }
        return true;
    };

    if (!skipWhitespace()) return null;

    let raw = '';
    if (text[cursor] === '<') {
        cursor += 1;
        while (cursor < text.length && text[cursor] !== '>') {
            if (text[cursor] === '\n') return null;
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

    if (!skipWhitespace()) return null;

    const titleOpen = text[cursor];
    if (titleOpen === '"' || titleOpen === "'" || titleOpen === '(') {
        const titleClose = titleOpen === '(' ? ')' : titleOpen;
        cursor += 1;
        while (cursor < text.length && text[cursor] !== titleClose) {
            cursor += text[cursor] === '\\' && cursor + 1 < text.length ? 2 : 1;
        }
        if (text[cursor] !== titleClose) return null;
        cursor += 1;
        if (!skipWhitespace()) return null;
    }

    if (text[cursor] !== ')') return null;
    return { destination: raw.replace(/\\(.)/g, '$1'), end: cursor + 1 };
}

// 닫는 대괄호에 대응하는 여는 대괄호가 같은 문단 안에 있는지 확인한다.
// 대응 짝이 없으면 링크가 아니라 본문에 쓰인 `](`이므로 destination을 파싱하지 않는다.
export function hasLinkTextBefore(text, closeBracket) {
    let depth = 0;
    for (let index = closeBracket - 1; index >= 0; index -= 1) {
        if (text[index] === '\n' && index > 0 && text[index - 1] === '\n') return false;
        if (index > 0 && text[index - 1] === '\\') {
            index -= 1;
            continue;
        }
        if (text[index] === ']') depth += 1;
        else if (text[index] === '[') {
            if (depth === 0) return true;
            depth -= 1;
        }
    }
    return false;
}

const REFERENCE_DEFINITION = /^ {0,3}\[[^\]]+\]:\s*(\S+)/;

// 문서 전체에서 검사할 링크 destination과 파싱 실패 조각을 줄 번호와 함께 반환한다.
export function linksIn(text) {
    const lines = linesOutsideCodeBlocks(text).map(maskInlineCode);
    const body = lines.join('\n');

    const lineStarts = [0];
    for (let index = 0; index < body.length; index += 1) {
        if (body[index] === '\n') lineStarts.push(index + 1);
    }
    const lineOf = (offset) => {
        let low = 0;
        let high = lineStarts.length - 1;
        while (low < high) {
            const middle = Math.ceil((low + high) / 2);
            if (lineStarts[middle] <= offset) low = middle;
            else high = middle - 1;
        }
        return low + 1;
    };

    const targets = [];
    const failures = [];

    for (let index = 0; index + 1 < body.length; index += 1) {
        if (body[index] !== ']' || body[index + 1] !== '(') continue;
        if (index > 0 && body[index - 1] === '\\') continue;
        if (!hasLinkTextBefore(body, index)) continue;

        const parsed = parseLinkDestination(body, index + 2);
        if (parsed === null) {
            failures.push({
                snippet: body.slice(index, index + 60).split('\n')[0].trimEnd(),
                line: lineOf(index),
            });
            continue;
        }
        targets.push({ destination: parsed.destination, line: lineOf(index) });
        index = parsed.end - 1;
    }

    lines.forEach((line, lineIndex) => {
        const definition = REFERENCE_DEFINITION.exec(line);
        if (definition) {
            targets.push({
                destination: definition[1].replace(/^<(.*)>$/, '$1'),
                line: lineIndex + 1,
            });
        }
    });

    return { targets, failures };
}

// GitHub이 제목에 부여하는 앵커 규칙을 따른다. 같은 제목이 반복되면 -1, -2를 덧붙인다.
export function headingAnchor(heading) {
    return heading
        .trim()
        .toLowerCase()
        .replace(/[`*~]/g, '')
        .replace(/[^\p{L}\p{N}\s_-]/gu, '')
        .replace(/\s+/g, '-');
}

export function anchorsIn(text) {
    const anchors = new Set();
    const seenCounts = new Map();
    for (const line of linesOutsideCodeBlocks(text)) {
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

// ADR 템플릿의 상대 링크는 파일 원위치가 아니라 도메인 폴더에 복사된 위치를 기준으로 한다.
function linkBaseDirectory(repoRoot, file, absoluteFile) {
    const normalizedFile = file.split(path.sep).join('/');
    if (normalizedFile === 'docs/adr/0000-template.md') {
        return path.join(repoRoot, 'docs', 'adr', '__template-domain__');
    }
    return path.dirname(absoluteFile);
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
        const { targets, failures } = linksIn(fs.readFileSync(absoluteFile, 'utf8'));

        for (const failure of failures) {
            problems.push({
                location: `${file}:${failure.line}`,
                kind: '파싱 실패',
                detail: failure.snippet,
            });
        }

        for (const { destination, line } of targets) {
            if (/^(https?:|mailto:|#!)/i.test(destination)) continue;

            const hashAt = destination.indexOf('#');
            const rawPath = hashAt === -1 ? destination : destination.slice(0, hashAt);
            const rawHash = hashAt === -1 ? '' : destination.slice(hashAt + 1);
            if (rawPath === '' && rawHash === '') continue;

            checkedLinks += 1;
            const location = `${file}:${line}`;

            let absoluteTarget = absoluteFile;
            if (rawPath !== '') {
                absoluteTarget = path.resolve(
                    linkBaseDirectory(repoRoot, file, absoluteFile),
                    decodeURIComponent(rawPath),
                );
                if (!fs.existsSync(absoluteTarget)) {
                    problems.push({ location, kind: '없는 파일', detail: destination });
                    continue;
                }
            }

            if (rawHash === '' || !absoluteTarget.toLowerCase().endsWith('.md')) continue;
            if (!anchorsOf(absoluteTarget).has(decodeURIComponent(rawHash).toLowerCase())) {
                problems.push({ location, kind: '없는 앵커', detail: destination });
            }
        }
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
