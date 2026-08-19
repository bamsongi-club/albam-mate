#!/usr/bin/env node

import { createHash, randomUUID } from 'node:crypto';
import {
    existsSync,
    lstatSync,
    readFileSync,
    realpathSync,
    renameSync,
    unlinkSync,
    writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
    loadManifest,
    validateEvaluationManifest,
} from '../p2-search-evaluation.mjs';

export const RESULT_SCHEMA_VERSION = 1;
export const RESULT_KIND = 'search-04-baseline-results';
export const RULE_VERSION = 'search-04-lexical-sparse-v1';
export const TRUSTED_INPUT_DESCRIPTOR_SHA256 = '800230a012d8a1a7d12e90b8b2d326a460c646bcc34ea0ae2eda063ce95e5aa7';
export const TRUSTED_EVALUATION_MANIFEST_SHA256 = 'e604e12740730aa9cb713e4b3db34f5ce311bcfff0db651da463a81f997329d4';
export const MODES = Object.freeze(['lexical', 'sparse']);

const FIELD_LABELS = Object.freeze({
    '게임명': 'name',
    '영문명': 'englishName',
    '메커니즘': 'mechanism',
    '카테고리': 'category',
    '테마': 'theme',
    '설명': 'description',
});
const LEXICAL_FIELDS = Object.freeze(['name', 'englishName']);
const SPARSE_FIELDS = Object.freeze(['mechanism', 'category', 'theme']);
const SPARSE_WEIGHTS = Object.freeze({ mechanism: 3, category: 2, theme: 1 });
const ALLOWED_APPROVED_FIELDS = new Set([
    'name',
    'englishName',
    'alias',
    'mechanism',
    'category',
    'theme',
    'description',
    'detailDescription',
]);
const HARD_FILTER_STOP_TOKENS = new Set([
    '가능',
    '가능한',
    '게임',
    '난이도',
    '명',
    '복잡도',
    '분',
    '시간',
    '최대',
    '최소',
    '미만',
    '초과',
    '이내',
    '안에',
    '이상',
    '이하',
    '인',
    '인원',
    '플레이',
    '플레이어',
    '초보',
    '초보자',
    '초보여도',
    '쉽게',
    '쉬운',
    '쉬움',
    '어려운',
]);
const REQUIRED_APPROVED_FIELDS = Object.freeze([
    'name',
    'englishName',
    'mechanism',
    'category',
    'theme',
]);
const LABEL_REQUIRED_FIELDS = Object.freeze({
    name: ['name'],
    englishName: ['englishName'],
    mechanism: ['mechanism'],
    category: ['category'],
    theme: ['theme'],
    description: ['description', 'detailDescription'],
});

export function sha256(value) {
    return createHash('sha256').update(value).digest('hex');
}

export function canonicalJson(value) {
    return `${JSON.stringify(value)}\n`;
}

export function normalizeText(value) {
    return String(value ?? '')
        .normalize('NFKC')
        .toLocaleLowerCase('ko-KR')
        .replace(/[^\p{L}\p{N}]+/gu, ' ')
        .trim()
        .replace(/\s+/gu, ' ');
}

export function signalTokens(value) {
    return normalizeText(value)
        .split(' ')
        .filter((token) => token.length > 0)
        .filter((token) => !/\d/u.test(token))
        .filter((token) => !HARD_FILTER_STOP_TOKENS.has(token));
}

export function parseSearchText(searchText, { approvedFields } = {}) {
    if (typeof searchText !== 'string' || searchText.trim() === '') {
        throw new Error('searchText는 비어 있지 않은 문자열이어야 합니다.');
    }

    const fields = Object.fromEntries(Object.values(FIELD_LABELS).map((field) => [field, []]));
    let currentField;
    for (const line of searchText.split(/\r?\n/u)) {
        if (line.trim() === '') continue;
        const separator = line.indexOf(':');
        const label = line.slice(0, separator).trim();
        const field = FIELD_LABELS[label];
        if (!field) {
            if (currentField !== 'description') {
                throw new Error(`승인되지 않은 searchText 필드입니다: ${line.trim()}`);
            }
            const continuation = line.trim();
            const values = fields[currentField];
            if (values.length === 0) values.push(continuation);
            else values[values.length - 1] += `\n${continuation}`;
            continue;
        }
        if (approvedFields !== undefined
            && !LABEL_REQUIRED_FIELDS[field].some((approvedField) => approvedFields.includes(approvedField))) {
            throw new Error(`searchText 필드가 approvedFields 밖에 있습니다: ${label}`);
        }
        const values = line
            .slice(separator + 1)
            .split(',')
            .map((value) => value.trim())
            .filter(Boolean);
        fields[field].push(...values);
        currentField = field;
    }
    return fields;
}

