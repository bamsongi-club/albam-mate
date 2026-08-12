#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { closeSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync, writeSync } from 'node:fs';
import { basename, resolve } from 'node:path';
import {
    validateApprovedInputReport,
    validatePositiveUniqueIds,
} from './catalog-pipeline-utils.mjs';

const UTF8_DECODER = new TextDecoder('utf-8', { fatal: true });
const CATALOG_FIELDS = [
    'bgg_id',
    'name',
    'english_name',
    'alias',
    'image_url',
    'supported_player_count',
    'tag',
    'estimated_play_time',
    'min_players',
    'max_players',
    'min_play_time_minutes',
    'max_play_time_minutes',
    'complexity',
    'release_year',
    'description',
    'detail_description',
];
const REQUIRED_FIELDS = [
    'name',
    'english_name',
    'supported_player_count',
    'tag',
    'estimated_play_time',
    'description',
    'detail_description',
];
// 단일 INSERT에 170,000행을 모두 넣으면 백엔드 메모리가 3GiB 이상 치솟아 PostgreSQL 인스턴스가
// 크래시한다 (issue #621). 청크 단위로 별도 INSERT 문을 발행해 statement당 메모리를 제한한다.
const INSERT_CHUNK_SIZE = 5000;

const options = parseOptions(process.argv.slice(2));
mkdirSync(options.out, { recursive: true });
try {
    build(options);
} catch (error) {
    removeGeneratedOutputs(options.out);
    writeFile(
        resolve(options.out, 'quality-report.json'),
        JSON.stringify({
            schemaVersion: 1,
            status: 'blocked_for_local_import',
            inputs: {
                base: { path: options.base, reportPath: options.baseReport },
                source: { path: options.source, reportPath: options.sourceReport },
            },
            errors: [{ code: 'INPUT_QUALITY_GATE_FAILED', message: error.message }],
            outputs: null,
        }, null, 2) + '\n',
    );
    console.error(error.message);
    process.exitCode = 1;
}

