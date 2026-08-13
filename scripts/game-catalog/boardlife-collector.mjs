import fs from 'node:fs';
import path from 'node:path';

import { validateCatalogRowsForDatabase } from './catalog-analysis.mjs';
import { renderUpsertSql } from './catalog-artifact-renderer.mjs';
import {
    commitZipArtifacts,
    resolveInputRoot,
    sha256,
    validatePositiveUniqueIds,
} from './catalog-pipeline-utils.mjs';

const CLI_ARGS = process.argv.slice(2);
const DOWNLOAD_DIR = resolveInputRoot(CLI_ARGS);
const INPUT_PATH = readRequiredOption('--input');
const INPUT_MANIFEST_PATH = readRequiredOption('--input-manifest');
const BGG_SOURCE_PATH = readRequiredOption('--bgg-source');
const LOCALIZATION_DIR = path.join(DOWNLOAD_DIR, 'reference/02-localization');
const NEW_GAMES_SQL_PATH = path.join(LOCALIZATION_DIR, '06-upsert-boardlife-new-games.sql');

async function collectBoardlifeData() {
    const inputBytes = fs.readFileSync(INPUT_PATH);
    const bggSourceBytes = fs.readFileSync(BGG_SOURCE_PATH);
    const rows = JSON.parse(inputBytes.toString('utf8'));
    const bggSourceRows = JSON.parse(bggSourceBytes.toString('utf8'));
    const manifest = JSON.parse(fs.readFileSync(INPUT_MANIFEST_PATH, 'utf8'));
    const bggIds = validatePositiveUniqueIds(rows, 'boardlife');
    if (rows.length === 0) {
        throw new Error('BoardLife 보완 입력은 최소 1행이어야 합니다');
    }
    validateBoardlifeManifest({ manifest, rows, bggIds, inputBytes, bggSourceBytes });
    validateBggMappings(rows, bggSourceRows);
    const rowErrors = validateCatalogRowsForDatabase(rows);
    if (rowErrors.length > 0) {
        throw new Error(`BoardLife 행 스키마 검증 실패: ${JSON.stringify(rowErrors)}`);
    }

    const zipPath = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
    console.log(`승인된 BGG 식별자와 연결된 BoardLife 보완 행 ${rows.length}건의 SQL을 생성합니다.`);
    await commitZipArtifacts({
        zipPath,
        zipEntry: '06-complete-local-import/06-upsert-boardlife-new-games.sql',
        zipFileTarget: NEW_GAMES_SQL_PATH,
        files: [{ target: NEW_GAMES_SQL_PATH, contents: renderUpsertSql(rows) }],
    });
    console.log('SQL과 handoff ZIP을 함께 교체했습니다.');
}

function validateBoardlifeManifest({ manifest, rows, bggIds, inputBytes, bggSourceBytes }) {
    const expectedIds = [...bggIds].sort((left, right) => left - right);
    if (manifest?.approved !== true
        || manifest.datasetKind !== 'boardlife-bgg-overlays'
        || manifest.grain !== '1 row per bgg_id'
        || manifest.rows !== rows.length
        || manifest.inputSha256 !== sha256(inputBytes)
        || manifest.bggSourceSha256 !== sha256(bggSourceBytes)
        || !validSourceMetadata(manifest, 'boardlifeSource')
        || !validSourceMetadata(manifest, 'bggSource')
        || JSON.stringify(manifest.bggIds ?? []) !== JSON.stringify(expectedIds)) {
        throw new Error('BoardLife 승인 manifest가 입력 및 BGG source snapshot과 일치하지 않습니다');
    }
}

function validSourceMetadata(manifest, prefix) {
    return typeof manifest[`${prefix}Reference`] === 'string'
        && manifest[`${prefix}Reference`].trim().length > 0
        && !Number.isNaN(Date.parse(manifest[`${prefix}AcquiredAt`]))
        && typeof manifest[`${prefix}UsageTerms`] === 'string'
        && manifest[`${prefix}UsageTerms`].trim().length > 0;
}

function validateBggMappings(rows, bggSourceRows) {
    const sourceIds = validatePositiveUniqueIds(bggSourceRows, 'BGG snapshot');
    const sourceById = new Map(sourceIds.map((bggId, index) => [bggId, bggSourceRows[index]]));
    for (const row of rows) {
        const source = sourceById.get(Number(row.bgg_id));
        if (!source) {
            throw new Error(`BoardLife bgg_id가 승인 BGG snapshot에 없습니다: ${row.bgg_id}`);
        }
        const sourceName = source.english_name ?? source.name;
        if (normalizeName(sourceName) !== normalizeName(row.english_name)) {
            throw new Error(`BoardLife 영문명이 승인 BGG snapshot과 다릅니다: ${row.bgg_id}`);
        }
    }
}

function normalizeName(value) {
    return String(value ?? '')
        .normalize('NFKC')
        .toLocaleLowerCase('en-US')
        .replaceAll(/[^\p{L}\p{N}]+/gu, '');
}

function readRequiredOption(name) {
    const index = CLI_ARGS.indexOf(name);
    const value = index >= 0 ? CLI_ARGS[index + 1] : null;
    if (!value || value.startsWith('--')) {
        throw new Error(`${name} <path>가 필요합니다`);
    }
    return path.resolve(value);
}

collectBoardlifeData().catch((error) => {
    console.error('오류 발생:', error);
    process.exitCode = 1;
});