export function validateSearchTextArtifact(
    artifact,
    manifest,
    manifestPath,
    { inputDescriptor, searchTextBytes, pocManifest, pocManifestBytes } = {},
) {
    validateInputDescriptor({
        inputDescriptor,
        manifest,
        manifestPath,
        pocManifest,
        pocManifestBytes,
    });
    requireObject(artifact, 'search-text artifact');
    const hasExecutionEnvelope = artifact.schemaVersion !== undefined
        || artifact.kind !== undefined
        || artifact.datasetRelease !== undefined;
    if (hasExecutionEnvelope && artifact.schemaVersion !== 1) {
        throw new Error('search-text artifact schemaVersion은 1이어야 합니다.');
    }
    if (hasExecutionEnvelope && artifact.kind !== 'poc-search-text') {
        throw new Error('search-text artifact kind이 올바르지 않습니다.');
    }
    if (artifact.datasetRelease !== undefined) {
        requireObject(artifact.datasetRelease, 'search-text artifact datasetRelease');
        assertDatasetRelease(artifact.datasetRelease, inputDescriptor.datasetRelease);
    }
    if (artifact.approvedFields !== undefined) {
        assertApprovedFields(artifact.approvedFields, inputDescriptor.approvedFields);
    }
    if (!Array.isArray(artifact.games) || artifact.games.length !== manifest.qualityCorpus.selection.targetSize) {
        throw new Error('search-text artifact는 Top 1,000 games를 포함해야 합니다.');
    }
    if (artifact.gameCount !== artifact.games.length) {
        throw new Error('search-text artifact gameCount가 games 길이와 다릅니다.');
    }

    const corpusMembers = [...manifest.qualityCorpus.members];
    const expectedIds = new Set(corpusMembers.map((member) => member.gameId));
    const seenIds = new Set();
    for (const [index, game] of artifact.games.entries()) {
        if (!Number.isSafeInteger(game?.gameId) || game.gameId < 1) {
            throw new Error(`search-text artifact games[${index}].gameId가 올바르지 않습니다.`);
        }
        if (seenIds.has(game.gameId)) throw new Error(`search-text artifact gameId가 중복되었습니다: ${game.gameId}`);
        seenIds.add(game.gameId);
        if (!expectedIds.has(game.gameId)) {
            throw new Error('search-text artifact games가 pinned quality corpus 밖의 gameId를 포함합니다.');
        }
        if (typeof game.searchText !== 'string' || game.searchText.trim() === '') {
            throw new Error(`search-text artifact games[${index}].searchText가 비어 있습니다.`);
        }
        parseSearchText(game.searchText, { approvedFields: inputDescriptor.approvedFields });
    }
    if (seenIds.size !== expectedIds.size) {
        throw new Error('search-text artifact games가 pinned quality corpus 전체 membership을 포함하지 않습니다.');
    }

    const actualSearchTextSha256 = sha256(Buffer.from(
        `${JSON.stringify(artifact.games)}\n`,
        'utf8',
    ));
    if (actualSearchTextSha256 !== inputDescriptor.searchTextArtifact.gamesSha256) {
        throw new Error('search-text artifact games checksum이 승인된 입력 descriptor와 다릅니다.');
    }
    if (artifact.searchTextSha256 !== undefined && artifact.searchTextSha256 !== actualSearchTextSha256) {
        throw new Error('search-text artifact searchTextSha256가 games 원자료와 다릅니다.');
    }
    if (searchTextBytes === undefined
        || sha256(searchTextBytes) !== inputDescriptor.searchTextArtifact.sha256) {
        throw new Error('search-text artifact 파일 checksum이 승인된 입력 descriptor와 다릅니다.');
    }

    if (artifact.corpus !== undefined) requireObject(artifact.corpus, 'search-text artifact corpus');
    if (artifact.corpus?.releaseId !== undefined
        && artifact.corpus.releaseId !== manifest.qualityCorpus.releaseId) {
        throw new Error('search-text artifact corpus release가 평가 manifest와 다릅니다.');
    }
    if (artifact.corpus?.corpusVersion !== undefined
        && artifact.corpus.corpusVersion !== manifest.qualityCorpus.corpusVersion) {
        throw new Error('search-text artifact corpus version이 평가 manifest와 다릅니다.');
    }
    return artifact;
}