function build({
    base: basePath,
    baseReport: baseReportPath,
    fixtureReport: fixtureReportPath,
    source: sourcePath,
    sourceReport: sourceReportPath,
    out,
}) {
    const baseBytes = readBytes(basePath);
    const baseReportBytes = readBytes(baseReportPath);
    const fixtureReportBytes = fixtureReportPath ? readBytes(fixtureReportPath) : null;
    const sourceBytes = readBytes(sourcePath);
    const sourceReportBytes = readBytes(sourceReportPath);
    const baseRows = parseJson(baseBytes, basePath);
    const baseReport = parseJson(baseReportBytes, baseReportPath);
    const fixtureReport = fixtureReportBytes ? parseJson(fixtureReportBytes, fixtureReportPath) : null;
    const sourceRows = parseJson(sourceBytes, sourcePath);
    const sourceReport = parseJson(sourceReportBytes, sourceReportPath);
    const baseIdValues = validatePositiveUniqueIds(baseRows, 'base');
    const sourceIdValues = validatePositiveUniqueIds(sourceRows, 'source');
    validateApprovedInputReport({
        report: baseReport,
        inputBytes: baseBytes,
        inputRows: baseRows.length,
        inputKeys: ['approvedBase', 'games'],
        datasetKind: 'approved-local-import-base',
        grain: '1 row per bgg_id',
    });
    validateApprovedInputReport({
        report: sourceReport,
        inputBytes: sourceBytes,
        inputRows: sourceRows.length,
        inputKeys: ['bggXmlCatalog', 'source'],
        datasetKind: 'bgg-xml-description-catalog',
        grain: '1 row per bgg_id',
    });
    const sourceById = new Map(sourceRows.map((row, index) => [sourceIdValues[index], row]));
    const baseIds = new Set(baseIdValues);
    const sourceIds = new Set(sourceIdValues);
    const rows = [];
    let descriptionFromBgg = 0;
    let descriptionFallback = 0;
    let detailFromBgg = 0;
    let detailFallback = 0;
    let baseRequiredNullRows = 0;
    let sourceMissingRows = 0;
    let idMismatchRows = 0;
    let sourceMissingBaseRows = 0;
    let sourceOnlyRows = 0;
    let summaryLongerThanDetail = 0;
    const duplicateBaseIdRows = 0;
    const baseFieldNulls = Object.fromEntries(CATALOG_FIELDS.map((field) => [field, 0]));
    const sourceFieldNulls = Object.fromEntries(CATALOG_FIELDS.map((field) => [field, 0]));

    for (const baseRow of baseRows) {
        const bggId = Number(baseRow.bgg_id);
        const sourceRow = sourceById.get(bggId);
        if (!sourceRow) {
            idMismatchRows += 1;
            sourceMissingBaseRows += 1;
        }
        const sourceDescription = sourceRow?.description ?? null;
        const sourceDetailDescription = sourceRow?.detail_description ?? null;
        const description = sourceDescription ?? baseRow.description ?? null;
        const detailDescription = sourceDetailDescription ?? baseRow.detail_description ?? null;
        if (sourceDescription === null) {
            descriptionFallback += 1;
        } else {
            descriptionFromBgg += 1;
        }
        if (sourceDetailDescription === null) {
            detailFallback += 1;
        } else {
            detailFromBgg += 1;
        }
        if (description && detailDescription && description.length > detailDescription.length) {
            summaryLongerThanDetail += 1;
        }
        const row = { ...baseRow, description, detail_description: detailDescription };
        rows.push(row);
        for (const field of CATALOG_FIELDS) {
            if (row[field] === null || row[field] === undefined || row[field] === '') {
                baseFieldNulls[field] += 1;
            }
            if (sourceRow?.[field] === null || sourceRow?.[field] === undefined || sourceRow?.[field] === '') {
                sourceFieldNulls[field] += 1;
            }
        }
        if (REQUIRED_FIELDS.some((field) => row[field] === null || row[field] === undefined || row[field] === '')) {
            baseRequiredNullRows += 1;
        }
    }
    for (const sourceRow of sourceRows) {
        if (!baseIds.has(Number(sourceRow.bgg_id))) {
            idMismatchRows += 1;
            sourceOnlyRows += 1;
        }
    }
    sourceMissingRows = sourceRows.length - [...sourceById.values()].filter(
        (row) => row.description !== null && row.description !== undefined && row.description !== '',
    ).length;
    rows.sort((left, right) => Number(left.bgg_id) - Number(right.bgg_id));

    const catalogPath = resolve(out, 'service-catalog.local-import-with-bgg-descriptions.json');
    const sqlPath = resolve(out, 'upsert-games.local-import-with-bgg-descriptions.sql');
    const reportPath = resolve(out, 'quality-report.json');
    const manifestPath = resolve(out, 'source-manifest.local-import.json');
    const status = baseRequiredNullRows === 0
        && sourceOnlyRows === 0
        && duplicateBaseIdRows === 0
        ? 'ready_for_local_import'
        : 'blocked_for_local_import';
    let catalogSha256 = null;
    let sqlSha256 = null;
    if (status === 'ready_for_local_import') {
        catalogSha256 = writeJsonArray(catalogPath, rows);
        sqlSha256 = writeUpsertSql(sqlPath, rows);
    } else {
        removeGeneratedOutputs(out);
    }
    const generatedAt = new Date().toISOString();
    const report = {
        schemaVersion: 1,
        datasetKind: 'production-local-import-with-bgg-xml-descriptions',
        grain: '1 row per bgg_id',
        batchId: 'complete-local-import-170k-bgg-descriptions-2026-08-10',
        status,
        generatedAt,
        generator: {
            fileName: basename(process.argv[1]),
            path: process.argv[1],
            sha256: sha256(readBytes(process.argv[1])),
        },
        inputs: {
            approvedBase: {
                path: basePath,
                fileName: basename(basePath),
                sha256: sha256(baseBytes),
                rows: baseRows.length,
                reportPath: baseReportPath,
                reportSha256: sha256(baseReportBytes),
            },
            bggXmlCatalog: {
                path: sourcePath,
                fileName: basename(sourcePath),
                sha256: sha256(sourceBytes),
                rows: sourceRows.length,
                reportPath: sourceReportPath,
                reportSha256: sha256(sourceReportBytes),
            },
            ...(fixtureReport
                ? {
                    baseFixtureQuality: {
                        path: fixtureReportPath,
                        fileName: basename(fixtureReportPath),
                        sha256: sha256(fixtureReportBytes),
                    },
                }
                : {}),
        },
        checks: {
            baseRows: baseRows.length,
            bggXmlRows: sourceRows.length,
            outputRows: rows.length,
            uniqueBaseIds: baseIds.size,
            uniqueBggXmlIds: sourceIds.size,
            idMismatchRows,
            sourceMissingBaseRows,
            sourceOnlyRows,
            duplicateBaseIdRows,
            requiredNullRows: baseRequiredNullRows,
            approvedBaseStatus: baseReport.status ?? null,
            outputFieldNulls: Object.fromEntries(
                CATALOG_FIELDS.map((field) => [
                    field,
                    rows.filter((row) => row[field] === null || row[field] === undefined || row[field] === '').length,
                ]),
            ),
            sourceFieldNulls,
            baseFieldNulls,
            summaryDetailConsistency: {
                descriptionFromBgg: descriptionFromBgg,
                descriptionFallbackToApprovedBase: descriptionFallback,
                detailDescriptionFromBgg: detailFromBgg,
                detailDescriptionFallbackToApprovedBase: detailFallback,
                sourceRowsWithoutDescription: sourceMissingRows,
                summaryLongerThanDetail,
            },
            requiredFields: REQUIRED_FIELDS,
        },
        provenance: {
            description: {
                source: 'BGG XML description의 첫 문장(첫 문장이 없으면 첫 문단)',
                bggRows: descriptionFromBgg,
                fallbackRows: descriptionFallback,
                fallbackSource: '기존 승인 local base catalog의 description',
            },
            detail_description: {
                source: 'BGG XML description 전체 원문',
                bggRows: detailFromBgg,
                fallbackRows: detailFallback,
                fallbackSource: '기존 승인 local base catalog의 detail_description',
            },
            otherFields: {
                source: '기존 승인 local base catalog',
                note: 'tag·인원·플레이 시간은 이 산출물에서 BGG XML 값으로 덮어쓰지 않았습니다. 기존 승인본의 운영·성능용 값을 유지합니다.',
            },
        },
        findings: [
            {
                code: 'DESCRIPTION_AND_DETAIL_FROM_ONE_BGG_FIELD',
                severity: 'LOW',
                confidence: 'high',
                message: 'BGG XML에 별도 요약·상세 필드가 없어 첫 문장 요약과 전체 원문으로 파생했습니다.',
                evidence: {
                    bggRows: descriptionFromBgg,
                    fallbackRows: descriptionFallback,
                    sourceMissingBaseRows,
                    sourceRowsWithoutDescription: sourceMissingRows,
                },
            },
            ...(descriptionFallback > 0
                ? [{
                    code: 'DESCRIPTION_FALLBACK_TO_APPROVED_BASE',
                    severity: 'MEDIUM',
                    confidence: 'high',
                message: 'BGG XML 설명이 없는 행은 기존 승인 local base 설명을 유지했습니다.',
                    evidence: {
                        rows: descriptionFallback,
                        sourceMissingBaseRows,
                        sourceRowsWithoutDescription: sourceMissingRows,
                    },
                }]
                : []),
            ...(fixtureReport
                ? [{
                    code: 'APPROVED_BASE_ATTRIBUTES_RETAINED',
                    severity: 'MEDIUM',
                    confidence: 'high',
                    message: 'tag·인원·플레이 시간·복잡도 등은 기존 승인 base 값을 유지했으며, source-backed BGG XML 값으로 주장하지 않습니다.',
                    evidence: {
                        syntheticAttributeRows: fixtureReport.enrichment?.syntheticAttributeRows ?? null,
                        intentionallyNotClaimedAsSourceFacts: (
                            fixtureReport.enrichment?.intentionallyNotClaimedAsSourceFacts ?? []
                        ).filter((field) => field !== '설명'),
                    },
                }]
                : []),
        ],
        outputs: {
            catalog: {
                fileName: basename(catalogPath),
                rows: rows.length,
                sha256: catalogSha256,
            },
            sql: {
                fileName: basename(sqlPath),
                rows: rows.length,
                sha256: sqlSha256,
                purpose: '현재 Flyway games 테이블에 로컬 적재하는 upsert SQL',
            },
            sourceManifest: {
                fileName: basename(manifestPath),
                sha256: null,
            },
        },
    };
    if (status !== 'ready_for_local_import') {
        report.outputs = null;
        writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
        process.exitCode = 1;
        return;
    }
    const manifest = {
        schemaVersion: 1,
        batchId: report.batchId,
        status: report.status,
        generatedAt,
        fieldSources: report.provenance,
        inputs: report.inputs,
        rules: [
            '기존 승인 base catalog의 모든 필드를 유지합니다.',
            'description은 BGG XML description의 첫 문장, 없으면 첫 문단을 사용합니다.',
            'detail_description은 BGG XML description 전체를 사용합니다.',
            'BGG XML 설명이 없는 36건은 기존 승인 base의 설명을 fallback으로 유지합니다.',
            'tag·인원·플레이 시간·복잡도는 기존 승인 base 값이며 BGG XML 원본값으로 표시하지 않습니다.',
        ],
        review: {
            status: 'generated_not_approved',
            reviewers: [],
        },
    };
    writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
    report.outputs.sourceManifest.sha256 = sha256(readBytes(manifestPath));
    writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
    process.stdout.write(
        JSON.stringify(
            {
                status: report.status,
                rows: rows.length,
                requiredNullRows: baseRequiredNullRows,
                duplicateBaseIdRows,
                approvedBaseStatus: baseReport.status ?? null,
                idMismatchRows,
                descriptionFromBgg,
                descriptionFallback,
                detailFromBgg,
                detailFallback,
                catalogSha256,
                sqlSha256,
            },
            null,
            2,
        ) + '\n',
    );
}

