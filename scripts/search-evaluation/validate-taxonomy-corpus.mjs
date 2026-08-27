#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const TAXONOMY_IDS = Object.freeze([
    'TITLE_EXACT',
    'TITLE_RECOVERY',
    'REFERENCE_SIMILARITY',
    'MECHANIC_RULE',
    'THEME_CONTENT',
    'INTERACTION_MODE',
    'PLAY_EXPERIENCE',
    'ACCESSIBILITY',
    'STRUCTURED_FIT',
    'OCCASION_CONTEXT',
    'OPEN_DISCOVERY',
]);

export const SOURCES = Object.freeze([
    'existing_fixture',
    'existing_regression',
    'synthetic',
]);

export const CONSTRAINT_TYPES = Object.freeze([
    'NONE',
    'HARD_PLAYERS',
    'HARD_TIME',
    'HARD_AGE',
    'STRUCTURED_PREFERENCE',
    'SEMANTIC_PREFERENCE',
    'CONTEXT_HINT',
    'AMBIGUOUS_SUBJECTIVE',
]);

export const QUERY_FORMS = Object.freeze([
    'natural_ko',
    'natural_en',
    'natural_ko_title',
    'natural_ko_uncertain',
    'natural_ko_reference',
    'natural_ko_title_constraint',
    'natural_ko_compound',
    'title_lookup',
    'title_lookup_en',
    'short_keyword',
    'short_title_en',
    'short_title_ko',
    'typo_ko',
    'fragment_ko',
    'mixed_ko_en',
    'mixed_ko_en_reference',
    'mixed_ko_en_fragment',
    'mixed_ko_en_title',
    'mixed_structured_en',
    'structured_fragment_ko',
]);

export const ANSWERABILITY = Object.freeze([
    'DIRECT_CATALOG',
    'PARTIAL_TITLE_CATALOG',
    'REFERENCE_QRELS',
    'CATALOG_METADATA_PLUS_QRELS',
    'STRUCTURED_CHECKABLE_PLUS_QRELS',
    'HUMAN_QRELS',
    'CLARIFY_FIRST',
    'NOT_REPRESENTED',
]);

export const QUERY_HEADERS = Object.freeze(['id', 'query', 'source', 'origin']);
export const ANNOTATION_HEADERS = Object.freeze([
    'query_id',
    'source',
    'primary_intent',
    'secondary_intents',
    'constraint_types',
    'query_form',
    'answerability',
    'ambiguous',
    'outlier',
]);

export const DEFAULT_MANIFEST_PATH = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    '../../docs/p2/search-evaluation/taxonomy-discovery-v1/manifest.json',
);

function invalid(message) {
    throw new Error(`[taxonomy-corpus] ${message}`);
}

function assert(condition, message) {
    if (!condition) invalid(message);
}

function assertObject(value, name) {
    assert(value !== null && typeof value === 'object' && !Array.isArray(value), `${name}은 객체여야 합니다.`);
}

function assertArray(value, name) {
    assert(Array.isArray(value), `${name}은 배열이어야 합니다.`);
}

function assertExactHeaders(headers, expectedHeaders, name) {
    assert(
        headers.length === expectedHeaders.length
            && headers.every((header, index) => header === expectedHeaders[index]),
        `${name} header가 예상과 다릅니다: ${headers.join(',')}`,
    );
}

function assertEnum(value, values, name) {
    assert(values.includes(value), `${name} 값이 허용 목록에 없습니다: ${value}`);
}

function splitValues(value, name) {
    if (value === '-') return [];
    const values = value.split(';');
    assert(values.length > 0 && values.every((item) => item !== ''), `${name} 값이 비어 있습니다.`);
    assert(new Set(values).size === values.length, `${name} 값이 중복됩니다: ${value}`);
    return values;
}

/**
 * 쉼표·따옴표·줄바꿈을 포함한 RFC 4180에 가까운 CSV를 읽는다.
 * 이 artifact는 query text를 그대로 보존해야 하므로 단순 split(',')을 사용하지 않는다.
 */