function validateInputDescriptor({ inputDescriptor, manifest, manifestPath, pocManifest, pocManifestBytes }) {
    requireObject(inputDescriptor, 'baseline input descriptor');
    if (inputDescriptor.schemaVersion !== 1 || inputDescriptor.kind !== 'search-04-baseline-input') {
        throw new Error('baseline input descriptor schemaVersion 또는 kind이 올바르지 않습니다.');
    }
    if (inputDescriptor.upstreamPullRequest !== 861) {
        throw new Error('baseline input descriptor의 upstreamPullRequest는 #861이어야 합니다.');
    }
    assertDatasetRelease(inputDescriptor.datasetRelease, manifest.catalog);
    const datasetManifestPath = resolveReference(manifestPath, manifest.catalog.manifestReference);
    const datasetManifestSha256 = sha256(readFileSync(datasetManifestPath));
    if (inputDescriptor.datasetRelease.manifestSha256 !== datasetManifestSha256) {
        throw new Error('baseline input descriptor dataset manifest checksum이 현재 승인 release와 다릅니다.');
    }
    requireObject(inputDescriptor.qualityCorpus, 'baseline input descriptor qualityCorpus');
    if (inputDescriptor.qualityCorpus.releaseId !== manifest.qualityCorpus.releaseId
        || inputDescriptor.qualityCorpus.corpusVersion !== manifest.qualityCorpus.corpusVersion
        || inputDescriptor.qualityCorpus.sha256 !== manifest.qualityCorpusSha256) {
        throw new Error('baseline input descriptor quality corpus가 평가 manifest와 다릅니다.');
    }
    assertApprovedFields(inputDescriptor.approvedFields);

    requireObject(inputDescriptor.pocManifest, 'baseline input descriptor pocManifest');
    if (typeof inputDescriptor.pocManifest.reference !== 'string'
        || inputDescriptor.pocManifest.reference.trim() === '') {
        throw new Error('baseline input descriptor pocManifest.reference가 없습니다.');
    }
    if (pocManifestBytes === undefined
        || sha256(pocManifestBytes) !== inputDescriptor.pocManifest.sha256) {
        throw new Error('POC search-text manifest checksum이 승인된 입력 descriptor와 다릅니다.');
    }
    requireObject(pocManifest, 'poc search-text manifest');
    if (pocManifest.kind !== 'poc-search-text-execution'
        || pocManifest.approved !== true
        || pocManifest.testOnly !== false) {
        throw new Error('POC search-text manifest가 approved execution manifest가 아닙니다.');
    }
    assertDatasetRelease(pocManifest.datasetRelease, inputDescriptor.datasetRelease);
    assertApprovedFields(pocManifest.approvedFields, inputDescriptor.approvedFields);
    if (pocManifest.corpus?.sha256 !== inputDescriptor.qualityCorpus.sha256) {
        throw new Error('POC search-text manifest corpus checksum이 승인된 quality corpus와 다릅니다.');
    }

    requireObject(inputDescriptor.searchTextArtifact, 'baseline input descriptor searchTextArtifact');
    if (typeof inputDescriptor.searchTextArtifact.reference !== 'string'
        || inputDescriptor.searchTextArtifact.reference.trim() === '') {
        throw new Error('baseline input descriptor searchTextArtifact.reference가 없습니다.');
    }
    if (!/^[a-f0-9]{64}$/u.test(inputDescriptor.searchTextArtifact.sha256)
        || !/^[a-f0-9]{64}$/u.test(inputDescriptor.searchTextArtifact.gamesSha256)
        || inputDescriptor.searchTextArtifact.gameCount !== manifest.qualityCorpus.selection.targetSize) {
        throw new Error('baseline input descriptor searchTextArtifact checksum/count가 올바르지 않습니다.');
    }
}

function assertDatasetRelease(actual, expected) {
    requireObject(actual, 'dataset release');
    requireObject(expected, 'expected dataset release');
    for (const field of ['releaseId', 'datasetId']) {
        if (actual[field] !== expected[field]) {
            throw new Error(`dataset release ${field}가 승인 입력과 다릅니다.`);
        }
    }
    if (expected.manifestSha256 !== undefined && actual.manifestSha256 !== expected.manifestSha256) {
        throw new Error('dataset release manifestSha256가 승인 입력과 다릅니다.');
    }
}

