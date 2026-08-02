#!/usr/bin/env node

import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const SEVERITIES = Object.freeze({
    critical: '🔴',
    major: '🟠',
    minor: '🟡',
    nit: '⚪',
});

export const INLINE_SECTION_HEADERS = Object.freeze({
    problem: '**🔍 문제점**',
    fix: '**🔧 수정 방향**',
    checked: '**✅ 확인한 점**',
});

export const SUMMARY_HEADERS = Object.freeze({
    resolved: '### 이전 지적 해소 확인',
    strengths: '### 잘된 점',
    findings: '### 주요 지적 (critical/major만)',
    unanchored: '### 앵커할 수 없는 지적',
    uncovered: '### 미검토 범위',
    actions: '### 다음 액션',
});

const SUMMARY_HEADER_ORDER = Object.keys(SUMMARY_HEADERS);
const REQUIRED_SUMMARY_HEADERS = new Set(['strengths', 'findings', 'actions']);
const TOP_LEVEL_FIELDS = new Set(['commit_id', 'body', 'event', 'comments']);
const COMMENT_FIELDS = new Set(['path', 'line', 'side', 'body']);

export class ReviewPayloadValidationError extends Error {
    constructor(errors) {
        super(`Review payload validation failed with ${errors.length} error(s).`);
        this.name = 'ReviewPayloadValidationError';
        this.errors = errors;
    }
}