export function parseCsv(csvText) {
    const text = String(csvText);
    const rows = [];
    let row = [];
    let field = '';
    let inQuotes = false;
    let closedQuote = false;

    const pushRow = () => {
        row.push(field);
        if (row.some((value) => value !== '')) rows.push(row);
        row = [];
        field = '';
        closedQuote = false;
    };

    for (let index = 0; index < text.length; index += 1) {
        const character = text[index];
        if (inQuotes) {
            if (character === '"') {
                if (text[index + 1] === '"') {
                    field += '"';
                    index += 1;
                } else {
                    inQuotes = false;
                    closedQuote = true;
                }
            } else {
                field += character;
            }
            continue;
        }

        if (character === '"') {
            assert(field === '' && !closedQuote, 'CSV field의 따옴표 위치가 올바르지 않습니다.');
            inQuotes = true;
        } else if (character === ',') {
            row.push(field);
            field = '';
            closedQuote = false;
        } else if (character === '\n') {
            pushRow();
        } else if (character === '\r') {
            if (text[index + 1] !== '\n') field += character;
        } else {
            assert(!closedQuote, '닫힌 CSV field 뒤에 허용되지 않은 문자가 있습니다.');
            field += character;
        }
    }

    assert(!inQuotes, 'CSV 따옴표가 닫히지 않았습니다.');
    if (row.length > 0 || field !== '') pushRow();
    assert(rows.length > 0, 'CSV가 비어 있습니다.');
    return rows;
}

function readCsvRecords(filePath, expectedHeaders, name) {
    const rows = parseCsv(readFileSync(filePath, 'utf8'));
    assertExactHeaders(rows[0], expectedHeaders, name);
    const records = rows.slice(1).map((values, rowIndex) => {
        assert(values.length === expectedHeaders.length, `${name} ${rowIndex + 2}행의 열 수가 올바르지 않습니다.`);
        return Object.fromEntries(expectedHeaders.map((header, index) => [header, values[index]]));
    });
    return records;
}