function assertApprovedFields(actual, expected) {
    if (!Array.isArray(actual)
        || new Set(actual).size !== actual.length
        || actual.some((field) => !ALLOWED_APPROVED_FIELDS.has(field))
        || !REQUIRED_APPROVED_FIELDS.every((field) => actual.includes(field))) {
        throw new Error('search-text approvedFields가 lexical/Sparse 승인 필드 집합이 아닙니다.');
    }
    if (Array.isArray(expected)
        && (expected.length !== actual.length || expected.some((field) => !actual.includes(field)))) {
        throw new Error('search-text approvedFields가 입력 descriptor와 다릅니다.');
    }
}

export function scoreCandidate({ mode, query, fields }) {
    if (!MODES.includes(mode)) throw new Error(`지원하지 않는 baseline mode입니다: ${mode}`);
    return mode === 'lexical'
        ? lexicalScore(query.query, fields)
        : sparseScore(query.query, fields);
}

export function matchesHardFilters(member, hardFilters = {}) {
    requireObject(member, 'quality corpus member');
    if (hardFilters.minPlayers !== undefined) {
        requireInteger(member.maxPlayers, 'quality corpus member.maxPlayers');
        if (member.maxPlayers < hardFilters.minPlayers) return false;
    }
    if (hardFilters.maxPlayers !== undefined) {
        requireInteger(member.minPlayers, 'quality corpus member.minPlayers');
        if (member.minPlayers > hardFilters.maxPlayers) return false;
    }
    if (hardFilters.maxPlayTimeMinutes !== undefined) {
        requireInteger(member.maxPlayTimeMinutes, 'quality corpus member.maxPlayTimeMinutes');
        if (member.maxPlayTimeMinutes > hardFilters.maxPlayTimeMinutes) return false;
    }
    return true;
}

export function rankQuery({ mode, query, games, corpusById, approvedFields }) {
    const scored = games.map((game) => {
        const fields = parseSearchText(game.searchText, { approvedFields });
        const member = corpusById.get(game.gameId);
        if (!member) throw new Error(`search-text gameId가 quality corpus에 없습니다: ${game.gameId}`);
        return {
            gameId: game.gameId,
            score: scoreCandidate({ mode, query, fields }),
            sortName: normalizeText(fields.name[0] ?? fields.englishName[0] ?? ''),
            member,
        };
    });

    const eligible = scored
        .filter(({ member }) => matchesHardFilters(member, query.hardFilters))
        .sort(compareCandidates);

    return {
        rankedGameIds: eligible.map(({ gameId }) => gameId),
        hardFilterViolationGameIds: [],
    };
}

export function buildBaselineResults({
    mode,
    manifest,
    searchTextArtifact,
    manifestPath,
    inputDescriptor,
    searchTextBytes,
    pocManifest,
    pocManifestBytes,
}) {
    if (!MODES.includes(mode)) throw new Error(`지원하지 않는 baseline mode입니다: ${mode}`);
    validateEvaluationManifest(manifest);
    if (manifest.evaluationProfile !== 'development-seed') {
        throw new Error('이 baseline은 pinned Development Seed에서만 실행할 수 있습니다.');
    }
    validateSearchTextArtifact(searchTextArtifact, manifest, manifestPath, {
        inputDescriptor,
        searchTextBytes,
        pocManifest,
        pocManifestBytes,
    });

    const corpusById = new Map(manifest.qualityCorpus.members.map((member) => [member.gameId, member]));
    const results = Object.fromEntries(
        [...manifest.queries]
            .sort((left, right) => compareStrings(left.id, right.id))
            .map((query) => [query.id, rankQuery({
                mode,
                query,
                games: searchTextArtifact.games,
                corpusById,
                approvedFields: inputDescriptor.approvedFields,
            })]),
    );
    return results;
}

export function renderResults(results) {
    return canonicalJson(results);
}

function lexicalScore(query, fields) {
    const queryTokens = signalTokens(query);
    if (queryTokens.length === 0) return 0;
    const queryPhrase = queryTokens.join(' ');
    return Math.max(...LEXICAL_FIELDS.map((field, fieldIndex) => Math.max(...fields[field].map((value) => {
        const valueNormalized = normalizeText(value);
        const valueTokens = valueNormalized.split(' ').filter(Boolean);
        const overlap = queryTokens.filter((token) => valueTokens.includes(token));
        let score = overlap.length * 100 + overlap.reduce((sum, token) => sum + token.length, 0);
        if (queryPhrase === valueNormalized) score += 1_000_000;
        else if (valueNormalized.includes(queryPhrase)) score += 100_000;
        if (queryTokens.every((token) => valueTokens.includes(token))) score += 10_000;
        return score * (fieldIndex === 0 ? 2 : 1);
    }), 0)));
}