function writeJsonArray(path, rows) {
    const fd = openSync(path, 'w');
    const hash = createHash('sha256');
    const write = (value) => {
        writeSync(fd, value, null, 'utf8');
        hash.update(value);
    };
    try {
        write('[\n');
        rows.forEach((row, index) => {
            const json = JSON.stringify(row, null, 2)
                .split('\n')
                .map((line) => '  ' + line)
                .join('\n');
            write(json + (index === rows.length - 1 ? '\n' : ',\n'));
        });
        write(']\n');
    } finally {
        closeSync(fd);
    }
    return hash.digest('hex');
}

function writeUpsertSql(path, rows) {
    const fd = openSync(path, 'w');
    const hash = createHash('sha256');
    const write = (value) => {
        writeSync(fd, value, null, 'utf8');
        hash.update(value);
    };
    const columns = CATALOG_FIELDS.concat(['created_at', 'updated_at']);
    const conflictSet = CATALOG_FIELDS
        .filter((field) => field !== 'bgg_id')
        .map((field) => '    ' + field + ' = EXCLUDED.' + field)
        .concat(['    updated_at = CURRENT_TIMESTAMP'])
        .join(',\n');
    try {
        write(
            'BEGIN;\n' +
                "SET LOCAL standard_conforming_strings = on;\n" +
                "SET LOCAL TIME ZONE 'UTC';\n\n",
        );
        for (let start = 0; start < rows.length; start += INSERT_CHUNK_SIZE) {
            const chunk = rows.slice(start, start + INSERT_CHUNK_SIZE);
            write(
                'INSERT INTO games (\n' +
                    columns.map((field) => '    ' + field).join(',\n') +
                    '\n) VALUES\n',
            );
            chunk.forEach((row, index) => {
                const values = CATALOG_FIELDS
                    .map((field) => sqlValue(row[field]))
                    .concat(['CURRENT_TIMESTAMP', 'CURRENT_TIMESTAMP'])
                    .join(', ');
                write('    (' + values + ')' + (index === chunk.length - 1 ? '\n' : ',\n'));
            });
            write('\nON CONFLICT (bgg_id) DO UPDATE SET\n' + conflictSet + ';\n\n');
        }
        write('COMMIT;\n');
    } finally {
        closeSync(fd);
    }
    return hash.digest('hex');
}