function sha256File(filePath) {
    return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function validateArtifactFile(manifestDirectory, descriptor, name) {
    assertObject(descriptor, `${name} artifact descriptor`);
    assert(typeof descriptor.path === 'string' && descriptor.path !== '', `${name} path가 없습니다.`);
    assert(/^[a-f0-9]{64}$/u.test(descriptor.sha256), `${name} SHA-256 형식이 올바르지 않습니다.`);
    const filePath = path.resolve(manifestDirectory, descriptor.path);
    assert(existsSync(filePath), `${name} artifact가 없습니다: ${descriptor.path}`);
    assert(sha256File(filePath) === descriptor.sha256, `${name} SHA-256이 manifest와 다릅니다.`);
    return filePath;
}

function expectedQueryIds(queryCount) {
    return Array.from({ length: queryCount }, (_, index) => `Q${String(index + 1).padStart(3, '0')}`);
}

function validateQueries(records, queryCount) {
    assert(records.length === queryCount, `query row 수가 ${queryCount}개가 아닙니다: ${records.length}`);
    const expectedIds = expectedQueryIds(queryCount);
    const seenQueries = new Set();
    records.forEach((record, index) => {
        assert(record.id === expectedIds[index], `query ID 순서가 올바르지 않습니다: ${record.id}`);
        assert(record.query.trim() !== '', `${record.id} query가 비어 있습니다.`);
        assert(!seenQueries.has(record.query), `query가 중복됩니다: ${record.query}`);
        seenQueries.add(record.query);
        assertEnum(record.source, SOURCES, `${record.id} source`);
        assert(record.origin.trim() !== '', `${record.id} origin이 비어 있습니다.`);
    });
    return new Map(records.map((record) => [record.id, record]));
}

function validateAnnotations(records, queryMap, queryCount, taxonomyIds) {
    assert(records.length === queryCount, `annotation row 수가 ${queryCount}개가 아닙니다: ${records.length}`);
    const seenIds = new Set();
    const primaryDistribution = Object.fromEntries(taxonomyIds.map((id) => [id, 0]));
    const sourceDistribution = Object.fromEntries(SOURCES.map((source) => [source, 0]));
    const answerabilityDistribution = Object.fromEntries(ANSWERABILITY.map((value) => [value, 0]));
    let multiLabelCount = 0;
    let ambiguousCount = 0;
    let outlierCount = 0;

    records.forEach((record) => {
        assert(!seenIds.has(record.query_id), `annotation query_id가 중복됩니다: ${record.query_id}`);
        seenIds.add(record.query_id);
        const query = queryMap.get(record.query_id);
        assert(query !== undefined, `annotation에 없는 query_id입니다: ${record.query_id}`);
        assert(record.source === query.source, `${record.query_id} source가 queries.csv와 다릅니다.`);
        assertEnum(record.primary_intent, taxonomyIds, `${record.query_id} primary_intent`);
        const secondaryIntents = splitValues(record.secondary_intents, `${record.query_id} secondary_intents`);
        secondaryIntents.forEach((intent) => assertEnum(intent, taxonomyIds, `${record.query_id} secondary_intents`));
        const constraintTypes = splitValues(record.constraint_types, `${record.query_id} constraint_types`);
        constraintTypes.forEach((constraint) => assertEnum(constraint, CONSTRAINT_TYPES, `${record.query_id} constraint_types`));
        assertEnum(record.query_form, QUERY_FORMS, `${record.query_id} query_form`);
        assertEnum(record.answerability, ANSWERABILITY, `${record.query_id} answerability`);
        assert(record.ambiguous === 'yes' || record.ambiguous === 'no', `${record.query_id} ambiguous 값이 올바르지 않습니다.`);
        assert(record.outlier === 'yes' || record.outlier === 'no', `${record.query_id} outlier 값이 올바르지 않습니다.`);

        primaryDistribution[record.primary_intent] += 1;
        sourceDistribution[record.source] += 1;
        answerabilityDistribution[record.answerability] += 1;
        if (secondaryIntents.length > 0) multiLabelCount += 1;
        if (record.ambiguous === 'yes') ambiguousCount += 1;
        if (record.outlier === 'yes') outlierCount += 1;
    });

    assert(seenIds.size === queryCount, `annotation unique query 수가 ${queryCount}개가 아닙니다: ${seenIds.size}`);
    return {
        primaryDistribution,
        sourceDistribution,
        answerabilityDistribution,
        multiLabelCount,
        ambiguousCount,
        outlierCount,
    };
}

function assertSameObject(actual, expected, name) {
    const canonicalize = (value) => {
        if (Array.isArray(value)) return value.map(canonicalize);
        if (value !== null && typeof value === 'object') {
            return Object.fromEntries(
                Object.keys(value)
                    .sort()
                    .map((key) => [key, canonicalize(value[key])]),
            );
        }
        return value;
    };
    assert(JSON.stringify(canonicalize(actual)) === JSON.stringify(canonicalize(expected)), `${name}이 manifest와 다릅니다.`);
}

export function validateTaxonomyCorpus(manifestPath = DEFAULT_MANIFEST_PATH) {
    const resolvedManifestPath = path.resolve(manifestPath);
    assert(existsSync(resolvedManifestPath), `manifest가 없습니다: ${resolvedManifestPath}`);
    const manifest = JSON.parse(readFileSync(resolvedManifestPath, 'utf8'));
    assertObject(manifest, 'manifest');
    assert(manifest.schemaVersion === 1, 'schemaVersion은 1이어야 합니다.');
    assert(manifest.kind === 'search-04-taxonomy-discovery-corpus', 'manifest kind이 올바르지 않습니다.');
    assert(manifest.status === 'draft-discovery', 'taxonomy discovery artifact는 draft-discovery 상태여야 합니다.');
    assert(Number.isInteger(manifest.queryCount) && manifest.queryCount > 0, 'queryCount가 올바르지 않습니다.');
    assert(manifest.annotationCount === manifest.queryCount, 'annotationCount가 queryCount와 다릅니다.');
    assertArray(manifest.taxonomyIds, 'taxonomyIds');
    assertSameObject(manifest.taxonomyIds, TAXONOMY_IDS, 'taxonomyIds');
    assertObject(manifest.artifacts, 'artifacts');
    assertObject(manifest.coverage, 'coverage');

    const manifestDirectory = path.dirname(resolvedManifestPath);
    const queryPath = validateArtifactFile(manifestDirectory, manifest.artifacts.queries, 'queries');
    const annotationPath = validateArtifactFile(manifestDirectory, manifest.artifacts.annotations, 'annotations');
    const taxonomyPath = validateArtifactFile(manifestDirectory, manifest.artifacts.taxonomy, 'taxonomy');

    const queryRecords = readCsvRecords(queryPath, QUERY_HEADERS, 'queries.csv');
    const queryMap = validateQueries(queryRecords, manifest.queryCount);
    const annotationRecords = readCsvRecords(annotationPath, ANNOTATION_HEADERS, 'annotations.csv');
    const computedCoverage = validateAnnotations(
        annotationRecords,
        queryMap,
        manifest.annotationCount,
        manifest.taxonomyIds,
    );
    const taxonomyText = readFileSync(taxonomyPath, 'utf8');
    manifest.taxonomyIds.forEach((taxonomyId) => {
        assert(taxonomyText.includes(`\`${taxonomyId}\``), `taxonomy.md에 ${taxonomyId}가 없습니다.`);
    });

    assert(manifest.artifacts.queries.rowCount === queryRecords.length, 'queries rowCount가 실제 row 수와 다릅니다.');
    assert(manifest.artifacts.annotations.rowCount === annotationRecords.length, 'annotations rowCount가 실제 row 수와 다릅니다.');
    assertSameObject(computedCoverage.primaryDistribution, manifest.coverage.primaryDistribution, 'primaryDistribution');
    assertSameObject(computedCoverage.sourceDistribution, manifest.coverage.sourceDistribution, 'sourceDistribution');
    assertSameObject(computedCoverage.answerabilityDistribution, manifest.coverage.answerabilityDistribution, 'answerabilityDistribution');
    assert(computedCoverage.multiLabelCount === manifest.coverage.multiLabelCount, 'multiLabelCount가 manifest와 다릅니다.');
    assert(computedCoverage.ambiguousCount === manifest.coverage.ambiguousCount, 'ambiguousCount가 manifest와 다릅니다.');
    assert(computedCoverage.outlierCount === manifest.coverage.outlierCount, 'outlierCount가 manifest와 다릅니다.');

    return {
        manifestPath: resolvedManifestPath,
        queryCount: queryRecords.length,
        annotationCount: annotationRecords.length,
        coverage: computedCoverage,
    };
}

function manifestPathFromArgs(args) {
    const manifestOptionIndex = args.indexOf('--manifest');
    if (manifestOptionIndex >= 0) {
        const value = args[manifestOptionIndex + 1];
        assert(value !== undefined && value !== '', '--manifest 뒤에 경로가 필요합니다.');
        return value;
    }
    const inlineOption = args.find((arg) => arg.startsWith('--manifest='));
    if (inlineOption) return inlineOption.slice('--manifest='.length);
    return DEFAULT_MANIFEST_PATH;
}

const isMain = process.argv[1] !== undefined
    && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isMain) {
    try {
        const result = validateTaxonomyCorpus(manifestPathFromArgs(process.argv.slice(2)));
        console.log(`[taxonomy-corpus] valid: ${result.queryCount} queries, ${result.annotationCount} annotations`);
    } catch (error) {
        console.error(error instanceof Error ? error.message : error);
        process.exitCode = 1;
    }
}