function sparseScore(query, fields) {
    const queryTokens = signalTokens(query);
    if (queryTokens.length === 0) return 0;
    return SPARSE_FIELDS.reduce((score, field) => score + fields[field].reduce((fieldScore, value) => {
        const fieldTokens = signalTokens(value);
        if (fieldTokens.length === 0 || !containsTokenSequence(queryTokens, fieldTokens)) return fieldScore;
        return fieldScore + SPARSE_WEIGHTS[field] * 1_000 + fieldTokens.length;
    }, 0), 0);
}

function containsTokenSequence(tokens, sequence) {
    if (sequence.length > tokens.length) return false;
    return tokens.some((_, index) => sequence.every((token, offset) => tokens[index + offset] === token));
}

function compareCandidates(left, right) {
    if (left.score !== right.score) return right.score - left.score;
    const nameOrder = compareStrings(left.sortName, right.sortName);
    return nameOrder !== 0 ? nameOrder : left.gameId - right.gameId;
}

function compareStrings(left, right) {
    return left < right ? -1 : left > right ? 1 : 0;
}

function resolveReference(manifestPath, reference) {
    if (typeof reference !== 'string' || reference.trim() === '') {
        throw new Error('catalog manifestReference가 없습니다.');
    }
    const candidates = [
        path.resolve(path.dirname(manifestPath), reference),
        path.resolve(process.cwd(), reference),
        path.resolve(path.dirname(manifestPath), '../../..', reference),
    ];
    const existing = candidates.find((candidate) => existsSync(candidate));
    if (!existing) throw new Error(`catalog manifestReference 파일을 찾을 수 없습니다: ${reference}`);
    return existing;
}

function requireObject(value, name) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
        throw new Error(`${name}은 object여야 합니다.`);
    }
}

function requireInteger(value, name) {
    if (!Number.isSafeInteger(value) || value < 1) throw new Error(`${name}은 양의 정수여야 합니다.`);
}

function readTrustedInputDescriptor(filePath) {
    const trustedPath = path.resolve(
        path.dirname(fileURLToPath(import.meta.url)),
        '../../docs/p2/search-evaluation/lexical-sparse-baseline-input.json',
    );
    if (path.resolve(filePath) !== trustedPath) {
        throw new Error(`--input-descriptor는 커밋된 고정 경로만 사용할 수 있습니다: ${trustedPath}`);
    }
    const bytes = readFileSync(trustedPath);
    if (sha256(bytes) !== TRUSTED_INPUT_DESCRIPTOR_SHA256) {
        throw new Error('커밋된 baseline input descriptor checksum이 trust anchor와 다릅니다.');
    }
    try {
        return JSON.parse(bytes.toString('utf8'));
    } catch (error) {
        throw new Error(`baseline input descriptor JSON을 읽을 수 없습니다: ${error.message}`);
    }
}

function loadTrustedEvaluationManifest(filePath) {
    const trustedPath = path.resolve(
        path.dirname(fileURLToPath(import.meta.url)),
        '../../docs/p2/search-evaluation/manifest.json',
    );
    if (path.resolve(filePath) !== trustedPath) {
        throw new Error(`--manifest는 커밋된 고정 경로만 사용할 수 있습니다: ${trustedPath}`);
    }
    const bytes = readFileSync(trustedPath);
    if (sha256(bytes) !== TRUSTED_EVALUATION_MANIFEST_SHA256) {
        throw new Error('커밋된 evaluation manifest checksum이 trust anchor와 다릅니다.');
    }
    return loadManifest(trustedPath);
}

function parseArgs(args) {
    const options = {};
    const valueOptions = new Set(['mode', 'manifest', 'inputDescriptor', 'pocManifest', 'searchText', 'out']);
    for (let index = 0; index < args.length; index += 1) {
        const argument = args[index];
        if (!argument.startsWith('--')) throw new Error(`알 수 없는 인자입니다: ${argument}`);
        const option = argument.slice(2).replace(/-([a-z])/gu, (_, letter) => letter.toUpperCase());
        if (!valueOptions.has(option) || options[option] !== undefined) {
            throw new Error(`알 수 없거나 중복된 옵션입니다: ${argument}`);
        }
        const value = args[index + 1];
        if (!value || value.startsWith('--')) throw new Error(`${argument} 값이 필요합니다.`);
        options[option] = value;
        index += 1;
    }
    for (const option of valueOptions) {
        if (!options[option]) throw new Error(`--${option.replace(/[A-Z]/gu, (letter) => `-${letter.toLowerCase()}`)} 경로가 필요합니다.`);
    }
    return options;
}