function sqlValue(value) {
    if (value === null || value === undefined) return 'NULL';
    if (typeof value === 'number') return String(value);
    return "'" + String(value).replaceAll("'", "''") + "'";
}

function parseOptions(args) {
    const values = {};
    for (let index = 0; index < args.length; index += 2) {
        const key = args[index];
        const value = args[index + 1];
        if (!key?.startsWith('--') || !value) failUsage();
        values[key.slice(2)] = value;
    }
    if (!values.base || !values['base-report'] || !values.source || !values['source-report'] || !values.out) {
        failUsage();
    }
    return {
        base: resolve(values.base),
        baseReport: resolve(values['base-report']),
        fixtureReport: values['fixture-report'] ? resolve(values['fixture-report']) : null,
        source: resolve(values.source),
        sourceReport: resolve(values['source-report']),
        out: resolve(values.out),
    };
}

function failUsage() {
    process.stderr.write(
        '사용법: node build-complete-local-import-catalog.mjs ' +
            '--base <json> --base-report <json> [--fixture-report <json>] ' +
            '--source <json> --source-report <json> --out <directory>\n',
    );
    process.exit(2);
}

function readBytes(path) {
    return readFileSync(path);
}

function parseJson(bytes, path) {
    try {
        return JSON.parse(UTF8_DECODER.decode(bytes).replace(/^\uFEFF/u, ''));
    } catch (error) {
        throw new Error('JSON 해석 실패: ' + path, { cause: error });
    }
}

function sha256(value) {
    return createHash('sha256').update(value).digest('hex');
}

function writeFile(path, value) {
    writeFileSync(path, value, 'utf8');
}

function removeGeneratedOutputs(out) {
    for (const fileName of [
        'service-catalog.local-import-with-bgg-descriptions.json',
        'upsert-games.local-import-with-bgg-descriptions.sql',
        'source-manifest.local-import.json',
    ]) {
        rmSync(resolve(out, fileName), { force: true });
    }
}