function isPlainObject(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function normalizeNewlines(value) {
    return value.replace(/\r\n?/gu, '\n');
}

function stripByteOrderMark(value) {
    return value.charCodeAt(0) === 0xfeff ? value.slice(1) : value;
}

export function parsePayloadJson(payloadText) {
    try {
        return JSON.parse(stripByteOrderMark(payloadText));
    } catch (error) {
        throw new ReviewPayloadValidationError([`payload is not valid JSON: ${error.message}`]);
    }
}

function countExactLine(lines, expected) {
    return lines.reduce((count, line) => count + (line === expected ? 1 : 0), 0);
}

function findExactLine(lines, expected) {
    return lines.findIndex((line) => line === expected);
}

function validateRepositoryPath(value, label, errors) {
    if (typeof value !== 'string' || value.length === 0) {
        errors.push(`${label} must be a non-empty repository-relative path.`);
        return false;
    }

    if (
        value.startsWith('/') ||
        value.includes('\\') ||
        value.split('/').some((segment) => segment.length === 0 || segment === '.' || segment === '..')
    ) {
        errors.push(`${label} must use a normalized repository-relative path: ${JSON.stringify(value)}.`);
        return false;
    }

    return true;
}

function decodeGitQuotedPath(value) {
    if (!(value.startsWith('"') && value.endsWith('"'))) {
        return value;
    }

    const source = value.slice(1, -1);
    const bytes = [];

    for (let index = 0; index < source.length; index += 1) {
        const character = source[index];
        if (character !== '\\') {
            bytes.push(...Buffer.from(character));
            continue;
        }

        index += 1;
        if (index >= source.length) {
            bytes.push(0x5c);
            break;
        }

        const escaped = source[index];
        const simpleEscapes = {
            '"': 0x22,
            '\\': 0x5c,
            a: 0x07,
            b: 0x08,
            f: 0x0c,
            n: 0x0a,
            r: 0x0d,
            t: 0x09,
            v: 0x0b,
        };

        if (Object.hasOwn(simpleEscapes, escaped)) {
            bytes.push(simpleEscapes[escaped]);
            continue;
        }

        if (/[0-7]/u.test(escaped)) {
            let octal = escaped;
            while (octal.length < 3 && index + 1 < source.length && /[0-7]/u.test(source[index + 1])) {
                index += 1;
                octal += source[index];
            }
            bytes.push(Number.parseInt(octal, 8));
            continue;
        }

        bytes.push(...Buffer.from(escaped));
    }

    return Buffer.from(bytes).toString('utf8');
}

function parseDiffMarkerPath(line) {
    const rawPath = line.slice(4).split('\t', 1)[0];
    if (rawPath === '/dev/null') {
        return null;
    }

    const decodedPath = decodeGitQuotedPath(rawPath);
    return decodedPath.startsWith('a/') || decodedPath.startsWith('b/')
        ? decodedPath.slice(2)
        : decodedPath;
}

export function parseDiffAnchors(diffText) {
    const anchors = new Set();
    const lines = normalizeNewlines(stripByteOrderMark(diffText)).split('\n');
    let oldPath = null;
    let newPath = null;
    let commentPath = null;
    let oldLine = 0;
    let newLine = 0;
    let inHunk = false;

    for (const line of lines) {
        if (line.startsWith('diff --git ')) {
            oldPath = null;
            newPath = null;
            commentPath = null;
            inHunk = false;
            continue;
        }

        const hunkMatch = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/u.exec(line);
        if (hunkMatch) {
            oldLine = Number.parseInt(hunkMatch[1], 10);
            newLine = Number.parseInt(hunkMatch[2], 10);
            inHunk = commentPath !== null;
            continue;
        }

        if (inHunk) {
            if (line.startsWith('\\ No newline at end of file')) {
                continue;
            }
            if (line.startsWith('+')) {
                anchors.add(`RIGHT\u0000${commentPath}\u0000${newLine}`);
                newLine += 1;
                continue;
            }
            if (line.startsWith('-')) {
                anchors.add(`LEFT\u0000${commentPath}\u0000${oldLine}`);
                oldLine += 1;
                continue;
            }
            if (line.startsWith(' ')) {
                anchors.add(`RIGHT\u0000${commentPath}\u0000${newLine}`);
                oldLine += 1;
                newLine += 1;
                continue;
            }
            inHunk = false;
        }

        if (line.startsWith('--- ')) {
            oldPath = parseDiffMarkerPath(line);
            continue;
        }

        if (line.startsWith('+++ ')) {
            newPath = parseDiffMarkerPath(line);
            commentPath = newPath ?? oldPath;
        }
    }

    return anchors;
}

function validateInlineSectionSpacing(lines, index, label, errors) {
    if (index <= 0 || lines[index - 1] !== '') {
        errors.push(`${label} must have a blank line before it.`);
    }
    if (index + 1 >= lines.length || lines[index + 1] === '') {
        errors.push(`${label} must be followed by non-empty content.`);
    }
}

function validateSummarySectionSpacing(lines, index, label, errors) {
    if (index <= 0 || lines[index - 1] !== '') {
        errors.push(`${label} must have a blank line before it.`);
    }
    if (index + 2 >= lines.length || lines[index + 1] !== '' || lines[index + 2] === '') {
        errors.push(`${label} must have one blank line before its non-empty content.`);
    }
}

function validateInlineBody(body, index, errors) {
    const label = `comments[${index}].body`;
    if (typeof body !== 'string') {
        errors.push(`${label} must be a string.`);
        return null;
    }

    const lines = normalizeNewlines(body).split('\n');
    const titleMatch = /^(?<emoji>🔴|🟠|🟡|⚪) (?<severity>critical|major|minor|nit) \| (?<title>\S.*)$/u.exec(lines[0]);
    if (!titleMatch) {
        errors.push(`${label} must start with "<severity emoji> <severity> | <title>".`);
        return null;
    }

    const { emoji, severity, title } = titleMatch.groups;
    if (SEVERITIES[severity] !== emoji) {
        errors.push(`${label} uses an emoji that does not match ${severity}.`);
    }

    if (lines.some((line) => line.startsWith('위치:'))) {
        errors.push(`${label} must omit the location line for an inline GitHub comment.`);
    }

    const problemIndex = findExactLine(lines, INLINE_SECTION_HEADERS.problem);
    const fixIndex = findExactLine(lines, INLINE_SECTION_HEADERS.fix);
    const checkedIndex = findExactLine(lines, INLINE_SECTION_HEADERS.checked);

    for (const [header, sectionLabel] of [
        [INLINE_SECTION_HEADERS.problem, 'problem section'],
        [INLINE_SECTION_HEADERS.fix, 'fix section'],
    ]) {
        if (countExactLine(lines, header) !== 1) {
            errors.push(`${label} must contain exactly one ${sectionLabel}.`);
        }
    }
    if (countExactLine(lines, INLINE_SECTION_HEADERS.checked) > 1) {
        errors.push(`${label} may contain at most one checked section.`);
    }

    if (problemIndex < 0 || fixIndex < 0) {
        return { severity, title };
    }
    if (problemIndex !== 2 || lines[1] !== '') {
        errors.push(`${label} must place the problem section after one blank line following the title.`);
    }
    if (fixIndex <= problemIndex) {
        errors.push(`${label} must place the fix section after the problem section.`);
        return { severity, title };
    }
    if (checkedIndex >= 0 && checkedIndex <= fixIndex) {
        errors.push(`${label} must place the checked section after the fix section.`);
    }

    validateInlineSectionSpacing(lines, problemIndex, `${label} problem section`, errors);
    validateInlineSectionSpacing(lines, fixIndex, `${label} fix section`, errors);
    if (checkedIndex >= 0) {
        validateInlineSectionSpacing(lines, checkedIndex, `${label} checked section`, errors);
    }

    const problemText = lines.slice(problemIndex + 1, fixIndex).join('\n').trim();
    const fixEnd = checkedIndex >= 0 ? checkedIndex : lines.length;
    const fixText = lines.slice(fixIndex + 1, fixEnd).join('\n').trim();
    const checkedText = checkedIndex >= 0 ? lines.slice(checkedIndex + 1).join('\n').trim() : null;

    if (problemText.length === 0) {
        errors.push(`${label} problem section must not be empty.`);
    }
    if (fixText.length === 0) {
        errors.push(`${label} fix section must not be empty.`);
    }
    if (checkedIndex >= 0 && checkedText.length === 0) {
        errors.push(`${label} checked section must not be empty.`);
    }

    return { severity, title };
}

function validateDiffLocation(path, line, label, anchors, errors) {
    if (!validateRepositoryPath(path, `${label} path`, errors)) {
        return false;
    }
    if (!Number.isInteger(line) || line <= 0) {
        errors.push(`${label} line must be a positive integer.`);
        return false;
    }
    if (!anchors.has(`RIGHT\u0000${path}\u0000${line}`) && !anchors.has(`LEFT\u0000${path}\u0000${line}`)) {
        errors.push(`${label} is not anchored to the supplied diff: ${path}:${line}.`);
        return false;
    }
    return true;
}

function parseFindingSummaryLines(lines, allowedSeverities, label, anchors, errors) {
    const meaningfulLines = lines.filter((line) => line !== '');
    if (meaningfulLines.length === 1 && meaningfulLines[0] === '없습니다.') {
        return [];
    }

    const findings = [];
    const pattern = /^- (?<emoji>🔴|🟠|🟡|⚪) (?<path>.+):(?<line>\d+) — (?<title>\S.*)$/u;
    for (const line of meaningfulLines) {
        const match = pattern.exec(line);
        if (!match) {
            errors.push(`${label} has an invalid finding line: ${JSON.stringify(line)}.`);
            continue;
        }

        const severity = Object.keys(SEVERITIES).find((key) => SEVERITIES[key] === match.groups.emoji);
        if (!allowedSeverities.has(severity)) {
            errors.push(`${label} does not allow ${severity} findings.`);
            continue;
        }

        const lineNumber = Number.parseInt(match.groups.line, 10);
        validateDiffLocation(match.groups.path, lineNumber, `${label} finding`, anchors, errors);
        findings.push({
            severity,
            path: match.groups.path,
            line: lineNumber,
            title: match.groups.title,
        });
    }
    return findings;
}

function sectionContent(lines, headerIndexes, name) {
    const start = headerIndexes[name];
    if (start < 0) {
        return [];
    }
    const nextIndex = Object.values(headerIndexes)
        .filter((index) => index > start)
        .sort((left, right) => left - right)[0] ?? lines.length;
    return lines.slice(start + 2, nextIndex);
}

function validateSummaryBody(body, anchors, errors) {
    if (typeof body !== 'string') {
        errors.push('body must be a string.');
        return { verdict: null, counts: null, findings: [], unanchoredFindings: [] };
    }

    const lines = normalizeNewlines(body).split('\n');
    const verdictMatch = /^## 판정: (?<verdict>Approve|Changes Requested|Blocked|Incomplete)$/u.exec(lines[0]);
    const verdictMatches = lines.filter((line) => /^## 판정: (Approve|Changes Requested|Blocked|Incomplete)$/u.test(line));
    if (!verdictMatch || verdictMatches.length !== 1) {
        errors.push('body must start with exactly one supported verdict heading.');
    }
    const verdict = verdictMatch?.groups.verdict ?? null;
    if (lines[1] !== '') {
        errors.push('body must have a blank line after the verdict heading.');
    }

    const reReviewIndexes = lines
        .map((line, index) => (line.startsWith('재리뷰 기준 커밋:') ? index : -1))
        .filter((index) => index >= 0);
    if (reReviewIndexes.length > 1) {
        errors.push('body may contain at most one re-review commit line.');
    }
    if (reReviewIndexes.length === 1) {
        const index = reReviewIndexes[0];
        if (index !== 2 || !/^재리뷰 기준 커밋: `[0-9a-f]{40}`$/iu.test(lines[index])) {
            errors.push('body re-review commit must follow the verdict and contain one 40-character SHA.');
        }
        if (lines[index + 1] !== '') {
            errors.push('body must have a blank line after the re-review commit.');
        }
    }

    const severityPattern = /^심각도: 🔴 (\d+)  🟠 (\d+)  🟡 (\d+)  ⚪ (\d+)$/u;
    const severityLines = lines
        .map((line, index) => ({ match: severityPattern.exec(line), index }))
        .filter(({ match }) => match !== null);
    let counts = null;
    let severityIndex = -1;
    if (severityLines.length !== 1) {
        errors.push('body must contain exactly one severity count line in the required format.');
    } else {
        const [{ match, index }] = severityLines;
        severityIndex = index;
        counts = {
            critical: Number.parseInt(match[1], 10),
            major: Number.parseInt(match[2], 10),
            minor: Number.parseInt(match[3], 10),
            nit: Number.parseInt(match[4], 10),
        };
        const expectedIndex = reReviewIndexes.length === 1 ? 4 : 2;
        if (index !== expectedIndex || lines[index - 1] !== '' || lines[index + 1] !== '') {
            errors.push('body severity count line must follow the verdict metadata with blank lines around it.');
        }
    }

    const changeSummaryIndexes = lines
        .map((line, index) => (line.startsWith('변경 요약:') ? index : -1))
        .filter((index) => index >= 0);
    let changeSummaryIndex = -1;
    if (changeSummaryIndexes.length !== 1) {
        errors.push('body must contain exactly one change summary line.');
    } else {
        [changeSummaryIndex] = changeSummaryIndexes;
        if (lines[changeSummaryIndex].slice('변경 요약:'.length).trim().length === 0) {
            errors.push('body change summary must not be empty.');
        }
        if (severityIndex >= 0 && changeSummaryIndex !== severityIndex + 2) {
            errors.push('body change summary must follow the severity count after one blank line.');
        }
    }

    const knownHeaders = new Set(Object.values(SUMMARY_HEADERS));
    for (const line of lines.filter((line) => line.startsWith('### '))) {
        if (!knownHeaders.has(line)) {
            errors.push(`body contains an unsupported summary section: ${line}.`);
        }
    }

    const headerIndexes = Object.fromEntries(
        Object.entries(SUMMARY_HEADERS).map(([name, header]) => [name, findExactLine(lines, header)]),
    );
    for (const [name, header] of Object.entries(SUMMARY_HEADERS)) {
        const count = countExactLine(lines, header);
        if (REQUIRED_SUMMARY_HEADERS.has(name) && count !== 1) {
            errors.push(`body must contain exactly one ${name} section.`);
        } else if (!REQUIRED_SUMMARY_HEADERS.has(name) && count > 1) {
            errors.push(`body may contain at most one ${name} section.`);
        }
        if (count === 1) {
            validateSummarySectionSpacing(lines, headerIndexes[name], `body ${name} section`, errors);
        }
    }

    let previousIndex = changeSummaryIndex;
    for (const name of SUMMARY_HEADER_ORDER) {
        const index = headerIndexes[name];
        if (index < 0) {
            continue;
        }
        if (index <= previousIndex) {
            errors.push('body summary sections must appear in the required order.');
            break;
        }
        previousIndex = index;
    }

    for (const name of Object.keys(headerIndexes)) {
        if (headerIndexes[name] >= 0 && sectionContent(lines, headerIndexes, name).join('\n').trim().length === 0) {
            errors.push(`body ${name} section must not be empty.`);
        }
    }

    const findings = parseFindingSummaryLines(
        sectionContent(lines, headerIndexes, 'findings'),
        new Set(['critical', 'major']),
        'body major findings section',
        anchors,
        errors,
    );
    const unanchoredFindings = parseFindingSummaryLines(
        sectionContent(lines, headerIndexes, 'unanchored'),
        new Set(['minor', 'nit']),
        'body unanchored findings section',
        anchors,
        errors,
    );

    if (verdict === 'Incomplete' && headerIndexes.uncovered < 0) {
        errors.push('Incomplete verdict requires a non-empty uncovered scope section.');
    }
    if (verdict !== 'Incomplete' && headerIndexes.uncovered >= 0) {
        errors.push('uncovered scope section requires an Incomplete verdict.');
    }

    const findingKeys = new Set();
    for (const finding of [...findings, ...unanchoredFindings]) {
        const key = `${finding.severity}\u0000${finding.path}\u0000${finding.line}\u0000${finding.title}`;
        if (findingKeys.has(key)) {
            errors.push(`body repeats a finding summary: ${finding.path}:${finding.line} ${finding.title}.`);
        }
        findingKeys.add(key);
    }

    return { verdict, counts, findings, unanchoredFindings };
}

export function validateReviewPayload({ payload, expectedHeadSha, diffText }) {
    const errors = [];
    if (!isPlainObject(payload)) {
        throw new ReviewPayloadValidationError(['payload must be a JSON object.']);
    }

    for (const field of Object.keys(payload)) {
        if (!TOP_LEVEL_FIELDS.has(field)) {
            errors.push(`payload contains an unsupported top-level field: ${field}.`);
        }
    }
    for (const field of TOP_LEVEL_FIELDS) {
        if (!Object.hasOwn(payload, field)) {
            errors.push(`payload is missing required field: ${field}.`);
        }
    }

    if (typeof expectedHeadSha !== 'string' || !/^[0-9a-f]{40}$/iu.test(expectedHeadSha)) {
        errors.push('expected head SHA must be a 40-character hexadecimal Git object ID.');
    }
    if (typeof payload.commit_id !== 'string' || !/^[0-9a-f]{40}$/iu.test(payload.commit_id)) {
        errors.push('commit_id must be a 40-character hexadecimal Git object ID.');
    } else if (
        typeof expectedHeadSha === 'string' &&
        payload.commit_id.toLowerCase() !== expectedHeadSha.toLowerCase()
    ) {
        errors.push(`commit_id does not match the latest PR head SHA (${expectedHeadSha}).`);
    }

    if (payload.event !== 'COMMENT') {
        errors.push('event must be COMMENT.');
    }

    const anchors = parseDiffAnchors(typeof diffText === 'string' ? diffText : '');
    const summary = validateSummaryBody(payload.body, anchors, errors);
    const comments = Array.isArray(payload.comments) ? payload.comments : [];
    if (!Array.isArray(payload.comments)) {
        errors.push('comments must be an array.');
    }

    const inlineFindings = [];
    for (const [index, comment] of comments.entries()) {
        const label = `comments[${index}]`;
        if (!isPlainObject(comment)) {
            errors.push(`${label} must be an object.`);
            continue;
        }

        for (const field of Object.keys(comment)) {
            if (!COMMENT_FIELDS.has(field)) {
                errors.push(`${label} contains an unsupported field: ${field}.`);
            }
        }
        for (const field of COMMENT_FIELDS) {
            if (!Object.hasOwn(comment, field)) {
                errors.push(`${label} is missing required field: ${field}.`);
            }
        }

        const pathIsValid = validateRepositoryPath(comment.path, `${label}.path`, errors);
        if (!Number.isInteger(comment.line) || comment.line <= 0) {
            errors.push(`${label}.line must be a positive integer.`);
        }
        if (comment.side !== 'RIGHT' && comment.side !== 'LEFT') {
            errors.push(`${label}.side must be RIGHT or LEFT.`);
        }

        if (
            pathIsValid &&
            Number.isInteger(comment.line) &&
            comment.line > 0 &&
            (comment.side === 'RIGHT' || comment.side === 'LEFT') &&
            !anchors.has(`${comment.side}\u0000${comment.path}\u0000${comment.line}`)
        ) {
            errors.push(`${label} is not anchored to the supplied diff: ${comment.path}:${comment.line} ${comment.side}.`);
        }

        const finding = validateInlineBody(comment.body, index, errors);
        if (finding) {
            inlineFindings.push({ ...finding, path: comment.path, line: comment.line });
        }
    }

    const inlineKeys = new Set();
    for (const finding of inlineFindings) {
        const key = `${finding.severity}\u0000${finding.path}\u0000${finding.line}\u0000${finding.title}`;
        if (inlineKeys.has(key)) {
            errors.push(`inline comments repeat a finding: ${finding.path}:${finding.line} ${finding.title}.`);
        }
        inlineKeys.add(key);
    }

    const majorSummaryKeys = new Set(
        summary.findings.map(
            (finding) => `${finding.severity}\u0000${finding.path}\u0000${finding.line}\u0000${finding.title}`,
        ),
    );
    for (const finding of inlineFindings.filter(({ severity }) => severity === 'critical' || severity === 'major')) {
        const key = `${finding.severity}\u0000${finding.path}\u0000${finding.line}\u0000${finding.title}`;
        if (!majorSummaryKeys.has(key)) {
            errors.push(
                `critical/major inline finding is missing from the summary: ${finding.path}:${finding.line} ${finding.title}.`,
            );
        }
    }

    const unanchoredKeys = new Set(
        summary.unanchoredFindings.map(
            (finding) => `${finding.severity}\u0000${finding.path}\u0000${finding.line}\u0000${finding.title}`,
        ),
    );
    for (const key of inlineKeys) {
        if (unanchoredKeys.has(key)) {
            errors.push('a finding cannot be both inline and listed as unanchored.');
        }
    }

    const actualCounts = {
        critical: summary.findings.filter(({ severity }) => severity === 'critical').length,
        major: summary.findings.filter(({ severity }) => severity === 'major').length,
        minor:
            inlineFindings.filter(({ severity }) => severity === 'minor').length +
            summary.unanchoredFindings.filter(({ severity }) => severity === 'minor').length,
        nit:
            inlineFindings.filter(({ severity }) => severity === 'nit').length +
            summary.unanchoredFindings.filter(({ severity }) => severity === 'nit').length,
    };
    if (summary.counts) {
        for (const severity of Object.keys(SEVERITIES)) {
            if (summary.counts[severity] !== actualCounts[severity]) {
                errors.push(
                    `severity count for ${severity} is ${summary.counts[severity]}, but ${actualCounts[severity]} finding(s) were found.`,
                );
            }
        }
    }

    if (summary.verdict && summary.verdict !== 'Incomplete') {
        const expectedVerdict = actualCounts.critical > 0
            ? 'Blocked'
            : actualCounts.major > 0
                ? 'Changes Requested'
                : 'Approve';
        if (summary.verdict !== expectedVerdict) {
            errors.push(`verdict must be ${expectedVerdict} for the validated severity counts.`);
        }
    }

    if (errors.length > 0) {
        throw new ReviewPayloadValidationError(errors);
    }

    return { commentCount: comments.length, severityCounts: actualCounts, verdict: summary.verdict };
}

function parseArguments(args) {
    const options = {};
    for (let index = 0; index < args.length; index += 1) {
        const name = args[index];
        if (name === '--help') {
            options.help = true;
            continue;
        }
        if (!['--payload', '--expected-head', '--diff'].includes(name)) {
            throw new Error(`Unknown argument: ${name}`);
        }
        if (index + 1 >= args.length || args[index + 1].startsWith('--')) {
            throw new Error(`Missing value for ${name}.`);
        }
        if (Object.hasOwn(options, name)) {
            throw new Error(`Duplicate argument: ${name}.`);
        }
        options[name] = args[index + 1];
        index += 1;
    }
    return options;
}

function printUsage() {
    console.log(
        'Usage: node validate-review-payload.mjs --payload <payload.json> --expected-head <sha> --diff <pr.diff>',
    );
}

async function main() {
    try {
        const options = parseArguments(process.argv.slice(2));
        if (options.help) {
            printUsage();
            return;
        }

        for (const required of ['--payload', '--expected-head', '--diff']) {
            if (!Object.hasOwn(options, required)) {
                throw new Error(`Missing required argument: ${required}.`);
            }
        }

        const [payloadText, diffText] = await Promise.all([
            readFile(resolve(options['--payload']), 'utf8'),
            readFile(resolve(options['--diff']), 'utf8'),
        ]);
        const payload = parsePayloadJson(payloadText);

        const result = validateReviewPayload({
            payload,
            expectedHeadSha: options['--expected-head'],
            diffText,
        });
        console.log(
            `Review payload validation passed: ${result.verdict}, ${result.commentCount} inline comment(s).`,
        );
    } catch (error) {
        if (error instanceof ReviewPayloadValidationError) {
            console.error('INVALID_REVIEW_PAYLOAD');
            for (const validationError of error.errors) {
                console.error(`- ${validationError}`);
            }
        } else {
            console.error(`ERROR: ${error.message}`);
        }
        process.exitCode = 1;
    }
}

const currentFile = fileURLToPath(import.meta.url);
const invokedFile = process.argv[1] ? resolve(process.argv[1]) : null;
if (invokedFile && currentFile.toLowerCase() === invokedFile.toLowerCase()) {
    await main();
}