function assertOutputIsSeparate(outputPath, inputPaths) {
    const resolvedOutput = path.resolve(outputPath);
    const outputExists = existsSync(outputPath);
    if (outputExists && lstatSync(outputPath).isSymbolicLink()) {
        throw new Error('--out은 symlink일 수 없습니다. 새 일반 파일 경로를 사용하십시오.');
    }
    const realOutput = outputExists ? realpathSync(outputPath) : resolvedOutput;
    const outputStat = outputExists ? lstatSync(outputPath) : null;
    if (inputPaths.some((inputPath) => {
        const inputStat = lstatSync(inputPath);
        return realpathSync(inputPath) === realOutput
            || (outputStat !== null && outputStat.dev === inputStat.dev && outputStat.ino === inputStat.ino);
    })) {
        throw new Error('--out은 입력 파일을 덮어쓸 수 없습니다.');
    }
}

function writeOutputAtomically(outputPath, contents) {
    const temporaryPath = path.join(
        path.dirname(outputPath),
        `.${path.basename(outputPath)}.${randomUUID()}.tmp`,
    );
    try {
        writeFileSync(temporaryPath, contents, { encoding: 'utf8', flag: 'wx', mode: 0o600 });
        renameSync(temporaryPath, outputPath);
    } catch (error) {
        try {
            unlinkSync(temporaryPath);
        } catch {
            // Preserve the original write/rename error.
        }
        throw error;
    }
}

function main() {
    try {
        const options = parseArgs(process.argv.slice(2));
        if (!MODES.includes(options.mode)) throw new Error(`--mode는 ${MODES.join(' 또는 ')}이어야 합니다.`);
        const manifestPath = path.resolve(options.manifest);
        const inputDescriptorPath = path.resolve(options.inputDescriptor);
        const pocManifestPath = path.resolve(options.pocManifest);
        const searchTextPath = path.resolve(options.searchText);
        const outputPath = path.resolve(options.out);
        const manifest = loadTrustedEvaluationManifest(manifestPath);
        const inputDescriptor = readTrustedInputDescriptor(inputDescriptorPath);
        const pocManifestBytes = readFileSync(pocManifestPath);
        const pocManifest = JSON.parse(pocManifestBytes.toString('utf8'));
        const searchTextBytes = readFileSync(searchTextPath);
        const searchTextArtifact = JSON.parse(searchTextBytes.toString('utf8'));
        const indirectInputPaths = [
            manifestPath,
            inputDescriptorPath,
            pocManifestPath,
            searchTextPath,
            manifest.queriesPath && path.resolve(path.dirname(manifestPath), manifest.queriesPath),
            manifest.qualityCorpusPath && path.resolve(path.dirname(manifestPath), manifest.qualityCorpusPath),
            resolveReference(manifestPath, manifest.catalog.manifestReference),
        ].filter(Boolean);
        assertOutputIsSeparate(outputPath, indirectInputPaths);
        const results = buildBaselineResults({
            mode: options.mode,
            manifest,
            searchTextArtifact,
            manifestPath,
            inputDescriptor,
            searchTextBytes,
            pocManifest,
            pocManifestBytes,
        });
        const contents = renderResults(results);
        writeOutputAtomically(outputPath, contents);
        console.log(JSON.stringify({
            ok: true,
            kind: RESULT_KIND,
            schemaVersion: RESULT_SCHEMA_VERSION,
            mode: options.mode,
            ruleVersion: RULE_VERSION,
            evaluationManifestSha256: TRUSTED_EVALUATION_MANIFEST_SHA256,
            inputDescriptorSha256: TRUSTED_INPUT_DESCRIPTOR_SHA256,
            queryCount: Object.keys(results).length,
            resultSha256: sha256(Buffer.from(contents, 'utf8')),
            output: outputPath,
        }, null, 2));
    } catch (error) {
        console.error(JSON.stringify({ ok: false, error: error.message }, null, 2));
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? path.resolve(process.argv[1]) : null;
if (entryPoint === fileURLToPath(import.meta.url)) main();
